package dev.ksurdy.claudeautocomplete.backend

import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds the MCP servers the user's Codex setup would start, so a completion process can switch each one off.
 * Codex merges `-c mcp_servers={}` into the user config instead of replacing it, so they have to be disabled by name.
 */
object CodexMcpServers {
    private val BARE_KEY = Regex("^[A-Za-z0-9_-]+$")
    private val cache = ConcurrentHashMap<String, List<String>>()

    fun disableArgs(binary: String): List<String> =
        enabledNames(binary).flatMap { listOf("-c", "mcp_servers.$it.enabled=false") }

    fun enabledNames(binary: String): List<String> = cache.getOrPut(binary) { query(binary) }

    fun parse(json: String): List<String> = try {
        JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
            val server = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val enabled = server.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            server.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { enabled && BARE_KEY.matches(it) }
        }
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun query(binary: String): List<String> = try {
        val process = ProcessSupport.launch(
            listOf(binary, "mcp", "list", "--json", "-c", "features.plugins=false"),
            CodexCommandBuilder.environment(System.getenv(), binary),
        )
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!process.waitFor(QUERY_TIMEOUT_S, TimeUnit.SECONDS)) {
            ProcessSupport.terminate(process)
            emptyList()
        } else {
            reader.join(1000)
            parse(output.toString())
        }
    } catch (_: Exception) {
        emptyList()
    }

    private const val QUERY_TIMEOUT_S = 10L
}
