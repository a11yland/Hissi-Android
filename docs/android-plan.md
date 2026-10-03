# Android App Plan

Plan for an Android app with feature parity to the iOS app (favorites monitoring
of transit.accessibility.cloud elevators). The iOS repo's docs are the baseline
for requirements and architecture (see [README.md](README.md)); this document
records the Android-specific decisions and divergences. Status: in progress — phases 1
(bootstrap), 2 (core port) and 3 (phone app v1 incl. detail view, nearby,
onboarding sheets and favorites reordering) merged via PR #13, phase 4 (one
responsive Glance widget: verdict small, broken-first list with room, reload
via `AppContainer.reloadWidgets` + 30-min `updatePeriodMillis`, stored
favorites as cache fallback) done, phase 5 (disruption alerts: periodic
WorkManager evaluation on the shared refresh path + an immediate baseline run
on opt-in, `DisruptionAlert` transition rules ported to `:core`, one
notification channel, in-context `POST_NOTIFICATIONS` ask from the toggle,
battery-optimization guidance) done; next up: phase 6 (distribution — a user
task, see `android-distribution.md`). Tracked in issue #8.

## Decisions (2026-08-20)

- **Stack: native Kotlin + Jetpack Compose.** The pure logic in the iOS app's
  [`Shared/`](https://github.com/a11yland/Hissi-iOS/tree/main/Shared) (~2.4k
  LOC) is ported by hand into a platform-free `:core` Gradle module; its tests are
  ported 1:1 and run against the same JSON fixtures as the Swift tests
  ([`HissiTests/Tests/Fixtures/`](https://github.com/a11yland/Hissi-iOS/tree/main/HissiTests/Tests/Fixtures),
  copied to `core/src/test/fixtures/`) so behavioral drift between the two
  implementations surfaces in CI. KMP was rejected: it would require rewriting
  `Shared/` in Kotlin *and* retrofitting the working iOS app onto an XCFramework
  (actor/`Codable`/Swift-concurrency interop friction, Gradle in the Xcode build
  chain) — extraction remains possible later if drift ever hurts.
- **Separate repo** (since 2026-09-30, previously an `android/` Gradle root in
  the iOS monorepo). Seed catalog, generator scripts and the seed-drift workflow
  stay in Hissi-iOS; the seed and the test fixtures are copied here per release
  (see the top-level README). Requirements and architecture docs are not
  duplicated — Hissi-iOS/docs is the baseline.
- **Wear OS: decide after v1.** Nothing in the v1 architecture depends on it
  (`:core` is platform-free). Notification mirroring to paired watches covers the
  basic glanceable need for free.
- **Live status: standing disruption alerts instead of mirroring the iOS Live
  Activity.** Favorites are monitored permanently (WorkManager, same 30-min
  cadence); a broken↔repaired transition posts a notification. The iOS
  session model (user-started, 2 h / until-repaired) was partly a workaround for
  the lack of a push server — that constraint doesn't exist on Android. The
  transition rules (per-elevator id, no re-announce of standing disruptions, no
  alert on newly favorited broken or fall-back-to-unknown) are ported from
  [`Shared/LiveStatus.swift`](https://github.com/a11yland/Hissi-iOS/blob/main/Shared/LiveStatus.swift). Platform divergence is accepted and deliberate.
- **Street View: ported (2026-09-06), reversing the 2026-09-05 divergence.**
  The original rationale ("a second Google SDK with its own key scope") was
  wrong on both counts: `StreetViewPanoramaView` ships in the same
  `play-services-maps` artifact behind the map snippet, and Google's docs
  state all mobile usage of the Maps SDK is unlimited at no charge — the
  priced "Dynamic Street View" SKU applies to web. Residual risk, accepted:
  Street View rides a Pro SKU whose mobile exemption Google could revoke, and
  each panorama is another location-bearing request to Google (data-safety
  declaration). The preview mirrors the iOS Look Around row: shown only where
  coverage exists (the panorama-change listener answers that — no extra API),
  display-only, tap hands off to Google Maps' Street View rather than an
  in-app viewer — the same hand-off pattern as the map snippet's `geo:`
  intent.
- **Play: personal account, testing tracks first.** App lives in Internal/Closed
  testing (mirrors the iOS TestFlight-internal setup). Production — and with it
  the 12-testers-for-14-days requirement for new personal accounts and the
  public legal name — is deferred until a public release is actually wanted.
  Create the account early anyway: identity verification is lead time.
  Re-verify current Play policies at account creation; they shift.

## Defaults (assumed, veto if wrong)

- Package `com.a11yland.hissi`; minSdk 26, targetSdk current (36).
- Kotlin 2.x, Gradle version catalog, coroutines.
- HTTP: Ktor client + kotlinx.serialization (three flat Payload CMS lists,
  paginated GETs — no need for Retrofit's ceremony).
- Persistence: Preferences DataStore for favorites/recents/onboarding state,
  file in app storage for the catalog snapshot cache. No App-Group equivalent
  needed — the widget runs in the same process as the app.
- Widget: Jetpack Glance, one responsive widget covering the size classes.
- Localization: `values/` = German (source, mirrors the key convention),
  `values-en/`, per-app language via `LocaleConfig` (Android 13+).
- Notifications: one channel for disruption alerts; `POST_NOTIFICATIONS`
  requested in context (when enabling alerts), not at first launch.

## Phases

Each phase ships independently (trunk-based, small increments).

1. **Bootstrap.** `android/` skeleton: `:core` (pure JVM Kotlin module) +
   `:app`; `.gitignore` for Gradle artifacts; CI job (`gradle test` on ubuntu,
   `paths: android/**`).
2. **Core port.** Models, API client (Elevators/StatusSpans/StopPlaces with
   speculative pagination, targeted `where[id][in]` fetch), join/overlay/seed
   bridging, `StatusMerge`, `SearchGrouping`, `TransitRegion`/`TransitNetwork`,
   `ElevatorSummary`, seed catalog loading. Tests against the shared fixtures.
3. **Phone app v1.** Single-screen Compose UI mirroring `ContentView`:
   favorites, two-phase search with stale-snapshot banner, recents, catalog
   snapshot persistence, refresh cadence (on-resume + periodic + pull-to-refresh),
   DE/EN from the start. Nearby + welcome/what's-new sheets as follow-up
   increments within the phase.
4. **Widget.** Glance widget on the `ElevatorSummary` vocabulary (never a false
   all-clear; explicit no-favorites state), reload on refresh/toggle, cache
   fallback.
5. **Disruption alerts.** WorkManager periodic worker + ported transition rules,
   notification channel, permission onboarding, battery-optimization guidance
   (OEM killers, esp. Samsung, are the main reliability risk).
6. **Distribution.** Play account, internal testing track, data safety form
   (location on-device only, no accounts, no tracking). Checklist:
   [`android-distribution.md`](android-distribution.md).

Done after phase 5 (2026-10-03, catching up with the iOS app): the Creme-Lila
palette on the whole UI (`core/Palette.kt` carries the pairs and their WCAG
tests, `MainActivity` maps them onto the Material roles, status colours split
into symbol/text variants like iOS), a second Glance widget "In der Nähe"
(`widget/NearbyWidget.kt`, opens the app on the nearby block via
`MainActivity.EXTRA_SHOW_NEARBY`), static launcher shortcuts
(`res/xml/shortcuts.xml`: nearby, favorites) and the about sheet in sections
(tagline, features, data links, privacy note, version).

Deferred / decide later: Wear OS (app + Tiles + complications), Assistant App
Actions (effectively dead; launcher shortcuts are done), favorites export
(FR4b) and a backup/transfer story (FR4a — no `allowBackup` yet),
a startable Live-Update surface on top of the alerts (hybrid), Android 16
Live Updates styling.

## Local development (no test device)

- Android Studio on macOS; AVD emulator with ARM64 images runs
  hardware-accelerated on Apple Silicon. Images with Play services exist.
- The emulator covers: app, Glance widgets, notifications, per-app language,
  dark mode, mocked GPS routes (nearby), multiple API levels/screen sizes, and
  a pairable Wear OS emulator (whole sync path testable without hardware).
- Not covered: real Doze/OEM battery behavior, performance feel, Play install
  flows. Before a release: a cheap physical device (Pixel a-series) or Firebase
  Test Lab.
- Workflow: `./gradlew assembleDebug` / `adb install` / `./gradlew test`
  (JVM-only `:core` tests are the SwiftPM-package equivalent: fast, no
  emulator). Compose Previews ≈ SwiftUI Previews. CI runs JVM tests on plain
  ubuntu runners; instrumented tests are deliberately out of scope (same call
  as on iOS).
