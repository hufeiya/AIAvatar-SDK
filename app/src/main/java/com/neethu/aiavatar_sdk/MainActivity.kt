package com.neethu.aiavatar_sdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAvatarSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    com.neethu.corelib.AvatarView(
                        modifier = Modifier.padding(innerPadding).fillMaxSize(),
                        modelPath = "model.glb",
                        iblPath = "default_env.ktx"
                    )
                }
            }
        }
    }
}