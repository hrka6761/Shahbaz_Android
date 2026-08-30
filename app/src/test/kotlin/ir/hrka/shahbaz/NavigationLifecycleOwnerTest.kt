package ir.hrka.shahbaz

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the NavigationLifecycleOwnerTest type and the role it plays in this module.
 */
class NavigationLifecycleOwnerTest {
    /**
     * Runs the destroy dispatches complete teardown to an active map lifecycle operation.
     */
    @Test
    fun `destroy dispatches complete teardown to an active map lifecycle`() {
        val parent = TestLifecycleOwner()
        val owner = testNavigationLifecycleOwner(parent.lifecycle)
        val observedEvents = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> observedEvents += event }
        )

        owner.attach()
        parent.handle(Lifecycle.Event.ON_CREATE)
        parent.handle(Lifecycle.Event.ON_START)
        parent.handle(Lifecycle.Event.ON_RESUME)
        owner.destroy()

        assertEquals(
            listOf(
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
            ),
            observedEvents,
        )
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    /**
     * Runs the destroyed map lifecycle stops following its parent operation.
     */
    @Test
    fun `destroyed map lifecycle stops following its parent`() {
        val parent = TestLifecycleOwner()
        val owner = testNavigationLifecycleOwner(parent.lifecycle)
        val observedEvents = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> observedEvents += event }
        )
        owner.attach()
        parent.handle(Lifecycle.Event.ON_CREATE)
        parent.handle(Lifecycle.Event.ON_START)
        owner.destroy()

        val eventCountAfterDestroy = observedEvents.size
        parent.handle(Lifecycle.Event.ON_RESUME)
        owner.destroy()

        assertEquals(eventCountAfterDestroy, observedEvents.size)
    }

    /**
     * Runs the owner attached after parent resume catches up before map creation operation.
     */
    @Test
    fun `owner attached after parent resume catches up before map creation`() {
        val parent = TestLifecycleOwner()
        parent.handle(Lifecycle.Event.ON_CREATE)
        parent.handle(Lifecycle.Event.ON_START)
        parent.handle(Lifecycle.Event.ON_RESUME)
        val owner = testNavigationLifecycleOwner(parent.lifecycle)
        val observedEvents = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> observedEvents += event }
        )

        owner.attach()

        assertEquals(
            listOf(
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
            ),
            observedEvents,
        )
    }

    /**
     * Runs the every lifecycle state has a legal destroy sequence operation.
     */
    @Test
    fun `every lifecycle state has a legal destroy sequence`() {
        assertEquals(
            listOf(Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY),
            lifecycleTeardownEvents(Lifecycle.State.STARTED),
        )
        assertEquals(
            listOf(Lifecycle.Event.ON_DESTROY),
            lifecycleTeardownEvents(Lifecycle.State.CREATED),
        )
        assertEquals(
            listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY),
            lifecycleTeardownEvents(Lifecycle.State.INITIALIZED),
        )
        assertEquals(
            emptyList<Lifecycle.Event>(),
            lifecycleTeardownEvents(Lifecycle.State.DESTROYED),
        )
    }

    /**
     * Documents the TestLifecycleOwner type and the role it plays in this module.
     */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle = registry

        /**
         * Runs the handle operation.
         */
        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    /**
     * Runs the testNavigationLifecycleOwner operation.
     */
    private fun testNavigationLifecycleOwner(parentLifecycle: Lifecycle) =
        NavigationLifecycleOwner(
            parentLifecycle = parentLifecycle,
            lifecycleRegistryFactory = LifecycleRegistry::createUnsafe,
        )
}
