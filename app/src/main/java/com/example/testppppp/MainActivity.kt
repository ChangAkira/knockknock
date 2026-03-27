package com.example.testppppp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.testppppp.ui.TapDirectionApp
import com.example.testppppp.ui.theme.TESTPPPPPTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TESTPPPPPTheme {
                TapDirectionApp()
            }
        }
    }
}