package win.fantest.opencodelocalhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerService extends Service {
    public static final String ACTION_START = "win.fantest.opencodelocalhost.START";
    public static final String ACTION_STOP = "win.fantest.opencodelocalhost.STOP";
    public static final String PREFS = "opencode_server";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_PID = "pid";
    public static final String KEY_LAST_LOG = "last_log";
    public static final String KEY_RUNNING = "running";
    public static final int PORT = 4096;
    public static final String USERNAME = "opencode";

    private static final String CHANNEL_ID = "opencode_server";
    private static final int NOTIFICATION_ID = 4096;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Process process;
    private volatile boolean explicitStop;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            explicitStop = true;
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }

        explicitStop = false;
        startForeground(NOTIFICATION_ID, notification("Starting embedded OpenCode…"));
        executor.execute(this::startServer);
        return START_STICKY;
    }

    private synchronized void startServer() {
        if (process != null && process.isAlive()) {
            updateRunning(true, "server already running");
            updateNotification("OpenCode server running on 127.0.0.1:" + PORT);
            return;
        }

        try {
            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            File binary = new File(nativeDir, "libopencode_exec.so");
            if (!binary.exists()) {
                updateRunning(false, "embedded OpenCode binary missing");
                updateNotification("Embedded runtime missing");
                return;
            }

            File home = new File(getFilesDir(), "opencode-home");
            File xdgConfig = new File(getFilesDir(), "config");
            File xdgData = new File(getFilesDir(), "data");
            File xdgState = new File(getFilesDir(), "state");
            File tmp = new File(getCacheDir(), "opencode-tmp");
            home.mkdirs();
            xdgConfig.mkdirs();
            xdgData.mkdirs();
            xdgState.mkdirs();
            tmp.mkdirs();

            File workspace;
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                workspace = Environment.getExternalStorageDirectory();
            } else {
                workspace = getExternalFilesDir(null);
                if (workspace == null) workspace = getFilesDir();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    binary.getAbsolutePath(),
                    "serve",
                    "--hostname", "127.0.0.1",
                    "--port", String.valueOf(PORT)
            );
            pb.directory(workspace);
            pb.redirectErrorStream(true);

            var env = pb.environment();
            env.clear();
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("XDG_CONFIG_HOME", xdgConfig.getAbsolutePath());
            env.put("XDG_DATA_HOME", xdgData.getAbsolutePath());
            env.put("XDG_STATE_HOME", xdgState.getAbsolutePath());
            env.put("PATH", nativeDir.getAbsolutePath() + ":/system/bin:/system/xbin");
            env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
            env.put("SHELL", "/system/bin/sh");
            env.put("OPENCODE_SERVER_USERNAME", USERNAME);
            env.put("OPENCODE_SERVER_PASSWORD", getOrCreatePassword(this));

            process = pb.start();
            long pid = process.pid();
            prefs().edit().putLong(KEY_PID, pid).apply();
            updateRunning(true, "process started");
            updateNotification("OpenCode server running on 127.0.0.1:" + PORT);

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    prefs().edit().putString(KEY_LAST_LOG, line).apply();
                    if (line.startsWith("server listening on")) {
                        updateRunning(true, line);
                        updateNotification("OpenCode server online • 127.0.0.1:" + PORT);
                    }
                }
            }

            int exit = process.waitFor();
            process = null;
            prefs().edit().remove(KEY_PID).apply();
            updateRunning(false, "server exited with code " + exit);
            if (!explicitStop) {
                updateNotification("OpenCode server stopped");
            }
        } catch (Throwable t) {
            process = null;
            prefs().edit().remove(KEY_PID).apply();
            updateRunning(false, "start failed: " + t.getMessage());
            updateNotification("OpenCode start failed");
        }
    }

    private synchronized void stopServer() {
        try {
            Process p = process;
            if (p != null && p.isAlive()) {
                p.destroy();
                try {
                    p.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (p.isAlive()) p.destroyForcibly();
            } else {
                long pid = prefs().getLong(KEY_PID, -1);
                if (pid > 0) {
                    try {
                        Os.kill((int) pid, OsConstants.SIGTERM);
                    } catch (Throwable ignored) {}
                }
            }
        } finally {
            process = null;
            prefs().edit().remove(KEY_PID).putBoolean(KEY_RUNNING, false)
                    .putString(KEY_LAST_LOG, "server stopped").apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private void updateRunning(boolean running, String log) {
        prefs().edit().putBoolean(KEY_RUNNING, running).putString(KEY_LAST_LOG, log).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public static String getOrCreatePassword(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = p.getString(KEY_PASSWORD, null);
        if (existing != null && !existing.isEmpty()) return existing;

        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String value = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        p.edit().putString(KEY_PASSWORD, value).apply();
        return value;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID,
                    "OpenCode Local Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("Keeps the embedded OpenCode localhost server running.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b
                .setContentTitle("OpenCode Localhost")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {
        if (explicitStop) stopServer();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
