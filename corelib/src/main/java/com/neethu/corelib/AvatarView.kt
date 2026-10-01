package com.neethu.corelib

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.neethu.corelib.internal.SoulLinkRenderer

/**
 * Jetpack Compose component that renders a 3D avatar.
 *
 * This is the primary UI entry point of the SDK. Pair it with an
 * [AvatarController] to load models and drive animations:
 *
 * ```kotlin
 * val controller = rememberAvatarController()
 *
 * AvatarView(
 *     modifier = Modifier.fillMaxSize(),
 *     controller = controller,
 *     config = AvatarConfig(iblPath = "default_env.ktx")
 * )
 *
 * LaunchedEffect(Unit) {
 *     controller.loadModel("avatar.vrm")
 * }
 * ```
 *
 * @param modifier Standard Compose [Modifier] for layout.
 * @param controller The [AvatarController] that manages this avatar.
 *   Obtain one via [rememberAvatarController].
 * @param config Rendering environment configuration. See [AvatarConfig].
 */
@Composable
fun AvatarView(
    modifier: Modifier = Modifier,
    controller: AvatarController,
    config: AvatarConfig = AvatarConfig(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                val renderer = SoulLinkRenderer(ctx, this, config)
                controller.attach(renderer)
                lifecycleOwner.lifecycle.addObserver(renderer)
            }
        },
        onRelease = { surfaceView ->
            controller.renderer?.let { renderer ->
                lifecycleOwner.lifecycle.removeObserver(renderer)
                renderer.onDestroy(lifecycleOwner)
            }
            controller.detach()
        }
    )
}
