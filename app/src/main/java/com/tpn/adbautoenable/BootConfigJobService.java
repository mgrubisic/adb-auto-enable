package com.tpn.adbautoenable;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Runs the boot-time ADB configuration as a JobScheduler job instead of a
 * foreground service. Jobs are not listed under "Active in background" and
 * need no notification. A regular job may run for up to 10 minutes, far more
 * than the boot sequence needs.
 */
public class BootConfigJobService extends JobService {
    private static final String TAG = "ADBAutoEnable";
    static final int JOB_ID = 5555;

    private static volatile boolean running = false;
    private Thread worker;

    /** Schedules the boot configuration unless it is already pending or running. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler not available");
            return;
        }
        if (running || scheduler.getPendingJob(JOB_ID) != null) {
            Log.i(TAG, "Boot configuration already scheduled or running, ignoring");
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, BootConfigJobService.class))
                // Run as soon as any network is up; the configurator itself waits for Wi-Fi.
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        int result = scheduler.schedule(job);
        Log.i(TAG, "Boot configuration job scheduled: "
                + (result == JobScheduler.RESULT_SUCCESS ? "OK" : "FAILED"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Boot configuration job started");
        running = true;
        worker = new Thread(() -> {
            try {
                new BootConfigurator(getApplicationContext()).run();
            } finally {
                running = false;
                Log.i(TAG, "Boot configuration job finished");
                jobFinished(params, false);
            }
        }, "boot-config");
        worker.start();
        return true; // work continues on the worker thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.w(TAG, "Boot configuration job stopped by the system");
        if (worker != null) {
            worker.interrupt();
        }
        running = false;
        return false; // do not reschedule
    }
}
