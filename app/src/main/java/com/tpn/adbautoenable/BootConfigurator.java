package com.tpn.adbautoenable;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Boot-time ADB configuration (enable wireless debugging, find the ADB port,
 * switch it to the target port). Moved out of AdbConfigService so it can run
 * inside a JobService instead of a foreground service: a job is not listed
 * under "Active in background" and needs no persistent notification.
 */
class BootConfigurator {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_TARGET_PORT = "target_port";
    private static final int INITIAL_BOOT_DELAY_SECONDS = 30;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 10;
    private static final long TARGET_PORT_VERIFY_TIMEOUT_MS = 15000;

    private final Context context;

    BootConfigurator(Context context) {
        this.context = context;
    }

    /** Runs the full boot sequence. Blocking; call from a worker thread. */
    void run() {
        enableWirelessDebuggingImmediately();
        try {
            // Step 1: Wait for WiFi to be connected
            waitForWifiConnection();
            // Step 2: Wait for system to stabilize
            waitForBootStabilization();
            // Step 3: Attempt configuration with retries
            configureAdbWithRetries();
        } catch (InterruptedException e) {
            Log.w(TAG, "Boot configuration interrupted");
            updateStatus("Failed - interrupted");
        } catch (Exception e) {
            Log.e(TAG, "Error in boot configuration", e);
            updateStatus("Failed - " + e.getMessage());
        }
    }

    private SharedPreferences getPrefs() {
        return NetworkUtils.getDeviceProtectedPrefs(context, PREFS_NAME);
    }

    // No notification any more: progress goes to the log only.
    private void updateNotification(String text) {
        Log.i(TAG, "Boot config: " + text);
    }

    void enableWirelessDebuggingImmediately() {
        try {
            Log.i(TAG, "Step 0: Immediately enabling wireless debugging & disabling key revocation...");

            // Enable Wireless Debugging
            Settings.Global.putInt(
                    context.getContentResolver(),
                    "adb_wifi_enabled",
                    1
            );

            // Disable automatic ADB authorization revocation (0 = never expire)
            Settings.Global.putLong(
                    context.getContentResolver(),
                    "adb_allowed_connection_time",
                    0L
            );

            Log.i(TAG, "Wireless debugging and key revocation settings successfully updated early");
        } catch (SecurityException e) {
            Log.e(TAG, "Early settings write failed - permission WRITE_SECURE_SETTINGS missing", e);
        } catch (Exception e) {
            Log.e(TAG, "Early settings write unexpected error", e);
        }
    }

    private void waitForWifiConnection() throws InterruptedException {
        Log.i(TAG, "Waiting for WiFi connection...");
        updateNotification("Waiting for WiFi...");
        int maxWaitSeconds = 60;
        int waitedSeconds = 0;

        while (waitedSeconds < maxWaitSeconds) {
            if (isWifiConnected()) {
                String deviceIP = getDeviceIP();
                if (!deviceIP.equals("127.0.0.1") && !deviceIP.equals("0.0.0.0")) {
                    Log.i(TAG, "WiFi connected! Device IP: " + deviceIP);
                    return;
                }
            }
            Thread.sleep(1000);
            waitedSeconds++;
            if (waitedSeconds % 10 == 0) {
                Log.i(TAG, "Still waiting for WiFi... (" + waitedSeconds + "s)");
                updateNotification("Waiting for WiFi... (" + waitedSeconds + "s)");
            }
        }
        Log.w(TAG, "WiFi wait timeout - proceeding anyway");
    }

    private boolean isWifiConnected() {
        return NetworkUtils.isNetworkConnected(context);
    }

    private void waitForBootStabilization() throws InterruptedException {
        Log.i(TAG, "Waiting " + INITIAL_BOOT_DELAY_SECONDS + " seconds for system to stabilize...");
        for (int i = INITIAL_BOOT_DELAY_SECONDS; i > 0; i--) {
            updateNotification("System stabilizing... " + i + "s");
            Thread.sleep(1000);
            if (i % 10 == 0) {
                Log.i(TAG, "Boot stabilization: " + i + " seconds remaining");
            }
        }
        Log.i(TAG, "Boot stabilization complete");
    }

