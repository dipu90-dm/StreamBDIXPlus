<p align="center">
  <img src="docs/icon.png" width="120" alt="StreamBDIX+ Launcher icon">
</p>

<h1 align="center">StreamBDIX+ Launcher</h1>

<p align="center">
  One-tap launcher for <b>StreamBDIX+</b> on Android phones and Android TV boxes, powered by Termux.
</p>

<p align="center">
  <a href="../../actions/workflows/build.yml"><img src="../../actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/OWNER/REPO?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Android%20TV-supported-4285F4" alt="Android TV">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT license"></a>
</p>

> Replace `OWNER/REPO` in the release badge above with your GitHub user and repository name.

## Features
- **Start / Stop / Status** for StreamBDIX+ with a live view of the Termux screen
- **Always the newest StreamBDIX+:** every Start looks up the latest `streambdixplus` on npm and runs exactly that version (no pinned version to update by hand)
- **Always the newest Node.js:** the setup installs Termux's current `nodejs` package, not the older `nodejs-lts`
- **One-time Setup button** that installs Node.js, tmux and enables `allow-external-apps` in Termux
- **Never starts a second copy**, and reopens your existing Termux session instead
- **Built for TV remotes:** D-pad focus, banner, and a permission help card for boxes that hide the system dialog
- **Live dashboard:** status, uptime, PID, running version, Web UI link, Stremio manifest link and a live terminal view
- Native Java, no dependencies, about 100 KB

