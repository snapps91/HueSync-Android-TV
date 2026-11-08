package com.snapps91.huesync;

import java.lang.reflect.InvocationTargetException;

/** Read-only capability query, executed by the already-authorized ADB shell.
 * Does not enable sampling, request pixel buffers, change permissions, or read histogram values.
 * Java-only so app_process can load it directly from the test APK without a Kotlin runtime.
 */
public final class HistogramSupportProbe {
    public static void main(String[] args) {
        System.out.println("HUE_HISTOGRAM_PROBE uid=" + android.os.Process.myUid());
        try {
            Class<?> surface = Class.forName("android.view.SurfaceControl");
            long[] ids = (long[]) surface.getDeclaredMethod("getPhysicalDisplayIds").invoke(null);
            System.out.println("PHYSICAL_DISPLAYS=" + ids.length);
            boolean supported = false;
            for (long id : ids) {
                System.out.println("DISPLAY=" + id);
                Object token = surface.getDeclaredMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
                Object attributes = surface.getDeclaredMethod("getDisplayedContentSamplingAttributes", android.os.IBinder.class)
                        .invoke(null, token);
                if (attributes == null) {
                    System.out.println("ATTRIBUTES=UNSUPPORTED_OR_NO_ATTRIBUTES");
                    continue;
                }
                supported = true;
                Class<?> type = attributes.getClass();
                for (String name : new String[]{"getPixelFormat", "getDataspace", "getComponentMask"}) {
                    System.out.println(name + "=" + type.getDeclaredMethod(name).invoke(attributes));
                }
            }
            System.out.println("RESULT=" + (supported ? "SUPPORTED" : "UNSUPPORTED_OR_NO_ATTRIBUTES"));
        } catch (Throwable error) {
            Throwable root = error;
            while (root instanceof InvocationTargetException && root.getCause() != null) root = root.getCause();
            System.out.println("RESULT=" + (root instanceof SecurityException ? "ACCESS_DENIED" : "API_UNAVAILABLE"));
            System.out.println(root.getClass().getName() + ": " + root.getMessage());
        }
    }
}
