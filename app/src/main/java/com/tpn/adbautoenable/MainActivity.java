package com.tpn.adbautoenable;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Button;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int WEB_PORT = 9093;
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read web server preference
        SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(this, PREFS_NAME);
        boolean webServerEnabled = prefs.getBoolean("web_server_enabled", true);

        // No Android service at all: the web server runs in the app process only
        // while the app is open, so nothing is ever "active in background".
        AppController.runStartupChecks(this);
        if (webServerEnabled) {
            AppController.startWebServer(this);
        } else {
            AppController.stopWebServer();
        }

        // Create UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView titleText = new TextView(this);
        titleText.setText("ADB Auto-Enable");
        titleText.setTextSize(24);

        TextView statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setText("\nWeb interface running on:\n");

        TextView urlText = new TextView(this);
        urlText.setTextSize(18);
        urlText.setTextColor(0xFF2196F3);
        urlText.setText("http://" + getLocalIpAddress() + ":" + WEB_PORT);

        // Native On-Device Toggle Switch for Web Server
        Switch webServerSwitch = new Switch(this);
        webServerSwitch.setTextSize(16);
        webServerSwitch.setText("\nEnable Web Interface");
        webServerSwitch.setChecked(webServerEnabled);
        webServerSwitch.setPadding(0, 30, 0, 10);

        webServerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("web_server_enabled", isChecked).apply();

            if (isChecked) {
                AppController.startWebServer(this);
            } else {
                AppController.stopWebServer();
            }
        });

        // Button to open Accessibility settings, where the keep-alive service is enabled
        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("Keep app alive (Accessibility)");
        accessibilityButton.setPadding(0, 30, 0, 10);
        accessibilityButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        TextView accessibilityHint = new TextView(this);
        accessibilityHint.setTextSize(13);
        accessibilityHint.setText("Enable \"" + "ADB Auto-Enable" + "\" under Installed services to stop the system from killing the app. Optional.");

        TextView instructionText = new TextView(this);
        instructionText.setText("\nOpen this URL in your browser to configure pairing, target ports, and manage the app.\n\nThe web interface runs only while this app is open, so the app never stays active in the background. ADB is still configured automatically on every boot.");

        layout.addView(titleText);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(webServerSwitch);
        layout.addView(accessibilityButton);
        layout.addView(accessibilityHint);
        layout.addView(instructionText);

        setContentView(layout);
    }

    private String getLocalIpAddress() {
        return NetworkUtils.getLiveDeviceIP(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // App closed (Back / removed from Recents): stop the web server.
        // Leaving the app temporarily (e.g. to pair in Settings) keeps it running.
        if (isFinishing()) {
            AppController.stopWebServer();
        }
    }
}
