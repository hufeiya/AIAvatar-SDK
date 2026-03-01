package com.neethu.aiavatar_sdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.rememberAvatarController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAvatarSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val controller = rememberAvatarController()
                    val state by controller.state.collectAsState()

                    AvatarView(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        controller = controller,
                        config = AvatarConfig(
                            iblPath = "default_env.ktx"
                        )
                    )

                    // Load the model once the view is attached
                    LaunchedEffect(Unit) {
                        controller.loadModel("model.glb")
                    }
                }
            }
        }
    }
}