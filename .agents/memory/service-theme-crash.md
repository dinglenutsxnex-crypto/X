---
name: Service context theme crash
description: ContextThemeWrapper required when inflating any view with ?attr/ references inside a Service
---

Services in Android run with Theme.DeviceDefault.Light.DarkActionBar forced on them — NOT the app's theme. Any layout that uses `?attr/` references (e.g. `?attr/selectableItemBackground`, `?attr/colorPrimary`) will crash at inflate time with:

    UnsupportedOperationException: Failed to resolve attribute at index N

The attribute ID starts with 0x7f (app namespace) — it's an AppCompat/MaterialComponents attr not defined in DeviceDefault.

**Why:** Android forces a system theme onto Service contexts to prevent accidental UI dependency in background components. The crash surfaces as InflateException → ViewGroup.<init> → TypedArray.getDrawable().

**How to apply:** Before inflating ANY view in a Service, wrap the context:
```kotlin
val themedCtx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
floatView = LayoutInflater.from(themedCtx).inflate(R.layout.my_overlay, null)
```
Also replace `?attr/` refs in layouts with `?android:attr/` equivalents OR hardcoded values as a belt-and-suspenders measure. `?android:attr/` refs resolve against the system theme and are always safe.
