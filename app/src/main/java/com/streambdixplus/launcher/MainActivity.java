package com.streambdixplus.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private enum Purpose { REFRESH, START, STOP, INSTALL, PROBE }

    private static final int REQ_PERMISSION = 1001;
    private static final long RESULT_TIMEOUT_MS = 15000L;
    private static final long PROBE_TIMEOUT_MS = 7000L;
    private static final long AUTO_REFRESH_MS = 5000L;
    private static final String PREVIEW_MARKER = "@@PREVIEW@@";
    private static final String DEFAULT_PORT = "7001";
    private static final String DASH = "\u2014";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+");
    private static final Pattern PORT_PATTERN =
            Pattern.compile("https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0):(\\d{2,5})");

    // ---- state (declared before the anonymous classes that use it) ----
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private boolean receiverRegistered;
    private long pendingRequestId;
    private Purpose pendingPurpose;
    private Purpose queuedAfterPermission;
    private Dialog activeDialog;

    private TextView statusView;
    private TextView detailView;
    private TextView messageView;
    private TextView previewView;
    private TextView pillView;
    private TextView uptimeView;
    private TextView pidView;
    private TextView versionView;
    private TextView lastStartedView;
    private TextView urlView;
    private TextView connView;
    private View statusCard;
    private View statusRing;
    private View lastDot;
    private View terminalBody;
    private View collapseButton;
    private ImageView statusIcon;
    private ScrollView logScroll;
    private Switch autoScrollSwitch;

    private String appVersion = "";
    private String serverPort = DEFAULT_PORT;
    private String logText = "";
    private String clearedSnapshot;
    private String lastPreview = "";
    private boolean autoScroll = true;

    private final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() {
            Purpose p = pendingPurpose;
            pendingPurpose = null;
            pendingRequestId = 0;
            if (p == Purpose.PROBE) {
                showSetupManual(R.string.setup_why_blocked);
            } else {
                showError(getString(R.string.err_timeout));
            }
        }
    };

    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override public void run() {
            if (pendingPurpose == null && receiverRegistered) {
                perform(Purpose.REFRESH, true);
            }
        }
    };

    private final Runnable openTermuxRunnable = new Runnable() {
        @Override public void run() {
            openTermuxUi();
        }
    };

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(TermuxBridge.EXTRA_REQUEST_ID, 0L);
            if (pendingPurpose == null || id == 0L || id != pendingRequestId) {
                return; // stale or foreign result
            }
            handler.removeCallbacks(timeoutRunnable);
            Purpose purpose = pendingPurpose;
            pendingPurpose = null;
            pendingRequestId = 0;
            handleResult(purpose, intent.getBundleExtra(TermuxBridge.EXTRA_RESULT_BUNDLE));
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.tvStatus);
        detailView = findViewById(R.id.tvDetail);
        messageView = findViewById(R.id.tvMessage);
        previewView = findViewById(R.id.tvPreview);
        pillView = findViewById(R.id.tvPill);
        uptimeView = findViewById(R.id.tvUptime);
        pidView = findViewById(R.id.tvPid);
        versionView = findViewById(R.id.tvVersion);
        lastStartedView = findViewById(R.id.tvLastStarted);
        urlView = findViewById(R.id.tvUrl);
        connView = findViewById(R.id.tvConn);
        statusCard = findViewById(R.id.statusCard);
        statusRing = findViewById(R.id.statusRing);
        lastDot = findViewById(R.id.lastDot);
        terminalBody = findViewById(R.id.terminalBody);
        collapseButton = findViewById(R.id.btnCollapse);
        statusIcon = findViewById(R.id.statusIcon);
        logScroll = findViewById(R.id.logScroll);
        autoScrollSwitch = findViewById(R.id.swAutoScroll);

        appVersion = readAppVersion();
        String titleText = getString(R.string.header_title);
        SpannableString title = new SpannableString(titleText);
        title.setSpan(new ForegroundColorSpan(getColor(R.color.accent)),
                titleText.length() - 1, titleText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        ((TextView) findViewById(R.id.tvAppVersion)).setText("v" + appVersion);
        ((TextView) findViewById(R.id.tvFooter)).setText(getString(R.string.footer_title, appVersion));

        findViewById(R.id.btnStart).setOnClickListener(v -> perform(Purpose.START));
        findViewById(R.id.btnStop).setOnClickListener(v -> confirmStop());
        findViewById(R.id.btnStatus).setOnClickListener(v -> perform(Purpose.REFRESH));
        findViewById(R.id.btnSetup).setOnClickListener(v -> startSetup());
        findViewById(R.id.btnInstall).setOnClickListener(v -> perform(Purpose.INSTALL));
        findViewById(R.id.btnTermux).setOnClickListener(v -> openTermuxUi());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnInfo).setOnClickListener(v -> showAbout());
        findViewById(R.id.btnUrl).setOnClickListener(v -> openWebUi());
        findViewById(R.id.btnManifest).setOnClickListener(
                v -> copyToClipboard(manifestUrl(), R.string.toast_manifest_copied));
        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            if (logText.isEmpty()) return;
            copyToClipboard(logText, R.string.toast_log_copied);
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> clearLogs());
        collapseButton.setOnClickListener(v -> toggleTerminal());
        autoScrollSwitch.setOnCheckedChangeListener((button, checked) -> {
            autoScroll = checked;
            if (checked) scrollLogToEnd();
        });

        clearStats();
        showState(getString(R.string.status_idle), getString(R.string.detail_idle), R.color.status_neutral);
        showPreview(getString(R.string.preview_empty));
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter resultFilter = new IntentFilter(TermuxBridge.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, resultFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, resultFilter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPurpose != null || activeDialog != null) return;
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showError(getString(R.string.err_termux_missing));
        } else if (!TermuxBridge.hasRunCommandPermission(this)) {
            showState(getString(R.string.status_idle), getString(R.string.msg_permission_needed),
                    R.color.status_warn);
        } else {
            perform(Purpose.REFRESH);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver);
            receiverRegistered = false;
        }
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(openTermuxRunnable);
        handler.removeCallbacks(autoRefreshRunnable);
        pendingPurpose = null;
        pendingRequestId = 0;
        dismissDialog();
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        Purpose queued = queuedAfterPermission;
        queuedAfterPermission = null;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            // Android TV often shows no usable dialog (or none at all). Offer ways around it.
            queuedAfterPermission = queued;
            showError(getString(R.string.err_permission_denied));
            showPermissionHelp();
            return;
        }
        if (queued == Purpose.STOP) {
            confirmStop();
        } else if (queued != null) {
            perform(queued);
        }
    }

    // ---- actions ----

    private boolean ensureReady(Purpose purpose) {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showError(getString(R.string.err_termux_missing));
            return false;
        }
        if (!TermuxBridge.hasRunCommandPermission(this)) {
            queuedAfterPermission = purpose;
            showState(getString(R.string.status_idle), getString(R.string.msg_permission_needed),
                    R.color.status_warn);
            requestRunCommandPermission();
            return false;
        }
        return true;
    }

    private void requestRunCommandPermission() {
        try {
            requestPermissions(new String[]{TermuxBridge.PERMISSION_RUN_COMMAND}, REQ_PERMISSION);
        } catch (RuntimeException e) {
            showPermissionHelp();
        }
    }

    private void perform(Purpose purpose) {
        perform(purpose, false);
    }

    /** silent = background auto-refresh: no "working" flicker, no "busy" toast. */
    private void perform(Purpose purpose, boolean silent) {
        if (pendingPurpose != null) {
            if (!silent) Toast.makeText(this, R.string.msg_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureReady(purpose)) return;

        long id = newRequestId();
        PendingIntent resultIntent = newResultIntent(id);
        String error;
        switch (purpose) {
            case INSTALL:
                String script;
                try {
                    script = readAsset(TermuxBridge.ASSET_SCRIPT);
                } catch (IOException e) {
                    showError(getString(R.string.err_asset, e.getMessage()));
                    return;
                }
                error = TermuxBridge.installScript(this, script, resultIntent);
                break;
            case STOP:
                error = TermuxBridge.runBackground(this, "stop", resultIntent);
                break;
            default: // REFRESH and START both begin with a status check
                error = TermuxBridge.runBackground(this, "status", resultIntent);
                break;
        }
        if (error != null) {
            showError(error);
            return;
        }
        pendingRequestId = id;
        pendingPurpose = purpose;
        handler.postDelayed(timeoutRunnable, RESULT_TIMEOUT_MS);
        if (!silent) showState(getString(R.string.status_working), "", R.color.status_neutral);
    }

    private void confirmStop() {
        if (pendingPurpose != null) {
            Toast.makeText(this, R.string.msg_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureReady(Purpose.STOP)) return;
        showDialogCard(getString(R.string.confirm_stop_title), getString(R.string.confirm_stop_msg), null,
                new String[]{getString(R.string.act_stop), getString(R.string.act_cancel)},
                new Runnable[]{() -> perform(Purpose.STOP), null},
                0, 1, true);
    }

    // ---- one-time setup (the "pkg update && ... && npx streambdixplus" command) ----

    private void startSetup() {
        if (pendingPurpose != null) {
            Toast.makeText(this, R.string.msg_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showError(getString(R.string.err_termux_missing));
            return;
        }
        if (!TermuxBridge.hasRunCommandPermission(this)) {
            showSetupManual(R.string.setup_why_permission);
            return;
        }
        // Find out first whether Termux accepts commands from us (allow-external-apps).
        long id = newRequestId();
        String error = TermuxBridge.probe(this, newResultIntent(id));
        if (error != null) {
            showSetupManual(R.string.setup_why_blocked);
            return;
        }
        pendingRequestId = id;
        pendingPurpose = Purpose.PROBE;
        handler.postDelayed(timeoutRunnable, PROBE_TIMEOUT_MS);
        showState(getString(R.string.status_working), getString(R.string.msg_setup_probing),
                R.color.status_neutral);
    }

    private void runSetupInTermux() {
        String script = "";
        try {
            script = readAsset(TermuxBridge.ASSET_SCRIPT);
        } catch (IOException ignored) {
            // setup still works; the script can be installed later with "Install / update script"
        }
        String error = TermuxBridge.runSetupForeground(this, script);
        if (error != null) {
            showSetupManual(R.string.setup_why_blocked);
            return;
        }
        showState(getString(R.string.status_starting), getString(R.string.msg_setup_started),
                R.color.status_accent);
        // From Android 10 on, Termux may not be allowed to pop up by itself. We are in the foreground, so we bring it up.
        handler.removeCallbacks(openTermuxRunnable);
        handler.postDelayed(openTermuxRunnable, 900L);
    }

    /** Fallback that works everywhere: copy the command, open Termux, let the person paste it. */
    private void showSetupManual(int reasonRes) {
        copyToClipboard(TermuxBridge.SETUP_COMMAND);
        showState(getString(R.string.status_idle), getString(reasonRes), R.color.status_warn);
        showDialogCard(getString(R.string.setup_manual_title),
                getString(R.string.setup_manual_msg, getString(reasonRes)),
                TermuxBridge.SETUP_COMMAND,
                new String[]{getString(R.string.act_open_termux), getString(R.string.act_copy_again),
                        getString(R.string.act_close)},
                new Runnable[]{this::openTermuxUi, () -> copyToClipboard(TermuxBridge.SETUP_COMMAND), null},
                0, 0, false);
    }

    // ---- permission help ----

    private void showPermissionHelp() {
        boolean defined = true;
        try {
            getPackageManager().getPermissionInfo(TermuxBridge.PERMISSION_RUN_COMMAND, 0);
        } catch (PackageManager.NameNotFoundException e) {
            defined = false;
        }
        String message = (defined ? "" : getString(R.string.perm_not_defined_note)) + getString(R.string.perm_msg);
        showDialogCard(getString(R.string.perm_title), message, TermuxBridge.SELF_GRANT_COMMAND,
                new String[]{getString(R.string.act_try_again), getString(R.string.act_app_settings),
                        getString(R.string.act_copy_selfgrant), getString(R.string.act_close)},
                new Runnable[]{
                        this::requestRunCommandPermission,
                        this::openAppSettings,
                        () -> {
                            copyToClipboard(TermuxBridge.SELF_GRANT_COMMAND);
                            openTermuxUi();
                        },
                        null},
                0, 0, false);
    }

    private void openAppSettings() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(details);
            return;
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.err_settings_missing, Toast.LENGTH_LONG).show();
        }
    }

    // ---- popup card (D-pad friendly replacement for AlertDialog) ----

    private void showDialogCard(String title, String message, String code, String[] labels,
                                Runnable[] actions, int primaryIdx, int focusIdx, boolean primaryDanger) {
        dismissDialog();
        View view = getLayoutInflater().inflate(R.layout.dialog_card, null);
        ((TextView) view.findViewById(R.id.dlgTitle)).setText(title);
        ((TextView) view.findViewById(R.id.dlgMessage)).setText(message);
        TextView codeView = view.findViewById(R.id.dlgCode);
        if (code == null) {
            codeView.setVisibility(View.GONE);
        } else {
            codeView.setText(code);
        }
        LinearLayout box = view.findViewById(R.id.dlgActions);
        final Dialog dialog = new Dialog(this, R.style.DialogTheme);
        float dp = getResources().getDisplayMetrics().density;
        Button focusButton = null;
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this, null, 0, R.style.DialogButton);
            b.setText(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * dp));
            lp.topMargin = (int) (10 * dp);
            b.setLayoutParams(lp);
            if (i == primaryIdx) {
                b.setBackgroundResource(primaryDanger ? R.drawable.button_danger : R.drawable.button_primary);
                b.setTextColor(getColor(primaryDanger ? R.color.danger : R.color.on_primary));
            }
            final Runnable action = actions[i];
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (action != null) action.run();
            });
            box.addView(b);
            if (i == focusIdx) focusButton = b;
        }
        dialog.setContentView(view);
        dialog.setOnDismissListener(d -> {
            if (activeDialog == d) activeDialog = null;
        });
        activeDialog = dialog;
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            int max = (int) (560 * dp);
            int avail = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(Math.min(max, avail), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (focusButton != null) focusButton.requestFocus();
    }

    private void dismissDialog() {
        if (activeDialog != null) {
            Dialog d = activeDialog;
            activeDialog = null;
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
                // window already gone
            }
        }
    }

    private void copyToClipboard(String text) {
        copyToClipboard(text, R.string.toast_copied);
    }

    private void copyToClipboard(String text, int toastRes) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        try {
            cm.setPrimaryClip(ClipData.newPlainText("StreamBDIX+", text));
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // clipboard blocked: the command is still shown in the popup
        }
    }

    private long newRequestId() {
        long id = random.nextLong();
        return id == 0L ? 1L : id;
    }

    private PendingIntent newResultIntent(long id) {
        Intent intent = new Intent(TermuxBridge.ACTION_RESULT)
                .setPackage(getPackageName())
                .putExtra(TermuxBridge.EXTRA_REQUEST_ID, id);
        // targetSdk 28: PendingIntents are mutable by default, which Termux needs in order to add its
        // result extras. If you ever raise targetSdk to 31+, add PendingIntent.FLAG_MUTABLE here.
        return PendingIntent.getBroadcast(this, (int) (id & 0x7fffffffL), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void openTermuxUi() {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE);
        if (launch == null) {
            launch = pm.getLeanbackLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE);
        }
        if (launch == null) {
            // last resort: Termux's main activity, by name
            launch = new Intent(Intent.ACTION_MAIN)
                    .setClassName(TermuxBridge.TERMUX_PACKAGE, TermuxBridge.TERMUX_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            showError(getString(R.string.err_termux_missing));
        }
    }

    // ---- results ----

    private void handleResult(Purpose purpose, Bundle b) {
        handler.removeCallbacks(autoRefreshRunnable);
        if (purpose == Purpose.PROBE) {
            handleProbe(b);
            return;
        }
        if (b == null) {
            showError(getString(R.string.err_no_result));
            return;
        }
        int err = b.getInt("err", 0);
        String errmsg = b.getString("errmsg");
        if (err > 0 || (errmsg != null && !errmsg.trim().isEmpty())) {
            showError(getString(R.string.err_termux_failed,
                    (errmsg == null || errmsg.trim().isEmpty()) ? ("code " + err) : errmsg.trim()));
            return;
        }
        int exitCode = b.getInt("exitCode", -1);
        String stdout = nz(b.getString("stdout")).trim();
        String stderr = nz(b.getString("stderr")).trim();

        if (exitCode == 127 || stderr.contains("No such file or directory")) {
            showError(getString(R.string.err_script_missing));
            return;
        }

        if (purpose == Purpose.INSTALL) {
            if (exitCode == 0) {
                showState(getString(R.string.status_idle), getString(R.string.msg_install_ok),
                        R.color.status_ok);
                perform(Purpose.REFRESH);
            } else {
                showError(getString(R.string.err_install_failed, stderr.isEmpty() ? ("exit " + exitCode) : stderr));
            }
            return;
        }

        String kvText = stdout;
        String preview = null;   // null = old script without preview support
        int marker = stdout.indexOf(PREVIEW_MARKER);
        if (marker >= 0) {
            kvText = stdout.substring(0, marker);
            preview = stdout.substring(marker + PREVIEW_MARKER.length()).trim();
        }
        Map<String, String> kv = parseKeyValues(kvText);

        if (purpose == Purpose.STOP) {
            boolean stopped = "STOPPED".equals(kv.get("STATE"));
            if (stopped) {
                showPreview(getString(R.string.preview_empty));
                clearStats();
            }
            showState(getString(stopped ? R.string.status_stopped : R.string.status_running),
                    stopped ? getString(R.string.msg_stopped_ok) : stdout,
                    stopped ? R.color.status_warn : R.color.status_ok);
            return;
        }

        if (exitCode != 0) {
            showError(getString(R.string.err_script_failed, exitCode, stderr.isEmpty() ? stdout : stderr));
            return;
        }

        lastPreview = preview == null ? "" : preview;
        if (preview == null) {
            showPreview(getString(R.string.preview_update_script));
        } else if (preview.isEmpty()) {
            showPreview(getString(R.string.preview_empty));
        } else {
            showPreview(preview);
        }

        String state = kv.get("STATE");
        if ("RUNNING".equals(state)) {
            onRunning(purpose, kv);
        } else if ("STOPPED".equals(state)) {
            onStopped(purpose, kv);
        } else {
            showError(getString(R.string.err_unexpected, stdout));
        }
    }

    private void handleProbe(Bundle b) {
        if (b == null) {
            showSetupManual(R.string.setup_why_blocked);
            return;
        }
        int err = b.getInt("err", 0);
        String errmsg = b.getString("errmsg");
        if (err > 0 || (errmsg != null && !errmsg.trim().isEmpty())) {
            showSetupManual(R.string.setup_why_blocked);
            return;
        }
        runSetupInTermux();
    }

    private void onRunning(Purpose purpose, Map<String, String> kv) {
        String pids = nz(kv.get("PIDS"));
        String pid = nz(kv.get("NODE_PID"));
        if (pid.isEmpty()) {
            int sp = pids.indexOf(' ');
            pid = sp > 0 ? pids.substring(0, sp) : pids;
        }
        String version = nz(kv.get("VERSION"));
        setStats(nz(kv.get("UPTIME")), pid, version.isEmpty() ? "" : "v" + version);
        setLastStarted(nz(kv.get("STARTED")));
        updateServerUrl(lastPreview);
        if (purpose != Purpose.START) {
            showState(getString(R.string.status_running), getString(R.string.detail_running), R.color.status_ok);
            handler.removeCallbacks(autoRefreshRunnable);
            handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
            return;
        }
        // START while already running: never start a second instance and never create a new Termux
        // session (a foreground RUN_COMMAND always creates one). Tell the person, then bring Termux
        // back to the front; it resumes the session that was in use last (the one running StreamBDIX+).
        int msg = "yes".equals(kv.get("ATTACHABLE")) ? R.string.msg_attached : R.string.msg_already_other;
        showState(getString(R.string.status_running), getString(msg, pids), R.color.status_ok);
        Toast.makeText(this, R.string.toast_already_running, Toast.LENGTH_LONG).show();
        handler.removeCallbacks(openTermuxRunnable);
        handler.postDelayed(openTermuxRunnable, 1200L);
    }

    private void onStopped(Purpose purpose, Map<String, String> kv) {
        clearStats();
        if ("missing".equals(kv.get("NODE"))) {
            showError(getString(R.string.err_node_missing));
            return;
        }
        if ("missing".equals(kv.get("NPX"))) {
            showError(getString(R.string.err_npx_missing));
            return;
        }
        if (purpose != Purpose.START) {
            String last = nz(kv.get("LAST_EXIT"));
            String detail = last.isEmpty() ? getString(R.string.detail_idle)
                    : getString(R.string.detail_last_exit, last);
            showState(getString(R.string.status_stopped), detail, R.color.status_warn);
            return;
        }
        String error = TermuxBridge.runForeground(this, "start");
        if (error != null) {
            showError(error);
            return;
        }
        showState(getString(R.string.status_starting), getString(R.string.msg_starting), R.color.status_accent);
        // TV / Android 10+ fix: Termux is usually not allowed to open its own window from the background
        // (it needs "Display over other apps", which many TV boxes cannot grant). This app is in the
        // foreground, so it brings Termux up itself; the new session is the one shown.
        handler.removeCallbacks(openTermuxRunnable);
        handler.postDelayed(openTermuxRunnable, 900L);
    }

    // ---- helpers ----

    private void showState(String headline, String detail, int colorRes) {
        int color = getColor(colorRes);
        String text = nz(detail);
        boolean shortText = !text.isEmpty() && text.length() <= 40 && text.indexOf('\n') < 0;
        statusView.setText(headline);
        statusView.setTextColor(color);
        detailView.setText(shortText ? text : "");
        detailView.setVisibility(shortText ? View.VISIBLE : View.GONE);
        messageView.setText(text);
        messageView.setVisibility(!shortText && !text.isEmpty() ? View.VISIBLE : View.GONE);

        ColorStateList tint = ColorStateList.valueOf(color);
        statusRing.setBackgroundTintList(tint);
        statusIcon.setImageResource(iconFor(colorRes));
        statusIcon.setImageTintList(tint);
        pillView.setBackgroundTintList(tint);
        lastDot.setBackgroundTintList(tint);

        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(28 * dp);
        card.setStroke(Math.round(2 * dp), (color & 0x00FFFFFF) | 0xCC000000);
        card.setColor((color & 0x00FFFFFF) | 0x26000000);
        statusCard.setBackground(card);
    }

    private static int iconFor(int colorRes) {
        if (colorRes == R.color.status_ok) return R.drawable.ic_check;
        if (colorRes == R.color.status_error) return R.drawable.ic_alert;
        if (colorRes == R.color.status_warn) return R.drawable.ic_stop;
        return R.drawable.ic_dots;
    }

    // ---- stats row, URL row, last started ----

    private void setStats(String uptime, String pid, String version) {
        uptimeView.setText(uptime.isEmpty() ? DASH : uptime);
        pidView.setText(pid.isEmpty() ? DASH : pid);
        versionView.setText(version.isEmpty() ? DASH : version);
    }

    private void clearStats() {
        setStats("", "", "");
        setLastStarted("");
    }

    private void setLastStarted(String epochSeconds) {
        long secs = 0L;
        try {
            secs = Long.parseLong(epochSeconds.trim());
        } catch (NumberFormatException ignored) {
            // unknown: show a dash
        }
        if (secs <= 0L) {
            lastStartedView.setText(R.string.last_started_none);
            return;
        }
        String when = new SimpleDateFormat("MMM d, yyyy  \u2022  h:mm a", Locale.getDefault())
                .format(new Date(secs * 1000L));
        lastStartedView.setText(getString(R.string.last_started, when));
    }

    private void updateServerUrl(String previewText) {
        Matcher m = PORT_PATTERN.matcher(nz(previewText));
        if (m.find()) serverPort = m.group(1);
        urlView.setText("http://127.0.0.1:" + serverPort);
    }

    private String webUiUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    private String manifestUrl() {
        return webUiUrl() + "/manifest.json";
    }

    private void openWebUi() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUiUrl())));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    // ---- live terminal ----

    private void showPreview(String text) {
        String t = nz(text);
        boolean placeholder = t.equals(getString(R.string.preview_empty))
                || t.equals(getString(R.string.preview_update_script));
        boolean live = !placeholder && !t.isEmpty();
        if (live) {
            logText = t;
            if (t.equals(clearedSnapshot)) {
                previewView.setText("");          // the person cleared this exact output
            } else {
                clearedSnapshot = null;
                previewView.setText(styleLog(t));
            }
        } else {
            logText = "";
            SpannableString dim = new SpannableString(t);
            dim.setSpan(new ForegroundColorSpan(getColor(R.color.text_dim)), 0, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            previewView.setText(dim);
        }
        setConnected(live);
        if (autoScroll) scrollLogToEnd();
    }

    /** Green for success lines, red for errors, cyan underlined for links. */
    private SpannableString styleLog(String text) {
        SpannableString sp = new SpannableString(text);
        int pos = 0;
        for (String line : text.split("\n", -1)) {
            int end = pos + line.length();
            if (end > pos) {
                String l = line.toLowerCase(Locale.ROOT);
                int color = 0;
                if (l.contains("error") || l.contains("failed") || l.contains("exception")
                        || l.contains("eaddrinuse") || l.contains("not found")) {
                    color = getColor(R.color.terminal_err);
                } else if (l.contains("running") || l.contains("success") || l.contains("listening")
                        || l.contains("started") || l.contains("online") || l.contains("ready")) {
                    color = getColor(R.color.terminal_ok);
                }
                if (color != 0) {
                    sp.setSpan(new ForegroundColorSpan(color), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            pos = end + 1;
        }
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            sp.setSpan(new ForegroundColorSpan(getColor(R.color.terminal_link)), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new UnderlineSpan(), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    private void setConnected(boolean connected) {
        int color = getColor(connected ? R.color.status_ok : R.color.text_dim);
        connView.setText(connected ? R.string.term_connected : R.string.term_disconnected);
        connView.setTextColor(color);
        connView.setBackgroundTintList(ColorStateList.valueOf(color));
        connView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                connected ? R.drawable.dot_small_green : R.drawable.dot_small_dim, 0, 0, 0);
    }

    private void clearLogs() {
        if (!logText.isEmpty()) clearedSnapshot = logText;
        logText = "";
        previewView.setText("");
    }

    private void toggleTerminal() {
        boolean collapsed = terminalBody.getVisibility() == View.GONE;
        terminalBody.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        collapseButton.setRotation(collapsed ? 0f : 180f);
    }

    private void scrollLogToEnd() {
        logScroll.post(() -> logScroll.scrollTo(0, previewView.getHeight()));
    }

    // ---- settings / about ----

    private void showSettings() {
        showDialogCard(getString(R.string.settings_title), getString(R.string.settings_msg),
                TermuxBridge.SETUP_COMMAND,
                new String[]{getString(R.string.act_copy_setup), getString(R.string.act_reinstall),
                        getString(R.string.act_app_settings), getString(R.string.act_close)},
                new Runnable[]{
                        () -> copyToClipboard(TermuxBridge.SETUP_COMMAND),
                        () -> perform(Purpose.INSTALL),
                        this::openAppSettings,
                        null},
                -1, 3, false);
    }

    private void showAbout() {
        showDialogCard(getString(R.string.about_title),
                getString(R.string.about_msg, appVersion, webUiUrl()), null,
                new String[]{getString(R.string.act_close)}, new Runnable[]{null}, -1, 0, false);
    }

    private String readAppVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void showError(String message) {
        showState(getString(R.string.status_error), message, R.color.status_error);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, String> parseKeyValues(String text) {
        Map<String, String> map = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int i = line.indexOf('=');
            if (i > 0) {
                String key = line.substring(0, i).trim();
                if (key.matches("[A-Z_]+")) {
                    map.put(key, line.substring(i + 1).trim());
                }
            }
        }
        return map;
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            // Normalise to LF so a CRLF checkout on Windows can never break bash.
            return out.toString("UTF-8").replace("\r\n", "\n").replace('\r', '\n');
        }
    }
}
