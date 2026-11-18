package com.snapps91.huesync

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test fun compatibilityActionIsAvailableOnlyDuringGpuCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                var requests = 0
                val screen = MainScreen(activity, SyncOptions(), object : MainScreen.Actions by activity {
                    override fun onUseCompatibilityCapture() { requests++ }
                })
                fun find(view: View): Button? {
                    if (view is Button && view.text == English.TRY_COMPATIBILITY_CAPTURE) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) {
                        find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val button = checkNotNull(find(screen.root))
                screen.render(SyncSnapshot(), false, true, true)
                assertFalse(button.isEnabled)
                screen.render(SyncSnapshot(running=true, canSwitchToRgba=true), false, true, true)
                assertTrue(button.isEnabled)
                button.performClick()
                assertEquals(1, requests)
                screen.render(SyncSnapshot(running=true, canSwitchToRgba=false), false, true, true)
                assertFalse(button.isEnabled)
                screen.render(SyncSnapshot(running=false, canSwitchToRgba=true), false, true, true)
                assertFalse(button.isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun launchesNativeTvControlsWithoutStartingCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var pair: Button
            instrumentation.runOnMainSync {
                val all = mutableListOf<View>()
                fun collect(view: View) {
                    all.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(activity.window.decorView)
                assertTrue(all.filterIsInstance<EditText>().any { it.hint.toString() == "Bridge IP address" })
                pair = all.filterIsInstance<Button>().single { it.text.toString() == "Pair Bridge" }
                assertTrue(all.filterIsInstance<Button>().any { it.text.toString().contains("Start sync") })
                assertFalse(SyncStatus.value.running)
            }
            // A paired TV refreshes its areas asynchronously during launch.
            val deadline = SystemClock.uptimeMillis() + 12_000
            var enabled = false
            while (!enabled && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { enabled = pair.isEnabled }
                if (!enabled) SystemClock.sleep(50)
            }
            assertTrue("Pairing control stayed disabled after startup", enabled)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
