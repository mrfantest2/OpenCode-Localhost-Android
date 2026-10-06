package win.fantest.opencodelocalhost;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_TERMUX = 9401;
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String LOCAL_URL = "http://127.0.0.1:4096";
    private static final String HEALTH_URL = LOCAL_URL + "/global/health";

    private TextView status;
    private TextView details;
    private TextView setup;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Runnable pendingAfterPermission;

    private final Runnable healthLoop = new Runnable() {
        @Override public void run() {
            checkHealth(false);
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshPrereqs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(healthLoop);
        handler.post(healthLoop);
        refreshPrereqs();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(healthLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView title = label("OpenCode Localhost", 27, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = label("Runs a real OpenCode server on this Android device through Termux.", 15,
                Color.rgb(148, 163, 184), false);
        LinearLayout.LayoutParams subLp = fullWrap();
        subLp.topMargin = dp(5);
        root.addView(subtitle, subLp);

        status = label("CHECKING", 18, Color.rgb(250, 204, 21), true);
        LinearLayout.LayoutParams statusLp = fullWrap();
        statusLp.topMargin = dp(28);
        root.addView(status, statusLp);

        details = label(LOCAL_URL, 15, Color.rgb(226, 232, 240), false);
        details.setTextIsSelectable(true);
        LinearLayout.LayoutParams detailLp = fullWrap();
        detailLp.topMargin = dp(8);
        root.addView(details, detailLp);

        root.addView(space(18));

        Button start = button("START SERVER");
        start.setOnClickListener(v -> withTermuxPermission(this::startServer));
        root.addView(start, fullButton());

        Button stop = button("STOP SERVER");
        stop.setOnClickListener(v -> withTermuxPermission(this::stopServer));
        root.addView(stop, spacedButton());

        Button repair = button("INSTALL / REPAIR RUNTIME");
        repair.setOnClickListener(v -> withTermuxPermission(this::repairRuntime));
        root.addView(repair, spacedButton());

        Button check = button("CHECK HEALTH");
        check.setOnClickListener(v -> checkHealth(true));
        root.addView(check, spacedButton());

        Button copy = button("COPY LOCALHOST URL");
        copy.setOnClickListener(v -> copyUrl());
        root.addView(copy, spacedButton());

        Button termux = button("OPEN TERMUX");
        termux.setOnClickListener(v -> openTermux());
        root.addView(termux, spacedButton());

        setup = label("", 13, Color.rgb(148, 163, 184), false);
        setup.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams setupLp = fullWrap();
        setupLp.topMargin = dp(20);
        root.addView(setup, setupLp);

        return root;
    }

    private void startServer() {
        String script =
                "export PREFIX=/data/data/com.termux/files/usr; " +
                "export HOME=/data/data/com.termux/files/home; " +
                "export PATH=\\"$PREFIX/bin:$PATH\\"; " +
                "mkdir -p \\"$HOME/.opencode-localhost\\"; " +
                "if ! command -v opencode >/dev/null 2>&1 && ! command -v opencode2 >/dev/null 2>&1; then " +
                "  if ! command -v npm >/dev/null 2>&1; then pkg update -y && pkg install -y nodejs; fi; " +
                "  npm install -g opencode-termux; " +
                "fi; " +
                "OC=opencode; command -v opencode >/dev/null 2>&1 || OC=opencode2; " +
                "PIDFILE=\\"$HOME/.opencode-localhost/server.pid\\"; " +
                "LOG=\\"$HOME/.opencode-localhost/server.log\\"; " +
                "if [ -f \\"$PIDFILE\\" ] && kill -0 $(cat \\"$PIDFILE\\") 2>/dev/null; then exit 0; fi; " +
                "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true; " +
                "nohup $OC serve --hostname 127.0.0.1 --port 4096 >\\"$LOG\\" 2>&1 < /dev/null & " +
                "echo $! > \\"$PIDFILE\\";";
        runTermux(script, "Start OpenCode Localhost");
        status.setText("STARTING…");
        status.setTextColor(Color.rgb(250, 204, 21));
        handler.postDelayed(() -> checkHealth(true), 3500);
    }

    private void stopServer() {
        String script =
                "export HOME=/data/data/com.termux/files/home; " +
                "PIDFILE=\\"$HOME/.opencode-localhost/server.pid\\"; " +
                "if [ -f \\"$PIDFILE\\" ]; then kill $(cat \\"$PIDFILE\\") 2>/dev/null || true; rm -f \\"$PIDFILE\\"; fi; " +
                "pkill -f 'opencode.*serve.*4096' 2>/dev/null || true; " +
                "command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true;";
        runTermux(script, "Stop OpenCode Localhost");
        status.setText("STOPPING…");
        status.setTextColor(Color.rgb(250, 204, 21));
        handler.postDelayed(() -> checkHealth(true), 1800);
    }

    private void repairRuntime() {
        String script =
                "export PREFIX=/data/data/com.termux/files/usr; " +
                "export HOME=/data/data/com.termux/files/home; " +
                "export PATH=\\"$PREFIX/bin:$PATH\\"; " +
                "pkg update -y; pkg install -y nodejs curl coreutils; " +
                "npm install -g opencode-termux; " +
                "opencode --version || true;";
        runTermux(script, "Install / Repair OpenCode Runtime");
        status.setText("RUNTIME INSTALL STARTED");
        status.setTextColor(Color.rgb(56, 189, 248));
    }

    private void runTermux(String script, String label) {
        if (!isTermuxInstalled()) {
            showSetup("Termux is not installed. Install the current F-Droid/GitHub Termux build first.");
            openUrl("https://github.com/termux/termux-app/releases/latest");
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
            startService(intent);
            showSetup("Command sent to Termux. If nothing happens, enable allow-external-apps=true in ~/.termux/termux.properties and grant this app the Termux RUN_COMMAND permission.");
        } catch (Exception e) {
            showSetup("Could not launch Termux command: " + e.getMessage());
        }
    }

    private void withTermuxPermission(Runnable action) {
        if (!isTermuxInstalled()) {
            showSetup("Termux is required. Tap OPEN TERMUX after installing it.");
            openUrl("https://github.com/termux/termux-app/releases/latest");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingAfterPermission = action;
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQ_TERMUX);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TERMUX) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Runnable r = pendingAfterPermission;
                pendingAfterPermission = null;
                if (r != null) r.run();
            } else {
                showSetup("Grant 'Run commands in Termux environment' under this app's Additional permissions.");
                openAppDetails();
            }
        }
    }

    private void checkHealth(boolean userInitiated) {
        io.execute(() -> {
            String body = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
                c.setConnectTimeout(1200);
                c.setReadTimeout(1200);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code >= 200 && code < 300) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    body = sb.toString();
                }
                c.disconnect();
            } catch (Exception ignored) {}

            final String result = body;
            runOnUiThread(() -> {
                if (result != null && !result.isEmpty()) {
                    status.setText("SERVER ONLINE");
                    status.setTextColor(Color.rgb(34, 197, 94));
                    details.setText(LOCAL_URL + "\n" + result);
                } else {
                    status.setText("SERVER OFFLINE");
                    status.setTextColor(Color.rgb(248, 113, 113));
                    details.setText(LOCAL_URL + "\nStart the server, then connect OpenCode Mobile to this address.");
                    if (userInitiated) refreshPrereqs();
                }
            });
        });
    }

    private void refreshPrereqs() {
        boolean termux = isTermuxInstalled();
        boolean perm = checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        String text = "Termux: " + (termux ? "installed" : "missing") +
                "\nRUN_COMMAND permission: " + (perm ? "granted" : "not granted") +
                "\n\nRequired once in Termux: ~/.termux/termux.properties must contain:\nallow-external-apps=true";
        setup.setText(text);
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.termux");
        if (launch != null) startActivity(launch);
        else openUrl("https://github.com/termux/termux-app/releases/latest");
    }

    private void copyUrl() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("OpenCode localhost", LOCAL_URL));
        showSetup("Copied " + LOCAL_URL);
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    private void showSetup(String msg) {
        setup.setText(msg);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private Space space(int heightDp) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return s;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullButton() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
    }

    private LinearLayout.LayoutParams spacedButton() {
        LinearLayout.LayoutParams p = fullButton();
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
