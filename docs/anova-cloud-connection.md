# Anova Cloud Connection — Complete Implementation Reference

This document describes the complete end-to-end flow for connecting to the Anova Precision Cooker
via the Anova cloud WebSocket. It exists to serve as zero-context reference if you need to debug,
re-test, or extend the connection layer.

---

## Architecture Overview

```
AnovaRepository
    └── AnovaCloudTransport          ← WebSocket client (this doc)
            └── AnovaFirebaseAuth    ← Token management
                    └── SecureKeyManager  ← Encrypted on-device storage
```

**Key files:**
- `anova/cloud/AnovaCloudTransport.kt` — WebSocket lifecycle, message parsing
- `anova/cloud/AnovaFirebaseAuth.kt` — Firebase token refresh, Google SSO, Anova JWT exchange
- `anova/cloud/AnovaCloudConfig.kt` — All endpoint URLs and constants
- `anova/cloud/AnovaCloudModels.kt` — All JSON data classes (inbound and outbound)

---

## Auth Token Types

There are **two distinct tokens** — confusing them is the single most common failure cause.

| Token | Lifetime | Used for | Stored in |
|---|---|---|---|
| Firebase ID token | **1 hour** | **WebSocket URL `?token=`** | Memory only |
| Firebase refresh token | Long-lived | Minting new ID tokens | `SecureKeyManager` key `firebase_refresh_token` |
| Anova JWT | ~1 year | Nothing (obtained and stored but not used for WebSocket) | `SecureKeyManager` key `anova_jwt` |

> ⚠️ **The WebSocket MUST use the Firebase ID token, NOT the Anova JWT.**
> Using the Anova JWT causes the server to close the connection with code 1005 immediately.
> This was the root cause of all historical connection failures before v0.0.26.

---

## Step 1 — Get a Firebase ID Token

### Option A: Email / Password
`POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=<FIREBASE_API_KEY>`

```json
{ "email": "user@example.com", "password": "...", "returnSecureToken": true }
```

Response fields used: `idToken` (1h), `refreshToken` (long-lived).

### Option B: Google SSO (CredentialManager / implicit OIDC)
1. Build auth URL via `AnovaFirebaseAuth.createGoogleAuthUri()` → opens Google login.
2. Google redirects to `https://anova-app.firebaseapp.com/__/auth/handler#id_token=<google_id_token>&...`
3. Extract `id_token` from fragment, pass to `AnovaFirebaseAuth.signInWithGoogleIdToken()`.
4. That calls `POST .../accounts:signInWithIdp` with `postBody=id_token=<google_id_token>&providerId=google.com`.
5. Returns Firebase `idToken` + `refreshToken`.

### Option C: Refresh an existing session (normal app flow)
`POST https://securetoken.googleapis.com/v1/token?key=<FIREBASE_API_KEY>`

Form body: `grant_type=refresh_token&refresh_token=<stored_refresh_token>`

Response fields: `id_token` (new 1h token), `refresh_token` (rotated).

**In code:** `AnovaFirebaseAuth.getValidTokenOrRefresh()` handles all of this transparently.

---

## Step 2 — Open the WebSocket

```
wss://devices.anovaculinary.io/?token=<FIREBASE_ID_TOKEN>&supportedAccessories=APC,APO&platform=android
```

- The Firebase ID token goes in the query string (`?token=`).
- It is also sent as `Authorization: Bearer <token>` header (belt-and-suspenders).
- **No message needs to be sent on open.** The server pushes `EVENT_APC_WIFI_LIST` automatically.

---

## Step 3 — Inbound Message Format

> ⚠️ All server messages use a **`"command"`** field — **NOT `"type"`**.
> Parsing `"type"` will silently match nothing.

```json
{ "command": "EVENT_APC_WIFI_LIST", "payload": [...] }
{ "command": "EVENT_APC_STATE",     "payload": {...} }
{ "command": "EVENT_APO_WIFI_LIST", "payload": [...] }
{ "command": "EVENT_USER_STATE",    "payload": {...} }
```

### EVENT_APC_WIFI_LIST

Arrives automatically right after the WebSocket opens. Contains the list of devices on the account.

```json
{
  "command": "EVENT_APC_WIFI_LIST",
  "payload": [
    {
      "cookerId": "PmDD2Pils28ooooEMuAMY0",
      "name": "Anova Precision Cooker 3.0",
      "type": "a7"
    }
  ]
}
```

`payload` is a **list** (not an object). Take `payload[0].cookerId` — this is required for all
outgoing commands. If the list is empty or cookerId is null, the account has no registered device.

### EVENT_APC_STATE

Arrives after `EVENT_APC_WIFI_LIST` and periodically thereafter.

```json
{
  "command": "EVENT_APC_STATE",
  "payload": {
    "cookerId": "PmDD2Pils28ooooEMuAMY0",
    "state": {
      "nodes": {
        "waterTemperatureSensor": {
          "current":  { "celsius": 72.01, "fahrenheit": 161.62 },
          "setpoint": { "celsius": 72.0,  "fahrenheit": 161.6  }
        },
        "timer": {
          "initial": 194400,
          "startedAtTimestamp": "2026-03-11T19:31:29Z"
        }
      },
      "state": {
        "mode": "cook",
        "temperatureUnit": "C"
      },
      "systemInfo": {
        "online": true
      }
    }
  }
}
```

