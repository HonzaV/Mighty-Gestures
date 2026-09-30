package io.github.honzav.mightygestures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.honzav.mightygestures.ui.HomeScreen
import io.github.honzav.mightygestures.ui.theme.MightyGesturesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MightyGesturesTheme {
                HomeScreen()
            }
        }
    }
}
