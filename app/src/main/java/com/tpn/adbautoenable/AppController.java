package com.tpn.adbautoenable;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;

/**
 * Hosts the web interface WITHOUT any Android service. The server thread lives
 * in the app process only while the user has the app open (and until Android
 * reclaims the process), so the app has no running service and is never listed
 * among apps active in the background.
 */
final class AppController {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_TARGET_PORT = "target_port";
    static final int WEB_SERVER_PORT = 9093;

    private static WebServer webServer;

    private AppController() {}

    static synchronized void startWebServer(Context context) {
        if (webServer != null && webServer.isAlive()) {
            return;
        }
        try {
            webServer = new WebServer(context.getApplicationContext(), WEB_SERVER_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + WEB_SERVER_PORT);
        } catch (IOException e) {
            webServer = null;
            Log.e(TAG, "Failed to start web server", e);
        }
    }

    static synchronized void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            Log.i(TAG, "Web server stopped");
        }
    }

    /** Quick, non-blocking upkeep that used to run on every service start. */
    static void runStartupChecks(Context context) {
        ensureBootReceiverEnabled(context);
        ensureTclAutostartProtectionAsync(context);
        new Thread(() -> new BootConfigurator(context.getApplicationContext())
                .enableWirelessDebuggingImmediately(), "enable-wifi-adb").start();
    }

    static void ensureBootReceiverEnabled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName receiver = new ComponentName(context, BootReceiver.class);
            // Anything that is not enabled, rather than the one disabled state:
            // DISABLED_USER and DISABLED_UNTIL_USED keep it out of the resolution
            // set just as surely, and DEFAULT means the manifest value, enabled.
            int state = pm.getComponentEnabledSetting(receiver);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(receiver,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "BootReceiver was disabled, re-enabled it");
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not check or re-enable BootReceiver", e);
        }
    }

    static void ensureTclAutostartProtectionAsync(Context context) {
        new Thread(() -> {
            TclAutostartProtection.Result policy = TclAutostartProtection.apply(
                    context.getContentResolver(),
                    context.getPackageName()
            );
            if (!policy.available) {
                return;
            }
            if (!policy.success) {
                Log.w(TAG, "TCL AppBootPolicy protection could not be applied");
                return;
            }

            SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(context, PREFS_NAME);
            if (!prefs.getBoolean("is_paired", false)) {
                Log.i(TAG, "TCL AppBootPolicy applied; ADB pairing is needed for AUTO_START app-op");
                return;
            }

            int targetPort = prefs.getInt(KEY_TARGET_PORT, 5555);
            AdbHelper adbHelper = new AdbHelper(context);
            boolean allowed = adbHelper.ensureTclAutoStart(
                    NetworkUtils.getLiveDeviceIP(context),
                    targetPort,
                    context.getPackageName()
            );
            if (!allowed) {
                Log.w(TAG, "TCL AUTO_START app-op could not be verified");
            }
        }, "tcl-autostart-protection").start();
    }
}
