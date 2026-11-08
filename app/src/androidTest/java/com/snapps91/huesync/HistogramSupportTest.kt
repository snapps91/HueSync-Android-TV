package com.snapps91.huesync

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata-only device probe. Does not turn on the hardware histogram engine. */
@RunWith(AndroidJUnit4::class)
class HistogramSupportTest {
    @Test(timeout = 20000) fun queryHardwareHistogramCapabilitiesWithoutEnablingCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val apk = instrumentation.context.applicationInfo.sourceDir
        require(apk.matches(Regex("[A-Za-z0-9_./=~+\\-]+")))
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "/system/bin/env CLASSPATH=$apk /system/bin/app_process /system/bin com.snapps91.huesync.HistogramSupportProbe")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        Log.i("HueSyncHistogram", output.take(16000))
        assertTrue("Read-only probe failed to execute: $output", output.contains("HUE_HISTOGRAM_PROBE"))
        assertTrue("Read-only probe did not return a capability result", output.contains("RESULT="))
    }
}
