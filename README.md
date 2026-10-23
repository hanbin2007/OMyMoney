# OMyMoney

Native Android personal bookkeeping app. Kotlin, Jetpack Compose Material 3 / Material You, with local bookkeeping and optional remote diagnostics plus signed APK updates.

## Public Source Snapshot

This repository contains the current `0.2.9` (Build 11) source. Local development history, signing private keys, credentials, device diagnostic uploads, build caches and generated APKs are not included. The original workspace and its history remain separate.

The bundled update verification key is public by design. Self-built APKs use a different signing identity unless you supply your own release key; the official update channel does not bypass Android's same-signer requirement. Preserve the bundled third-party notices when redistributing a build.

## Project Status

The approved local features are implemented: manual and rule/OCR drafts, linked and multi-currency accounting, budgets/statistics, schedules/installments, savings, validated data exchange, backups, privacy and standard widgets. Whole-app acceptance and delivery evidence are tracked in [the coverage report](docs/full-coverage.json), with explicit [differences and unverified external compatibility](docs/known-differences.md). Historical stage reports are not current acceptance claims.

No generative AI provider is implemented. The OCR model is bundled for offline image recognition. Version 0.2 adds Internet access for opt-in diagnostic uploads and APK updates; cleartext traffic and automatic Android backup remain disabled. Bundled ML Kit may send upstream SDK metrics when networking is available. See [diagnostics and update boundaries](docs/diagnostics-updates.md) for current release evidence and outstanding operational items.

## Layout

- `app/`: Android UI and platform integration.
- `core/`: platform-independent bookkeeping and recognition logic.
- `data/`: local persistence and transactions.
- `server/`: bounded, authenticated diagnostic receiver and release distribution.
- `docs/`: approved specification, plans, and reverse-engineering evidence.
- `work/`: ignored analysis material, build caches, logs, and the isolated test emulator.
- `outputs/`: ignored installable builds and acceptance deliverables.

## Build And Test

Use JDK 17 and Android SDK 36. The Gradle Wrapper and dependency verification metadata are checked in.

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The application label is `OMyMoney`; its independent application ID is `app.omymoney.android`. Debug builds use a development signature, not a production release signature.

For the complete test matrix, dedicated-device checks, private release signing and dependency notices, see [build and test instructions](docs/build-and-test.md). Every delivered version must be published through the built-in update channel after verification, as required by [the release policy](AGENTS.md). A local APK alone is not a completed release; never substitute an older stage debug build.

Transaction notification access, review-only recognition and consent-controlled diagnostic samples are described in [notification capture](docs/notification-capture.md).

The interface supports [Simplified Chinese, Traditional Chinese and English](docs/app-languages.md). Native McDonald's China app recognition and its initial review-only evidence boundary are described in [McDonald's recognition](docs/recognition-mcdonalds.md).
