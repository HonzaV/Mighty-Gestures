package cz.mightybities.mightygestures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cz.mightybities.mightygestures.ui.HomeScreen
import cz.mightybities.mightygestures.ui.theme.MightyGesturesTheme

/**
 * Exported launcher activity; also reachable via `android.app.action.AUTOMATIC_ZEN_RULE` so Settings can
 * "configure" the Mighty Gestures Do Not Disturb mode (ADR 0004 decision 8). Both intent filters mean **all
 * incoming intents are untrusted**: never act on extras or data from `intent` without validation (security
 * review, PR #4 fix round). Today this activity ignores every extra and just shows the normal home screen.
 */
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
