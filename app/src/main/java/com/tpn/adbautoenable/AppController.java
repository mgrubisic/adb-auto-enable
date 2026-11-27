package com.tpn.adbautoenable;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppController {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_TARGET_PORT = "target_port";
    static final int WEB_SERVER_PORT = 9093;

    private static WebServer webServer;
    private static PowerManager.WakeLock wakeLock;
    private static WifiManager.WifiLock wifiLock;
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static ScheduledExecutorService watchdogExecutor;

    private static final AtomicBoolean startupChecksRunning = new AtomicBoolean(false);
    private static volatile boolean backgroundExemptionsApplied = false;

    private AppController() {}

    /**
     * Glavna ulazna točka za stalni rad u pozadini (poziva se iz KeepAliveAccessibilityService,
     * BootConfigJobService nakon boota i po potrebi iz MainActivity).
     */
    static synchronized void startBackgroundRuntime(Context context) {
        Context appCtx = context.getApplicationContext();
        acquireKeepAliveLocks(appCtx);
        startWebServer(appCtx);
        registerNetworkWatcher(appCtx);
        startBackgroundWatchdog(appCtx);
        BootConfigJobService.scheduleKeepAlive(appCtx);
        runStartupChecks(appCtx);
    }

    /**
     * Lagana periodična provjera koju poziva BootConfigJobService watchdog.
     * Vraća u život WebServer, lockove i AccessibilityService, a ADB iznimke
     * ponavlja samo ako u ovom procesu još nisu uspješno potvrđene.
     */
    static synchronized void ensureBackgroundAlive(Context context) {
        Context appCtx = context.getApplicationContext();
        acquireKeepAliveLocks(appCtx);
        startWebServer(appCtx);
        registerNetworkWatcher(appCtx);
        startBackgroundWatchdog(appCtx);
        ensureBootReceiverEnabled(appCtx);
        ensureAccessibilityServiceEnabled(appCtx);
        BootConfigJobService.scheduleKeepAlive(appCtx);

        if (!backgroundExemptionsApplied) {
            runStartupChecks(appCtx);
        }
    }

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

    static synchronized void acquireKeepAliveLocks(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":KeepAliveWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }

            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                int mode = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, TAG + ":KeepAliveWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire wake/wifi locks", e);
        }
    }

    static synchronized void releaseKeepAliveLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void registerNetworkWatcher(Context context) {
        if (networkCallback != null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "Wi-Fi available -> verifying WebServer and wireless ADB");
                    startWebServer(context);
                    if (!backgroundExemptionsApplied) {
                        runStartupChecks(context);
                    } else {
                        new Thread(() -> new BootConfigurator(context)
                                .enableWirelessDebuggingImmediately(), "wifi-reconnect-adb").start();
                    }
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register Wi-Fi network callback", e);
        }
    }

    private static synchronized void startBackgroundWatchdog(Context context) {
        if (watchdogExecutor != null && !watchdogExecutor.isShutdown()) {
            return;
        }
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
        watchdogExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (webServer == null || !webServer.isAlive()) {
                    Log.w(TAG, "Watchdog: WebServer was not alive, restarting");
                    startWebServer(context);
                }
                ensureBootReceiverEnabled(context);
                ensureAccessibilityServiceEnabled(context);
                if (!backgroundExemptionsApplied) {
                    runStartupChecks(context);
                }
            } catch (Exception e) {
                Log.e(TAG, "Watchdog check failed", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    static synchronized void stopBackgroundWatchdog() {
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdownNow();
            watchdogExecutor = null;
        }
    }

    static void runStartupChecks(Context context) {
        Context appCtx = context.getApplicationContext();
        ensureBootReceiverEnabled(appCtx);
        ensureAccessibilityServiceEnabled(appCtx);

        if (!startupChecksRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Startup ADB/protection checks already running, skipping duplicate trigger");
            return;
        }

        // Sekvencijalno: prvo uključi Wi-Fi ADB, zatim primijeni sistemske i OEM ADB politike
        new Thread(() -> {
            try {
                new BootConfigurator(appCtx).enableWirelessDebuggingImmediately();
                applyTclAndSystemProtections(appCtx);
            } catch (Exception e) {
                Log.e(TAG, "Startup ADB/protection checks failed", e);
            } finally {
                startupChecksRunning.set(false);
            }
        }, "startup-adb-and-protections").start();
    }

    /**
     * Koristi postojeću WRITE_SECURE_SETTINGS dozvolu da automatski ponovo uključi
     * KeepAliveAccessibilityService ako ga OEM ugasi nakon restarta.
     */
    static void ensureAccessibilityServiceEnabled(Context context) {
        try {
            ComponentName cn = new ComponentName(context, KeepAliveAccessibilityService.class);
            String fullName = cn.flattenToString();
            String shortName = cn.flattenToShortString();

            String existing = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );

            boolean alreadyEnabled = false;
            if (existing != null && !existing.isEmpty()) {
                for (String entry : existing.split(":")) {
                    if (entry.equalsIgnoreCase(fullName) || entry.equalsIgnoreCase(shortName)) {
                        alreadyEnabled = true;
                        break;
                    }
                }
            }

            if (!alreadyEnabled) {
                String updated = (existing == null || existing.isEmpty())
                        ? fullName
                        : existing + ":" + fullName;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated
                );
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                );
                Log.i(TAG, "Re-enabled KeepAliveAccessibilityService via WRITE_SECURE_SETTINGS");
            }
        } catch (SecurityException se) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot auto-enable accessibility service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify/re-enable accessibility service", e);
        }
    }

    static void ensureBootReceiverEnabled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName receiver = new ComponentName(context, BootReceiver.class);
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

    private static void applyTclAndSystemProtections(Context context) {
        String packageName = context.getPackageName();

        TclAutostartProtection.Result policy = TclAutostartProtection.apply(
                context.getContentResolver(),
                packageName
        );
        if (policy.available && !policy.success) {
            Log.w(TAG, "TCL AppBootPolicy protection could not be applied");
        }

        SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(context, PREFS_NAME);
        if (!prefs.getBoolean("is_paired", false)) {
            Log.i(TAG, "ADB pairing is needed for shell-based background exemptions");
            return;
        }

        int targetPort = prefs.getInt(KEY_TARGET_PORT, 5555);
        String ip = NetworkUtils.getLiveDeviceIP(context);
        AdbHelper adbHelper = new AdbHelper(context);

        boolean exemptionsOk = adbHelper.ensureBackgroundExemptions(ip, targetPort, packageName);
        if (exemptionsOk) {
            backgroundExemptionsApplied = true;
            Log.i(TAG, "System background exemptions applied and verified");
        } else {
            Log.w(TAG, "System background exemptions could not be verified yet");
        }

        if (policy.available && policy.success) {
            boolean allowed = adbHelper.ensureTclAutoStart(ip, targetPort, packageName);
            if (!allowed) {
                Log.w(TAG, "TCL AUTO_START app-op could not be verified");
            }
        }
    }
}