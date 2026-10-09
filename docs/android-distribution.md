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
- [x] Accept Play App Signing (the default). Afterwards note **both** SHA-1s
      under Console → Protected with Play → Play Store protection → Play App
      Signing (formerly "App integrity"): the *upload key* signs what you
      build, the *app signing key* signs what testers actually install.

## One-time: release build plumbing

- [x] Upload keystore: `~/Keys/hissi-upload.jks` (alias `hissi-upload`, created
      2026-10-04 via Android Studio), outside the repo; the `release` signing
      config reads `hissi.upload.*` from `local.properties`.
- [x] Restrict the Maps API key to the package plus **both** SHA-1s (done
      2026-10-04 in the Cloud Console next to the LiftBoy entries). With only
      the upload-key SHA-1 the map snippet goes blank for testers, because
      Play re-signs the AAB with the app signing key.
- [ ] Real transit token in `local.properties` on the release-building machine
      — CI's empty token would ship an app that cannot fetch anything.
- [ ] GitHub secrets for the release workflow (same values as
      `local.properties`, keystore base64-encoded):

      ```sh
      gh secret set HISSI_UPLOAD_KEYSTORE_BASE64 < <(base64 -i ~/Keys/hissi-upload.jks)
      gh secret set HISSI_UPLOAD_STORE_PASSWORD
      gh secret set HISSI_UPLOAD_KEY_ALIAS
      gh secret set HISSI_UPLOAD_KEY_PASSWORD
      gh secret set HISSI_TRANSIT_TOKEN
      gh secret set HISSI_MAPS_API_KEY
      ```

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
- [ ] A curated "Was ist neu" needs the new version in
      `WelcomeGate.curatedVersions` + a `WhatsNewContent` entry, merged
      before cutting the release.
- [ ] Run the **Release** workflow (Actions → Release → Run workflow, from
      `main`) with the new `versionName`. It increments `versionCode`,
      runs the tests, builds the signed AAB, commits the bump, tags
      `v<version>` and publishes a GitHub release with the AAB and the R8
      mapping. The bump commit is pushed with the workflow token, so CI does
      not run on it again (the release build already covered it).
- [ ] Download the AAB from the GitHub release; smoke-test the release build
      (not the debug build) on the AVD before uploading.
- [ ] Upload the AAB to the internal-testing track, release notes DE/EN.
- [ ] First time only: add the tester e-mail list (≤ 100 for internal
      testing) and share the opt-in link. Testers accept once and get every
      later build via Play automatically.

Production later: new personal accounts must first run a closed test with
12 testers over 14 days, and the account's public legal name goes live —
both reasons the plan stops at testing tracks for now.
