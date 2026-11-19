package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ide.IdeViewModel
import com.example.ui.IdeShellScreen

class MainActivity : ComponentActivity() {
  private val ideViewModel: IdeViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      IdeShellScreen(viewModel = ideViewModel)
    }
  }
}
