# Stage A Acceptance

Stage A is implemented and verified on the owned `ledger34` Android 14 / API 34 emulator. This is an intermediate build, not the complete OMyMoney project. Plans B and C remain required.

## Build

- Product/package: OMyMoney / `app.omymoney.android`.
- Version: `0.1.0` (`versionCode 1`), debug signature, min API 26, target/compile API 36.
- Staged APK: `outputs/OMyMoney-stage-A-debug.apk`.
- SHA-256: `ed07078e1890b1ff682e68675e71723a283577c8b59d668bb4e5f5b9a4d08c7d`.
- Source branch: `codex/omymoney`; no merge, push or production-signing claim.

## Verification

`sh work/run-gradle.sh :core:test :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest :data:lintDebug :app:lintDebug :app:assembleDebug` passed after the review fixes. Reports record 15 core, 44 data and 53 executed app cases with zero failures. Three additional app cases are deliberately skipped in the ordinary portrait run: compact-height navigation, two-process draft recovery, and two-process attachment recovery. Dedicated native runs cover those cases, rather than counting a skip as a pass.

Lint has no errors and 22 pinned dependency/toolchain upgrade notices. `scripts/verify-offline.sh` passes: the merged manifest has no INTERNET permission and automatic legacy, cloud and device-transfer backups are disabled. Dependency checksums are enforced. These checks do not constitute a comprehensive supply-chain or legal audit.

The combined acceptance flow creates a book and accounts with 100.00/50.00 opening balances, records income 25.30, expense 12.30 and transfer 40.00, edits the expense to 15.60, deletes and undoes it, exports/restores a backup, recreates the Activity, then force-stops and reopens the process. Final balances are exactly 69.70 and 90.00, with exactly the three expected bills. Both dedicated process phases report `OK (1 test)`.

## Review Fixes

An independent whole-branch review found five Important issues. Each has a reproducing failed test followed by a passing fix and green full regression:

1. Blocked image reads now close their provider source on cancellation; cancel, keep and discard no longer wait indefinitely.
2. SAF import/export callbacks received during initial state loading are queued instead of dropped.
3. Backup entity IDs and draft references must use canonical UUIDs before they can reach navigation.
4. A validated backup repairs known corrupt attachment bytes without losing old rows on a subsequent database rollback.
5. Merge balance overflow is a visible merge conflict, not a reason to hide a valid replacement preview.

No Critical or deferred Minor finding remained from that review. Earlier independent UX reviews also drove field error focus, accessible controls, draft recovery, large-amount layout, image-viewer access and backup-confirmation fixes. Review dispositions and implementation tradeoffs are retained in `docs/decisions-A.md`.

## Visual And Device Evidence

- `work/qa/a4`: compact/wide, light/dark, dynamic/fixed color and font matrices.
- `work/qa/a5` to `work/qa/a7`: catalog workflows, compact-height navigation, 200-percent font, keyboard/back, raw-draft process recovery, bill lifecycle, actual image picker and private image checksum readback.
- `work/qa/a8`: readable backup conflict/confirmation, cancellation/error states and actual SAF export/import with checksum readback. The actual SAF archive had an empty ledger; nonempty records and images are separately covered by database round trips.
- `work/qa/a9`: final combined workflow, process-phase logs and `reopened-native.png`/XML. The settled native screen was inspected and shows the book, three exact amounts and navigation without overlap. The earlier in-test cold-start screenshot omitted insets while rendering and is not accepted as final visual evidence.

## Boundaries

This stage enables expense, income and same-currency transfer, catalog management, raw drafts, bill search/edit/delete/undo, private images and full current-schema backup. `docs/feature-coverage.json` separates implementation, tests, device evidence and differences. Automatic capture/OCR and advanced finance/statistics/settings/widgets belong to B/C, not this staged APK.

No physical payment-app compatibility, API 26/36 runtime, physical-device TalkBack/predictive-back, hardware power-loss durability or exhaustive image-decoder security claim is made. Backups are unencrypted and an external document provider may independently synchronize a user-selected file. Soft-deleted history, attachment orphans/quarantine and one latest pre-replacement recovery snapshot are intentionally retained; see `docs/backup-format.md`.
