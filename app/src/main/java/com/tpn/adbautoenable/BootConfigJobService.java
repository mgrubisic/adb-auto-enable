package com.tpn.adbautoenable;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs both the boot-time ADB configuration and a periodic silent keep-alive
 * watchdog as JobScheduler jobs instead of a foreground service. Jobs are not
 * listed under "Active in background" and require no notification.
 */
public class BootConfigJobService extends JobService {
    private static final String TAG = "ADBAutoEnable";
    static final int JOB_ID = 5555;
    static final int KEEP_ALIVE_JOB_ID = 5556;
    private static final long KEEP_ALIVE_INTERVAL_MS = 15L * 60L * 1000L;

    private static final ConcurrentHashMap<Integer, Thread> activeWorkers = new ConcurrentHashMap<>();

    /** Schedules the one-shot boot configuration unless it is already pending or running. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler not available");
            return;
        }
        if (activeWorkers.containsKey(JOB_ID) || scheduler.getPendingJob(JOB_ID) != null) {
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

        // Istovremeno osiguraj i vanjski periodični watchdog
        scheduleKeepAlive(context);
    }

    /**
     * Schedules a periodic JobScheduler watchdog (every 15 min) that revives
     * the WebServer, AccessibilityService, and background runtime if an OEM
     * kills the process silently.
     */
    static void scheduleKeepAlive(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler not available for keep-alive");
            return;
        }
        if (scheduler.getPendingJob(KEEP_ALIVE_JOB_ID) != null) {
            return;
        }

        long intervalMs = KEEP_ALIVE_INTERVAL_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intervalMs = Math.max(KEEP_ALIVE_INTERVAL_MS, JobInfo.getMinPeriodMillis());
        }

        boolean userUnlocked = isUserUnlocked(context);
        try {
            JobInfo.Builder builder = new JobInfo.Builder(
                    KEEP_ALIVE_JOB_ID,
                    new ComponentName(context, BootConfigJobService.class)
            ).setPeriodic(intervalMs);

            if (userUnlocked) {
                builder.setPersisted(true);
            }

            int result = scheduler.schedule(builder.build());
            Log.i(TAG, "Keep-alive watchdog job scheduled (persisted=" + userUnlocked + "): "
                    + (result == JobScheduler.RESULT_SUCCESS ? "OK" : "FAILED"));
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule keep-alive watchdog job", e);
        }
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        UserManager um = context.getSystemService(UserManager.class);
        return um == null || um.isUserUnlocked();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int jobId = params.getJobId();
        Context appCtx = getApplicationContext();

        if (jobId == KEEP_ALIVE_JOB_ID) {
            Log.i(TAG, "Keep-alive watchdog job started");
            Thread worker = new Thread(() -> {
                try {
                    AppController.ensureBackgroundAlive(appCtx);
                } catch (Exception e) {
                    Log.e(TAG, "Keep-alive watchdog job failed", e);
                } finally {
                    activeWorkers.remove(jobId);
                    Log.i(TAG, "Keep-alive watchdog job finished");
                    jobFinished(params, false);
                }
            }, "keep-alive-job");
            activeWorkers.put(jobId, worker);
            worker.start();
            return true;
        }

        Log.i(TAG, "Boot configuration job started");
        Thread worker = new Thread(() -> {
            try {
                new BootConfigurator(appCtx).run();
                // Nakon podizanja ADB-a odmah pokreni WebServer, lockove i ADB iznimke
                AppController.startBackgroundRuntime(appCtx);
            } catch (Exception e) {
                Log.e(TAG, "Boot configuration job failed", e);
            } finally {
                activeWorkers.remove(jobId);
                Log.i(TAG, "Boot configuration job finished");
                jobFinished(params, false);
            }
        }, "boot-config");
        activeWorkers.put(jobId, worker);
        worker.start();
        return true; // work continues on the worker thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int jobId = params.getJobId();
        Log.w(TAG, "Job " + jobId + " stopped by the system");
        Thread worker = activeWorkers.remove(jobId);
        if (worker != null) {
            worker.interrupt();
        }
        return false; // periodic job stays scheduled automatically; boot job is not rescheduled
    }
}