package com.synthalorian.openshark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synthalorian.openshark.ui.screens.AgentEditorScreen
import com.synthalorian.openshark.ui.screens.AgentsScreen
import com.synthalorian.openshark.ui.screens.ChatScreen
import com.synthalorian.openshark.ui.screens.ModelsScreen
import com.synthalorian.openshark.ui.screens.SettingsScreen
import com.synthalorian.openshark.ui.theme.OpenSharkTheme

import androidx.lifecycle.viewmodel.compose.viewModel
import com.synthalorian.openshark.ui.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from splash theme to main app theme
        setTheme(R.style.Theme_OpenShark)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenSharkTheme {
                val navController = rememberNavController()
                // Share one ViewModel across all screens
                val chatViewModel: ChatViewModel = viewModel()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "chat",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("chat") {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToModels = { navController.navigate("models") },
                                onNavigateToAgents = { navController.navigate("agents") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = chatViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("models") {
                            ModelsScreen(
                                viewModel = chatViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("agents") {
                            AgentsScreen(
                                viewModel = chatViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEditor = { agentId ->
                                    navController.navigate("agentEditor?agentId=$agentId")
                                }
                            )
                        }
                        composable(
                            route = "agentEditor?agentId={agentId}",
                            arguments = listOf(
                                navArgument("agentId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val agentId = backStackEntry.arguments?.getString("agentId")
                            AgentEditorScreen(
                                viewModel = chatViewModel,
                                agentId = agentId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
