# Rooted Environment — Architecture

## What This Is

A virtual app space for Android that works **without host device root**.  
Apps are copied into isolated container directories and loaded in-process via `DexClassLoader`.  
A floating overlay HUD (drawn over other apps via `WindowManager`) provides real-time debugging.

---

## How Container Loading Works

1. **Install** — `ContainerManager.install()` copies the target APK from its system location into `<filesDir>/containers/<pkg>/base.apk`. Creates isolated dirs: `data/`, `opt/`, `cache/`.

2. **Launch** — `ContainerManager.launch()` starts `ContainerHostActivity` with the package name.

3. **Load** — `AppLoader.loadFromPath()` creates a `DexClassLoader` pointing at the container copy of the APK. The loaded code runs **inside our process** (same PID as the host app).

4. **Context** — `ContainerContext` wraps the host `Context` and redirects all file/db/cache calls to the container's isolated data directory. It also spoofs `getPackageName()` so the app thinks it is itself.

5. **Init** — `AppLoader.invokeApplication()` reflectively calls `Application.attach(context)` then `Application.onCreate()` with the `ContainerContext`. This runs the app's init code with our fake-root PATH active.

6. **Register** — `ContainerManager.registerSession(pkg, pid, classLoader, apkPath)` stores the live session in a `ConcurrentHashMap`. The PID is `Process.myPid()` because the app runs in our process.

7. **Overlay** — `InspectorOverlayService` is started with the pkg + PID. It detects the session, streams `logcat --pid=<ourPid>`, and shows methods enumerated from the session's `DexClassLoader`.

---

## Fake Root (No Host Root Required)

`FakeSuProvider`:
- Writes fake `su` and `id` shell scripts to `<filesDir>/vsbin/`
- Prepends `vsbin/` to PATH via `System.setProperty("vs.fake_bin_path", ...)`
- Apps calling `Runtime.exec("su")` or `which su` find the fake binary first
- The fake `su` always succeeds and passes commands through as current user

---

## Overlay HUD

`InspectorOverlayService` is a foreground service managing a `ComposeView` added to `WindowManager.TYPE_APPLICATION_OVERLAY`.

| Tab | Contents |
|-----|----------|
| APPS | All container-installed apps with running/attached status |
| LOGS | Live `logcat --pid=<pid>` stream for the attached session |
| METHODS | All methods from the APK, enumerated via `DexFile.entries()` + session classloader |
| MEMORY | `/proc/<pid>/maps` polled every 4s |
| FILES | `/proc/<pid>/fd` polled every 4s |

The overlay starts as a draggable floating pill. Tapping it expands to the full panel. It can be dismissed via the × button (stops the service).

---

## File Map

| Path | Purpose |
|------|---------|
| `virtual/ContainerManager.kt` | Install/uninstall/list/launch container apps, session registry |
| `virtual/ContainerContext.kt` | ContextWrapper that redirects file paths to container data dir |
| `virtual/AppLoader.kt` | DexClassLoader wrapper, Application.onCreate invocation |
| `virtual/FakeSuProvider.kt` | Fake su/id binaries + PATH injection |
| `virtual/ContainerEngine.kt` | Shell exec, logcat stream, process info helpers |
| `virtual/MethodEnumerator.kt` | Method enumeration from DexFile + ClassLoader |
| `ContainerHostActivity.kt` | Hosts a container launch — parses APK permissions, loads app, shows Toast on error |
| `service/InspectorOverlayService.kt` | Floating overlay HUD with all debug tabs |
| `MainActivity.kt` | Entry point, home screen, overlay perm request |
| `BootReceiver.kt` | Auto-starts overlay on device boot (only if overlay perm granted) |
| `ui/HomeScreen.kt` | Container app grid (install/launch/remove) |
| `ui/AppPickerSheet.kt` | Bottom sheet to pick device apps for container install |
| `ui/theme/Color.kt` | GitHub-dark color palette (`#0D1117` background, `#58A6FF` accent) |
| `model/Models.kt` | Shared data types: `LogEntry`, `InstalledApp`, `LogLevel`, etc. |
| `repository/InstalledAppsRepository.kt` | Lists all device-installed apps + fetches icons |

---

## Key Constraints

- `minSdk = 28` (Android 9+)
- `SYSTEM_ALERT_WINDOW` is required for the overlay — requested via Android system Settings on first launch, no custom permission screen
- `DANGEROUS` permissions declared in our manifest are forwarded to container apps — `ContainerHostActivity` parses the target APK's `requestedPermissions` and requests any missing ones before loading
- Apps using **Activities, Services, ContentProviders, native libs extensively** will fail in-process loading — a `Toast` shows the error, no silent fallback
- Logcat capture works because the container app runs in **our PID** — `logcat --pid=Process.myPid()` captures everything
- `DexFile.entries()` is deprecated in Android 14+ but still functional and is the only way to enumerate all classes without running the app

---

## CI

GitHub Actions (`.github/workflows/`) builds and self-signs the APK on every push to `main`.
