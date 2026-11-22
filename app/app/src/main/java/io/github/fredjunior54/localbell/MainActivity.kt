package io.github.fredjunior54.localbell

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.fredjunior54.localbell.ui.screens.MainScreen
import io.github.fredjunior54.localbell.ui.theme.LocalBellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val reversePreview =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                intent.getBooleanExtra("io.github.fredjunior54.localbell.REVERSE_PREVIEW", false)
        setContent {
            LocalBellTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(reversePreview = reversePreview)
                }
            }
        }
    }
}
