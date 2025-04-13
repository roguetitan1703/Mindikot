package com.example.mindikot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Modifier // Ensure this import is added
import androidx.navigation.compose.rememberNavController
import com.example.mindikot.ui.navigation.MainNavHost
import com.example.mindikot.ui.components.GameFooter
import com.example.mindikot.ui.theme.MindikotTheme

class MainActivity : ComponentActivity() {

    private external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("mindikot")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class) // Opt-In annotation for Material3 APIs
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MindikotTheme {
                val navController = rememberNavController()

                // State to hold the native string
                var nativeText by remember { mutableStateOf("") }

                // Use LaunchedEffect to call the native method and set the result
                LaunchedEffect(Unit) {
                    nativeText = stringFromJNI() // This will run once during composition
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Mindikot Game") },
                            colors = TopAppBarDefaults.mediumTopAppBarColors()
                        )
                    }
                    ,
                    bottomBar = {
                        GameFooter(version = "v1.0.0") // 👈 Footer added here
                    }
                ) { paddingValues ->
                    // Main content is handled by MainNavHost, passing the navigation controller
                    MainNavHost(navController = navController, modifier = Modifier.padding(paddingValues))

                }

            }
        }
    }
}
