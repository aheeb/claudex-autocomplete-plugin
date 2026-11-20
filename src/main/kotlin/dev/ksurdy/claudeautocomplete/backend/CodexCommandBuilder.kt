package dev.ksurdy.claudeautocomplete.backend

import java.io.File

object CodexCommandBuilder {
    private val EXTRA_PATH_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin")

    fun execCommand(binary: String, config: CodexConfig): List<String> = buildList {
        add(binary)
        add("exec")
        add("--json")
        add("--skip-git-repo-check")
        add("--ephemeral")
        addAll(listOf("-s", "read-only"))
        addAll(listOf("-m", config.model))
        addAll(listOf("-c", "model_reasoning_effort=${tomlString(config.reasoningEffort)}"))
        addAll(listOf("-c", "developer_instructions=${tomlString(PromptBuilder.systemPrompt(config.customInstructions))}"))
        addAll(listOf("-c", "include_environment_context=false"))
        addAll(listOf("-c", "include_permissions_instructions=false"))
        addAll(listOf("-c", "project_doc_max_bytes=0"))
        addAll(listOf("-c", "hide_agent_reasoning=true"))
        addAll(listOf("-c", "web_search=${tomlString("disabled")}"))
        addAll(leanOverrides())
    }

    fun appServerCommand(binary: String): List<String> = listOf(binary, "app-server")

    /** A long-lived app-server for completions only: no tools, MCP servers, hooks, plugins, skills or project docs. */
    fun completionServerCommand(binary: String): List<String> = buildList {
        add(binary)
        add("app-server")
        addAll(listOf("-c", "include_environment_context=false"))
        addAll(listOf("-c", "include_permissions_instructions=false"))
        addAll(listOf("-c", "project_doc_max_bytes=0"))
        addAll(listOf("-c", "hide_agent_reasoning=true"))
        addAll(listOf("-c", "web_search=${tomlString("disabled")}"))
        addAll(leanOverrides())
    }

    /**
     * Strips what an agent session loads from the user's Codex setup but a completion never uses. Hooks, plugins,
     * skills and agent tools otherwise run and add thousands of prompt tokens to every suggestion. MCP servers are
     * switched off separately by name, see [CodexMcpServers].
     */
    fun leanOverrides(): List<String> = buildList {
        addAll(listOf("-c", "skills.enabled=false"))
        for (feature in DISABLED_FEATURES) addAll(listOf("-c", "features.$feature=false"))
    }

    private val DISABLED_FEATURES = listOf(
        "apps", "browser_use", "browser_use_external", "computer_use", "goals", "hooks", "image_generation",
        "in_app_browser", "multi_agent", "plugins", "remote_plugin", "shell_snapshot", "shell_tool",
        "skill_mcp_dependency_install", "skill_search", "sleep_tool", "tool_suggest", "unified_exec", "view_image",
    )

    fun tomlString(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    fun environment(base: Map<String, String>, binary: String): Map<String, String> {
        val env = base.filterKeys { it != "OPENAI_API_KEY" && it != "CODEX_API_KEY" && it != "CLAUDECODE" && !it.startsWith("CLAUDE_CODE_") }
            .toMutableMap()
        val dirs = listOfNotNull(File(binary).parent) + base["PATH"].orEmpty().split(":") + EXTRA_PATH_DIRS
        env["PATH"] = dirs.filter { it.isNotEmpty() }.distinct().joinToString(":")
        return env
    }
}
