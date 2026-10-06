package com.neethu.corelib

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.neethu.corelib.internal.SoulLinkRenderer

/**
 * Classic-View entry point of the SDK — the non-Compose twin of [AvatarView].
 *
 * Renders the 3D avatar into a plain [SurfaceView] so traditional View/XML
 * projects can integrate without any Compose dependency. Pair it with an
 * [AvatarController] to load models and drive animations:
 *
 * ```kotlin
 * // Activity onCreate — programmatic, or inflate <com.neethu.corelib.AvatarSurfaceView/>
 * val avatarView = AvatarSurfaceView(this)
 * setContentView(avatarView)
 *
 * // Lifecycle is auto-bound to the enclosing Activity/Fragment when found;
 * // explicit wiring works too: avatarView.setLifecycleOwner(lifecycle)
 *
 * avatarView.controller.loadModel("avatar.vrm")
 * ```
 *
 * The same [controller] instance plugs into `AvatarSession` for the
 * conversational pipeline (see the orchestrator module).
 *
 * Must be constructed on the main thread (it creates the Filament engine).
 * The view owns an internal renderer ([SoulLinkRenderer]) attached to
 * [controller]; destroying the lifecycle destroys the renderer and detaches
 * the controller, after which the view must not be reused.
 *
 * @param config Rendering environment configuration (see [AvatarConfig]).
 *   Only readable at construction — pass it in the constructor; XML inflation
 *   gets the defaults.
 * @param controller An external [AvatarController] to bind; omit to let the
 *   view create and own one (retrieve it via [controller]).
 */
class AvatarSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    config: AvatarConfig = AvatarConfig(),
    controller: AvatarController? = null,
) : SurfaceView(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    /**
     * The controller driving this view — either the one passed to the
     * constructor or an internally created instance. Pass it to
     * `AvatarSession` / use it for `loadModel`, `playAnimation`, …
     */
    val controller: AvatarController = controller ?: AvatarController()

    private val renderer: SoulLinkRenderer = SoulLinkRenderer(context, this, config)

    /** The lifecycle this view is currently observing (explicit or auto-found). */
    private var boundOwner: LifecycleOwner? = null

    /** True when [boundOwner] was found automatically (unbound on detach). */
    private var autoBound = false

    /** Destruction guard: no re-binding after the lifecycle destroyed the view. */
    private var destroyed = false

    init {
        this.controller.attach(renderer) // this.：构造参数与属性同名，显式指属性
        // View detach/attach without a lifecycle change (manual re-parenting)
        // must still bind a lifecycle when none was set — otherwise the frame
        // loop never starts and the surface stays black.
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                if (boundOwner == null) tryAutoBindLifecycle()
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                if (autoBound) unbindLifecycle()
            }
        })
    }

    /**
     * Bind rendering to a [LifecycleOwner]: resume starts the frame loop,
     * pause stops it, destroy releases the engine. Calling again moves the
     * binding. `lifecycle.addObserver(view)` reaches the same code (the view
     * is a [DefaultLifecycleObserver]); this method additionally manages the
     * previous binding.
     */
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

    /**
     * Glide-style automatic lifecycle discovery: walk the context chain and
     * bind the first [LifecycleOwner] found (Activity / Fragment contexts).
     * When none exists (plain application context), rendering only starts
     * after an explicit [setLifecycleOwner].
     */
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

    // ── DefaultLifecycleObserver → renderer forwarding ─────────────────────

    override fun onResume(owner: LifecycleOwner) {
        renderer.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        renderer.onPause(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (destroyed) return
        destroyed = true
        renderer.onDestroy(owner)
        controller.detach()
    }
}
