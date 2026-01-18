package com.neethu.corelib

import android.view.SurfaceView
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun AvatarView(
    modifier: Modifier = Modifier,
    modelPath: String? = null,
    iblPath: String? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                val renderer = SoulLinkRenderer(ctx, this)
                this.tag = renderer
                lifecycleOwner.lifecycle.addObserver(renderer)
            }
        },
        update = { surfaceView ->
            val renderer = surfaceView.tag as? SoulLinkRenderer
            if (renderer != null) {
                 if (modelPath != null) renderer.loadModel(modelPath)
                 if (iblPath != null) renderer.loadEnvironment(iblPath)
            }
        },
        onRelease = { surfaceView ->
            val renderer = surfaceView.tag as? SoulLinkRenderer
            if (renderer != null) {
                lifecycleOwner.lifecycle.removeObserver(renderer)
                // We strictly don't need to call onDestroy() manually if the lifecycle destroys it,
                // but onRelease might happen before onDestroy (e.g. removed from composition but Activity alive).
                // So checking if lifecycle is destroyed?
                // Actually, if View is removed, we MUST destroy renderer.
                // And remove observer.
                renderer.onDestroy(lifecycleOwner) 
            }
        }
    )
}

