# Anova Cloud API — Research Log

All Mac-side experiments run against device `PmDD2Pils28ooooEMuAMY0` (type `a7`, firmware `01.02.05`).
Scripts live in `/tmp/anova_*.js`. Firebase token refreshed via `/tmp/anova_refresh_token.txt`.

---

## Summary — What Works

| Operation | Command | Payload | Result |
|---|---|---|---|
| Set temperature | `CMD_APC_SET_TARGET_TEMP` | `{cookerId, type:"a7", targetTemperature:<C>, unit:"C"}` | ✅ Confirmed |
| Start cooking | `CMD_APC_START` | `{cookerId, type:"a7", targetTemperature:<C>, unit:"C"}` | ✅ Confirmed |
| Stop cooking | `CMD_APC_STOP` | `{cookerId, type:"a7"}` | ✅ Confirmed |

**Critical insight**: payload must include `type:"a7"` and use `targetTemperature` (not `targetTemp`). Without `type:"a7"` the commands return OK but silently do nothing.

WebSocket envelope:
```json
{ "command": "CMD_APC_...", "requestId": "<uuid>", "payload": { ... } }
```

---

## Chronological Test Log

### Phase 1 — Baseline (initial session)

**Script**: `anova_spy.js`, `anova_rawstate.js`

- Connected to `wss://devices.anovaculinary.io/?token=<FB_TOKEN>&supportedAccessories=APC,APO&platform=android`
- Server pushes `EVENT_APC_WIFI_LIST` on open (no request needed)
- Server then pushes `EVENT_APC_STATE` continuously
- **Spy finding**: when user changed temp to 72°C from phone app, `state.cook.stages[0]` was fully replaced with new stage ID, new setpoint, new ±0.3°C entry conditions. Two new `processedCommandIds` appeared:
  - `"7af6b655f30e8b27d838e5"` (22 chars — matches pyanova-api REST job ID format)
  - `"2032877a-3286-4642-a6ce-c47ebe797e91"` (UUID format)

---

### Phase 2 — WebSocket command attempts (wrong payload format)

**Scripts**: `anova_cmd_test.js`, `anova_cmds2.js`, `anova_cmds3.js`, `anova_a7_cmds.js`

| Command | Payload used | Result |
|---|---|---|
| `CMD_APC_SET_TARGET_TEMP` | `{cookerId, targetTemp:73}` | ✅ OK response, ❌ no state change |
| `CMD_APC_START` | `{cookerId}` | ❌ `invalid command` (device already cooking) |
| `CMD_APC_STOP` | `{cookerId}` | ✅ OK response, ❌ no state change |
| `CMD_APC_SET_TIMER` | `{cookerId, timer:3600}` | ✅ OK response, ❌ no state change |

**Conclusion**: Commands reach the server and return OK, but do not affect the A7 device. Wrong payload format — `type` field missing.

---

### Phase 3 — REST API probe

**Scripts**: `anova_rest2.js`, `anova_rest3.js`, `anova_rest4.js`, `anova_rest_auth.js`

| Endpoint | Auth | Result |
|---|---|---|
| `PUT /devices/{id}/current-job` | Firebase Bearer | 🔒 401 `Bearer realm="Anova Culinary API"` |
| `PUT /devices/{id}/current-job` | Anova JWT Bearer | 🔒 401 |
| `PUT /devices/{id}/current-job` | `firebase-token` header | 🔒 401 |
| `PATCH /devices/{id}/current-job` | any | 405 Method Not Allowed |
| `POST /devices/{id}/current-job` | any | 405 Method Not Allowed |
| `PUT /v1/devices/{id}/current-job` | any | 404 |
| `PUT /cookers/{id}/current-job` | any | 404 |
| `PUT /cooks/{id}` | any | 404 |
| `GET /devices/{id}` | Firebase Bearer | 🔒 401 |
| `GET /devices/{id}` | Anova JWT Bearer | 🔒 401 |

**Conclusion**: REST control API is locked. The `PUT /devices/{id}/current-job` endpoint exists (not 404) but rejects all known token types. Only GET and PUT are allowed (PATCH/POST → 405). The "Anova Culinary API" Bearer token is not obtainable via `/authenticate` or any other known endpoint. REST control is not viable for A7.

---

### Phase 4 — WebSocket command name fuzzing

**Scripts**: `anova_init_probe.js`, `anova_deep_probe.js`, `anova_fuzz_cmds.js`, `anova_probe_cmds.js`

Tested all commands from the `anova_wifi` Python library enum plus ~50 guessed names.

**Error taxonomy**:
- `"Unknown command type"` — server does not recognize this command name at all
- `"invalid command"` — server knows it but wrong device state / params
- `"failed to process command"` — server tried to process, failed
- `"OK"` — accepted (but no state change without correct payload)

**Commands from `anova_wifi` APK enum** (not documented, extracted from APK):

| Command | Result |
|---|---|
| `CMD_APC_HEALTHCHECK` | ERR: `failed to process command` |
| `CMD_APC_DISCONNECT` | ERR: `invalid command` |
| `CMD_APC_SET_METADATA` | ERR: `invalid command` |
| `CMD_APC_SET_TEMPERATURE_UNIT` | ✅ OK |
| `CMD_APC_REGISTER_PUSH_TOKEN` | ERR: `invalid command` |
| `CMD_APC_START_ICEBATH_MONITORING` | ERR: `invalid command` |
| `CMD_AUTH_TOKEN` | ERR: `unauthorized` |
| `AUTH_TOKEN_V2` | ERR: `failed to process command` |

