# Anova Precision Cooker — Android App Product Spec

> Living document. Update when decisions change.
> Last updated: 2026-03-12

---

## 1. Goal

A clean, reliable Android companion app for the **Anova Precision Cooker 3.0** that lets the
owner monitor and control the device comfortably from their phone — including background
monitoring, scheduling, history, and proactive alerts.

---

## 2. Device & Connectivity

| Item | Value |
|---|---|
| Device | Anova Precision Cooker 3.0 (`type: a7`) |
| cookerId | `PmDD2Pils28ooooEMuAMY0` |
| Transport | Cloud only (no local Wi-Fi / BLE for PC3) |
| Protocol | WebSocket via `anovaculinary.io` (reverse-engineered, unofficial) |
| Auth | Google account (`itsik.harel@gmail.com`) → Firebase → Anova JWT |

See `docs/anova-token-management.md` for the full auth chain.

---

## 3. Feature List

### 3.1 Monitor (main screen)

- [x] Current temperature (large, Anova-orange hero display)
- [x] Target temperature
- [x] Timer remaining
- [x] Device status (Running / Stopped / Disconnected)
- [x] Connect / Disconnect button
- [ ] **Start / Stop cook** — send `CMD_APC_START` / `CMD_APC_STOP` via WebSocket
- [ ] **Change target temperature** — inline edit, send `CMD_APC_UPDATE_COOK`
- [ ] **Change timer** — inline edit, send `CMD_APC_UPDATE_COOK`

### 3.2 Scheduling

- [ ] Schedule a **Start** or **Stop** command at a specific date + time
- [ ] Phone must be on with the app running in the background
- [ ] If device unreachable at scheduled time: retry on a configurable interval
- [ ] Alert the user if device remains unreachable after retries
- [ ] One-time schedules only (no recurrence)
- [ ] Schedule list: view, edit, delete upcoming schedules

### 3.3 History

- [ ] Continuous temperature logging while connected
- [ ] Configurable sample interval (default: **60 min**; range: 1 min – 24 h)
- [ ] Retention: **30 days** (configurable: 7, 14, 30, 60, 90 days)
- [ ] **In-app graph**: temperature vs time, per cook session
- [ ] **Export**: share as CSV (date, temp, target, unit, status, timer_remaining)

### 3.4 Alerts & Notifications

#### Active-cook notification (persistent, shown while device is Running)
- Shows: current temp · target temp · timer remaining
- Action buttons: **Stop** | **+1 hour**
- Updated on each poll cycle

#### Temperature threshold alerts
- Configurable min temp / max temp
- Trigger: sound alert (loud heads-up, not full-screen)
- Respects neither DND nor silent mode (`AudioManager.STREAM_ALARM`, `IMPORTANCE_HIGH`)
- Settable from a dialog (gear icon in top bar)

#### Event alerts (each configurable on/off in Settings)
| Event | Default |
|---|---|
| Cook finished (timer → 0) | On |
| Temperature reached target | On |
| Device went offline / unreachable | On |
| Scheduled command failed | On |
| Cook started (detected remotely) | Off |

#### Alert behaviour
- All alerts: loud heads-up notification
- The persistent cook notification is the normal channel

### 3.5 Home Screen Widget

- Shows: device status · current temp · timer remaining
- Updates on each poll cycle (requires background service running)
- Taps open the app

### 3.6 Settings

| Setting | Default | Notes |
|---|---|---|
| Temperature unit | Celsius | Toggle C / F |
| App theme | Dark | System / Light / Dark |
| History sample interval | 60 min | 1 min – 24 h |
| History retention | 30 days | 7 / 14 / 30 / 60 / 90 days |
| Background poll interval | 30 s | 10 s – 5 min (cloud transport) |
| Schedule retry interval | 60 s | How often to retry an unreachable device |
| Schedule max retries | 5 | After this, show "unreachable" alert |
| Alert: cook finished | On | |
| Alert: temp reached target | On | |
| Alert: device offline | On | |
| Alert: scheduled command failed | On | |
| Alert: cook started remotely | Off | |
| Cloud account info | — | Email, session expiry, Re-authenticate |

