# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Hissi for Android shows whether the elevators at Berlin and Brandenburg
stations work, for wheelchair users, parents with strollers and people with
luggage. Users star elevators (favorites), search stations, see nearby
stations, get disruption alerts and use two home-screen widgets. Kotlin +
Jetpack Compose (Material 3), Glance widgets, WorkManager.

It is the Android port of [Hissi-iOS](https://github.com/a11yland/Hissi-iOS),
which is the baseline for requirements and architecture (see `docs/README.md`).
Most code comments name the iOS counterpart; keep both apps behaviourally in
step.

## Commands

Requires a JDK 17+ (CI uses Temurin 21) and an Android SDK in
`local.properties` (`sdk.dir=…`).

```bash
./gradlew test :app:assembleDebug     # what CI runs: :core unit tests + debug APK
./gradlew :core:test                   # JVM unit tests only (fast, no emulator)
./gradlew :core:test --tests "com.a11yland.hissi.core.PaletteTest"   # single class
./gradlew :app:lintDebug               # Android lint (not run in CI)
./gradlew :app:installDebug            # install on a connected device/emulator
./gradlew :app:signingReport           # debug SHA-1 for the Maps key
```

There are no instrumented or Compose UI tests; `:app` has no test source set.

`local.properties` (gitignored) holds machine-local secrets read by
`app/build.gradle.kts`: `hissi.transitToken` (API Bearer token, becomes
`BuildConfig.TRANSIT_TOKEN`), `hissi.mapsApiKey` (manifest placeholder) and
`hissi.upload.*` (release keystore). Without them the build still works;
requests fail at runtime and the map snippet stays blank.

## Architecture

Two Gradle modules (`settings.gradle.kts`), versions in
`gradle/libs.versions.toml`. AGP 9 with built-in Kotlin for `:app`; Kotlin is
pinned to the version AGP bundles (2.2.10), and Ktor, serialization, coroutines
and maps-compose are pinned to releases readable by it. Check the comments in
the catalog before bumping anything.

- **`:core`** (`com.a11yland.hissi.core`): pure JVM, no Android dependencies.
  Hand-ported from the iOS `Shared/` folder. Models (`MonitoredElevator`,
  `ElevatorStatus`, `ElevatorSummary`), the API client (`TransitApi` for
  network and pagination, `AccessibilityCloudClient` for pure parsing and
  joins), catalog handling (`CatalogCache`, `EquipmentCatalog`, `SeedCatalog`,
  `StatusMerge`), search (`SearchGrouping`, `SearchHighlight`,
  `TransitClassification`), `NearbyStations`, `DisruptionAlert` transition
  rules, `FavoritesOrdering`, `WelcomeGate`, and `Palette` + `WcagContrast`
  (colours as hex strings so tests can compute contrast). Tests in
  `core/src/test` run against JSON fixtures in `core/src/test/fixtures/`
  shared with the iOS tests; do not edit fixtures here without the iOS side.
- **`:app`** (`com.a11yland.hissi`, applicationId `com.a11yland.Hissi`):
  - `MainActivity`: maps the palette onto Material 3 light/dark colour
    schemes, session-only theme override, provides `LocalDarkTheme`.
  - `data/`: `AppContainer` (hand-wired singletons, no DI framework; created in
    `HissiApplication`), DataStore-backed repositories (favorites, recents,
    onboarding, alerts), `FavoritesRefresher` (the single refresh path for app,
    widgets and alert worker; the stored favorites double as status cache).
  - `ui/`: one screen, no navigation library. `ContentScreen` switches between
    favorites, recents (with `NearbySection`) and search results based on
    search focus and query; the detail screen (`ElevatorDetailScreen`) is a
    state-based overlay. ViewModels: `MonitorViewModel` (favorites, refresh,
    order, alerts, onboarding) and `SearchViewModel` (two-phase search: instant
    from cached catalog or bundled seed, then live statuses). Map and Street
    View snippets use maps-compose in lite/display-only mode and hand off to
    the maps app.
  - `widget/`: Glance widgets `HissiWidget` (favorites, responsive sizes) and
    `NearbyWidget`.
  - `alerts/`: `DisruptionAlertsWorker` (periodic WorkManager job) and
    `AlertNotifications` (one channel, one notification).
- **Data source:** transit.accessibility.cloud (Payload CMS, Bearer token):
  three flat collections (Elevators, StatusSpans, StopPlaces) fetched with
  depth=0 and joined locally; targeted `where[id][in]` fetch for favorites.
  `app/src/main/assets/seed-catalog.json` is the bundled seed, generated in
  Hissi-iOS and copied per release. Refresh cadence is 30 minutes
  (`RefreshInterval`).
- Platform-free logic goes into `:core` with a test; `:app` only maps it onto
  Android (string resources, Compose, Glance).

## Conventions

- UI language: German is the source (`res/values/strings.xml`), English in
  `res/values-en/`. Add every string to both. `:core` German labels are test
  vocabulary only; the UI uses string resources.
- No hard-coded colours in UI code: add them to `core/.../Palette.kt` with a
  `PaletteTest` assertion and read them through the colour scheme or
  `StatusUi.kt`.
- Status is always shape + word + colour, never colour alone; never show an
  unknown status as working.
- Permission prompts (location, notifications) only from a user tap.
- Comments explain why, often with the iOS counterpart. Keep that style.
- Conventional Commits with scopes (`feat(ui):`, `fix(a11y):`,
  `fix(nearby):`, `build:`, `ci:`, `docs:`).
- GitHub issues are mostly written in German.

## CI and release

- `.github/workflows/ci.yml`: on push to `main` and on pull requests, runs
  `./gradlew test :app:assembleDebug` (Temurin 21). No lint, no UI tests.
- `.github/workflows/release.yml`: manual, from `main` only. Bumps
  `versionCode`/`versionName` in `app/build.gradle.kts`, builds the signed
  bundle from repository secrets, tags `v<version>` and publishes a GitHub
  release. `main` requires verified signatures, so the bump commit and tag
  are created via the GitHub API (signed by GitHub), never `git push`. Play upload stays manual (`docs/android-distribution.md`).
  It refuses to run while the seed or the test fixtures differ from
  Hissi-iOS `main`.
- `.github/workflows/shared-files.yml`: compares `app/src/main/assets/seed-catalog.json`
  and `core/src/test/fixtures/` with Hissi-iOS `main`. On a push or pull
  request touching them it only warns; weekly (Mondays, after the iOS seed
  drift check) and on a manual run it opens or rebuilds a `shared-sync` PR
  that copies the iOS versions over (commit via the GitHub API, signed by
  GitHub). Needs "Allow GitHub Actions to create and approve pull requests"
  in the repo's Actions settings.

## Accessibility

- Target: WCAG 2.2 AA is required for every change; AAA is a bonus goal where feasible. Accessibility issues must carry the `a11y` label.
- Read `ACCESSIBILITY.md` before any UI change and keep it updated (especially Known barriers).
- Every interactive element needs a name, role and state; decorative icons use
  `contentDescription = null`. After `clearAndSetSemantics` on an actionable
  element, re-add `role` and `onClick`.
- Targets at least 24 dp, preferably 44 dp; text in `sp` without fixed
  heights; gestures need an accessibility-action alternative; status changes
  that appear without focus go through a live region.
