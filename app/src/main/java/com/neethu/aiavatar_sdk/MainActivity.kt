package com.neethu.aiavatar_sdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarView
import com.neethu.corelib.rememberAvatarController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAvatarSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = rememberAvatarController()
    val state by controller.state.collectAsState()

    // List all files under assets/vrms/
    val modelFiles = remember {
        try {
            context.assets.list("vrms")
                ?.filter { it.endsWith(".glb") || it.endsWith(".vrm") }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    var selectedModel by remember { mutableStateOf("model.glb") }
    var showList by remember { mutableStateOf(false) }

    // Load the selected model whenever it changes
    LaunchedEffect(selectedModel) {
        controller.loadModel("vrms/$selectedModel")
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 3D Avatar (full-screen background)
        AvatarView(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            config = AvatarConfig(iblPath = "default_env.ktx")
        )

        // Toggle FAB
        SmallFloatingActionButton(
            onClick = { showList = !showList },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = if (showList) Icons.Default.Close else Icons.Default.List,
                contentDescription = if (showList) "Hide models" else "Show models"
            )
        }

        // Model selector overlay at the bottom
        AnimatedVisibility(
            visible = showList,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Models",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(modelFiles) { fileName ->
                            val isSelected = fileName == selectedModel
                            val bgColor by animateColorAsState(
                                targetValue = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface,
                                label = "itemBg"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bgColor)
                                    .clickable {
                                        selectedModel = fileName
                                        showList = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = fileName,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}