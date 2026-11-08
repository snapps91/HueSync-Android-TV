package com.snapps91.huesync

import android.content.Intent
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
    @Test fun launchesNativeTvControlsWithoutStartingCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val all = mutableListOf<View>()
                fun collect(view: View) {
                    all.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(activity.window.decorView)
                assertTrue(all.filterIsInstance<EditText>().any { it.hint.toString() == "Bridge IP address" })
                assertTrue(all.filterIsInstance<Button>().any { it.text.toString() == "Pair Bridge" && it.isEnabled })
                assertTrue(all.filterIsInstance<Button>().any { it.text.toString().contains("Start sync") })
                assertFalse(SyncStatus.value.running)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
