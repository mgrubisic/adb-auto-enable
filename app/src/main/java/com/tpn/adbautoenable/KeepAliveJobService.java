package com.tpn.adbautoenable;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Invisible keep-alive. A periodic, persisted JobScheduler job that re-asserts
 * the app's state on its own after the system reclaims the process:
 *   - re-enables wireless debugging and the boot receiver,
 *   - re-applies the TCL auto-start policy,
 *   - re-adds the keep-alive accessibility service to the enabled list.
 *
 * Unlike a foreground service, a job is NOT listed under "Apps running in the
 * background" and needs no notification, so the app stays invisible while still
 * recovering unattended. The job does its work through {@link AppController}
 * startup checks only; it never opens an ADB connection and never starts the
 * web server, so it stays cheap and silent. The web UI remains a run-only-while-
 * open feature, exactly as before.
 *
 * Persistence exemptions (Doze whitelist, standby bucket) and the one-time
 * self-grant are applied where an ADB endpoint already exists (after pairing and
 * on every boot) and persist across reboots, so they are intentionally not
 * repeated here.
 */
public class KeepAliveJobService extends JobService {
    private static final String TAG = "ADBAutoEnable";
    static final int JOB_ID = 5556;
    // 15 minutes is the shortest periodic interval the platform allows; shorter
    // values are clamped by the system anyway.
    private static final long PERIOD_MS = 15L * 60L * 1000L;

    private Thread worker;

    /** Schedules the periodic keep-alive unless it is already scheduled. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler not available for keep-alive");
            return;
        }
        // A pending periodic job means it is already scheduled; do not reschedule,
        // which would reset the period on every startup check.
        if (scheduler.getPendingJob(JOB_ID) != null) {
            Log.i(TAG, "Keep-alive job already scheduled");
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, KeepAliveJobService.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true) // survive reboot (needs RECEIVE_BOOT_COMPLETED)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        int result = scheduler.schedule(job);
        Log.i(TAG, "Keep-alive job scheduled: "
                + (result == JobScheduler.RESULT_SUCCESS ? "OK" : "FAILED"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Keep-alive job running");
        worker = new Thread(() -> {
            try {
                AppController.runStartupChecks(getApplicationContext());
            } catch (Exception e) {
                Log.e(TAG, "Keep-alive job error", e);
            } finally {
                Log.i(TAG, "Keep-alive job finished");
                jobFinished(params, false);
            }
        }, "keep-alive");
        worker.start();
        return true; // work continues on the worker thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.w(TAG, "Keep-alive job stopped by the system");
        if (worker != null) {
            worker.interrupt();
        }
        return true; // reschedule the periodic job if it was stopped early
    }
}
