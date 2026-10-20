package com.example.railtune

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.railtune.ui.screens.MainScreen
import com.example.railtune.ui.theme.RailTuneTheme
import com.example.railtune.viewmodel.BleViewModel

class MainActivity : ComponentActivity() {

    private val bleViewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            RailTuneTheme {
                MainScreen(viewModel = bleViewModel)
            }
        }
    }
}