**Guessed stage-control commands** — all returned `"Unknown command type"`:
`CMD_APC_SET_COOK`, `CMD_APC_UPDATE_COOK`, `CMD_APC_SET_STAGE`, `CMD_APC_SET_JOB`, `CMD_APC_SET_COOK_STAGES`, `CMD_APC_A7_SET_COOK`, `CMD_APC_SET_COOK_V2`, `CMD_APC_UPDATE_JOB`, `CMD_APC_SET_CURRENT_COOK`, `SET_COOK`, `UPDATE_COOK`, `CMD_A7_SET_COOK`, and ~20 more variants.

**Conclusion**: No hidden stage-control WS command exists. The A7 is controlled via the standard commands but with the correct payload format (see Phase 6).

---

### Phase 5 — Alternative WebSocket hosts and URL params

**Script**: `anova_deep_probe.js`, `anova_url_test.js`

| Host | Result |
|---|---|
| `wss://api.anovaculinary.com/` | ERR / timeout |
| `wss://app.anovaculinary.io/` | ERR / timeout |
| `wss://cloud.anovaculinary.io/` | ERR / timeout |
| `wss://connect.anovaculinary.io/` | ERR / timeout |
| `wss://ws.anovaculinary.io/` | ERR / timeout |

Only `wss://devices.anovaculinary.io/` works.

URL parameter variants (`userId`, `uid`, `APC` only vs `APC,APO`) — all produce identical behavior, no control difference.

---

### Phase 6 — `type` field discovery (BREAKTHROUGH)

**Script**: `anova_pro_payload.js`

Tested `CMD_APC_SET_TARGET_TEMP` with `type` field variants in the payload:

| Payload `type` | `targetTemperature` field | Result |
|---|---|---|
| `"pro"` | ✅ | ✅ OK, ❌ no state change |
| `"a7"` | ✅ | ✅ OK, ✅ **STATE CHANGED** (72→73°C) |
| omitted | ✅ | ✅ OK, ❌ no state change |
| omitted | `targetTemp` (old field) | ✅ OK, ❌ no state change |

**Root cause identified**: The `type` field in the payload must match the device's actual type string (`"a7"`). Without it, the server accepts the command but does not route it to the device.

Also discovered: `CMD_APC_START` with `type:"a7"` changes device mode (cook ↔ idle).

---

### Phase 7 — Full control confirmation

**Scripts**: `anova_full_control_test.js`, `anova_start_from_idle.js`

| Test | Command | Payload | Result |
|---|---|---|---|
| Set temp 72→73 | `CMD_APC_SET_TARGET_TEMP` | `{cookerId, type:"a7", targetTemperature:73, unit:"C"}` | ✅ sp changed to 73 |
| Set temp 73→72 | `CMD_APC_SET_TARGET_TEMP` | `{cookerId, type:"a7", targetTemperature:72, unit:"C"}` | ✅ sp changed to 72 |
| Stop cook | `CMD_APC_STOP` | `{cookerId, type:"a7"}` | ✅ mode → idle |
| Start cook | `CMD_APC_START` | `{cookerId, type:"a7", targetTemperature:72, unit:"C"}` | ✅ mode → cook |

**WebSocket behavior after control commands**: server closes the connection with code 1005 after the `RESPONSE` is sent. This is normal — reconnect immediately to observe the new state.

---

### Phase 8 — `processedCommandIds` investigation

**Script**: `anova_deep_probe.js`

- `processedCommandIds` change continuously (every few seconds) even with the Anova app force-closed
- They are **internal device state machine events**, not user command confirmations
- The 22-char ID (`"7af6b655f30e8b27d838e5"`) seen when user changed temp is in pyanova-api REST job ID format — but this does not mean REST works; it is likely generated internally by the device firmware

---

### Phase 9 — Firebase RTDB probe

**Script**: `anova_fb_rtdb.js`

- `anova-app.firebaseio.com` exists and returns 401 Permission Denied for all paths
- No usable data accessible without admin credentials

---

## Source Libraries Analyzed

| Repo | Relevance | Verdict |
|---|---|---|
| `Lash-L/anova_wifi` | Python WS listener for A6/A7 | Read-only, no control — but confirmed A7 state schema |
| `ammarzuberi/pyanova-api` | REST control for older APC | REST 401 for A7; job ID format useful |
| `rizwanjiwan/anovaphp` | PHP REST client | Same REST endpoint, same 401 |
| `anova-culinary/developer-project-wifi` | Official Anova dev docs | Uses PAT (`anova-XXX`) tokens |
| `bogd/anova-oven-api` | APO control docs | Confirmed `type` field in payload matters |

---

## Things NOT Tried / Future Ideas

- **Personal Access Token (PAT)**: Tokens starting with `anova-` obtained from the Anova Oven app (More → Developer → Personal Access Tokens). May work as an alternative to Firebase ID token for WS connection.
- **`CMD_APC_SET_TIMER`** with `type:"a7"`: Not yet tested with the correct payload format — may actually work now.
- **Proxy the Anova phone app**: Capture exact HTTP traffic with Charles/mitmproxy to see if the app sends any REST calls we missed.
- **Timer control**: Whether `type:"a7"` also fixes `CMD_APC_SET_TIMER`.
