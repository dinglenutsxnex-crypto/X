# RootDroid Inspector

A native Android app (Kotlin + Jetpack Compose) for on-device app instrumentation using root access.

## Features

- **App Grid** — pick any installed app and add it as a target
- **Root Bridge** — executes `su -c` commands for privileged access
- **Live Logcat** — streams `logcat --pid=<PID>` in real time, color-coded by level
- **Syscall Tracer** — attaches `strace -p <PID>` and parses every intercepted call
- **Memory Maps** — reads `/proc/<PID>/maps` showing every mapped region with rwx flags
- **Open Files** — reads `/proc/<PID>/fd` for file descriptor listing
- **Floating Overlay** — `SYSTEM_ALERT_WINDOW` foreground service, draggable, collapsible
- **Process Stats** — VmRSS, VmSize, thread count, state from `/proc/<PID>/status`

## Requirements

- Android 9+ (API 28+)
- Rooted device — Magisk, KernelSU, or APatch
- `strace` binary on device (optional, for syscall tracing) — install via Termux: `pkg install strace`

## Building

### Local

```bash
cd android-app
./gradlew :app:assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Release (signed)

```bash
./gradlew :app:assembleRelease \
  KEYSTORE_PATH=/path/to/keystore.jks \
  KEYSTORE_PASSWORD=<pass> \
  KEY_ALIAS=<alias> \
  KEY_PASSWORD=<pass>
```

## GitHub Actions — Automated APK Build

Push to `main` or trigger manually from **Actions → Build Signed APK**.

### Setup (one time)

1. Generate a signing keystore:
   ```bash
   keytool -genkey -v -keystore rootdroid.jks -alias rootdroid -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Base64-encode it:
   ```bash
   base64 -i rootdroid.jks | pbcopy   # macOS
   base64 -w 0 rootdroid.jks          # Linux
   ```

3. Add these **GitHub Secrets** (Settings → Secrets → Actions):

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | Base64 output from step 2 |
   | `KEYSTORE_PASSWORD` | Your keystore password |
   | `KEY_ALIAS` | `rootdroid` |
   | `KEY_PASSWORD` | Your key password |

4. Push any change to `android-app/` — the workflow fires automatically.

### Artifacts

- **Debug APK** — always built, no secrets needed, available immediately
- **Signed Release APK** — built when `KEYSTORE_BASE64` secret is set
- **GitHub Release** — auto-created when you push a `v*` tag (e.g. `git tag v1.0.0 && git push --tags`)

## Architecture

```
MainActivity.kt          Entry point, Compose host, permission flow
├── PermissionsScreen    First-launch: root check + permission grants
├── HomeScreen           App grid + FAB
│   └── AppPickerSheet   Bottom sheet listing all installed packages
└── InspectorScreen      Per-app instrumentation view
    ├── LogsTab          Real-time logcat stream (color-coded)
    ├── CallsTab         strace syscall intercepts
    ├── MemoryTab        /proc/PID/maps regions
    └── FilesTab         /proc/PID/fd descriptors

RootBridge.kt            All root operations via `su -c`
InspectorOverlayService  Foreground service → WindowManager overlay
InstalledAppsRepository  PackageManager + local persistence (JSON)
```

## Extending with Frida

For true function-level hooking (Java/native method interception), deploy `frida-server` on the device and pipe its output into the logcat stream:

```bash
# On device (root shell):
adb push frida-server /data/local/tmp/
adb shell "su -c 'chmod +x /data/local/tmp/frida-server && /data/local/tmp/frida-server &'"

# From PC:
frida -U -n com.target.app -l your_script.js
```

RootDroid Inspector captures anything the target app logs. You can also extend `RootBridge.kt` to spawn `frida-server` directly and stream its output.
