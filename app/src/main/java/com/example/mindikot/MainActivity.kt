package com.example.mindikot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import com.example.mindikot.ui.MainNavHost
import com.example.mindikot.ui.theme.MindikotTheme
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {

    external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("mindikot")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class) // Add the OptIn annotation here
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MindikotTheme {
                val navController = rememberNavController()

                // Instead of using remember, just call stringFromJNI directly
                var nativeText by remember { mutableStateOf("") }

                // Use LaunchedEffect to handle the side-effect of calling stringFromJNI
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
                ) { padding ->
                    MainNavHost(navController = navController)
                }
            }
        }
    }
}
