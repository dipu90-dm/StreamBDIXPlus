package com.streambdixplus.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Thin wrapper around the official Termux RUN_COMMAND intent API.
 * https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 *
 * NOTE: a foreground RUN_COMMAND always creates a NEW Termux session. SESSION_ACTION only decides
 * which session is focused / whether the Termux UI opens. Duplicate-process protection therefore
 * lives in start-streambdixplus.sh, not here.
 */
final class TermuxBridge {

    static final String TERMUX_PACKAGE = "com.termux";
    static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    static final String SERVICE_CLASS = "com.termux.app.RunCommandService";
    static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    static final String TERMUX_ACTIVITY = "com.termux.app.TermuxActivity";

    static final String HOME_DIR = "/data/data/com.termux/files/home";
    static final String BASH_PATH = "/data/data/com.termux/files/usr/bin/bash";
    static final String SCRIPT_PATH = HOME_DIR + "/start-streambdixplus.sh";
    static final String ASSET_SCRIPT = "start-streambdixplus.sh";

    /** npm package that gets installed and run. Never pinned: "@latest" always means the newest release. */
    static final String NPM_PACKAGE = "streambdixplus";

    /**
     * The one-time setup, exactly as typed by hand in Termux.
     * - installs the newest Node.js Termux offers ("nodejs", not the older "nodejs-lts"; the two cannot coexist)
     * - runs the newest StreamBDIX+ published on npm ("@latest", no pinned version)
     */
    static final String SETUP_COMMAND =
            "pkg update && pkg upgrade -y"
            + " && { pkg uninstall -y nodejs-lts >/dev/null 2>&1 || true; }"
            + " && pkg install -y nodejs tmux"
            + " && mkdir -p ~/.termux && touch ~/.termux/termux.properties"
            + " && { grep -q '^allow-external-apps = true$' ~/.termux/termux.properties || echo \"allow-external-apps = true\" >> ~/.termux/termux.properties; }"
            + " && termux-reload-settings && cd && npx --yes --package=" + NPM_PACKAGE + "@latest " + NPM_PACKAGE;

    /**
     * Grants this app the RUN_COMMAND permission without a PC and without any system dialog, by letting
     * Termux talk to the device's own adb (needs USB debugging enabled on the device).
     */
    static final String SELF_GRANT_COMMAND =
            "pkg install -y android-tools && adb connect 127.0.0.1:5555"
            + " && adb -s 127.0.0.1:5555 shell pm grant com.streambdixplus.launcher " + PERMISSION_RUN_COMMAND
            + " && echo \"Done. Go back to StreamBDIX+ Launcher.\"";

    /** Same command wrapped for 'bash -c': saves the helper script ($1), runs the setup, keeps the window open. */
    private static final String SETUP_WRAPPER =
            "trap ':' INT\n"   // keep this shell alive after Ctrl+C so "Press Enter to close..." still shows
            + "[ -n \"$1\" ] && { printf '%s' \"$1\" > \"$HOME/start-streambdixplus.sh\"; chmod 700 \"$HOME/start-streambdixplus.sh\"; }\n"
            + SETUP_COMMAND + "\n"
            + "rc=$?\n"
            + "echo\n"
            + "echo \"[Setup finished, exit code $rc]\"\n"
            + "read -r -p \"Press Enter to close... \" _\n"
            + "exit $rc\n";

    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    /** "0" = switch to the new session and open the Termux activity. */
    private static final String SESSION_ACTION_SWITCH_AND_OPEN = "0";

    // Result delivery (background commands)
    static final String ACTION_RESULT = "com.streambdixplus.launcher.TERMUX_RESULT";
    static final String EXTRA_REQUEST_ID = "request_id";
    static final String EXTRA_RESULT_BUNDLE = "result";   // Bundle: stdout, stderr, exitCode, err, errmsg

    private TermuxBridge() {}

    static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean hasRunCommandPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs "bash start-streambdixplus.sh <sub>" in a visible Termux session. Returns an error text or null. */
    static String runForeground(Context ctx, String subcommand) {
        return runCommand(ctx, BASH_PATH, new String[]{SCRIPT_PATH, subcommand}, false, null);
    }

    /** Runs "bash start-streambdixplus.sh <sub>" in the background; result goes to the PendingIntent. */
    static String runBackground(Context ctx, String subcommand, PendingIntent resultIntent) {
        return runCommand(ctx, BASH_PATH, new String[]{SCRIPT_PATH, subcommand}, true, resultIntent);
    }

    /** Writes the given script text to ~/start-streambdixplus.sh inside Termux (background). */
    static String installScript(Context ctx, String scriptText, PendingIntent resultIntent) {
        // bash -c '<cmd>' installer <content> <path>   ->  $1 = content, $2 = path
        String cmd = "printf '%s' \"$1\" > \"$2\" && chmod 700 \"$2\"";
        return runCommand(ctx, BASH_PATH,
                new String[]{"-c", cmd, "installer", scriptText, SCRIPT_PATH}, true, resultIntent);
    }

    /** Harmless background command, used to find out whether Termux accepts commands from this app. */
    static String probe(Context ctx, PendingIntent resultIntent) {
        return runCommand(ctx, BASH_PATH, new String[]{"-c", "echo ok"}, true, resultIntent);
    }

    /** Runs the one-time setup in a visible Termux session and also saves the bundled helper script. */
    static String runSetupForeground(Context ctx, String scriptText) {
        return runCommand(ctx, BASH_PATH,
                new String[]{"-c", SETUP_WRAPPER, "streambdixplus-setup", scriptText == null ? "" : scriptText},
                false, null);
    }

    private static String runCommand(Context ctx, String path, String[] args,
                                     boolean background, PendingIntent resultIntent) {
        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PACKAGE, SERVICE_CLASS);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ARGUMENTS, args);
        intent.putExtra(EXTRA_WORKDIR, HOME_DIR);
        intent.putExtra(EXTRA_BACKGROUND, background);
        if (!background) {
            intent.putExtra(EXTRA_SESSION_ACTION, SESSION_ACTION_SWITCH_AND_OPEN);
        }
        if (resultIntent != null) {
            intent.putExtra(EXTRA_PENDING_INTENT, resultIntent);
        }
        try {
            ComponentName started = ctx.startService(intent);
            if (started == null) {
                return ctx.getString(R.string.err_service_missing);
            }
            return null;
        } catch (SecurityException e) {
            return ctx.getString(R.string.err_security);
        } catch (IllegalStateException e) {
            return ctx.getString(R.string.err_not_allowed_now);
        }
    }
}
