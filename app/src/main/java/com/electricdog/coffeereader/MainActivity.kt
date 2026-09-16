package com.electricdog.coffeereader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.electricdog.coffeereader.reader.CoffeeReaderApp
import com.electricdog.coffeereader.reader.ReaderViewModel
import com.electricdog.coffeereader.ui.theme.CoffeeReaderTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private lateinit var reader: ReaderViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reader = ViewModelProvider(this)[ReaderViewModel::class.java]
        setContent {
            CoffeeReaderTheme {
                CoffeeReaderApp(reader)
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) reader.recordVisit()
        super.onStop()
    }
}
