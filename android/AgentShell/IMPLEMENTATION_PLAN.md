# AgentShell — Implementation Plan (first pass)

AgentShell is the launcher app that becomes the boot face of AgentOS. It runs as a normal
Android app first; Device Owner powers (see `scripts/provision_device_owner.md`) make it dominant.

## Stack

- Kotlin + Jetpack Compose (matches the calm, custom-drawn UI; no XML launcher chrome).
- Min SDK 34, target SDK 35 (matches S25 / Android 15).
- Single-activity, screen state machine (Boot → Lock → Home → Now/People/Memory → Manual).

## Manifest essentials

```xml
<activity android:name=".ShellActivity" android:launchMode="singleTask"
          android:stateNotNeeded="true" android:excludeFromRecents="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.HOME"/>
    <category android:name="android.intent.category.DEFAULT"/>
  </intent-filter>
</activity>

<receiver android:name=".AdminReceiver" android:permission="android.permission.BIND_DEVICE_ADMIN"
          android:exported="true">
  <meta-data android:name="android.app.device_admin" android:resource="@xml/device_admin"/>
  <intent-filter><action android:name="android.app.action.DEVICE_ADMIN_ENABLED"/></intent-filter>
</receiver>

<service android:name=".AgentNotificationListener"
         android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
         android:exported="false">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter>
</service>
```

(Registering as `HOME` is what replaces the One UI launcher — the core "boot into AgentOS" effect.)

## Module map

```
AgentShell/
  ShellActivity.kt        single activity, hosts the screen state machine
  AdminReceiver.kt        DeviceAdminReceiver — Device Owner entry point
  screens/
    BootScreen.kt         wordmark + "waking up…"
    LockScreen.kt         time, battery, "X things that matter", 3 priorities, hold-to-speak
    HomeScreen.kt         "what should happen?" input + hold-to-talk + Today line
    NowScreen.kt          important items + collapsed "Quiet — N muted"
    PeopleScreen.kt       people needing attention + one suggested action each
    MemoryScreen.kt       projects, recent files, remembered context, receipts
    ManualModeScreen.kt   Phone/Messages/Camera/Browser/Files/Settings + "Agent paused."
  theme/Tokens.kt         generated from ui/tokens.json
  voice/HoldToTalk.kt     SpeechRecognizer placeholder
```

## Build order (milestones)

1. **M1 — Launcher shell.** All 7 screens render with mock data; AgentShell installs and can be
   set as default Home. *No privilege needed.* This is the first "it boots into AgentOS" win.
2. **M2 — Notification + people layer.** `NotificationListenerService` feeds Now/People; on-device
   summarization of "things that matter."
3. **M3 — Tool registry + intents.** Manual Mode tools and agent-invocable intents (dial, SMS,
   camera, browser, files, settings) behind the Permission Manager.
4. **M4 — Device Owner kiosk.** Provision as Device Owner; enable lock-task, app hiding, shade
   suppression. The aggressive takeover.
5. **M5 — Shizuku elevation + LLM router + audit receipts.** Hidden-API calls, local/cloud routing
   with redaction, append-only receipt log.

## Tool registry concept

A declarative list: `{id, label, intent/shizuku-call, default_permission_tier}`. The agent reads
this registry; it never hardcodes app launches. Each invocation passes through the Permission
Manager and emits a receipt.

## Permission tiers (engine in android/AgentPermissions)

`read only · draft only · ask before action · autonomous · blocked` — per-tool, with a global
"pause agent" kill switch that drops everything to Manual Mode instantly.
