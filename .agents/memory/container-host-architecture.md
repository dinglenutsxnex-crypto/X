---
name: ContainerHostActivity architecture
description: No-root in-process container — DexClassLoader + FakeSuProvider + ContainerContext
---

## Rule
**The device is NOT rooted. Never use `su -c`, UserSpaceManager, or RootBridge in the main flow.**
The app simulates root for guest apps. Root is not available on the host device.

**Why:** This is a root-simulation tool. The entire point is to make target apps think they have root on a non-rooted device. Using actual `su -c` is wrong by design.

## Correct architecture (no host root required)

1. `ContainerManager.install()` — copies APK to `filesDir/containers/<pkg>/base.apk`, makes it read-only (Android 8+ DexClassLoader requirement)
2. `ContainerHostActivity.loadContainer()`:
   - Make APK read-only if not already
   - `AppLoader.loadFromPath()` — DexClassLoader in-process
   - `ContainerManager.registerSession(pkg, Process.myPid(), loader, apkPath)` — **PID is our own process**
   - `AppLoader.invokeApplication()` — reflective Application.onCreate() with ContainerContext (catches all errors, returns status string, never throws)
   - Start `InspectorOverlayService` with our PID
   - `finish()`
3. `InspectorOverlayService`:
   - `ContainerEngine.streamLogcat(pid)` — `logcat --pid=<ourPid>`, no root needed since guest runs in our process
   - `/proc/<pid>/maps` and `/proc/<pid>/fd` via plain `sh -c cat`, no su
4. `FakeSuProvider.install()` — writes fake `su`/`id` scripts to `filesDir/vsbin/`, prepends to PATH via `System.setProperty("vs.fake_bin_path", ...)`

## Read-only APK rules
- `setWritable(true)` BEFORE copy on re-install (file is read-only from previous install)
- `setWritable(false)` + `setReadable(true)` AFTER copy
- `deleteRecursively()` needs `walkBottomUp { setWritable(true) }` first
- Double-check in ContainerHostActivity before DexClassLoader load

## What NEVER to do
- Never call `su -c` anywhere in the main container flow
- Never use UserSpaceManager or RootBridge from ContainerHostActivity, InspectorOverlayService, or ContainerManager
- `invokeApplication()` is the correct approach even though it crashes for complex apps — errors are caught and shown as Toast, not crashes
- The opensdk/HackApi from MultiApp reference is a closed-source precompiled binary not available to us
