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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private enum Purpose { REFRESH, START, START_BG, STOP, INSTALL, PROBE }

    private static final int REQ_PERMISSION = 1001;
    private static final long RESULT_TIMEOUT_MS = 15000L;
    private static final long PROBE_TIMEOUT_MS = 7000L;
    private static final long AUTO_REFRESH_MS = 5000L;
    private static final String PREVIEW_MARKER = "@@PREVIEW@@";
    private static final String DEFAULT_PORT = "7001";
    private static final String DASH = "\u2014";
    private static final String PREFS = "launcher";
    private static final String PREF_SCRIPT_FOR = "script_for_version";
    private static final int MAX_LOG_LINES = 300;
    private static final int MAX_LINE_CHARS = 300;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern BORDER_ONLY = Pattern.compile("^[-=_*+|~#.:\\s]+$");
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
    private String lastPreview = "";
    private boolean autoScroll = true;
    private boolean autoInstallTried;
    private long startedEpoch;                         // seconds, 0 = unknown / not running

    // Live terminal: every line seen so far with the time the launcher first saw it.
    private final List<String[]> logEntries = new ArrayList<>();   // {time, text}
    private List<String> lastWindow = new ArrayList<>();
    private int clearedUpTo;
    private TextView manifestView;

    private final Runnable uptimeTicker = new Runnable() {
        @Override public void run() {
            if (startedEpoch <= 0L) return;
            long secs = System.currentTimeMillis() / 1000L - startedEpoch;
            uptimeView.setText(formatUptime(secs));
            handler.postDelayed(this, 1000L);
        }
    };

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

    /** After Stop Server: closes Termux itself (no result is expected; the process is gone afterwards). */
    private final Runnable closeTermuxRunnable = new Runnable() {
        @Override public void run() {
            if (TermuxBridge.isTermuxInstalled(MainActivity.this)
                    && TermuxBridge.hasRunCommandPermission(MainActivity.this)) {
                TermuxBridge.runBackground(MainActivity.this, "quit", null);
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
        manifestView = findViewById(R.id.tvManifest);
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
            String text = visibleLogText();
            if (text.isEmpty()) return;
            copyToClipboard(text, R.string.toast_log_copied);
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
        } else if (!autoInstallTried && !getPrefs().getString(PREF_SCRIPT_FOR, "").equals(appVersion)) {
            // New launcher version: put the matching helper script into Termux first (once).
            autoInstallTried = true;
            perform(Purpose.INSTALL);
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
        handler.removeCallbacks(uptimeTicker);
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
            case START_BG:
                error = TermuxBridge.runBackground(this, "start-bg", resultIntent);
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
                getPrefs().edit().putString(PREF_SCRIPT_FOR, appVersion).apply();
                showState(getString(R.string.status_idle), getString(R.string.msg_install_ok),
                        R.color.status_ok);
                perform(Purpose.REFRESH);
            } else {
                showError(getString(R.string.err_install_failed, stderr.isEmpty() ? ("exit " + exitCode) : stderr));
            }
            return;
        }

        if (purpose == Purpose.START_BG) {
            if (exitCode != 0) {
                showError(getString(R.string.err_script_failed, exitCode, stderr.isEmpty() ? stdout : stderr));
                return;
            }
            // Started detached in tmux. Termux stays where it is; confirm with a status check shortly.
            showState(getString(R.string.status_starting), getString(R.string.msg_starting_bg), R.color.status_accent);
            handler.removeCallbacks(autoRefreshRunnable);
            handler.postDelayed(autoRefreshRunnable, 2000L);
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
                // Server is down: close Termux too. Short delay so the screen updates first.
                handler.postDelayed(closeTermuxRunnable, 700L);
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
        setLastStarted(nz(kv.get("STARTED")));      // also starts the 1-second uptime ticker
        setStats(nz(kv.get("UPTIME")), pid, version.isEmpty() ? "" : "v" + version);
        updateServerUrl(lastPreview);
        if (purpose != Purpose.START) {
            showState(getString(R.string.status_running), getString(R.string.detail_running), R.color.status_ok);
            handler.removeCallbacks(autoRefreshRunnable);
            handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
            return;
        }
        // START while already running: never start a second instance and never create a new Termux
        // session. Just tell the person; Termux is not brought to the front. The status keeps refreshing.
        int msg = "yes".equals(kv.get("ATTACHABLE")) ? R.string.msg_attached : R.string.msg_already_other;
        showState(getString(R.string.status_running), getString(msg, pids), R.color.status_ok);
        Toast.makeText(this, R.string.toast_already_running, Toast.LENGTH_LONG).show();
        handler.removeCallbacks(autoRefreshRunnable);
        handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
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
        if ("installed".equals(kv.get("TMUX"))) {
            // Normal path: start detached in tmux, Termux stays in the background.
            showState(getString(R.string.status_starting), getString(R.string.msg_starting_bg), R.color.status_accent);
            perform(Purpose.START_BG, true);
            return;
        }
        // No tmux (or an old helper script that does not report it): old behaviour, visible Termux session.
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
        if (startedEpoch <= 0L) uptimeView.setText(uptime.isEmpty() ? DASH : uptime);
        pidView.setText(pid.isEmpty() ? DASH : pid);
        versionView.setText(version.isEmpty() ? DASH : version);
    }

    private void clearStats() {
        handler.removeCallbacks(uptimeTicker);
        startedEpoch = 0L;
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
        handler.removeCallbacks(uptimeTicker);
        if (secs <= 0L) {
            startedEpoch = 0L;
            lastStartedView.setText(R.string.last_started_none);
            return;
        }
        startedEpoch = secs;
        String when = new SimpleDateFormat("MMM d, yyyy  \u2022  h:mm a", Locale.getDefault())
                .format(new Date(secs * 1000L));
        lastStartedView.setText(getString(R.string.last_started, when));
        uptimeTicker.run();            // shows the uptime now and then ticks every second
    }

    private static String formatUptime(long secs) {
        if (secs < 0L) secs = 0L;
        long h = secs / 3600L;
        long m = (secs % 3600L) / 60L;
        long sec = secs % 60L;
        if (h > 0L) return String.format(Locale.US, "%02dh %02dm", h, m);
        return String.format(Locale.US, "%dm %02ds", m, sec);
    }

    private void updateServerUrl(String previewText) {
        Matcher m = PORT_PATTERN.matcher(nz(previewText));
        if (m.find()) {
            // The text comes from terminal output (untrusted): accept only a real TCP port.
            try {
                int port = Integer.parseInt(m.group(1));
                if (port >= 1 && port <= 65535) serverPort = String.valueOf(port);
            } catch (NumberFormatException ignored) {
                // keep the previous port
            }
        }
        urlView.setText(webUiUrl());
        manifestView.setText(manifestUrl());
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

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void showPreview(String text) {
        String t = nz(text);
        boolean placeholder = t.equals(getString(R.string.preview_empty))
                || t.equals(getString(R.string.preview_update_script));
        if (!placeholder && !t.isEmpty()) {
            addToLog(cleanLines(t));
            renderLog();
            setConnected(true);
            return;
        }
        // Nothing live. Keep showing what was already logged (a crash message stays readable).
        setConnected(false);
        if (clearedUpTo >= logEntries.size()) {
            lastWindow = new ArrayList<>();
            SpannableString dim = new SpannableString(t);
            dim.setSpan(new ForegroundColorSpan(getColor(R.color.text_dim)), 0, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            previewView.setText(dim);
        }
    }

    /**
     * Turns the raw tmux text into plain lines. StreamBDIX+ draws boxes and spinners; Android's monospace
     * font has no glyph for many of them, so they showed up as empty squares. They are removed here.
     */
    private List<String> cleanLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : ANSI_PATTERN.matcher(raw).replaceAll("").split("\r?\n")) {
            StringBuilder sb = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == '\t') {
                    sb.append(' ');
                } else if (cp < 0x20 || cp == 0x7F || cp == 0xFFFD
                        || (cp >= 0x2500 && cp <= 0x25FF)      // box drawing, blocks, geometric shapes
                        || (cp >= 0x2800 && cp <= 0x28FF)      // braille spinners
                        || (cp >= 0xE000 && cp <= 0xF8FF)      // private-use icon fonts
                        || (cp >= 0x200B && cp <= 0x200F)) {   // zero-width marks
                    sb.append(' ');
                } else {
                    sb.appendCodePoint(cp);
                }
            }
            String clean = sb.toString().replaceAll("\\s{2,}", " ").trim();
            if (clean.isEmpty() || BORDER_ONLY.matcher(clean).matches()) continue;
            if (clean.length() > MAX_LINE_CHARS) clean = clean.substring(0, MAX_LINE_CHARS);
            out.add(clean);
        }
        return out;
    }

    /** Adds only the lines that are new since the last look (the tmux window scrolls). */
    private void addToLog(List<String> window) {
        int overlap = 0;
        for (int k = Math.min(lastWindow.size(), window.size()); k > 0; k--) {
            if (lastWindow.subList(lastWindow.size() - k, lastWindow.size()).equals(window.subList(0, k))) {
                overlap = k;
                break;
            }
        }
        String now = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        for (int i = overlap; i < window.size(); i++) {
            logEntries.add(new String[]{now, window.get(i)});
        }
        lastWindow = window;
        while (logEntries.size() > MAX_LOG_LINES) {
            logEntries.remove(0);
            if (clearedUpTo > 0) clearedUpTo--;
        }
    }

    private String visibleLogText() {
        StringBuilder sb = new StringBuilder();
        for (int i = clearedUpTo; i < logEntries.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(logEntries.get(i)[0]).append("] ").append(logEntries.get(i)[1]);
        }
        return sb.toString();
    }

    private void renderLog() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int indent = (int) Math.ceil(previewView.getPaint().measureText("[00:00:00] "));
        for (int i = clearedUpTo; i < logEntries.size(); i++) {
            int lineStart = sb.length();
            String time = "[" + logEntries.get(i)[0] + "] ";
            String text = logEntries.get(i)[1];
            sb.append(time).append(text);
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.terminal_ok)), lineStart,
                    lineStart + time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styleText(sb, text, lineStart + time.length());
            sb.append('\n');
            // wrapped text lines up under the message, not under the time
            sb.setSpan(new LeadingMarginSpan.Standard(0, indent), lineStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        previewView.setText(sb);
        if (autoScroll) scrollLogToEnd();
    }

    /** Green for success lines, red for errors, cyan underlined for links. Offsets are into sb. */
    private void styleText(SpannableStringBuilder sb, String text, int offset) {
        String l = text.toLowerCase(Locale.ROOT);
        int color = 0;
        if (l.contains("error") || l.contains("failed") || l.contains("exception")
                || l.contains("eaddrinuse") || l.contains("not found")) {
            color = getColor(R.color.terminal_err);
        } else if (l.contains("running") || l.contains("success") || l.contains("listening")
                || l.contains("started") || l.contains("online") || l.contains("ready")) {
            color = getColor(R.color.terminal_ok);
        }
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), offset, offset + text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.terminal_link)),
                    offset + m.start(), offset + m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new UnderlineSpan(), offset + m.start(), offset + m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
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
        clearedUpTo = logEntries.size();
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
