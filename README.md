# LocShare

Minimal live location sharing: an Android app that shows your precise position on
a ~30 m-wide map view and can share it via a web link. The link opens a web page
showing the same map, dot, and accuracy circle, updated every 5 seconds.

Released versions and the versioning policy are in [CHANGELOG.md](CHANGELOG.md).

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
  Deleting a node that is already absent is a permitted no-op, so a repeated
  delete succeeds instead of being refused — see the cleanup section.
- Stopping a share deletes its node; viewers see "share ended".

## Expired-share cleanup

Only the creating anonymous user may delete a share, and scheduled Cloud
Functions need the paid Blaze plan — so the owning device is the only party that
can reclaim its own nodes. Cleanup therefore happens client-side.

`OwnedShares` (SharedPreferences) records every token this device creates as
`"<expiresAt>|<attempts>"`, written **before** the node is created so a share
that reaches the server while the caller is cancelled is still reclaimable. An
entry is removed only once its delete is confirmed. `expiresAt` may be:

- a timestamp — reclaim once it has passed,
- `0` — "forever", never reclaimed by expiry,
- `-1` — *pending delete*: a delete was requested but not confirmed, so retry
  regardless of expiry. This is what makes a failed Stop on a "forever" share
  recoverable.

Deletion is attempted from three places:

1. `LocationSharingService` deletes the node when the duration elapses. Expiry
   is checked on every location update against the wall clock, because the
   `postDelayed` timer is uptime-based and stalls in Doze.
2. `ShareCleanup.sweep()` runs on every foreground entry (`onStart`, not
   `onCreate` — the service keeps the process alive, so warm launches would
   otherwise never sweep). It runs on `Dispatchers.IO` and skips the token
   currently being broadcast.

   A failed delete keeps its entry. Only a delete the server actively *refuses*
   counts toward `OwnedShares.MAX_ATTEMPTS`; a timeout means the request never
   got an answer, and abandoning the record then would discard the only handle
   on a node that is still live. That distinction is why the sweep uses the
   completion listener rather than awaiting the write Task — the Task reports
   "refused" and "never reached the server" identically, and the two call for
   opposite responses.
3. A redelivered start intent for a lapsed share reclaims that node without
   disturbing a newer share that replaced it.

Expiry is judged against server-corrected time (`.info/serverTimeOffset`) plus a
one-minute margin, since the server enforces expiry with its own clock and a
fast device clock would otherwise delete shares that are still live.

Not covered: shares orphaned by uninstalling the app, which drops the anonymous
uid that owns them. Those need a manual delete in the console, or Blaze for a
scheduled server-side sweep.

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
  (`Geo.boundsAround` / `boundsAround` in the web viewer). The gate for a
  "precise" fix is 8 m, below a quarter of the view width, so the accuracy
  circle fits inside the viewport it triggers.
- The app reaches that view through an opening descent — globe, continent,
  region, locality, then the fit — which owns the camera until it settles.
  Follow-mode must stay out of the way while any camera animation is in
  flight: easing to a new centre cancels an animation, and a cancelled
  animation leaves the camera at whatever zoom it had reached, not the one
  requested. That is how the 30 m view was silently lost before 0.2.1.
  Nothing on screen distinguishes 30 m from 200 m, so `logViewportWidth`
  measures the settled width from the map's own projection.
- The app UI runs its own 1 s location stream for display; the 5 s upload stream
  lives in `LocationSharingService` and survives backgrounding (persistent
  notification with a Stop action).
- Web viewer gets updates pushed by RTDB (no polling); "updated X s ago" uses
  `.info/serverTimeOffset` to correct clock skew.
