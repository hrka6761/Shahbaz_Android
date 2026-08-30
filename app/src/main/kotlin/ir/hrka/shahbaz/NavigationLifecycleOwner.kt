/** Navigation-scoped lifecycle support for native views hosted by conditional Compose screens. */
package ir.hrka.shahbaz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Mirrors a parent lifecycle until navigation explicitly destroys this screen lifecycle.
 *
 * MapLibre observes [lifecycle] and releases its native renderer on `ON_DESTROY`. Conditional
 * Compose removal alone only unregisters that observer, so map-bearing destinations destroy this
 * owner before changing the branch that contains their `MapView`.
 */
internal class NavigationLifecycleOwner(
    /**
     * Exposes the parentLifecycle value.
     */
    private val parentLifecycle: Lifecycle,
    lifecycleRegistryFactory: (LifecycleOwner) -> LifecycleRegistry = { owner ->
        LifecycleRegistry(owner)
    },
) : LifecycleOwner, LifecycleEventObserver {
    /**
     * Exposes the registry value.
     */
    private val registry = lifecycleRegistryFactory(this)
    /**
     * Stores the mutable attached value.
     */
    private var attached = false
    /**
     * Stores the mutable destroyed value.
     */
    private var destroyed = false

    /**
     * Exposes the lifecycle value.
     */
    override val lifecycle: Lifecycle = registry

    /** Starts mirroring the parent and immediately catches up to its current lifecycle state. */
    fun attach() {
        if (attached || destroyed) return
        attached = true
        parentLifecycle.addObserver(this)
    }

    /** Forwards host lifecycle events while this navigation destination remains active. */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_DESTROY -> destroy()
            Lifecycle.Event.ON_ANY -> Unit
            else -> registry.handleLifecycleEvent(event)
        }
    }

    /** Delivers every required downward event exactly once before native views leave composition. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (attached) parentLifecycle.removeObserver(this)
        lifecycleTeardownEvents(registry.currentState).forEach(registry::handleLifecycleEvent)
    }
}

/** Returns the legal downward event sequence required to destroy a lifecycle from [state]. */
internal fun lifecycleTeardownEvents(state: Lifecycle.State): List<Lifecycle.Event> = when (state) {
    Lifecycle.State.RESUMED -> listOf(
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_DESTROY,
    )

    Lifecycle.State.STARTED -> listOf(
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_DESTROY,
    )

    Lifecycle.State.CREATED -> listOf(Lifecycle.Event.ON_DESTROY)
    Lifecycle.State.INITIALIZED -> listOf(
        Lifecycle.Event.ON_CREATE,
        Lifecycle.Event.ON_DESTROY,
    )

    Lifecycle.State.DESTROYED -> emptyList()
}

/** Creates one lifecycle owner for a navigation destination and closes it with its host. */
@Composable
internal fun rememberNavigationLifecycleOwner(): NavigationLifecycleOwner {
    val parentLifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parentLifecycle) {
        NavigationLifecycleOwner(parentLifecycle)
    }
    DisposableEffect(owner) {
        owner.attach()
        onDispose(owner::destroy)
    }
    return owner
}
