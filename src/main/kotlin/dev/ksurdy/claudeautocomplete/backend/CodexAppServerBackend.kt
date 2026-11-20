package dev.ksurdy.claudeautocomplete.backend

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Keeps one `codex app-server` process alive and runs every completion as a fresh, ephemeral thread on it.
 *
 * Compared with spawning `codex exec` per request this skips process start-up, MCP server launches, hooks and
 * plugin loading, sends the completion prompt as the base instructions instead of the full agent prompt, and
 * streams the reply as it is generated.
 */
class CodexAppServerBackend(
    private val locate: (String) -> String? = CodexCliLocator::locate,
    private val usage: UsageTracker,
    private val processFactory: CodexProcessFactory = RealCodexProcessFactory,
) {
    private class Session(val process: ClaudeProcess, val binary: String) {
        var nextId = FIRST_REQUEST_ID
        var served = 0
        /** Notifications that arrived while [call] waited for a response, replayed by the turn loop. */
        val pending = ArrayDeque<JsonObject>()
    }

    private val mutex = Mutex()

    @Volatile
    private var session: Session? = null

    suspend fun complete(context: CompletionContext, config: CodexConfig, onText: (String) -> Unit): CompletionResult =
        mutex.withLock {
            val current = when (val start = obtainSession(config)) {
                is Start.Failed -> return start.failure
                is Start.Ready -> start.session
            }
            val turn = TurnRef()
            try {
                withTimeout(config.requestTimeoutMs.toLong()) { runTurn(current, context, config, turn, onText) }
            } catch (e: TimeoutCancellationException) {
                withContext(NonCancellable) { interrupt(current, turn) }
                CompletionResult.Failure(FailureKind.Timeout, "Timed out after ${config.requestTimeoutMs} ms", ProviderKind.Codex)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { interrupt(current, turn) }
                throw e
            } catch (e: IOException) {
                discard(current)
                CompletionResult.Failure(FailureKind.Other, e.message.orEmpty(), ProviderKind.Codex)
            }
        }

    suspend fun warmUp(config: CodexConfig) {
        mutex.withLock {
            try {
                withTimeout(config.requestTimeoutMs.toLong().coerceAtLeast(WARM_UP_TIMEOUT_MS)) { obtainSession(config) }
            } catch (_: TimeoutCancellationException) {
                session?.let(::discard)
            }
        }
    }

    fun shutdown() {
        session?.let(::discard)
        session = null
    }

    private class TurnRef {
        @Volatile var threadId: String? = null
        @Volatile var turnId: String? = null
        @Volatile var done: Boolean = false
    }

    private sealed interface Start {
        data class Ready(val session: Session) : Start
        data class Failed(val failure: CompletionResult.Failure) : Start
    }

    private suspend fun obtainSession(config: CodexConfig): Start {
        val binary = locate(config.codexPath)
            ?: return Start.Failed(CompletionResult.Failure(FailureKind.CliNotFound, "Codex CLI not found", ProviderKind.Codex))
        session?.let { existing ->
            if (existing.binary == binary && existing.process.isAlive && existing.served < MAX_TURNS_PER_PROCESS) {
                return Start.Ready(existing)
            }
            discard(existing)
        }
        val process = try {
            processFactory.start(binary, config, CodexMode.CompletionServer)
        } catch (e: IOException) {
            return Start.Failed(CompletionResult.Failure(FailureKind.CliNotFound, e.message.orEmpty(), ProviderKind.Codex))
        }
        val fresh = Session(process, binary)
        try {
            val init = call(fresh, "initialize", JsonObject().apply {
                add("clientInfo", JsonObject().apply { addProperty("name", "claudex-autocomplete"); addProperty("version", "1") })
            })
            init.errorMessage()?.let {
                discard(fresh)
                return Start.Failed(CodexFailureMapper.map(it))
            }
            send(fresh, JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", "initialized") })
        } catch (e: IOException) {
            discard(fresh)
            return Start.Failed(CompletionResult.Failure(FailureKind.Other, e.message.orEmpty(), ProviderKind.Codex))
        }
        session = fresh
        return Start.Ready(fresh)
    }

    private suspend fun runTurn(
        current: Session,
        context: CompletionContext,
        config: CodexConfig,
        turn: TurnRef,
        onText: (String) -> Unit,
    ): CompletionResult {
        current.served++
        val thread = call(current, "thread/start", threadParams(config))
        thread.errorMessage()?.let { return CodexFailureMapper.map(it) }
        val threadId = thread.obj("result")?.obj("thread")?.string("id")
            ?: return CompletionResult.Failure(FailureKind.Other, "Codex app-server returned no thread", ProviderKind.Codex)
        turn.threadId = threadId
        val started = call(current, "turn/start", JsonObject().apply {
            addProperty("threadId", threadId)
            add("input", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", PromptBuilder.userMessage(context)) })
            })
            if (config.reasoningEffort.isNotBlank()) addProperty("effort", config.reasoningEffort)
        })
        started.errorMessage()?.let { return CodexFailureMapper.map(it) }
        turn.turnId = started.obj("result")?.obj("turn")?.string("id")

        val streamed = StringBuilder()
        var lastError: String? = null
        while (true) {
            val message = current.pending.removeFirstOrNull()
                ?: StreamJsonParser.parseObject(current.process.lines.receiveCatching().getOrNull() ?: break)
                ?: continue
            if (message.has("id") && message.has("method")) {
                answerServerRequest(current, message)
                continue
            }
            val params = message.obj("params") ?: continue
            val method = message.string("method")
            if (method == "account/rateLimits/updated") {
                usage.publish(CodexJsonlParser.parseRateLimits(params))
                continue
            }
            if (params.string("threadId") != threadId) continue
            when (method) {
                "item/agentMessage/delta" -> {
                    streamed.append(params.string("delta").orEmpty())
                    onText(streamed.toString())
                }
                "error" -> if (params.get("willRetry")?.asBoolean != true) lastError = params.obj("error")?.string("message")
                "turn/completed" -> {
                    turn.done = true
                    unsubscribe(current, threadId)
                    val done = params.obj("turn")
                    val status = done?.string("status")
                    val error = done?.obj("error")?.string("message") ?: lastError
                    return when {
                        status == "failed" -> CodexFailureMapper.map(error ?: "Codex turn failed")
                        streamed.isBlank() -> if (error != null) CodexFailureMapper.map(error) else CompletionResult.Empty
                        else -> CompletionResult.Success(streamed.toString())
                    }
                }
            }
        }
        discard(current)
        return CompletionResult.Failure(FailureKind.Other, "Codex app-server exited without a response", ProviderKind.Codex)
    }

    private fun threadParams(config: CodexConfig): JsonObject = JsonObject().apply {
        addProperty("model", config.model)
        addProperty("cwd", ProcessSupport.workDirectory)
        addProperty("approvalPolicy", "never")
        addProperty("sandbox", "read-only")
        addProperty("ephemeral", true)
        addProperty("baseInstructions", PromptBuilder.systemPrompt(config.customInstructions))
    }

    private suspend fun call(current: Session, method: String, params: JsonObject?): JsonObject {
        current.pending.clear()
        val id = current.nextId++
        send(current, JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        })
        for (line in current.process.lines) {
            val message = StreamJsonParser.parseObject(line) ?: continue
            if (message.has("method")) {
                if (message.has("id")) answerServerRequest(current, message) else current.pending.addLast(message)
                continue
            }
            if (message.get("id")?.takeIf { it.isJsonPrimitive }?.asInt == id) return message
        }
        throw IOException("Codex app-server exited")
    }

    /** The completion threads have no tools, so any approval or elicitation request is declined straight away. */
    private suspend fun answerServerRequest(current: Session, request: JsonObject) {
        send(current, JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", request.get("id"))
            add("error", JsonObject().apply { addProperty("code", -32601); addProperty("message", "not supported by autocomplete") })
        })
    }

    private suspend fun interrupt(current: Session, turn: TurnRef) {
        if (turn.done || !current.process.isAlive) return
        val threadId = turn.threadId ?: return
        val turnId = turn.turnId
        try {
            if (turnId != null) {
                send(current, JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", current.nextId++)
                    addProperty("method", "turn/interrupt")
                    add("params", JsonObject().apply { addProperty("threadId", threadId); addProperty("turnId", turnId) })
                })
            }
            unsubscribe(current, threadId)
        } catch (_: IOException) {
            discard(current)
        }
    }

    private suspend fun unsubscribe(current: Session, threadId: String) {
        send(current, JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", current.nextId++)
            addProperty("method", "thread/unsubscribe")
            add("params", JsonObject().apply { addProperty("threadId", threadId) })
        })
    }

    private suspend fun send(current: Session, message: JsonObject) {
        withContext(Dispatchers.IO) { current.process.send(message.toString()) }
    }

    private fun discard(target: Session) {
        target.process.kill()
        if (session === target) session = null
    }

    private fun JsonObject.errorMessage(): String? = obj("error")?.let { it.string("message") ?: it.toString() }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private companion object {
        const val FIRST_REQUEST_ID = 1
        const val MAX_TURNS_PER_PROCESS = 500
        const val WARM_UP_TIMEOUT_MS = 15_000L
    }
}