---

## 4. Background Operation

The app uses a **foreground service** (`AnovaMonitorService`) to:

1. Maintain the WebSocket connection while the app is in the background
2. Poll device state on the configured interval
3. Log temperature readings to Room DB
4. Fire alert notifications
5. Execute scheduled commands at their due time
6. Update the home screen widget
7. Keep the Anova JWT fresh (see `docs/anova-token-management.md`)

The foreground service shows a persistent notification while running (Android requirement).
This is separate from the cook-status notification.

**Permissions required at startup:**

| Permission | When prompted | Why |
|---|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | On first launch | All alerts |
| `FOREGROUND_SERVICE` | On first connect | Background monitoring |
| `RECEIVE_BOOT_COMPLETED` | On first connect | Restart service after reboot |
| `USE_EXACT_ALARM` (Android 12+) | On first schedule | Precise scheduled start/stop |
| `SCHEDULE_EXACT_ALARM` | On first schedule | Same |

BLE / Wi-Fi permissions are requested only if those transport modes are selected (currently
neither is used for PC3).

---

## 5. WebSocket Protocol

### Connection

```
wss://devices.anovaculinary.io/?token=<ANOVA_JWT>&supportedAccessories=APC,APO&platform=android
```

### Inbound events (server → app)

| Event type | Meaning |
|---|---|
| `EVENT_APC_WIFI_LIST` | Device list; extract `cookerId` |
| `EVENT_APC_STATE` | Live device state push |

### Outbound commands (app → server)

| Command | Payload fields | Effect |
|---|---|---|
| `CMD_APC_REQUEST_DEVICE_STATUS` | `cookerId` | Request current state |
| `CMD_APC_START` | `cookerId` | Start cook |
| `CMD_APC_STOP` | `cookerId` | Stop cook |
| `CMD_APC_UPDATE_COOK` | `cookerId`, `targetTemp`, `timerSeconds` | Update temp or timer |

### State fields of interest (`EVENT_APC_STATE`)

```json
{
  "type": "EVENT_APC_STATE",
  "body": {
    "cookerId": "PmDD2Pils28ooooEMuAMY0",
    "status": "cook" | "idle",
    "targetTemp": 72.0,
    "currentTemp": 71.99,
    "unit": "c",
    "timer": {
      "running": true,
      "initial": 3240,
      "elapsed": 855
    }
  }
}
```

`timerRemaining = timer.initial - timer.elapsed` (seconds)

---

## 6. Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Theme | `AnovaTheme` — Anova orange `#FF8B01`, dark navy `#07131C` |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Networking | OkHttp (WebSocket) |
| Persistence | Room (history) + DataStore (settings) + EncryptedSharedPreferences (tokens) |
| Auth | Firebase REST API + `AnovaFirebaseAuth` |
| Background | Foreground Service |
| Widget | AppWidget (Glance API) |
| Charts | [decide: MPAndroidChart or Vico] |

---

## 7. Implementation Phases

### Phase 1 — Fix auth + WebSocket transport (CURRENT)
- [ ] Fix Android Google sign-in (signInWithIdp state format issue)
- [ ] Rewrite `AnovaCloudTransport` to use WebSocket (`anovaculinary.io`)
- [ ] Implement Anova JWT storage + expiry decode + auto-refresh
- [ ] Show session expiry in Settings

### Phase 2 — Control
- [ ] Start / Stop cook buttons on Monitor screen
- [ ] Inline temperature edit
- [ ] Inline timer edit
- [ ] Background foreground service (keep WebSocket alive)
- [ ] Persistent "cook active" notification with Stop / +1h actions

### Phase 3 — Alerts
- [ ] Threshold alerts (loud, alarm channel)
- [ ] Cook finished alert
- [ ] Temp reached target alert
- [ ] Device offline alert
- [ ] Per-alert toggles in Settings

