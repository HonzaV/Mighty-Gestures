package cz.mightybities.mightygestures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cz.mightybities.mightygestures.ui.HomeScreen
import cz.mightybities.mightygestures.ui.theme.MightyGesturesTheme

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
