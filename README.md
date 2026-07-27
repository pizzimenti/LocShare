# LocShare

Minimal live location sharing: an Android app that shows your precise position on
a ~30 m-wide map view and can share it via a web link. The link opens a web page
showing the same map, dot, and accuracy circle, updated every 5 seconds.

## Stack

- **Android**: Kotlin + Jetpack Compose, MapLibre Android SDK (OpenGL variant),
  Fused Location Provider (high accuracy), foreground service for sharing.
- **Web viewer**: static `web/index.html` — MapLibre GL JS + Firebase JS SDK from CDN.
- **Map tiles**: [OpenFreeMap](https://openfreemap.org) `liberty` style — free, no API key.
- **Backend**: Firebase Realtime Database (share state + live location), Firebase
  Anonymous Auth (write protection), Firebase Hosting (serves the viewer at
  `https://<project>.web.app/s/<token>`). All within the free Spark plan.

## How sharing works

- "Share Location" asks for a name and duration (5 min → forever, default 1 h),
  then writes `shares/<token>` to RTDB and starts a foreground service that
  uploads `{lat, lng, acc, ts}` every 5 s. `<token>` is 128-bit random
  (unguessable URL).
- Database rules: anyone with the token may **read** until `expiresAt` (0 = forever,
  enforced server-side via `now`); only the creating anonymous user may **write**.
- Stopping a share deletes its node; viewers see "share ended".

## One-time Firebase setup

1. `npx firebase-tools login`
2. `npx firebase-tools projects:create <project-id>` (or use an existing project)
3. In the [console](https://console.firebase.google.com): enable **Realtime
   Database** (any region) and **Authentication → Anonymous**.
4. `npx firebase-tools apps:create web LocShareWeb` and
   `npx firebase-tools apps:sdkconfig web` — copy `apiKey`, `appId`,
   `databaseURL`, `projectId` into `web/index.html` (`firebaseConfig`).
5. `npx firebase-tools apps:create android com.gennakersystems.locshare`, then
   `npx firebase-tools apps:sdkconfig android` — copy the values into
   `app/src/main/java/com/gennakersystems/locshare/Config.kt`
   (`SHARE_BASE_URL` is `https://<project-id>.web.app/s/`).
6. `npx firebase-tools deploy` (hosting + database rules).
7. Build and install the app:
   `JAVA_HOME=/opt/android-studio/jbr ./gradlew installDebug`

## Development notes

- The 30 m viewport: camera fits a bounds whose east-west span is 30 m
  (`Geo.boundsAround` / `boundsAround` in the web viewer) once a fix with
  accuracy ≤ 20 m arrives.
- The app UI runs its own 1 s location stream for display; the 5 s upload stream
  lives in `LocationSharingService` and survives backgrounding (persistent
  notification with a Stop action).
- Web viewer gets updates pushed by RTDB (no polling); "updated X s ago" uses
  `.info/serverTimeOffset` to correct clock skew.
