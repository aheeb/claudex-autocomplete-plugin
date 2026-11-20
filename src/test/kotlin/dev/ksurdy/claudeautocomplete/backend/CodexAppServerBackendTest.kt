package dev.ksurdy.claudeautocomplete.backend

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexAppServerBackendTest {
    private val context = testContext(prefix = "fun add(a: Int, b: Int) = ", languageId = "kotlin")

    /** Answers the JSON-RPC handshake and turns like `codex app-server`, streaming [reply] in two deltas. */
    private fun server(
        reply: String = "<completion>a + b</completion>",
        replyBeforeTurnResponse: Boolean = false,
        holdTurn: CompletableDeferred<Unit>? = null,
        turnStatus: String = "completed",
    ): FakeClaudeProcess {
        var thread = 0
        return FakeClaudeProcess { line ->
            val msg = JsonParser.parseString(line).asJsonObject
            val id = msg.get("id")?.asInt
            val params = msg.getAsJsonObject("params")
            when (msg.get("method")?.asString) {
                "initialize" -> emit("""{"id":$id,"result":{"userAgent":"fake"}}""")
                "thread/start" -> {
                    thread++
                    emit("""{"method":"thread/started","params":{"thread":{"id":"t$thread"}}}""")
                    emit("""{"id":$id,"result":{"thread":{"id":"t$thread"}}}""")
                }
                "turn/start" -> {
                    val tid = params.get("threadId").asString
                    val deltas = listOf(reply.take(reply.length / 2), reply.drop(reply.length / 2)).map {
                        """{"method":"item/agentMessage/delta","params":{"threadId":"$tid","turnId":"u1","itemId":"i","delta":${FakeClaudeProcess.quote(it)}}}"""
                    }
                    val done = """{"method":"turn/completed","params":{"threadId":"$tid","turn":{"id":"u1","status":"$turnStatus","items":[]${if (turnStatus == "failed") ""","error":{"message":"usage limit reached"}""" else ""}}}}"""
                    if (replyBeforeTurnResponse) deltas.forEach(::emit)
                    emit("""{"id":$id,"result":{"turn":{"id":"u1","status":"inProgress","items":[]}}}""")
                    if (holdTurn != null) return@FakeClaudeProcess
                    if (!replyBeforeTurnResponse) deltas.forEach(::emit)
                    emit(done)
                }
            }
        }
    }

    private fun backend(process: FakeClaudeProcess, starts: MutableList<CodexMode> = mutableListOf()) =
        CodexAppServerBackend({ "/bin/codex" }, UsageTracker(ProviderKind.Codex)) { _, _, mode ->
            starts += mode
            process
        }

    private fun sentMethods(process: FakeClaudeProcess) =
        process.sent.map { JsonParser.parseString(it).asJsonObject.get("method")?.asString }

    @Test
    fun `streams a completion over one long-lived process`() = runBlocking {
        val process = server()
        val starts = mutableListOf<CodexMode>()
        val backend = backend(process, starts)
        val seen = mutableListOf<String>()
        val config = testCodexConfig(persistentProcess = true)

        val first = backend.complete(context, config) { seen += it }
        val second = backend.complete(context, config) {}

        assertEquals(CompletionResult.Success("<completion>a + b</completion>"), first)
        assertEquals(CompletionResult.Success("<completion>a + b</completion>"), second)
        assertEquals(listOf(CodexMode.CompletionServer), starts)
        assertTrue(seen.size == 2 && seen.last() == "<completion>a + b</completion>")
        assertEquals(1, sentMethods(process).count { it == "initialize" })
        assertEquals(2, sentMethods(process).count { it == "thread/start" })
    }

    @Test
    fun `thread uses the completion prompt as base instructions and is ephemeral`() = runBlocking {
        val process = server()
        backend(process).complete(context, testCodexConfig(model = "gpt-6-luna", reasoningEffort = "low"), {})
        val thread = process.sent.map { JsonParser.parseString(it).asJsonObject }.first { it.get("method")?.asString == "thread/start" }
        val params: JsonObject = thread.getAsJsonObject("params")
        assertEquals("gpt-6-luna", params.get("model").asString)
        assertTrue(params.get("ephemeral").asBoolean)
        assertEquals("never", params.get("approvalPolicy").asString)
        assertEquals(PromptBuilder.systemPrompt(""), params.get("baseInstructions").asString)
    }

    @Test
    fun `keeps deltas that arrive before the turn response`() = runBlocking {
        val process = server(replyBeforeTurnResponse = true)
        val result = backend(process).complete(context, testCodexConfig(), {})
        assertEquals(CompletionResult.Success("<completion>a + b</completion>"), result)
    }

    @Test
    fun `cancelling interrupts the turn and keeps the process`() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val process = server(holdTurn = hold)
        val backend = backend(process)
        val job = async { backend.complete(context, testCodexConfig(), {}) }
        withTimeout(2000) { while (sentMethods(process).none { it == "turn/start" }) kotlinx.coroutines.delay(5) }
        kotlinx.coroutines.delay(20)
        job.cancel()
        job.join()
        assertTrue("turn/interrupt" in sentMethods(process))
        assertTrue(!process.killed)
    }

    @Test
    fun `a failed turn maps its error`() = runBlocking {
        val result = backend(server(turnStatus = "failed")).complete(context, testCodexConfig(), {})
        assertIs<CompletionResult.Failure>(result)
        assertEquals(FailureKind.RateLimited, result.kind)
    }

    @Test
    fun `parses enabled MCP server names`() {
        val json = """[{"name":"MCP_DOCKER","enabled":true},{"name":"computer-use","enabled":false},{"name":"ilias-hslu","enabled":true},{"name":"bad name","enabled":true}]"""
        assertEquals(listOf("MCP_DOCKER", "ilias-hslu"), CodexMcpServers.parse(json))
        assertEquals(emptyList(), CodexMcpServers.parse("not json"))
    }
}
