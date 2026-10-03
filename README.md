# Hissi-Android

Android app of Hissi: monitors user-selected elevators via the
[transit.accessibility.cloud](https://transit.accessibility.cloud) API and shows
their live status — favorites, search, nearby stations, disruption alerts and a
home-screen widget. Kotlin + Jetpack Compose; `:core` is a pure JVM module with
the platform-free logic, hand-ported from the iOS app's `Shared/`
([Hissi-iOS](https://github.com/a11yland/Hissi-iOS)).

## Build & test

```bash
# needs JAVA_HOME pointing at a JDK 17+ (e.g. Android Studio's bundled JBR)
# and an Android SDK in local.properties (sdk.dir=…)
./gradlew test :app:assembleDebug
```

`local.properties` (gitignored) also carries the machine-local secrets:
`hissi.transitToken` (transit.accessibility.cloud Bearer token),
`hissi.mapsApiKey` (Maps SDK key, restricted to package + signing SHA-1) and the
`hissi.upload.*` Play upload keystore settings — see `app/build.gradle.kts`.

## Shared with the iOS app

- `app/src/main/assets/seed-catalog.json` — the bundled seed catalog, generated
  in Hissi-iOS (`scripts/generate-seed-catalog.py`) and copied here per release.
- `core/src/test/fixtures/` — the JSON fixtures the `:core` tests share with the
  iOS Swift package (`Hissi-iOS/HissiTests/Tests/Fixtures`), so behavioral drift
  between the two ports surfaces in CI.

See [`docs/`](docs/README.md) for the Android-specific plan, the Play distribution
notes and the store listing; requirements and architecture are documented in
[Hissi-iOS/docs](https://github.com/a11yland/Hissi-iOS/tree/main/docs), the baseline
for both apps.