A small native Android app (Java, no dependencies) for **Android 9+ phones and Android TV boxes**.
It starts StreamBDIX+ (`npx --yes --package=streambdixplus@latest streambdixplus`) inside an already installed **Termux** using the official
[RUN_COMMAND intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

> The launcher does not bundle StreamBDIX+: it downloads the newest `streambdixplus` release from npm each time you
> press Start, so you always get whatever is published as **latest** on npmjs.com (for example 1.4.2).
> `--yes` skips npx's download question. Check the package yourself first.

## Download
Get the latest `StreamBDIX+.apk` from the [Releases](../../releases) page, or build it yourself (see "Build the APK on GitHub").

---

## Quick start (phone or TV box)

### Step 1: Install Termux
Install Termux from **F-Droid** or the **GitHub releases** (not the old Play Store version). Open it once.

### Step 2: One command in Termux (or just tap the new button)
In the launcher, tap **Set up Termux (one-time)**. It runs the command below for you. If Termux does not accept
commands from the app yet (the normal case on a fresh install), the app copies the command to the clipboard and opens
Termux, and you only have to paste it (long-press > Paste, or Ctrl+Shift+V) and press Enter.

Or paste this single command into Termux yourself and press Enter:

```sh
pkg update && pkg upgrade -y && { pkg uninstall -y nodejs-lts >/dev/null 2>&1 || true; } && pkg install -y nodejs tmux && mkdir -p ~/.termux && touch ~/.termux/termux.properties && { grep -q '^allow-external-apps = true$' ~/.termux/termux.properties || echo "allow-external-apps = true" >> ~/.termux/termux.properties; } && termux-reload-settings && cd && npx --yes --package=streambdixplus@latest streambdixplus
```

What it does, in order: updates Termux packages, removes the older `nodejs-lts` package if it is there (it cannot be
installed next to the current one), installs the **latest Node.js** (needed for `npx`) and tmux (optional, lets Start
re-attach to a running session), enables `allow-external-apps` (only once, no duplicate lines), reloads the settings, and
finally starts the **newest StreamBDIX+ on npm** right away (`@latest`, `--yes` skips the download question).

Notes:
- `pkg update` / `pkg upgrade` may ask a question about config files. Just press Enter to keep the default.
- The last part keeps running in that Termux session. Press **Ctrl+C** to stop it. The launcher also recognises an
  instance started this way as running, and will not start a second one.
- After the first run, **fully close and reopen Termux** so `allow-external-apps` is active for the launcher.
- Running the command again is safe, and it is also how you move to a newer Node.js: the `pkg upgrade` step brings it up to date.
- The package name is `streambdixplus` (with the plus). The old `streambdix@1.4.0 streambdix` form from the upstream
  project is a different package and is no longer used here.
- This has to be typed in Termux itself. The launcher cannot do it for you, because Termux refuses to take
  commands from other apps until `allow-external-apps` is set.

### Step 3: Install the launcher APK
Download `StreamBDIX+.apk` (see "Build the APK on GitHub" below), then follow the section for your device.

### Step 4: Download & install Stremio
1. Download **Stremio** from [stremio.com](https://www.stremio.com/), then install it on your device.
2. Create a Stremio account.
3. Open Stremio, go to **Addons**, and click **Add Addon**. Paste the following URL and install the addon:

```
http://127.0.0.1:7001/manifest.json
```

> **Important note**
> This addon cannot be added directly from platforms other than a PC.
> First, create or log in to your Stremio account on your PC and install the addon there. Once installed, simply log in with the same Stremio account on your other devices (Android TV, Android, etc.). The addon will automatically sync across all your devices.

### Alternative Installation Method (Stremio Web – PC & Mobile)

If you're having trouble installing the addon using the regular method, you can try installing it through [Stremio Web](https://web.stremio.com/).

**On PC (Windows / macOS / Linux):**

1. Open [Stremio Web](https://web.stremio.com/) in your browser.
2. Sign in to your Stremio account.
3. Open the **StreamBDIXPlus addon installation link** in a new browser tab.
4. If prompted, click **Install** or **Allow** to add the addon.
5. Return to Stremio Web and navigate to **Settings → Add-ons** to verify the installation.

**On Mobile (Android / iOS):**

1. Open your mobile browser (Chrome, Safari, etc.).
2. Visit [Stremio Web](https://web.stremio.com/).
3. Sign in to your Stremio account.
4. Open the **StreamBDIXPlus addon installation link** in the same browser.
5. If prompted, allow the addon to be installed.
6. Check **Settings → Add-ons** to confirm that the addon has been added.

**Note:** Stremio Web installation may not work on every device or browser. If the installation fails, try using a different browser or the regular Stremio app installation method.


### Step 5: First run
1. Open **StreamBDIX+ Launcher** and tap **Install / update script**. This writes `~/start-streambdixplus.sh` into Termux.
2. Tap **Start** (or **Check status**) and **allow** the Termux RUN_COMMAND permission when asked.
3. Tap **Start Operation**. Termux opens, asks npm for the newest `streambdixplus` version, and runs exactly that one.

---

## Run on a phone
1. Copy `StreamBDIX+.apk` to the phone (USB, Drive, Telegram, etc.).
2. Open it and allow **Install unknown apps** for your file manager or browser when Android asks.
3. Make sure Termux is installed first, and that you ran the one-line command above.
4. Open the launcher and follow "First run".
5. Keep Termux alive: in Termux's notification tap **Acquire wakelock**, and set Termux to **Unrestricted** battery use
   (Settings > Apps > Termux > Battery).
6. Open Stremio and enjoy your desired content.

## Run on an Android TV / TV box
TV boxes usually have no touchscreen, so use a remote (D-pad) and, for typing, a USB or Bluetooth keyboard
(or your remote app's keyboard).

1. **Install the APK** with adb from a PC on the same network:
   ```
   adb connect <tv-ip>
   adb install "StreamBDIX+.apk"
   ```
   (Enable Developer options > USB/Network debugging on the box first.) Or use a file-transfer app such as
   "Send Files to TV" and open the APK on the box.
2. Install **Termux** the same way (`adb install termux.apk`) and open it once.
3. Type the one-line command from Step 2 into Termux with a keyboard, then restart Termux.
4. Open **StreamBDIX+ Launcher** from the apps row. It has a TV banner and a leanback launcher entry.
   Move with the D-pad; the focused button gets a coloured ring. Press OK to select.
5. **If the permission dialog does not appear, or has no Allow button** (common on TV boxes), the launcher now opens a
   help card with three ways out, all usable with a remote:
   - *Show the permission dialog again.*
   - *Open app settings* and switch the permission on (not every TV has this screen).
   - *Copy command and open Termux*: paste it into Termux and press Enter. It installs `android-tools` and grants the
     permission through the box's own adb, so **no PC is needed** (turn on USB debugging in Developer options first,
     choose "Always allow" if asked, and run it a second time if it says *unauthorized*).

   With a PC you can still do it by hand:
   ```
   adb shell pm grant com.streambdixplus.launcher com.termux.permission.RUN_COMMAND
   ```
   If the launcher was installed before Termux, uninstall and reinstall the launcher once.
6. Optional but recommended on a TV box:
   ```
   adb shell appops set com.termux SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +com.termux
   ```
   The first allows "Display over other apps" for Termux, the second stops battery optimisation from killing it.
7. Termux has no TV launcher icon, so use the launcher's **Open Termux** row to bring it to the front.

---

## Build the APK on GitHub
1. Create an empty repository on github.com.
2. Upload this project (see "Upload" below).
3. Open the **Actions** tab, open the latest **Build debug APK** run, and download the artifact
   `StreamBDIX-apk`. Unzip it to get `StreamBDIX+.apk`.
4. If a build fails, download the `build-reports` artifact from the same run, or copy the red error lines from the log.

### Upload (Windows 10, PowerShell, Git for Windows installed)
```
cd StreamBDIXPlusLauncher
git init
git add .
git commit -m "StreamBDIX+ Launcher"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```
Use git rather than browser upload: the browser can miss the hidden `.github` folder.
To update later: replace the files, then `git add .`, `git commit -m "update"`, `git push`.

---

## Buttons
| Button | Action |
|---|---|
| Start Operation | status check, then start if not running. If it is already running (even if you started it by hand in Termux), it shows "Already running" and reopens Termux on your previous session: no new session, no second instance |
| Stop Server | confirm, then sends SIGTERM to the running StreamBDIX+ instance and closes Termux completely (the Termux app process is ended, so its notification goes away too). Opening the launcher or Termux again brings Termux back. Needs the updated script: it installs itself on first launch after the update |
| Set up Termux (one-time) | runs the one-line setup command (update, latest Node.js, tmux, allow-external-apps, then the newest StreamBDIX+) and also saves the helper script. If Termux is not yet open to the app, it copies the command and opens Termux so you can paste it |
| Check status | background check: running state, PIDs, missing Node/npx |
| Install / update script | writes the bundled script to Termux home |
| Settings (gear, top right) | copy the setup command, reinstall the helper script, open the app's Android settings |
| About (i, top right) | launcher version and what the launcher does |
| Web UI / `/manifest.json` (status card) | opens the local Web UI in a browser / copies the Stremio manifest link |
| Open Termux | brings Termux to the front |

## Dashboard and live terminal
The main screen shows:
- **Server status card**: *Running* with the PID, how long it has been up, the **StreamBDIX+ version it is running** (the
  version npm resolved when you pressed Start) and when it was last started. If it stopped, it shows *Not running* and the
  exit code of the last run. The card changes colour with the state (green, amber, red).
- **Web UI and manifest**: tap the first line to open the local Web UI, tap the second (the full
  `http://127.0.0.1:7001/manifest.json` link) to copy it for Stremio. The text shrinks to fit. The port is read from the
  server's own output (default `7001`).
- **Uptime and Last Started** come from a start time the helper script writes when it launches StreamBDIX+ (not from
  `/proc`, whose clocks disagree on Android after the device slept). Uptime counts up every second in the app.
- **Live Terminal**: plain text lines, each with a time stamp, in the style of the design: green times, white text,
  green success lines, red errors and cyan links. It refreshes every few seconds and keeps a history. The time is when the
  launcher first saw the line (so it can be a few seconds after the server printed it). Box-drawing characters, spinners
  and icon-font glyphs that Android cannot draw (they appeared as empty boxes) are removed, and long lines wrap under the
  message. *Copy* copies the lines, *Clear Logs* empties the view, *Auto-scroll* keeps it at the newest line, and the arrow
  at the top right collapses it. If StreamBDIX+ crashes, the error stays visible.

The terminal works when StreamBDIX+ runs inside **tmux**, which is the case whenever you press **Start Operation** in the app
(tmux is installed by the one-line command). Android does not let one app read another app's terminal, so a copy
started by hand outside tmux still shows as *Running*, but the terminal card says *No session*. To get it for a
manual run, start it inside tmux instead of the last part of the one-line command:
```sh
tmux new-session -A -s streambdixplus "npx --yes --package=streambdixplus@latest streambdixplus"
```
(Detach with Ctrl+b then d and it keeps running.)

After an app update, the launcher installs the matching helper script into Termux by itself the first time you open it (the
**Install / update script** row still does it on demand).

## Which version runs?
Nothing is pinned. When you press Start, the helper script runs `npm view streambdixplus version` to read the newest
published version (the `latest` tag on npmjs.com), then runs `npx --yes --package=streambdixplus@<that version> streambdixplus`.
Publish `1.4.2` on npm and the next Start runs `1.4.2`. If npm cannot be reached for the lookup, it falls back to
`streambdixplus@latest` and lets npx resolve it. Node.js comes from Termux's current `nodejs` package.

## How it avoids duplicate processes
Every foreground RUN_COMMAND opens a **new** Termux session, so `SESSION_ACTION` cannot reuse one.
Protection therefore lives in `~/start-streambdixplus.sh`:
1. a lock directory serialises double taps,
2. a PID file written by the script's own runner,
3. a strict `/proc` scan for a **node** process whose argument is `streambdixplus` (or a path to it),
   which cannot match the script itself.

If something is already running, nothing is started or killed, and **no new Termux session is opened**: the app
shows an "Already running" message and brings Termux back to the front on the session you used last (normally the
one running StreamBDIX+). Android does not let one app pick a specific Termux session; if a different one shows,
swipe in from Termux's left edge and choose the right one. If the launcher started it inside tmux, run
`tmux attach -t streambdixplus` in Termux to see it again.
Stop only ever runs when you press it and confirm (SIGTERM, never SIGKILL).

## TV: "Start does nothing"
Since 1.1.0 the launcher opens Termux itself right after Start and after Set up. On Android 10+ Termux is often not
allowed to open its own window from the background (it needs "Display over other apps", which many TV boxes cannot
grant), so previously the session started invisibly. Together with the permission help card above, Start now always
shows either the Termux session or a clear message.

## Troubleshooting
| Message | Fix |
|---|---|
| "Termux is not installed" | Install Termux (F-Droid / GitHub). |
| Permission denied / no dialog | Use the help card that opens (settings, or the copy-and-paste command for Termux). If the launcher was installed before Termux, reinstall the launcher. |
| "No answer from Termux" / "Termux reported an error" | Tap **Set up Termux (one-time)** (or run the one-line command) and restart Termux (`allow-external-apps` must be set). |
| "script was not found" | Tap Install / update script. |
| "Node.js is not installed" / "npx not found" | In Termux: `pkg install nodejs`. |
| Start still runs an old version, or uptime shows a dash | Tap **Install / update script** so the current helper script is installed, then press Start Operation again (a server started before the update has no start time recorded). |
| Empty squares in the terminal | Fixed in 1.5.1: unsupported box/spinner characters are stripped. Install / update script as well, so lines are no longer cut in the middle of a character. |
| `nodejs-lts` / `nodejs` conflict | Run `pkg uninstall nodejs-lts` and then `pkg install nodejs`. |
| CR / `$'\r': command not found` in Termux | CRLF line endings. Tap Install / update script (the app writes LF) or run `sed -i 's/\r$//' ~/start-streambdixplus.sh`. |
| Termux gets killed in the background | Acquire wakelock, set battery to Unrestricted, or use the `dumpsys deviceidle` command above. |

## Known limits
- The `/proc` scan identifies StreamBDIX+ as a node process with a `streambdixplus` argument. If the program
  rewrites its own process title, a manually started instance may not be recognised (instances started by this launcher always are).
- Status/Stop read results from Termux via a PendingIntent; this needs a reasonably recent Termux.
- Termux has no leanback launcher; on TV use the app's Open Termux row.
- There is no `gradle-wrapper.jar` in the repo; the GitHub workflow installs Gradle 8.7 directly.

## Releasing
Push a version tag and GitHub Actions builds the APK and attaches it to a new Release:
```
git tag v1.5.0
git push origin v1.5.0
```

## Contributing
Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
[MIT](LICENSE)

## Security hardening (public build)

The launcher runs the newest StreamBDIX+ published on npm. The hardening notes below describe the `1.4.1` build this
project was reviewed against; check the release notes of a newer version before relying on them. Intended for local use only:
- The Node.js addon binds to `127.0.0.1` and rejects non-local Host/Origin values.
- Management endpoints accept loopback requests only and apply JSON content-type and size checks.
- Cloudflare Tunnel and token management are disabled in this distribution.
- The GitHub Actions build job has read-only repository permissions; release publishing runs in a separate tag-only job with write permission.

The source connectors still contact their configured third-party endpoints. Several configured BDIX endpoints use plain HTTP, so traffic to those endpoints is not encrypted. This is an upstream service limitation and is not changed by the local API hardening.

“Stremio addon”, “BDIX”, “Android TV”, “Termux”