    private void configureAdbWithRetries() {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Log.i(TAG, "Configuration attempt " + attempt + " of " + MAX_RETRY_ATTEMPTS);
            updateNotification("Attempt " + attempt + " of " + MAX_RETRY_ATTEMPTS);

            boolean success = configureAdb();
            if (success) {
                Log.i(TAG, "Configuration successful on attempt " + attempt);
                return;
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Attempt " + attempt + " failed, waiting " + RETRY_DELAY_SECONDS + "s before retry...");
                updateNotification("Failed, retrying in " + RETRY_DELAY_SECONDS + "s...");
                try {
                    Thread.sleep(RETRY_DELAY_SECONDS * 1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Retry delay interrupted", e);
                    return;
                }
            }
        }

        Log.e(TAG, "All " + MAX_RETRY_ATTEMPTS + " attempts failed");
        updateStatus("Failed after " + MAX_RETRY_ATTEMPTS + " attempts");
        updateNotification("Failed after " + MAX_RETRY_ATTEMPTS + " attempts");
    }

    private boolean configureAdb() {
        try {
            // Re-assert wireless debugging & auto-revocation settings
            Settings.Global.putInt(
                    context.getContentResolver(),
                    "adb_wifi_enabled",
                    1
            );
            Settings.Global.putLong(
                    context.getContentResolver(),
                    "adb_allowed_connection_time",
                    0L
            );

            // Dual-homed devices can bind TLS ADB to Wi-Fi while Ethernet stays the
            // active network; prepare the Wi-Fi ADB stack before waiting for the
            // daemon. Adapted from #27 by @Andi316.
            prepareWirelessAdbStack();

            Log.i(TAG, "Step 2: Waiting for ADB service to start...");
            updateNotification("Waiting for ADB service...");
            Thread.sleep(15000);

            String deviceIP = getDeviceIP();
            Log.i(TAG, "Device IP: " + deviceIP);

            if (deviceIP.equals("127.0.0.1") || deviceIP.equals("0.0.0.0")) {
                Log.e(TAG, "Invalid device IP: " + deviceIP);
                updateStatus("Failed - no valid IP address");
                return false;
            }

            Log.i(TAG, "Step 3: Discovering ADB port...");
            updateNotification("Discovering ADB port...");
            updateStatus("Discovering ADB port...");

            AdbHelper adbHelper = new AdbHelper(context);

            AdbEndpoint endpoint = AdbDiscovery.discover(context);
            if (endpoint == null) {
                Log.i(TAG, "mDNS failed, falling back to port scan...");
                updateNotification("mDNS failed, scanning ports...");
                updateStatus("mDNS failed, scanning ports...");
                int cachedPort = getPrefs().getInt(KEY_LAST_PORT, -1);
                endpoint = AdbPortScanner.find(context, cachedPort,
                        candidate -> adbHelper.connect(candidate.host, candidate.port));
            }

            if (endpoint == null) {
                Log.e(TAG, "Could not find ADB port");
                updateStatus("Failed - port not found");
                updateNotification("Failed - port not found");
                return false;
            }

            Log.i(TAG, "Found ADB at " + endpoint);
            saveLastPort(endpoint.port);

            int targetPort = getTargetPort();
            Log.i(TAG, "Step 4: Switching to port " + targetPort + "...");
            updateNotification("Switching to port " + targetPort + "...");
            updateStatus("Switching to port " + targetPort + "...");

            // Loopback first (avoids hangs on some devices), then the resolved LAN
            // host and every local Wi-Fi/Ethernet address (Fixes #8, Fixes #21).
            boolean success = false;
            for (String host : NetworkUtils.getAdbCandidateHosts(context, endpoint.host)) {
                Log.i(TAG, "Switching via " + host + "...");
                success = adbHelper.switchToPort(host, endpoint.port, targetPort);
                if (success) break;
            }

            if (success) {
                // A successful tcpip command does not guarantee the listener is up yet.
                // Verify the target port is actually reachable before reporting success.
                // Adapted from #27 by @Andi316.
                String verifiedHost = waitForTargetPort(endpoint.host, targetPort);
                if (verifiedHost == null) {
                    Log.e(TAG, "tcpip command succeeded but target port "
                            + targetPort + " is not reachable");
                    updateStatus("Failed - target port not reachable");
                    updateNotification("Failed - target port not reachable");
                    return false;
                }
                Log.i(TAG, "Successfully configured ADB at " + verifiedHost + ":" + targetPort + "!");
                updateStatus("Success - ADB at " + verifiedHost + ":" + targetPort);
                updateNotification("Success - ADB on port " + targetPort);
                return true;
            } else {
                Log.e(TAG, "Failed to switch to port " + targetPort);
                updateStatus("Failed - could not switch port");
                updateNotification("Failed - could not switch port");
                return false;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied - grant WRITE_SECURE_SETTINGS via ADB", e);
            updateStatus("Failed - permission denied");
            updateNotification("Failed - permission denied");
            return false;
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted", e);
            updateStatus("Failed - interrupted");
            updateNotification("Failed - interrupted");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error", e);
            updateStatus("Failed - " + e.getMessage());
            updateNotification("Failed - error");
            return false;
        }
    }

    /**
     * Dual-homed devices (for example Sony BRAVIA) can bind TLS ADB to the Wi-Fi
     * stack while Ethernet stays the ConnectivityManager active network. Keep Wi-Fi
     * requested, and on such devices re-request wireless ADB so it binds before
     * discovery runs. The Wi-Fi bounce from the original PR is omitted because
     * {@code setWifiEnabled} is a no-op for third-party apps on API 29+.
     * Adapted from #27 by @Andi316.
     */
    private void prepareWirelessAdbStack() {
        try {
            Settings.Global.putInt(context.getContentResolver(), "wifi_always_requested", 1);
        } catch (Exception e) {
            Log.w(TAG, "Unable to set wifi_always_requested", e);
        }

        String wifiIp = NetworkUtils.getIpv4ForInterface("wlan0");
        String ethIp = NetworkUtils.getIpv4ForInterface("eth0");
        if (ethIp == null) ethIp = NetworkUtils.getIpv4ForInterface("eth1");
        if (wifiIp == null || ethIp == null) return;

        Log.i(TAG, "Dual-homed device (Wi-Fi " + wifiIp + ", Ethernet " + ethIp
                + "); re-requesting wireless ADB so it binds to Wi-Fi");
        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 0);
            Thread.sleep(2000);
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "Toggled adb_wifi_enabled 0 -> 1");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Unable to toggle adb_wifi_enabled", e);
        }
    }

    /**
     * Polls the target port across the candidate hosts until one accepts a TCP
     * connection or the deadline passes; returns the host that answered.
     * Adapted from #27 by @Andi316.
     */
    private String waitForTargetPort(String resolvedHost, int targetPort)
            throws InterruptedException {
        List<String> hosts = NetworkUtils.getAdbCandidateHosts(context, resolvedHost);
        long deadline = SystemClock.elapsedRealtime() + TARGET_PORT_VERIFY_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (String host : hosts) {
                if (Thread.currentThread().isInterrupted()) return null;
                if (isTcpPortOpen(host, targetPort)) return host;
            }
            Thread.sleep(500);
        }
        return null;
    }

    private boolean isTcpPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getDeviceIP() {
        return NetworkUtils.getLiveDeviceIP(context);
    }

    private int getTargetPort() {
        return getPrefs().getInt(KEY_TARGET_PORT, 5555);
    }

    private void updateStatus(String status) {
        getPrefs().edit().putString(KEY_LAST_STATUS, status).apply();
    }

    private void saveLastPort(int port) {
        getPrefs().edit().putInt(KEY_LAST_PORT, port).apply();
    }
}
