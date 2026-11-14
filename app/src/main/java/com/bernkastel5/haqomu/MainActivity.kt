package com.bernkastel5.haqomu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bernkastel5.haqomu.ui.SetupScreen
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaqomuTheme {
                SetupScreen()
            }
        }
    }
}
