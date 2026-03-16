# Anova Precision Cooker — Android App

A clean Android companion app for the **Anova Precision Cooker 3.0** (model `a7`).
Monitor and control your sous vide device from your phone — including background monitoring,
scheduling, history, and proactive alerts.

**Current release: v0.0.39**

---

## Features

| Area | Capability |
|------|-----------|
| **Monitor** | Live current temp, target temp, timer remaining, device status |
| **Cook control** | Start / stop cook, change target temperature, change timer |
| **Presets** | Save and launch named cook profiles (temp + timer) |
| **Scheduling** | Schedule start/stop commands at a specific date & time, with retry |
| **History** | Temperature graph sampled over time; configurable retention |
| **Alerts** | Cook finished, temp at target, device offline, cook started remotely |
| **Alert sound** | Custom ringtone via `STREAM_ALARM` — bypasses silent / DND / mute |
| **Background** | Foreground service keeps monitoring alive while connected |
| **Widget** | Home-screen widget showing current temp and status |
| **Auto-update** | Checks GitHub Releases for new APKs and installs in-app |
| **Backup** | Export / restore all data via Android Storage Access Framework |

---

## Architecture

```
app/src/main/java/com/template/app/
├── anova/              # Domain: repository, settings, device state, cloud transport
│   └── cloud/          # WebSocket transport + Firebase/Anova JWT auth
├── notifications/      # AnovaAlertManager — channels, alert posting, sound playback
├── service/            # AnovaMonitorService (foreground), BootReceiver, NotificationActionReceiver
├── schedule/           # AlarmManager-based scheduling, ScheduleAlarmReceiver
├── presets/            # Room-backed preset CRUD
├── history/            # Temperature history sampling + Room storage
├── widget/             # Glance home-screen widget
├── backup/             # Export/restore via SAF (works with Drive, Dropbox, etc.)
├── update/             # GitHub Releases update checker + in-app installer
├── bugreport/          # GitHub Issues bug reporter with log attachment
├── logging/            # In-memory ring buffer + crash handler
├── security/           # EncryptedSharedPreferences key manager
└── ui/
    ├── screens/
    │   ├── monitor/    # Main screen (MonitorScreen + AnovaViewModel)
    │   ├── settings/   # Settings screen + ViewModel
    │   ├── history/    # History graph screen
    │   ├── schedule/   # Schedule list + editor
    │   └── bugreport/  # Bug report screen
    ├── components/     # Shared Composables
    └── theme/          # Material 3 theme
```

**Tech stack:** Kotlin · Jetpack Compose · Hilt · Room · DataStore · OkHttp WebSocket ·
Coroutines/Flow · Glance widget · Firebase Auth

---

## Connectivity

The Anova Precision Cooker 3.0 only supports **cloud connection** (no local Wi-Fi / BLE):

```
Google Sign-In → Firebase JWT → Anova backend JWT → WebSocket (anovaculinary.io)
```

See [`docs/anova-token-management.md`](docs/anova-token-management.md) and
[`docs/anova-cloud-connection.md`](docs/anova-cloud-connection.md) for the full auth chain
and protocol details.

---

## Building

### Prerequisites
- Android Studio Ladybug or later
- Android SDK 35
- Java 17 (bundled with Android Studio)

### Debug build + install
```bash
./build.sh          # build debug APK
./install.sh        # build + install to connected device/emulator
```

Or from Android Studio: **Run ▶**

### Release build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/app-release.apk
```

Releases are signed with the debug keystore for now (personal use only).

---

## Releasing

Use the built-in `/release` slash command in Claude Code — it handles version bump, build,
tagging, and GitHub release creation automatically.

```
/release          # auto-increments patch (0.0.x → 0.0.x+1)
/release 1.0.0    # explicit version
```

---

## Remotes

| Remote | URL | Purpose |
|--------|-----|---------|
| `origin` | `git@bitbucket.org:itsik_harel/anova.git` | Private source backup |
| `github` | `https://github.com/itsikh/Anova.git` | Public releases |

GitHub Releases hosts the downloadable APKs. Bitbucket is a private mirror.

---

## Docs

| File | Contents |
|------|---------|
| [`docs/product-spec.md`](docs/product-spec.md) | Feature list, design decisions |
| [`docs/anova-cloud-connection.md`](docs/anova-cloud-connection.md) | WebSocket protocol |
| [`docs/anova-token-management.md`](docs/anova-token-management.md) | Auth / JWT chain |
| [`docs/anova-api-research-log.md`](docs/anova-api-research-log.md) | API reverse-engineering notes |
