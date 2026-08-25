/** Process entry point for app-wide Shahbaz infrastructure. */
package ir.hrka.shahbaz

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox

/** Initializes process-owned diagnostics before Activity or feature startup work begins. */
class ShahbazApplication : Application() {
    private var lastLifecycleEvent: FbbEventRef? = null
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        FlightBlackBox.initialize(
            context = this,
            config = FlightBlackBox.configuration(this).read(),
        )
        lastLifecycleEvent = FlightBlackBox.record(
            type = FbbEventType.APP,
            description = "ShahbazApplication.onCreate()",
            cause = FlightBlackBox.processStartEvent(),
            persistence = FbbPersistence.IMPORTANT,
        )
        registerActivityLifecycleCallbacks(FlightBlackBoxActivityCallbacks())
    }

    private inner class FlightBlackBoxActivityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            lifecycle(
                activity = activity,
                description = "${activity.localClassName}.onCreate()",
                metadata = mapOf("hasSavedState" to (savedInstanceState != null)),
            )
        }

        override fun onActivityStarted(activity: Activity) {
            if (startedActivities == 0) {
                lifecycle(
                    activity = activity,
                    description = "Shahbaz process entered foreground",
                    persistence = FbbPersistence.IMPORTANT,
                )
            }
            startedActivities += 1
            lifecycle(activity, "${activity.localClassName}.onStart()")
        }

        override fun onActivityResumed(activity: Activity) {
            lifecycle(activity, "${activity.localClassName}.onResume()")
        }

        override fun onActivityPaused(activity: Activity) {
            lifecycle(activity, "${activity.localClassName}.onPause()")
        }

        override fun onActivityStopped(activity: Activity) {
            lifecycle(activity, "${activity.localClassName}.onStop()")
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                lifecycle(
                    activity = activity,
                    description = "Shahbaz process entered background",
                    persistence = FbbPersistence.IMPORTANT,
                )
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            lifecycle(activity, "${activity.localClassName}.onSaveInstanceState()")
        }

        override fun onActivityDestroyed(activity: Activity) {
            lifecycle(activity, "${activity.localClassName}.onDestroy()")
        }

        private fun lifecycle(
            activity: Activity,
            description: String,
            metadata: Map<String, Any?> = emptyMap(),
            persistence: FbbPersistence = FbbPersistence.NORMAL,
        ) {
            lastLifecycleEvent = FlightBlackBox.record(
                type = FbbEventType.LIFECYCLE,
                description = description,
                cause = lastLifecycleEvent,
                metadata = metadata + mapOf("activity" to activity.javaClass.simpleName),
                persistence = persistence,
            )
        }
    }
}
