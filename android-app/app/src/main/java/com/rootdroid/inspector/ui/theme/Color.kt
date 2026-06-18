package com.rootdroid.inspector.ui.theme

import androidx.compose.ui.graphics.Color

// ── Backgrounds ───────────────────────────────────────────────────────────────
val Background  = Color(0xFF0D1117)
val Surface     = Color(0xFF161B22)
val SurfaceHigh = Color(0xFF21262D)
val SurfaceMid  = Color(0xFF1C2128)
val Border      = Color(0xFF30363D)
val BorderSub   = Color(0xFF21262D)

// ── Accent (blue) ─────────────────────────────────────────────────────────────
val Accent      = Color(0xFF58A6FF)
val AccentDim   = Color(0xFF388BFD)
val AccentMuted = Color(0xFF1F6FEB)

// ── Text ──────────────────────────────────────────────────────────────────────
val TextPrimary = Color(0xFFE6EDF3)
val TextSecond  = Color(0xFF8B949E)
val TextMuted   = Color(0xFF484F58)
val TextDim     = Color(0xFF30363D)

// ── Semantic ──────────────────────────────────────────────────────────────────
val StatusGreen  = Color(0xFF3FB950)
val StatusYellow = Color(0xFFD29922)
val StatusRed    = Color(0xFFF85149)
val StatusPurple = Color(0xFFBC8CFF)

// ── Log levels ────────────────────────────────────────────────────────────────
val LogVerbose  = Color(0xFF484F58)
val LogDebug    = Color(0xFF8B949E)
val LogInfo     = Color(0xFF58A6FF)
val LogWarn     = Color(0xFFD29922)
val LogError    = Color(0xFFF85149)
val LogFatal    = Color(0xFFFF7B72)
val LogFrida    = Color(0xFFBC8CFF)

// ── Legacy aliases (referenced in overlay + inspector) ────────────────────────
val NeonGreen    = Accent
val NeonGreenDim = AccentDim
val BtnDestructive = StatusRed
val BtnSecondary   = SurfaceHigh
