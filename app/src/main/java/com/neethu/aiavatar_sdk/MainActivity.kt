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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Build
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
                    DemoScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** Which panel is currently shown */
private enum class PanelType { NONE, MODELS, ANIMATIONS, EXPRESSIONS, SCENES }

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

    // List all .vrma files under assets/animations/
    val animationFiles = remember {
        try {
            context.assets.list("animations")
                ?.filter { it.endsWith(".vrma") }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // List all .glb files under assets/scene/
    val sceneFiles = remember {
        try {
            context.assets.list("scene")
                ?.filter { it.endsWith(".glb") }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    var selectedModel by remember { mutableStateOf("model.glb") }
    var selectedAnimation by remember { mutableStateOf<String?>(null) }
    var selectedExpression by remember { mutableStateOf<String?>(null) }
    var selectedScene by remember { mutableStateOf(sceneFiles.firstOrNull()) }
    var activePanel by remember { mutableStateOf(PanelType.NONE) }
    var isDragMode by remember { mutableStateOf(false) }

    // Preset expression names (used as fallback if model has none)
    val presetExpressions = remember {
        listOf("happy", "sad", "angry", "surprised", "relaxed", "blink",
            "blinkLeft", "blinkRight", "aa", "ih", "ou", "ee", "oh", "neutral")
    }

    // Use model-parsed expressions if available, otherwise use presets
    val expressionList = remember(state) {
        val modelExpressions = (state as? AvatarState.Ready)?.expressions ?: emptyList()
        if (modelExpressions.isNotEmpty()) modelExpressions else presetExpressions
    }

    // Load the selected model whenever it changes
    LaunchedEffect(selectedModel) {
        selectedExpression = null
        controller.clearAllExpressions()
        controller.loadModel("vrms/$selectedModel")
    }

    // Load the selected scene whenever it changes
    LaunchedEffect(selectedScene) {
        selectedScene?.let { scene ->
            controller.loadScene("scene/$scene")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 3D Avatar (full-screen background)
        AvatarView(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            config = AvatarConfig(iblPath = "default_env.ktx")
        )

        LaunchedEffect(isDragMode) {
            controller.setDragMode(isDragMode)
        }

        // Drag mode FAB at bottom-start
        SmallFloatingActionButton(
            onClick = { isDragMode = !isDragMode },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            shape = CircleShape,
            containerColor = if (isDragMode)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
            else
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        ) {
            Icon(
                imageVector = if (isDragMode) Icons.Default.Close else Icons.Default.Build,
                contentDescription = if (isDragMode) "Exit drag mode" else "Enter drag mode"
            )
        }

        // Row of FABs at bottom-end
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Scene FAB (🏠 Home icon)
            SmallFloatingActionButton(
                onClick = {
                    activePanel = if (activePanel == PanelType.SCENES) PanelType.NONE else PanelType.SCENES
                },
                shape = CircleShape,
                containerColor = if (activePanel == PanelType.SCENES)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (activePanel == PanelType.SCENES) Icons.Default.Close else Icons.Default.Home,
                    contentDescription = if (activePanel == PanelType.SCENES) "Hide scenes" else "Show scenes"
                )
            }

            // Expression FAB (😊 Face icon)
            SmallFloatingActionButton(
                onClick = {
                    activePanel = if (activePanel == PanelType.EXPRESSIONS) PanelType.NONE else PanelType.EXPRESSIONS
                },
                shape = CircleShape,
                containerColor = if (activePanel == PanelType.EXPRESSIONS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (activePanel == PanelType.EXPRESSIONS) Icons.Default.Close else Icons.Default.Face,
                    contentDescription = if (activePanel == PanelType.EXPRESSIONS) "Hide expressions" else "Show expressions"
                )
            }

            // Animation FAB
            SmallFloatingActionButton(
                onClick = {
                    activePanel = if (activePanel == PanelType.ANIMATIONS) PanelType.NONE else PanelType.ANIMATIONS
                },
                shape = CircleShape,
                containerColor = if (activePanel == PanelType.ANIMATIONS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (activePanel == PanelType.ANIMATIONS) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (activePanel == PanelType.ANIMATIONS) "Hide animations" else "Show animations"
                )
            }

            // Model FAB
            SmallFloatingActionButton(
                onClick = {
                    activePanel = if (activePanel == PanelType.MODELS) PanelType.NONE else PanelType.MODELS
                },
                shape = CircleShape,
                containerColor = if (activePanel == PanelType.MODELS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (activePanel == PanelType.MODELS) Icons.Default.Close else Icons.Default.List,
                    contentDescription = if (activePanel == PanelType.MODELS) "Hide models" else "Show models"
                )
            }
        }

        // Model selector overlay at the bottom
        AnimatedVisibility(
            visible = activePanel == PanelType.MODELS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Models",
                items = modelFiles,
                selectedItem = selectedModel,
                onItemClick = { fileName ->
                    selectedModel = fileName
                    selectedAnimation = null // reset animation on model switch
                    activePanel = PanelType.NONE
                }
            )
        }

        // Animation selector overlay at the bottom
        AnimatedVisibility(
            visible = activePanel == PanelType.ANIMATIONS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Animations",
                items = animationFiles,
                selectedItem = selectedAnimation,
                displayName = { it.removeSuffix(".vrma") },
                onItemClick = { fileName ->
                    selectedAnimation = fileName
                    controller.loadVrmaAnimation("animations/$fileName")
                    controller.playVrmaAnimation(loop = true)
                    activePanel = PanelType.NONE
                }
            )
        }

        // Expression selector overlay at the bottom
        AnimatedVisibility(
            visible = activePanel == PanelType.EXPRESSIONS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Expressions",
                items = expressionList,
                selectedItem = selectedExpression,
                onItemClick = { name ->
                    if (selectedExpression == name) {
                        // Toggle off — clear the expression
                        controller.clearAllExpressions()
                        selectedExpression = null
                    } else {
                        // Apply the new expression at full weight
                        controller.clearAllExpressions()
                        controller.setExpression(name, 1.0f)
                        selectedExpression = name
                    }
                }
            )
        }

        // Scene selector overlay at the bottom
        AnimatedVisibility(
            visible = activePanel == PanelType.SCENES,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Scenes",
                items = sceneFiles,
                selectedItem = selectedScene,
                displayName = { it.removeSuffix(".glb").replace("_", " ") },
                onItemClick = { fileName ->
                    selectedScene = fileName
                    activePanel = PanelType.NONE
                }
            )
        }
    }
}

/**
 * Reusable list panel component for models and animations.
 */
@Composable
private fun ListPanel(
    title: String,
    items: List<String>,
    selectedItem: String?,
    displayName: (String) -> String = { it },
    onItemClick: (String) -> Unit
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
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items) { fileName ->
                    val isSelected = fileName == selectedItem
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
                            .clickable { onItemClick(fileName) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName(fileName),
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