package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
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
 *
 * While connected it also holds a high-performance Wi-Fi lock and a multicast
 * lock. On many Android TV / Chromecast builds the network stack powers down in
 * standby, which is what makes the ADB target port unreachable after a few hours
 * of sleep; these locks keep Wi-Fi fully awake so the port stays reachable.
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable";

    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "KeepAlive accessibility service connected");
        acquireNetworkLocks();
        try {
            AppController.runStartupChecks(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Startup checks from accessibility service failed", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireNetworkLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                Log.w(TAG, "Wi-Fi manager unavailable; cannot hold network locks");
                return;
            }
            if (wifiLock == null) {
                // HIGH_PERF keeps Wi-Fi from entering power-save while held.
                wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ADBAutoEnable:wifi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("ADBAutoEnable:multicast");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) {
                multicastLock.acquire();
            }
            Log.i(TAG, "Wi-Fi and multicast locks acquired to keep the network up in standby");
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire Wi-Fi/multicast locks", e);
        }
    }

    private void releaseNetworkLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not release Wi-Fi/multicast locks", e);
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

    @Override
    public boolean onUnbind(Intent intent) {
        releaseNetworkLocks();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        releaseNetworkLocks();
        super.onDestroy();
    }
}
