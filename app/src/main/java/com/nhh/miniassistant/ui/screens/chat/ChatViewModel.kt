package com.nhh.miniassistant.ui.screens.chat

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nhh.miniassistant.R
import com.nhh.miniassistant.data.ChatMemory
import com.nhh.miniassistant.data.ChatMemoryDB
import com.nhh.miniassistant.data.ChunksDB
import com.nhh.miniassistant.data.DocumentsDB
import com.nhh.miniassistant.data.GeminiAPIKey
import com.nhh.miniassistant.data.LlmResult
import com.nhh.miniassistant.data.RetrievedContext
import com.nhh.miniassistant.domain.SentenceEmbeddingProvider
import com.nhh.miniassistant.domain.llm.GeminiRemoteAPI
import com.nhh.miniassistant.domain.llm.LLMInferenceAPI
//import com.nhh.miniassistant.domain.llm.LiteRTAPI
import com.nhh.miniassistant.ui.components.createAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

sealed interface ChatScreenUIEvent {
    data object OnEditCredentialsClick : ChatScreenUIEvent

    data object OnOpenDocsClick : ChatScreenUIEvent

    data object OnResetClick: ChatScreenUIEvent

    sealed class ResponseGeneration {
        data class Start(
            val query: String,
        ) : ChatScreenUIEvent

        data class StopWithSuccess(
            val response: LlmResult,
//            val retrievedContextList: List<RetrievedContext>,
        ) : ChatScreenUIEvent

        data class StopWithError(
            val errorMessage: String,
        ) : ChatScreenUIEvent
    }
}

sealed interface ChatNavEvent {
    data object None : ChatNavEvent

    data object ToEditAPIKeyScreen : ChatNavEvent

    data object ToDocsScreen : ChatNavEvent
}

data class ChatScreenUIState(
    val question: String = "",
    val response: String = "",
    val isGeneratingResponse: Boolean = false,
//    val retrievedContextList: List<RetrievedContext> = emptyList(),
)

@KoinViewModel
class ChatViewModel(
    private val context: Context,
    private val documentsDB: DocumentsDB,
    private val chunksDB: ChunksDB,
    private val chatMemoryDB: ChatMemoryDB,
    private val geminiAPIKey: GeminiAPIKey,
    private val sentenceEncoder: SentenceEmbeddingProvider,
//    private val liteRTAPI: LiteRTAPI,
) : ViewModel() {
    private val _chatScreenUIState = MutableStateFlow(ChatScreenUIState())
    val chatScreenUIState: StateFlow<ChatScreenUIState> = _chatScreenUIState

    private val _navEventChannel = Channel<ChatNavEvent>()
    val navEventChannel = _navEventChannel.receiveAsFlow()
    val states = context.getString(R.string.states)

    fun onChatScreenEvent(event: ChatScreenUIEvent) {
        when (event) {
            is ChatScreenUIEvent.ResponseGeneration.Start -> {
                if (!checkValidAPIKey()) {
                    createAlertDialog(
                        dialogTitle = "Invalid API Key",
                        dialogText = "Please enter a Gemini API key to use a LLM for generating responses.",
                        dialogPositiveButtonText = "Add API key",
                        onPositiveButtonClick = {
                            onChatScreenEvent(ChatScreenUIEvent.OnEditCredentialsClick)
                        },
                        dialogNegativeButtonText = "Open Gemini Console",
                        onNegativeButtonClick = {
                            Intent(Intent.ACTION_VIEW).apply {
                                data = "https://aistudio.google.com/apikey".toUri()
                                context.startActivity(this)
                            }
                        },
                    )
                    return
                }
                if (event.query.trim().isEmpty()) {
                    Toast
                        .makeText(context, "Enter a query to execute", Toast.LENGTH_LONG)
                        .show()
                    return
                }
                _chatScreenUIState.value =
                    _chatScreenUIState.value.copy(isGeneratingResponse = true)
                _chatScreenUIState.value =
                    _chatScreenUIState.value.copy(question = event.query)

                val apiKey = geminiAPIKey.getAPIKey() ?: throw Exception("Gemini API key is null")
                Toast.makeText(context, "Using Gemini cloud model...", Toast.LENGTH_LONG).show()
                val llm = GeminiRemoteAPI(
                    apiKey,
                    launchIntent = { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    appContext = context,
                    documentsDB = documentsDB,
                    chunksDB = chunksDB,
                    sentenceEncoder = sentenceEncoder,
                )

//                val llm =
//                    if (liteRTAPI.isLoaded) {
//                        Toast.makeText(context, "Using local model...", Toast.LENGTH_LONG).show()
//                        liteRTAPI
//                    } else {
//                        val apiKey = geminiAPIKey.getAPIKey() ?: throw Exception("Gemini API key is null")
//                        Toast.makeText(context, "Using Gemini cloud model...", Toast.LENGTH_LONG).show()
//                        GeminiRemoteAPI(apiKey)
//                    }
                getAnswer(llm, event.query)
            }

            is ChatScreenUIEvent.ResponseGeneration.StopWithSuccess -> {
                _chatScreenUIState.value =
                    _chatScreenUIState.value.copy(isGeneratingResponse = false)
                _chatScreenUIState.value = _chatScreenUIState.value.copy(response = event.response.response.ifBlank { event.response.toolOutput })
//                _chatScreenUIState.value =
//                    _chatScreenUIState.value.copy(retrievedContextList = event.retrievedContextList)
            }

            is ChatScreenUIEvent.ResponseGeneration.StopWithError -> {
                _chatScreenUIState.value =
                    _chatScreenUIState.value.copy(isGeneratingResponse = false)
                _chatScreenUIState.value = _chatScreenUIState.value.copy(question = "")
            }

            is ChatScreenUIEvent.OnOpenDocsClick -> {
                viewModelScope.launch {
                    _navEventChannel.send(ChatNavEvent.ToDocsScreen)
                }
            }

            is ChatScreenUIEvent.OnEditCredentialsClick -> {
                viewModelScope.launch {
                    _navEventChannel.send(ChatNavEvent.ToEditAPIKeyScreen)
                }
            }

            is ChatScreenUIEvent.OnResetClick -> {
                chatMemoryDB.clearAllMemories()
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    question = "",
                    response = "",
                    isGeneratingResponse = false
                )
                Toast.makeText(context, "Memory cleared.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun loadRecent() = chatMemoryDB.getRecentMemories()

    private fun getAnswer(
        llm: LLMInferenceAPI,
        query: String
    ) {
        val recent = loadRecent()
        val states = recent.joinToString("\n") { memory ->
            "Prompt: ${memory.prompt}\nTool output:\n${memory.tool}\nResponse: ${memory.response}"
        }
        CoroutineScope(Dispatchers.IO).launch {
            llm.getResponse(states, query)?.let { llmResponse ->
                onChatScreenEvent(
                    ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                        llmResponse,
//                        retrievedContextList,
                    ),
                )
                chatMemoryDB.addMemory(ChatMemory(prompt = query, response = llmResponse.response, tool = llmResponse.toolOutput))
            }
        }
    }

    fun checkValidAPIKey(): Boolean = geminiAPIKey.getAPIKey() != null
}
