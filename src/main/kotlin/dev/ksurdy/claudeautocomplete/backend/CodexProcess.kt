package dev.ksurdy.claudeautocomplete.backend

enum class CodexMode { Exec, AppServer, CompletionServer }

fun interface CodexProcessFactory {
    fun start(binary: String, config: CodexConfig, mode: CodexMode): ClaudeProcess
}

object RealCodexProcessFactory : CodexProcessFactory {
    override fun start(binary: String, config: CodexConfig, mode: CodexMode): ClaudeProcess {
        val command = when (mode) {
            CodexMode.Exec -> CodexCommandBuilder.execCommand(binary, config) + CodexMcpServers.disableArgs(binary)
            CodexMode.AppServer -> CodexCommandBuilder.appServerCommand(binary)
            CodexMode.CompletionServer -> CodexCommandBuilder.completionServerCommand(binary) + CodexMcpServers.disableArgs(binary)
        }
        return RealClaudeProcess(ProcessSupport.launch(command, CodexCommandBuilder.environment(System.getenv(), binary)))
    }
}
