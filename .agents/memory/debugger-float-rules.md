---
name: DebuggerFloat overlay rules
description: Critical rules for DebuggerFloatingService overlay to avoid freeze/crash
---

FLAG_NOT_FOCUSABLE must NEVER be removed from the overlay WindowManager.LayoutParams.
Removing it causes the overlay to steal system focus and freeze all device input.

Never call BlackBoxCore IPC from DebuggerFloatingService — causes binder deadlock/ANR.
Only use ActivityManager.getRunningAppProcesses() for process scanning.

Two executors: scanExecutor (process scanning) and logExecutor (logcat streaming).
Never submit scanExecutor work from within a scanExecutor task — causes self-deadlock.
Use doScanProcesses() directly when already on scanExecutor.

item_process.xml and all service-inflated layouts must use only:
  - Hardcoded color hex values (e.g. #1AFFFFFF)
  - ?android:attr/selectableItemBackground (NOT ?attr/selectableItemBackground)
Services run with Theme.DeviceDefault; app ?attr/ refs throw UnsupportedOperationException.

The overlay bubble is now a FrameLayout (not ImageView), collapse button is a TextView (not ImageView),
all filter/action controls are TextViews (not Buttons). Use findViewById<TextView>() / findViewById<View>().

**Why:** These were discovered through fatal InflateException crashes silently caught by CrashMonitor,
and a device-freeze bug caused by stripping FLAG_NOT_FOCUSABLE from the overlay window.
