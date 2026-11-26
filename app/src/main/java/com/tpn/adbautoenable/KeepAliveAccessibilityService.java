package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

/**
 * Accessibility service used ONLY to keep the app process alive so the system
 * does not freeze or kill it. While enabled in Settings -> Accessibility, Android
 * binds this service, gives the process high priority, exempts it from battery
 * optimisation/Doze app-standby, and restarts it if it is killed.
 *
 * It intentionally handles no accessibility events. When it (re)connects it runs
 * the same lightweight startup checks the app does on launch, so wireless ADB is
 * re-asserted whenever the service comes up (e.g. after a restart by the system).
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "KeepAlive accessibility service connected");
        try {
            AppController.runStartupChecks(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Startup checks from accessibility service failed", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: this service exists only to keep the process alive.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }
}
