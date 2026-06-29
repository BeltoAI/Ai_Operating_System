# Google Meet / Calendar setup (one-time, ~10 minutes)

This lets SlyOS create **real Google Meet links** and **email calendar invites**. You do this once;
every user then just taps **Connect** and signs in with their *own* Google account. You never see
their data — the phone talks to Google directly. The client ID below is the app's public identity,
**not a secret and not your account**.

## 1. Create a Google Cloud project
1. Go to <https://console.cloud.google.com/> → top bar → **New Project** → name it `SlyOS` → Create.
2. Make sure it's selected.

## 2. Enable the Calendar API
- **APIs & Services → Library** → search **Google Calendar API** → **Enable**.

## 3. Configure the OAuth consent screen
- **APIs & Services → OAuth consent screen**.
- User type: **External** → Create.
- App name `SlyOS`, your support email, app logo optional.
- **App domain** (needed for verification later): Home `https://slyos.world`,
  Privacy `https://slyos.world/privacy.html`, Terms `https://slyos.world/terms.html`.
- **Authorized domain**: `slyos.world`.
- **Scopes** → Add → select these three:
  - `openid`
  - `.../auth/userinfo.email`
  - `.../auth/calendar.events`
- **Test users**: while you're in *Testing*, add the Google addresses of anyone who'll use it
  (up to 100). They'll see one extra "Google hasn't verified this app → Advanced → Continue" tap.
  Remove that warning later by submitting for verification (Publishing status → Publish app).

## 4. Create the OAuth client ID
- **APIs & Services → Credentials → Create credentials → OAuth client ID**.
- Application type: **Android**.
- Name: `SlyOS Android`.
- **Package name**: `com.agentos.shell`
- **SHA-1**: from the keystore that signs the APK you distribute. Get it with:
  ```bash
  # debug builds (build_and_install.sh):
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
    -storepass android -keypass android | grep SHA1
  # a release keystore: keytool -list -v -keystore <your.keystore> -alias <alias>
  ```
  (The browser sign-in flow doesn't cert-check at runtime, so a single client ID works for both
  your debug and release builds — just register one SHA-1 to create it.)
- **Create**, then copy the **Client ID** — it looks like
  `1234567890-abcdefg.apps.googleusercontent.com`.

## 5. Drop it into the build
Add to `android/apikey.properties` (git-ignored):
```
GOOGLE_OAUTH_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
```
That's it — the redirect scheme is derived automatically. Rebuild:
```bash
cd ~/Downloads/MADSCIENTIST/agentos
./build_and_install.sh
```

## 6. Use it
- In the app: **Brain → settings → Connections → Google Calendar & Meet → Connect**.
- Then say *"create a Google Meet tomorrow at 4pm with anna@x.com and me"* — SlyOS makes the event,
  attaches a Meet link, and emails the invite. Without Google connected it falls back to a local
  calendar block (no Meet link).

## Notes
- Tokens are stored only on the device; **Disconnect** wipes them.
- Scope is minimal (`calendar.events`) — SlyOS can create/manage events it makes, nothing else.
- For >100 users with no warning screen, submit the consent screen for Google verification
  (needs the privacy/terms URLs above; review can take days to weeks).
