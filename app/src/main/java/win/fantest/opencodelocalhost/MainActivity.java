package win.fantest.opencodelocalhost;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String LOCAL_URL = "http://127.0.0.1:" + ServerService.PORT;
    private static final String INFO_URL = LOCAL_URL + "/api/info";

    private TextView status;
    private TextView connection;
    private TextView runtime;
    private TextView lastEvent;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            checkServer();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        updateStaticInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshLoop);
        handler.post(refreshLoop);
        updateStaticInfo();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 15, 30));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root);

        TextView title = text("OpenCode Localhost", 28, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = text("Embedded Android runtime • no Termux required", 14,
                Color.rgb(148, 163, 184), false);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = dp(6);
        root.addView(subtitle, slp);

        status = text("CHECKING…", 19, Color.rgb(250, 204, 21), true);
        LinearLayout.LayoutParams stlp = wrap();
        stlp.topMargin = dp(28);
        root.addView(status, stlp);

        connection = text("", 15, Color.rgb(226, 232, 240), false);
        connection.setTextIsSelectable(true);
        LinearLayout.LayoutParams clp = wrap();
        clp.topMargin = dp(14);
        root.addView(connection, clp);

        runtime = text("", 14, Color.rgb(148, 163, 184), false);
        LinearLayout.LayoutParams rlp = wrap();
        rlp.topMargin = dp(14);
        root.addView(runtime, rlp);

        lastEvent = text("", 13, Color.rgb(100, 116, 139), false);
        LinearLayout.LayoutParams elp = wrap();
        elp.topMargin = dp(10);
        root.addView(lastEvent, elp);

        root.addView(space(22));

        Button start = button("START SERVER");
        start.setOnClickListener(v -> startServer());
        root.addView(start, fullButton());

        Button stop = button("STOP SERVER");
        stop.setOnClickListener(v -> stopServer());
        root.addView(stop, spacedButton());

        Button copy = button("COPY CONNECTION DETAILS");
        copy.setOnClickListener(v -> copyConnection());
        root.addView(copy, spacedButton());

        Button web = button("OPEN OPENCODE WEB UI");
        web.setOnClickListener(v -> openWeb());
        root.addView(web, spacedButton());

        Button files = button("GRANT PHONE FILE ACCESS");
        files.setOnClickListener(v -> requestAllFilesAccess());
        root.addView(files, spacedButton());

        Button settings = button("APP SETTINGS");
        settings.setOnClickListener(v -> openAppSettings());
        root.addView(settings, spacedButton());

        TextView note = text(
                "The server listens only on this phone at 127.0.0.1:4096. " +
                "OpenCode v2 uses Basic authentication; use the username and password shown above.",
                13, Color.rgb(100, 116, 139), false);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = dp(22);
        root.addView(note, nlp);

        return scroll;
    }

    private void startServer() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        Intent i = new Intent(this, ServerService.class).setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);

        status.setText("STARTING…");
        status.setTextColor(Color.rgb(250, 204, 21));
        handler.postDelayed(this::checkServer, 1800);
    }

    private void stopServer() {
        Intent i = new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP);
        startService(i);
        status.setText("STOPPING…");
        status.setTextColor(Color.rgb(250, 204, 21));
        handler.postDelayed(this::checkServer, 1200);
    }

    private void checkServer() {
        io.execute(() -> {
            boolean ok = false;
            String version = null;
            String pid = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(INFO_URL).openConnection();
                c.setConnectTimeout(1200);
                c.setReadTimeout(1200);
                c.setRequestMethod("GET");

                String password = ServerService.getOrCreatePassword(this);
                String token = Base64.encodeToString(
                        (ServerService.USERNAME + ":" + password).getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                );
                c.setRequestProperty("Authorization", "Basic " + token);

                int code = c.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 8192) sb.append(line);
                    JSONObject obj = new JSONObject(sb.toString());
                    version = obj.optString("version", "unknown");
                    pid = String.valueOf(obj.optLong("pid", -1));
                    ok = true;
                }
                c.disconnect();
            } catch (Throwable ignored) {}

            final boolean online = ok;
            final String v = version;
            final String p = pid;
            runOnUiThread(() -> {
                if (online) {
                    status.setText("SERVER ONLINE");
                    status.setTextColor(Color.rgb(34, 197, 94));
                    runtime.setText("Runtime: Embedded Android ARM64\nOpenCode: " + v +
                            "\nPID: " + p + "\nPhone files: " + fileAccessState());
                } else {
                    status.setText("SERVER OFFLINE");
                    status.setTextColor(Color.rgb(248, 113, 113));
                    runtime.setText("Runtime: Embedded Android ARM64\nOpenCode: bundled v2.0.22" +
                            "\nPhone files: " + fileAccessState());
                }
                updateStaticInfo();
            });
        });
    }

    private void updateStaticInfo() {
        String password = ServerService.getOrCreatePassword(this);
        connection.setText(
                "Address\n" + LOCAL_URL +
                "\n\nUsername\n" + ServerService.USERNAME +
                "\n\nPassword\n" + password
        );

        String log = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                .getString(ServerService.KEY_LAST_LOG, "");
        if (log == null || log.isEmpty()) {
            lastEvent.setText("Ready.");
        } else {
            if (log.length() > 180) log = log.substring(0, 180) + "…";
            lastEvent.setText("Last event: " + log);
        }
    }

    private String fileAccessState() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager() ? "all shared storage enabled" : "app storage only";
        }
        return "shared storage available";
    }

    private void copyConnection() {
        String password = ServerService.getOrCreatePassword(this);
        String value =
                "URL: " + LOCAL_URL + "\n" +
                "Username: " + ServerService.USERNAME + "\n" +
                "Password: " + password;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("OpenCode connection", value));
        lastEvent.setText("Connection details copied.");
    }

    private void openWeb() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LOCAL_URL)));
        } catch (Throwable t) {
            lastEvent.setText("Could not open browser: " + t.getMessage());
        }
    }

    private void requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                openAppSettings();
            }
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable ignored) {
                openAppSettings();
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.08f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullButton() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
    }

    private LinearLayout.LayoutParams spacedButton() {
        LinearLayout.LayoutParams p = fullButton();
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
