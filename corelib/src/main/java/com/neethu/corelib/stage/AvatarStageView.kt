package com.neethu.corelib.stage

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.neethu.corelib.AvatarConfig

/**
 * Classic-View 多演员入口 —— [com.neethu.corelib.AvatarSurfaceView] 的
 * 多虚拟人孪生（N 个 VRM 同一个 Scene，见 docs/multi-avatar-feasibility.md）。
 *
 * ```kotlin
 * val stageView = AvatarStageView(this)
 * setContentView(stageView)
 * val alice = stageView.stage.loadAvatar(AvatarSpec(modelAssetPath = "vrms/10.vrm"))
 * ```
 *
 * 生命周期绑定与 AvatarSurfaceView 同款：自动沿 context 链找
 * LifecycleOwner，或显式 [setLifecycleOwner]。destroy 释放整个舞台
 * （Engine/灯光/全部演员/MToon 材质库），视图与舞台都不可复用。
 *
 * @param config 渲染环境配置（仅构造时可读）。
 * @param stage 外部 [AvatarStage]（如 Compose 侧 remember 的实例）；缺省时
 *   视图自建。同舞台重新挂新视图（视图重建）会先释放旧引擎再挂载。
 */
class AvatarStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    config: AvatarConfig = AvatarConfig(),
    stage: AvatarStage? = null,
) : SurfaceView(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    /** 本视图的舞台（外部传入或视图自建；演员经 [AvatarStage.loadAvatar] 上台）。 */
    val stage: AvatarStage = stage ?: AvatarStage(config)

    private var boundOwner: LifecycleOwner? = null
    private var autoBound = false
    private var destroyed = false

    init {
        this.stage.attachToView(this)
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                if (boundOwner == null) tryAutoBindLifecycle()
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                if (autoBound) unbindLifecycle()
            }
        })
    }

    /** 绑定渲染到 [LifecycleOwner]（重复调用=换绑）。 */
    fun setLifecycleOwner(owner: LifecycleOwner) {
        if (boundOwner === owner || destroyed) return
        unbindLifecycle()
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        boundOwner = owner
        owner.lifecycle.addObserver(this)
    }

    private fun unbindLifecycle() {
        boundOwner?.lifecycle?.removeObserver(this)
        boundOwner = null
        autoBound = false
    }

    private fun tryAutoBindLifecycle() {
        if (destroyed) return
        var c: Context = context
        while (true) {
            if (c is LifecycleOwner) {
                setLifecycleOwner(c)
                autoBound = boundOwner === c
                return
            }
            val base = (c as? ContextWrapper)?.baseContext ?: return
            c = base
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        stage.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        stage.onPause(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (destroyed) return
        destroyed = true
        stage.onDestroy(owner)
    }
}

/**
 * Compose 多演员入口 —— [com.neethu.corelib.AvatarView] 的多虚拟人孪生。
 *
 * ```kotlin
 * val stage = rememberAvatarStage()
 * AvatarStageView(modifier = Modifier.fillMaxSize(), stage = stage)
 *
 * val attached by stage.attached.collectAsState()
 * LaunchedEffect(stage, attached) {
 *     if (attached) stage.loadAvatar(AvatarSpec(modelAssetPath = "vrms/10.vrm"))
 * }
 * ```
 *
 * [stage] 由调用方持有（[rememberAvatarStage]），离开组合时随视图释放
 * （Engine 不可复用，与 AvatarView 同约束）。加载时机用 [AvatarStage.attached]
 * 门控——引擎在视图 factory 时才创建。
 */
@Composable
fun AvatarStageView(
    modifier: Modifier = Modifier,
    config: AvatarConfig = AvatarConfig(),
    stage: AvatarStage = rememberAvatarStage(config),
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // 只挂 view 这一个观察者（view 转发给 stage）；重复观察会双泵
            AvatarStageView(ctx, config = config, stage = stage).also { view ->
                lifecycleOwner.lifecycle.addObserver(view)
            }
        },
        onRelease = { view ->
            lifecycleOwner.lifecycle.removeObserver(view)
            stage.onDestroy(lifecycleOwner)
        },
    )
}

/**
 * Remember an [AvatarStage] across recompositions —— 需要在组合外持有舞台
 * 引用时的入口（配合经典 [AvatarStageView] 的外部 stage 参数）。
 */
@Composable
fun rememberAvatarStage(config: AvatarConfig = AvatarConfig()): AvatarStage {
    return remember(config) { AvatarStage(config) }
}
