package com.absi.ebb

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.absi.ebb.ui.EbbRoot
import com.absi.ebb.ui.EbbTheme

class MainActivity : ComponentActivity() {

    private var opens by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            EbbTheme {
                key(opens) { EbbRoot(celebrate = intent.getBooleanExtra(EXTRA_CELEBRATE, false)) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opens += 1
    }

    companion object {
        const val EXTRA_CELEBRATE = "celebrate"
    }
}
