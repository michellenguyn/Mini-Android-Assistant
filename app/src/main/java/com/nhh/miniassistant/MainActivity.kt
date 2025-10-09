package com.nhh.miniassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nhh.miniassistant.ui.screens.chat.ChatNavEvent
import com.nhh.miniassistant.ui.screens.chat.ChatScreen
import com.nhh.miniassistant.ui.screens.chat.ChatViewModel
import com.nhh.miniassistant.ui.screens.docs.DocsScreen
import com.nhh.miniassistant.ui.screens.docs.DocsViewModel
import com.nhh.miniassistant.ui.screens.edit_credentials.EditCredentialsScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
object ChatRoute

@Serializable
object EditAPIKeyRoute

@Serializable
object DocsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navHostController = rememberNavController()
            NavHost(
                navController = navHostController,
                startDestination = ChatRoute,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable<DocsRoute> { backStackEntry ->
                    val viewModel: DocsViewModel =
                        koinViewModel(viewModelStoreOwner = backStackEntry)
                    val docScreenUIState by viewModel.docsScreenUIState.collectAsState()
                    DocsScreen(
                        docScreenUIState,
                        onBackClick = { navHostController.navigateUp() },
                        onEvent = viewModel::onEvent,
                    )
                }
                composable<EditAPIKeyRoute> { EditCredentialsScreen(onBackClick = { navHostController.navigateUp() }) }
                composable<ChatRoute> { backStackEntry ->
                    val viewModel: ChatViewModel =
                        koinViewModel(viewModelStoreOwner = backStackEntry)
                    val chatScreenUIState by viewModel.chatScreenUIState.collectAsState()
                    val navEvent by viewModel.navEventChannel.collectAsState(ChatNavEvent.None)
                    LaunchedEffect(navEvent) {
                        when (navEvent) {
                            is ChatNavEvent.ToDocsScreen -> {
                                navHostController.navigate(DocsRoute)
                            }

                            is ChatNavEvent.ToEditAPIKeyScreen -> {
                                navHostController.navigate(EditAPIKeyRoute)
                            }
//
//                            is ChatNavEvent.ToLocalModelsScreen -> {
//                                navHostController.navigate(LocalModelsRoute)
//                            }

                            is ChatNavEvent.None -> {}
//                            else -> {}
                        }
                    }
                    ChatScreen(
                        screenUiState = chatScreenUIState,
                        onScreenEvent = { viewModel.onChatScreenEvent(it) },
                    )
                }
            }
        }
    }
}