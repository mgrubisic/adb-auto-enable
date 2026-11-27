package com.tpn.adbautoenable;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
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
        ensureAccessibilityServiceEnabled(context);
        KeepAliveJobService.schedule(context);
        ensureTclAutostartProtectionAsync(context);
        new Thread(() -> new BootConfigurator(context.getApplicationContext())
                .enableWirelessDebuggingImmediately(), "enable-wifi-adb").start();
    }

    /**
     * Keeps the KeepAlive accessibility service enabled without the user having
     * to re-toggle it in Settings. The accessibility binding is the strongest
     * keep-alive signal (high process priority, Doze/App-Standby exemption,
     * automatic restart), so if some OEM build or an app update drops it, this
     * re-adds it. Requires WRITE_SECURE_SETTINGS, which the app self-grants via
     * ADB after pairing; before that this is a harmless no-op. Existing enabled
     * services are preserved, never overwritten.
     */
    static void ensureAccessibilityServiceEnabled(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName service = new ComponentName(context, KeepAliveAccessibilityService.class);

            String enabled = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                enabled = "";
            }

            boolean present = false;
            for (String part : enabled.split(":")) {
                if (part.isEmpty()) {
                    continue;
                }
                ComponentName existing = ComponentName.unflattenFromString(part);
                if (service.equals(existing) || service.flattenToString().equals(part)) {
                    present = true;
                    break;
                }
            }

            if (!present) {
                String updated = TextUtils.isEmpty(enabled)
                        ? service.flattenToString()
                        : enabled + ":" + service.flattenToString();
                Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Log.i(TAG, "Re-added KeepAlive accessibility service to the enabled list");
            }

            // Master accessibility switch must be on for enabled services to bind.
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted yet; skipping accessibility self-enable");
        } catch (Exception e) {
            Log.e(TAG, "Could not ensure accessibility service enabled", e);
        }
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
