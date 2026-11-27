package com.tpn.adbautoenable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBAutoEnable";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            Log.i(TAG, "Boot event detected, scheduling ADB configuration job...");
            // A job instead of a foreground service: nothing appears under
            // "Active in background" and no notification is shown.
            BootConfigJobService.schedule(context);
            // Also (re)schedule the periodic keep-alive job so the app recovers
            // on its own after boot without any foreground service.
            KeepAliveJobService.schedule(context);
        } else {
            Log.i(TAG, "Ignoring broadcast: " + action);
        }
    }
}
