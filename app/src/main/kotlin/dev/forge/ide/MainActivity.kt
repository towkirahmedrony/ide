package dev.forge.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.forge.ide.foundation.Foundation
import dev.forge.ide.ui.FoundationScreen
import dev.forge.ide.ui.theme.ForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeTheme {
                val state = remember { Foundation.boot() }
                FoundationScreen(layers = state.layers, health = state.health)
            }
        }
    }
}