**Parsing rules:**

| App field | JSON path | Notes |
|---|---|---|
| `currentTemp` | `payload.state.nodes.waterTemperatureSensor.current.celsius` | Use `.fahrenheit` if `temperatureUnit == "F"` |
| `targetTemp` | `payload.state.nodes.waterTemperatureSensor.setpoint.celsius` | Use `.fahrenheit` if `temperatureUnit == "F"` |
| `unit` | `payload.state.state.temperatureUnit` | `"C"` → `TempUnit.CELSIUS`, `"F"` → `TempUnit.FAHRENHEIT` |
| `status` | `payload.state.state.mode` | `"cook"` → `RUNNING`, `"idle"` → `STOPPED` |
| `timerRemainingMin` | Calculated (see below) | |

**Timer remaining calculation:**
```
remainingSec = initial - floor((now_ms - Date.parse(startedAtTimestamp)) / 1000)
timerRemainingMin = floor(max(0, remainingSec) / 60)
```

---

## Step 4 — Outbound Commands

```json
{ "command": "CMD_APC_START",  "id": "<uuid>", "payload": { "cookerId": "..." } }
{ "command": "CMD_APC_STOP",   "id": "<uuid>", "payload": { "cookerId": "..." } }
{ "command": "CMD_APC_UPDATE_COOK", "id": "<uuid>", "payload": { "cookerId": "...", "targetTemp": 72.0, "timerSeconds": 3600 } }
```

> ⚠️ Outgoing commands also use **`"command"`**, not `"type"`.

---

## Token Lifecycle in the App

```
connect() called
    │
    ├─ pendingGoogleIdToken set? → signInWithGoogleIdToken()
    ├─ email+password set?       → getValidToken(email, password)
    └─ else                      → use stored session
    │
    └─ getValidTokenOrRefresh()
           │
           ├─ memory cache still valid?  → return cached idToken
           ├─ refresh token available?   → POST /token → new idToken + store
           └─ nothing?                   → return null → show "Not signed in" error
           │
           └─ openWebSocket(firebaseIdToken)
```

The ID token is refreshed automatically on every `connect()`. Auto-reconnect on failure (5s delay)
also calls `connect()`, so the token is always fresh.

---

## Known Device Info (as of 2026-03-12)

| Field | Value |
|---|---|
| cookerId | `PmDD2Pils28ooooEMuAMY0` |
| Device name | Anova Precision Cooker 3.0 |
| Device type | `a7` |
| Firebase project | `anova-app` |
| Firebase API key | `AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw` |

---

## Debugging from Mac (Node.js)

### 1. Get a fresh Firebase ID token

```bash
REFRESH=$(cat /tmp/anova_refresh_token.txt)
RESP=$(python3 -c "
import urllib.request, json, urllib.parse
data = urllib.parse.urlencode({'grant_type':'refresh_token','refresh_token':'$REFRESH'}).encode()
req = urllib.request.Request('https://securetoken.googleapis.com/v1/token?key=AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw', data=data)
with urllib.request.urlopen(req) as r: print(r.read().decode())
")
echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); open('/tmp/fresh_fb_token.txt','w').write(d['id_token']); print('expires_in='+str(d['expires_in']))"
```

Tokens stored on Mac: `/tmp/anova_refresh_token.txt` (long-lived), `/tmp/fresh_fb_token.txt` (1h).

### 2. Connect and read device state

```javascript
// node /tmp/anova_full_test.js
// Requires: /tmp/node_modules/ws  (install: cd /tmp && npm install ws)
const WebSocket = require('/tmp/node_modules/ws');
const FB = require('fs').readFileSync('/tmp/fresh_fb_token.txt', 'utf8').trim();

const url = 'wss://devices.anovaculinary.io/?token=' + FB + '&supportedAccessories=APC,APO&platform=android';
const ws = new WebSocket(url, { perMessageDeflate: false });

ws.on('open', () => console.log('OPEN — waiting for server push'));

ws.on('message', data => {
  const msg = JSON.parse(data.toString());
  console.log('[' + msg.command + ']', JSON.stringify(msg, null, 2));
  if (msg.command === 'EVENT_APC_STATE') ws.close();
});

ws.on('close',   c  => { console.log('CLOSE', c); process.exit(); });
ws.on('error',   e  => { console.log('ERR', e.message); process.exit(1); });
setTimeout(() => ws.close(), 15000);
```

### Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Server closes with code 1005 immediately | Anova JWT used instead of Firebase ID token | Use `getValidTokenOrRefresh()` not `getAnovaJwt()` |
| Messages arrive but nothing is parsed | Checking `msg.type` instead of `msg.command` | Parse `command` field |
| Temperatures/status are always null | Parsing flat `body.*` instead of nested `payload.state.nodes.*` | Use the nested structure above |
| `EVENT_APC_WIFI_LIST` has no devices | Device offline or not on account | Power cycle device, check Wi-Fi |
| Timer is always null | Reading non-existent `elapsed` field | Calculate from `initial - elapsed(startedAtTimestamp)` |
