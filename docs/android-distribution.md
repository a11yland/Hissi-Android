# Android: Distributing to Testers

Checklist for getting the app to testers via the Play Console internal-testing
track (plan decision: personal account, testing tracks only — production is
deliberately deferred, see [`android-plan.md`](android-plan.md)). The iOS side
already runs TestFlight.

## One-time: account & app

- [ ] Create the personal Play Console account ($25 one-time). Identity
      verification is lead time — start early. Re-verify current Play policies
      at creation; they shift.
- [ ] Create the app: name "Hissi", default language German, app (not game),
      free.
- [ ] Accept Play App Signing (the default). Afterwards note **both** SHA-1s
      under Console → App integrity: the *upload key* signs what you build,
      the *app signing key* signs what testers actually install.

## One-time: release build plumbing

- [ ] Generate an upload keystore (`keytool -genkeypair`), keep it out of the
      repo, and wire a `release` signing config off `local.properties` in
      `app/build.gradle.kts` — only the debug config exists today.
- [ ] Restrict the Maps API key to the package plus **both** SHA-1s. With only
      the upload-key SHA-1 the map snippet goes blank for testers, because
      Play re-signs the AAB with the app signing key.
- [ ] Real transit token in `local.properties` on the release-building machine
      — CI's empty token would ship an app that cannot fetch anything.

## One-time: store presence (minimum for internal testing)

- [ ] Store listing: short/full description, 512 px icon, feature graphic,
      ≥ 2 phone screenshots.
- [ ] Privacy policy URL — required, the app declares location permissions: `https://github.com/a11yland/Hissi-Android/blob/main/PRIVACY.md` (the Android policy: Maps SDK, local-only favorites, alerts).
- [ ] Data safety form: *our* code collects and shares nothing — location is
      used on-device only and never transmitted, no accounts, no tracking.
      (On-device-only processing does not count as "collected".) The **Maps
      SDK is the exception**: even in lite mode it talks to Google via Play
      services, and Google publishes data-safety disclosures for it that must
      be merged into the form — check the Play SDK Index / Maps SDK
      documentation for the current ones at submission time.
- [ ] Content rating questionnaire; target audience (not child-directed);
      ads declaration (none).

## Per release

- [ ] Regenerate the seed catalog (`scripts/generate-seed-catalog.py`) — the
      seed is refreshed manually per release.
- [ ] Bump `versionCode` (and `versionName` when user-facing) in
      `app/build.gradle.kts`. A curated "Was ist neu" additionally
      needs the version in `WelcomeGate.curatedVersions` + a
      `WhatsNewContent` entry.
- [ ] `./gradlew :app:bundleRelease`; smoke-test the release build (not the
      debug build) on the AVD before uploading.
- [ ] Upload the AAB to the internal-testing track, release notes DE/EN.
- [ ] First time only: add the tester e-mail list (≤ 100 for internal
      testing) and share the opt-in link. Testers accept once and get every
      later build via Play automatically.

Production later: new personal accounts must first run a closed test with
12 testers over 14 days, and the account's public legal name goes live —
both reasons the plan stops at testing tracks for now.
