# Docs

The **iOS repo is the baseline**: product requirements, architecture, data
model, API migration notes and the favorites export contract live in
[Hissi-iOS/docs](https://github.com/a11yland/Hissi-iOS/tree/main/docs) and are
not duplicated here. The documents in this folder add what is Android-specific
and reference the baseline where they build on it.

| Baseline (Hissi-iOS) | What it covers |
|---|---|
| [business-requirements.md](https://github.com/a11yland/Hissi-iOS/blob/main/docs/business-requirements.md) | the FR/NFR list both apps implement |
| [architecture.md](https://github.com/a11yland/Hissi-iOS/blob/main/docs/architecture.md) | data flow; `:core` here mirrors the iOS `Shared/` |
| [transit-api-migration.md](https://github.com/a11yland/Hissi-iOS/blob/main/docs/transit-api-migration.md) | transit.accessibility.cloud endpoints and fields |
| [favorites-export.md](https://github.com/a11yland/Hissi-iOS/blob/main/docs/favorites-export.md) | the favorites JSON export contract |

| Android-specific | What it covers |
|---|---|
| [android-plan.md](android-plan.md) | stack decisions, port strategy, deliberate divergences from iOS |
| [android-distribution.md](android-distribution.md) | Play Console internal testing, signing, release checklist |
| [play-listing.md](play-listing.md) | the Play store listing text and assets |
