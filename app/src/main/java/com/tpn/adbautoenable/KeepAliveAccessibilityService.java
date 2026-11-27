package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "KeepAlive accessibility service connected");
        try {
            AppController.startBackgroundRuntime(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Background runtime start from accessibility service failed", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: servis služi isključivo za održavanje procesa aktivnim.
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "KeepAlive accessibility service interrupted");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.w(TAG, "KeepAlive accessibility service unbound");
        AppController.releaseKeepAliveLocks();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "KeepAlive accessibility service destroyed");
        AppController.stopBackgroundWatchdog();
        AppController.releaseKeepAliveLocks();
        super.onDestroy();
    }
}