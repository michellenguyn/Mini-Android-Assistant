package com.nhh.miniassistant.domain.llm

import com.nhh.miniassistant.data.LlmResult

abstract class LLMInferenceAPI {
    abstract suspend fun getResponse(state: String, query: String): LlmResult?
}
