package com.toposcribe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.toposcribe.app.ui.nav.TopoScribeRoot
import com.toposcribe.app.ui.theme.TopoScribeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TopoScribeTheme {
                TopoScribeRoot()
            }
        }
    }
}
