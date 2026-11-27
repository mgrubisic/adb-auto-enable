package com.tpn.adbautoenable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int WEB_PORT = 9093;
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";

    // Fixed, hard-coded access PIN required on every entry into the app.
    private static final String ACCESS_PIN = "092013";

    private LinearLayout rootLayout;
    private boolean unlocked = false;
    private AlertDialog pinDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read web server preference
        SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(this, PREFS_NAME);
        boolean webServerEnabled = prefs.getBoolean("web_server_enabled", true);

        // Engage the FULL background runtime when the app is opened: locks, the
        // network watcher, the in-process watchdog AND the periodic keep-alive
        // job. This is what lets the app keep running after the window is closed
        // (together with the KeepAlive accessibility service keeping the process
        // alive). Opening the app no longer just starts a bare web server.
        if (webServerEnabled) {
            AppController.startBackgroundRuntime(this);
        } else {
            // Web interface turned off by the user: keep ADB upkeep running but
            // do not start the server.
            AppController.runStartupChecks(this);
            AppController.stopWebServer();
        }

        // Create UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        rootLayout = layout;

        TextView titleText = new TextView(this);
        titleText.setText("Galaxy Buds");
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
                AppController.startBackgroundRuntime(this);
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
        accessibilityHint.setText("Enable \"" + "Galaxy Buds" + "\" under Installed services so the app keeps running after you close this window. Required for background operation.");

        TextView instructionText = new TextView(this);
        instructionText.setText("\nOpen this URL in your browser to configure pairing, target ports, and manage the app.\n\nADB is configured automatically on every boot.");

        layout.addView(titleText);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(webServerSwitch);
        layout.addView(accessibilityButton);
        layout.addView(accessibilityHint);
        layout.addView(instructionText);

        setContentView(layout);

        // Hide the UI until the correct PIN is entered (handled in onResume).
        rootLayout.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!unlocked) {
            promptForPin();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Re-lock the UI on every exit so the PIN is required on every entry.
        // NOTE: this only hides the UI; it does NOT stop the background runtime.
        unlocked = false;
        if (rootLayout != null) {
            rootLayout.setVisibility(View.INVISIBLE);
        }
    }

    /** Shows a blocking PIN prompt; the UI stays hidden until the PIN matches. */
    private void promptForPin() {
        if (pinDialog != null && pinDialog.isShowing()) {
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN");
        input.setGravity(Gravity.CENTER);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter PIN");
        builder.setMessage("Enter the PIN to open the app.");
        builder.setView(input);
        builder.setCancelable(false);
        builder.setPositiveButton("Unlock", null); // handler overridden below
        builder.setNegativeButton("Exit", (d, which) -> finish());

        pinDialog = builder.create();
        pinDialog.setCanceledOnTouchOutside(false);
        pinDialog.setOnShowListener(d -> {
            Button unlock = pinDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            unlock.setOnClickListener(v -> {
                if (ACCESS_PIN.equals(input.getText().toString())) {
                    unlocked = true;
                    if (rootLayout != null) {
                        rootLayout.setVisibility(View.VISIBLE);
                    }
                    pinDialog.dismiss();
                } else {
                    input.setText("");
                    input.setError("Wrong PIN");
                    Toast.makeText(MainActivity.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                }
            });
        });
        pinDialog.show();
    }

    private String getLocalIpAddress() {
        return NetworkUtils.getLiveDeviceIP(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Deliberately does NOT stop the web server or background runtime.
        // Closing the window must not make the app inactive; the KeepAlive
        // accessibility service keeps the process (and the server) alive.
    }
}
