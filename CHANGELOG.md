# Changelog

Notable changes to LocShare, newest first. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in
[Versioning](#versioning).

## [Unreleased]

Nothing yet.

## [0.2.0] — 2026-08-09

Expired and stopped shares are now reclaimed. Before this release nothing ever
deleted a share node, so every share ever created stayed in the database and
stayed readable by anyone holding its link.

### Added

- Client-side cleanup of expired and stopped shares (`ShareCleanup`,
  `OwnedShares`). The database has no server-side TTL — scheduled Cloud
  Functions need the paid Blaze plan — and only the creating anonymous user may
  delete a share, so the owning device is the only party that can reclaim its
  own nodes.
- A sweep on every foreground entry, deleting shares that lapsed while the
  process was dead.
- Pending-delete tracking, so a stop that never reached the server is retried.
  Without it a stopped "forever" share had no expiry to catch it and would stay
  live permanently.
- A `CHANGELOG.md`, and a documented versioning policy.

### Changed

- `versionName` `1.0` → `0.2.0` and `versionCode` `1` → `2`. Both were still
  the AGP scaffold defaults, so every build so far reported itself as 1.0.
- Expiry is enforced against the wall clock on each location update, not only
  by a `postDelayed` timer — that timer is uptime-based and stalls in Doze.
- Expiry is judged against server-corrected time (`.info/serverTimeOffset`)
  plus a one-minute margin, since the server enforces expiry with its own clock
  and a fast device clock would otherwise delete shares that are still live.
- The precise-fix gate is 8 m rather than 20 m, keeping the accuracy circle
  inside the 30 m viewport it triggers; at 20 m the circle was 40 m across and
  overflowed the view entirely. Falls back to whatever accuracy is available
  after 20 s, since indoors a fix may never get that good.
- Ownership records are written synchronously before the remote node is
  created. `apply()` returns before the write reaches disk, so a share could
  reach the server and then lose its only local record to process death.

### Fixed

- Deleting an already-absent share node was **refused** rather than treated as
  a no-op: with no `data` and a null `newData`, neither write rule clause
  matched. That made "already gone" indistinguishable from "not the owner", so
  a share reclaimed by the SDK's offline queue was retried and then misreported
  as orphaned. The rules now permit a write that neither creates nor removes
  anything.
- Transient cleanup failures no longer discard the ownership record. Only a
  delete the server actively refuses counts toward the retry cap; a timeout
  means the request never got an answer, and abandoning the record then would
  strand a node that is still live and still readable.
- A stale start intent no longer calls `stopSelf()` while a newer share is
  being broadcast. `stopSelf()` stops the whole service, so reclaiming a lapsed
  node ended the uploads for the share that was actually live.
- The cleanup sweep runs from `onStart` rather than `onCreate`. The activity is
  `singleTask` and the foreground service keeps the process alive, so warm
  launches never swept.
- Stopping a share from a restarted service instance now deletes the node. That
  instance carries no token of its own, so Stop cleared the UI without ever
  deleting anything.

### Known gaps

- Shares orphaned by uninstalling the app cannot be reclaimed: the uninstall
  drops the anonymous uid that owns them. They need a manual delete in the
  console, or Blaze for a scheduled server-side sweep.
- Doze wall-clock expiry is unverified. An emulator never actually sleeps, so
  the branch guarding against a stalled `postDelayed` timer needs a physical
  device and several hours to exercise.

## [0.1.0] — 2026-07-27

Tagged retroactively: the first commit at which the app and viewer worked end
to end against a real backend.

### Added

- Android app: Kotlin and Jetpack Compose, MapLibre, Fused Location Provider at
  high accuracy, and a foreground service uploading position every 5 seconds.
- Web viewer (`web/index.html`): MapLibre GL JS plus the Firebase JS SDK,
  showing the same 30 m view, dot, and accuracy circle, updated by RTDB push
  rather than polling.
- Sharing: a name, a duration from 5 minutes to forever, and a generated link
  carrying a 128-bit random token.
- Database rules gating reads on the token until `expiresAt` and writes on the
  owning anonymous uid.
- Real Firebase project configuration for both clients, plus `.firebaserc`.

### Fixed

- Toolchain: AGP 9.3.1 requires Gradle 9.5+ (wrapper moved to 9.6.1), and
  AndroidX requires `compileSdk` 37.

## Versioning

`versionName` is the semantic version and matches the git tag without its `v`
prefix. While the major version is 0 the public surface is the share link
format and the database schema; a minor bump may change either.

- **Major** — reserved for 1.0, once the link format and schema are stable.
- **Minor** — new capability, or a behavior change a user would notice.
- **Patch** — fixes that change no documented behavior.

`versionCode` is a plain monotonic integer, incremented once per release and
never reused, because Android requires it to increase for every installable
build regardless of what the semantic version does.

Releases are cut from `main` after the pull request merges:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` **on the
   feature branch**, so the version lands with the work rather than as a loose
   commit on `main`.
2. Move the `Unreleased` heading above to a version heading with the date, and
   open a fresh empty `Unreleased`.
3. After merging, tag the merge commit: `git tag -a vX.Y.Z`, with a message
   summarizing the release. Tags are always annotated, never lightweight, so
   they carry a date, an author, and notes.
4. `git push origin vX.Y.Z`.

Published tags are never moved or deleted; a mistake in a release is corrected
by the next one.

[Unreleased]: https://github.com/pizzimenti/LocShare/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/pizzimenti/LocShare/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/pizzimenti/LocShare/releases/tag/v0.1.0