### Phase 4 — History
- [ ] Configurable sample interval (Settings)
- [ ] Configurable retention (Settings + Room prune)
- [ ] In-app graph (Vico or MPAndroidChart)
- [ ] CSV export + share

### Phase 5 — Scheduling
- [ ] Schedule entity + DAO
- [ ] Schedule UI (list + create/edit screen)
- [ ] Scheduler in foreground service (AlarmManager for exact timing)
- [ ] Retry logic + "unreachable" alert
- [ ] Permissions flow (USE_EXACT_ALARM)

### Phase 6 — Widget + Polish
- [ ] Home screen widget (Glance API)
- [ ] All permissions requested on startup
- [ ] Temperature unit toggle (C/F) wired everywhere
- [ ] Theme selector (Dark / Light / System)
- [ ] Settings screen complete

---

## 8. Known Constraints & Decisions

| Decision | Rationale |
|---|---|
| Cloud-only transport | Anova Precision Cooker 3 does not expose local TCP/BLE |
| Foreground service (not WorkManager) | Need persistent WebSocket connection + exact scheduling |
| Loud alarm channel for threshold alerts | User explicitly wants alerts even in DND / silent |
| No recurring schedules | User confirmed one-time only |
| Dark theme default | User preference |
| Celsius default, both units supported | User preference |
| 30-day history, 60-min sample default | User preference |
| App must be running for scheduling | Cannot schedule on-device; phone sends command |
| Firebase refresh token stored | Single sign-in, auto-refresh → see token-management.md |

---

## 9. Files & Key Classes

```
app/src/main/java/com/template/app/
├── anova/
│   ├── AnovaDeviceState.kt        — device state data class (currentTemp, targetTemp, …)
│   ├── AnovaRawState.kt           — raw poll result from transports
│   ├── AnovaRepository.kt         — single source of truth, routes to active transport
│   ├── AnovaSettings.kt           — DataStore settings
│   ├── cloud/
│   │   ├── AnovaCloudConfig.kt    — API URLs, keys
│   │   ├── AnovaCloudTransport.kt — WebSocket transport (Phase 1 rewrite)
│   │   ├── AnovaFirebaseAuth.kt   — Firebase ID token + Anova JWT management
│   │   └── AnovaCloudModels.kt    — JSON models
├── notifications/
│   └── AnovaAlertManager.kt       — notification channels + posting
├── history/
│   ├── TemperatureReading.kt      — Room entity
│   ├── TemperatureReadingDao.kt
│   └── AnovaDatabase.kt
├── schedule/                      — Phase 5 (new)
│   ├── ScheduleEntry.kt
│   ├── ScheduleDao.kt
│   └── AnovaScheduler.kt
├── service/                       — Phase 2 (new)
│   └── AnovaMonitorService.kt     — foreground service
├── widget/                        — Phase 6 (new)
│   └── AnovaWidget.kt
└── ui/
    ├── theme/
    │   └── AnovaTheme.kt          — Anova brand colors + Material 3 scheme
    └── screens/
        ├── monitor/               — Main screen
        ├── history/               — Graph + export
        ├── schedule/              — Phase 5 (new)
        └── settings/              — Settings
```

---

## 10. Auth Flow Quick Reference

See full details in `docs/anova-token-management.md`.

```
First launch:
  User taps "Sign in with Google"
    → WebView OAuth → Firebase ID token + Refresh token
    → POST anovaculinary.io/authenticate → Anova JWT
    → Store Refresh token + Anova JWT + expiry in EncryptedSharedPreferences

Subsequent launches:
  AnovaFirebaseAuth.getValidTokenOrRefresh()
    → if Anova JWT valid (exp > now + 90d): use as-is
    → if Anova JWT within 90d of expiry: exchange Firebase Refresh → new ID → new Anova JWT
    → if Refresh token invalid: clear tokens, prompt re-auth

WebSocket uses Anova JWT in URL query param `token`.
```
