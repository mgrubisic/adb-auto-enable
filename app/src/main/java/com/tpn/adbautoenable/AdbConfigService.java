package com.tpn.adbautoenable;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Plain (NOT foreground) service that hosts the web interface while the user is
 * configuring the app. It is started from MainActivity only, so the app never
 * appears under "Active in background" and shows no persistent notification.
 * Android stops it on its own some time after the app leaves the screen.
 * Boot-time configuration runs in BootConfigJobService instead.
 */
public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_TARGET_PORT = "target_port";
    private static final int WEB_SERVER_PORT = 9093;

    private WebServer webServer;

    private SharedPreferences getPrefs() {
        return NetworkUtils.getDeviceProtectedPrefs(this, PREFS_NAME);
    }

    // Some devices disable BootReceiver behind the app's back, which takes it out
    // of the BOOT_COMPLETED resolution set and stops the app ever starting at
    // boot. A shell cannot undo that for an app that is not test-only, and
    // reinstalling loses the app's adb key, but the app may set its own
    // components, so it is repaired here on every service start.
    private void ensureBootReceiverEnabled() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName receiver = new ComponentName(this, BootReceiver.class);
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

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConfigService onCreate() called");
        ensureBootReceiverEnabled();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AdbConfigService onStartCommand() called");
        try {
            ensureTclAutostartProtectionAsync();
            new BootConfigurator(this).enableWirelessDebuggingImmediately();

            if (getPrefs().getBoolean("web_server_enabled", true)) {
                if (webServer == null || !webServer.isAlive()) {
                    startWebServer();
                }
            } else {
                stopWebServerAndSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand", e);
        }
        // Never ask Android to restart this service in the background.
        return START_NOT_STICKY;
    }

    /** Called when the web interface is switched off from the browser. */
    void stopWebServerAndSelf() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            Log.i(TAG, "Web server stopped");
        }
        stopSelf();
    }

    private void ensureTclAutostartProtectionAsync() {
        new Thread(() -> {
            TclAutostartProtection.Result policy = TclAutostartProtection.apply(
                    getContentResolver(),
                    getPackageName()
            );
            if (!policy.available) {
                return;
            }
            if (!policy.success) {
                Log.w(TAG, "TCL AppBootPolicy protection could not be applied");
                return;
            }

            SharedPreferences prefs = getPrefs();
            if (!prefs.getBoolean("is_paired", false)) {
                Log.i(TAG, "TCL AppBootPolicy applied; ADB pairing is needed for AUTO_START app-op");
                return;
            }

            int targetPort = getTargetPort();
            AdbHelper adbHelper = new AdbHelper(this);
            boolean allowed = adbHelper.ensureTclAutoStart(
                    getDeviceIP(),
                    targetPort,
                    getPackageName()
            );
            if (!allowed) {
                Log.w(TAG, "TCL AUTO_START app-op could not be verified");
            }
        }, "tcl-autostart-protection").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "AdbConfigService onDestroy() called");
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            Log.i(TAG, "Web server stopped");
        }
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(this, WEB_SERVER_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + WEB_SERVER_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start web server", e);
        }
    }

    private String getDeviceIP() {
        return NetworkUtils.getLiveDeviceIP(this);
    }

    private int getTargetPort() {
        return getPrefs().getInt(KEY_TARGET_PORT, 5555);
    }
}
