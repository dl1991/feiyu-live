package com.dl1991.feiyu.llm

/** One chat message for an OpenAI-compatible endpoint. */
data class LLMMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

/** Result of a local command (the module's former agent-tool entry point). */
data class ToolExecutionResult(val text: String, val success: Boolean)

/** Self-contained description of a command, kept so DfyTools reads the same as upstream. */
data class AgentToolParam(val type: String, val description: String, val enumValues: List<String>? = null)
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String> = emptyList(),
)
