package com.ali.assistant.network

data class ToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

data class AgentReply(
    val responseId: String,
    val text: String,
    val toolCalls: List<ToolCall>,
)

data class ToolOutput(
    val callId: String,
    val output: String,
)
