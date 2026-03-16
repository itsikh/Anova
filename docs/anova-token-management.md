# Anova Cloud Authentication — Token Management

## Overview

The app authenticates with Anova's cloud API in two steps:

```
Google Account
     │
     ▼  (Firebase Web SDK / OAuth WebView)
Firebase ID Token  ──► (auto-refresh via Refresh Token, every 1 hour)
     │
     ▼  POST https://anovaculinary.io/authenticate
Anova JWT  ──► stored on device, used for WebSocket connection
     │
     ▼  wss://devices.anovaculinary.io/?token=<ANOVA_JWT>&…
WebSocket session (live device data)
```

---

## Token Types

| Token | Lifetime | Stored | Refreshable |
|---|---|---|---|
| Firebase ID Token | 1 hour | Memory only | ✅ Auto via Refresh Token |
| Firebase Refresh Token | Until revoked | EncryptedSharedPreferences | ❌ Requires new sign-in |
| Anova JWT | ~1 year (decode `exp`) | EncryptedSharedPreferences | ✅ Auto via Firebase ID Token |

---

## Storage Keys (SecureKeyManager)

| Key | Content |
|---|---|
| `anova_firebase_refresh_token` | Firebase refresh token |
| `anova_jwt` | Anova JWT (long-lived) |
| `anova_jwt_expiry_ms` | Anova JWT expiry as epoch millis (decoded from JWT `exp`) |
| `anova_auth_email` | Google account email (display only) |

---

## Auto-Refresh Logic

The app (`AnovaFirebaseAuth`) continuously manages token freshness:

1. **Firebase ID Token** — refreshed automatically every hour using the stored Firebase
   Refresh Token. No user interaction required. This happens silently in the background.

2. **Anova JWT** — the app decodes the `exp` claim from the stored Anova JWT.
   When **90 days or fewer** remain before expiry:
   - The app silently obtains a new Firebase ID Token (step 1)
   - Uses it to call `POST https://anovaculinary.io/authenticate`
   - Stores the new Anova JWT and its new expiry date
   - Logs: `"Anova JWT proactively refreshed — new expiry: <date>"`

3. **Session expired / token revoked** — if the Firebase Refresh Token is rejected
   (HTTP 400 from Google's token endpoint), the app:
   - Clears all stored tokens
   - Shows a notification: *"Anova: Re-authentication required"*
   - Shows a banner in the app with a **Sign in again** button

---

## How to Obtain a New Token Manually

Use this process when automatic refresh has failed and the app shows
*"Re-authentication required"*.

### Step 1 — Get a Firebase ID Token on your Mac

1. Save the HTML below to a file (e.g. `~/Desktop/anova_auth.html`):

```html
<!DOCTYPE html>
<html>
<head><title>Anova Auth</title></head>
<body>
<button onclick="signIn()">Sign in with Google</button>
<pre id="out">Click the button…</pre>
<script type="module">
  import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js";
  import { getAuth, signInWithPopup, GoogleAuthProvider }
    from "https://www.gstatic.com/firebasejs/10.12.0/firebase-auth.js";

  const app = initializeApp({
    apiKey:    "AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw",
    authDomain:"anova-app.firebaseapp.com",
    projectId: "anova-app"
  });
  const auth = getAuth(app);

  window.signIn = async () => {
    const result = await signInWithPopup(auth, new GoogleAuthProvider());
    const idToken      = await result.user.getIdToken();
    const refreshToken = result.user.refreshToken;
    document.getElementById("out").textContent =
      "ID Token:\n" + idToken + "\n\nRefresh Token:\n" + refreshToken;
  };
</script>
</body>
</html>
```

2. Serve it locally (required — Firebase blocks `file://`):

```bash
python3 -m http.server 8888 --directory ~/Desktop
```

3. Open `http://localhost:8888/anova_auth.html` in Chrome.
4. Click **Sign in with Google** and sign in with your Anova account (`itsik.harel@gmail.com`).
5. Copy the **Refresh Token** shown on the page.

### Step 2 — Exchange for an Anova JWT

```bash
# Replace <ID_TOKEN> with the token from Step 1
curl -s -X POST https://anovaculinary.io/authenticate \
  -H "firebase-token: <ID_TOKEN>" \
  -H "Content-Type: application/json" | python3 -m json.tool
```

The response contains your Anova JWT. Example:
```json
{ "jwt": "eyJhbGciOi..." }
```

### Step 3 — Enter tokens into the app

In the app → Settings → Cloud Account → **Enter tokens manually**:
- Paste the **Firebase Refresh Token** from Step 1
- The app will automatically exchange it for an Anova JWT and store both

---

## Token Expiry Display (in Settings)

The Settings screen shows:

```
Cloud Account
  Signed in as: itsik.harel@gmail.com
  Session valid until: 14 Jan 2026  [98 days]
  [Re-authenticate]
```

Colour coding:
- **Green**: > 90 days remaining
- **Amber**: 30–90 days remaining (silent auto-refresh attempted)
- **Red**: < 30 days remaining (show banner, auto-refresh attempted)
- **Grey/expired**: Token expired or revoked (manual re-auth required)

---

## Refresh Token Rotation (Firebase)

Firebase may rotate the refresh token after use. The app always stores
the latest refresh token returned by the `/token` endpoint response
(`refresh_token` field in `FirebaseRefreshResponse`). This is handled
automatically by `AnovaFirebaseAuth.refreshIdToken()`.

---

## Endpoints Reference

| Purpose | Method | URL |
|---|---|---|
| Sign in (email/password) | POST | `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=<API_KEY>` |
| Sign in (Google SSO / IdP) | POST | `https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=<API_KEY>` |
| Refresh ID token | POST | `https://securetoken.googleapis.com/v1/token?key=<API_KEY>` |
| Get Anova JWT | POST | `https://anovaculinary.io/authenticate` (header: `firebase-token`) |
| WebSocket | WSS | `wss://devices.anovaculinary.io/?token=<ANOVA_JWT>&supportedAccessories=APC,APO&platform=android` |

API Key: `AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw`

---

## Security Notes

- All tokens are stored in `EncryptedSharedPreferences` (AES-256-GCM via Jetpack Security)
- Tokens are **never** logged — `AppLogger` redacts auth headers
- Firebase Refresh Token is the most sensitive credential: if it leaks,
  an attacker can access the Anova account until revoked from
  [Google Account security settings](https://myaccount.google.com/security)
- To revoke: Google Account → Security → Your devices → Sign out all

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "Authentication failed" on connect | Cached token expired/revoked | Settings → Re-authenticate |
| `TOKEN_EXPIRED` in logs | Firebase ID token stale | Auto-refresh should fix; if not, check network |
| `INVALID_REFRESH_TOKEN` | Refresh token revoked (password change?) | Manual re-auth (Steps 1–3 above) |
| `No Anova device found` | Wrong account or device offline | Check device is powered on |
| WebSocket disconnects after ~1h | ID token not refreshed before expiry | Check `AnovaFirebaseAuth.refreshIdToken()` |
