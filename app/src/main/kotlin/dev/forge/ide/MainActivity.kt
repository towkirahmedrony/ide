package dev.forge.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.forge.ide.foundation.Foundation
import dev.forge.ide.ui.ide.ForgeIdeApp
import dev.forge.ide.ui.ide.IdeDependencies
import dev.forge.ide.ui.theme.ForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeTheme {
                // The core foundation still boots (health + layers); its status
                // is now surfaced from Settings → About → Developer information.
                val foundation = remember { Foundation.boot() }
                val dependencies = remember { IdeDependencies.mock() }
                ForgeIdeApp(
                    dependencies = dependencies,
                    appName = foundation.config.appName,
                    version = "0.1.0",
                    layers = foundation.layers,
                    health = foundation.health,
                )
            }
        }
    }
}
