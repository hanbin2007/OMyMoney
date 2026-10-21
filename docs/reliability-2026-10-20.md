# Service Reliability and Notification Audit

## Evidence Window

Final read-only server snapshot at **2026-10-20 11:19:59 UTC** (19:19:59 Hong Kong). Every retained batch for the customer's paired device was checked against its receipt SHA-256 and record count. The scan finished in 3.73 seconds under explicit CPU, memory and wall-time bounds; no server configuration or diagnostic consent was changed.

| Measure | Result |
| --- | --- |
| Durable acknowledged batches | 6,172 |
| Acknowledged records, including expired history | 655,463 |
| Retained, fully scanned batches | 6,089 |
| Retained records | 646,740 |
| Retained compressed bytes | 26,676,854 |
| Receipt-only batches evicted by existing retention | 83 (8,723 records) |
| Latest retained record | 2026-10-20 11:06:12 UTC |
| Transaction notification samples | **0** |
| Notification messages rejected as unrelated or sensitive | 8 processing events |

The screenshot's 6,079 is a diagnostic-upload batch count, not a notification or transaction count. The 21,069 dropped records are diagnostic records, already present before Build 6; they cannot be attributed to this update. Complete retained coverage does not recover dropped or expired content.

## Latest State and Limits

An earlier snapshot ended at 08:59 UTC and showed notification access disabled. **Newly uploaded records supersede that finding**: at 10:38:45 UTC the notification listener was authorized and connected; at 10:38:59 UTC Accessibility was also connected. Therefore the user need not operate the phone now merely to satisfy the earlier permission question. The eight filtered notification events prove callbacks occurred, not that any payment template was acquired. These timestamps are the last recorded runtime state, not a live connection probe of the phone.

The latest runtime is Build 6 on OnePlus PKG110, Android API 36, with no reported capture error, power-saving mode or low-memory state. Battery-optimization exemption is false. That setting alone is not evidence that battery management caused an exit. Continuous debugging had not been started in the retained evidence; it must remain an explicit, visible user action.

There are 14 distinct bounded historical process-exit records: nine reason 13 (`OTHER`) and five reason 16 (`PACKAGE_UPDATED`). The available history does not identify a Java/native crash, ANR or low-memory exit. It also does not prove that none ever occurred: retention, dropped records and the bounded OS history limit coverage. The latest unclean-log-session count is 13, **not a crash counter**. `OTHER` cannot identify an OEM's specific cause; no free-form OS description or crash trace was collected.

One old Build 4 `queue_error/reopen_required` was found on October 15. No corresponding current-build failure justified changing financial validation. Historical configuration-event flooding was concentrated in Build 4 and had already been fixed; it was not treated as a new Build 6 defect.

Android documents the user-controlled Accessibility lifecycle and the exit-reason meanings in [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager) and [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo). This audit does not claim an app can silently restore a revoked grant or guarantee survival under OEM restrictions, force-stop or process termination.

## Changes

- Separate the saved Accessibility authorization, OS-bound state and service callback state. Financial capture, overlays and continuous-debug eligibility require actual availability. Observe saved-setting and service-state changes, refresh on activity resume and record content-free state transitions.
- Distinguish retained authorization with a disconnected service from authorization actually absent. Diagnostic log batches and interrupted sessions now have unambiguous labels. Battery exemption is factual, with a user-controlled system-settings link.
- Direct notification authorization to this app's system detail page where supported, with an OEM fallback. On notification-service disconnection request one rebind only when the grant remains; do not grant permissions, replay old notifications, restart stopped diagnostics or add invisible keepalive services.
- Persist numeric notification-processing counters and show received events, candidates, filtered events and queue drops separately from log-upload batches. Counts are events, not unique payments or bills; notification updates/replays can increment events. Persistence is best-effort asynchronous telemetry, not a financial ledger. Additional suppressed/failed counters are available in runtime diagnostics. No text or notification keys are stored in the counter preferences.
- Preserve existing diagnostic consent, supported-package scope, redaction, bounded sampling and paired upload path. Notification drafts remain `PENDING` and require manual review; no automatic notification posting to the ledger was added.
- Independent Meituan Takeout package support remains included. Existing consented in-app Accessibility diagnostics collect permitted page samples; another screenshot, new payment or extra collection switch is not required. Real standalone-app page and transaction-notification compatibility remains unverified without corresponding customer samples.

## Verification

- Red/green device tests reproduced the old false-negative grant status under Android UiAutomation's service suppression and the non-targeted permission link, then passed after the fixes. An OEM-missing-detail-activity fallback is also covered.
- Core: 229 JVM tests passed. App: 13 JVM tests passed, including counter summaries, queue overflow, failed telemetry observers, consent/stop, replay and privacy cases. Debug lint and signed-release verification passed.
- Focused API 36 device suite: 30 tests passed, covering permission state, persisted/corrupt counters, Accessibility privacy and cancellation, diagnostic consent/recovery, notification queue and standalone Takeout capture.
- The exact signed Build 7 passed 14 focused device tests. A repeat-run fixture originally reused a fixed book name and ignored the rejected catalog result; it was corrected to create/assert a unique test book and restore the selected book. The production financial code was not changed to hide that test failure. Data-device regression: 30 tests passed; local receiver/TLS/publisher tests: 19 passed.
- A real OS-delivered **synthetic** notification from the isolated fixture reached the pending queue without Accessibility, incremented received/candidate counters and did not create a bill. Diagnostic recording was disabled and the emulator was unpaired; no synthetic sample was uploaded to production.
- Diagnostic/update UI tests passed at 411dp/default font and 360dp/200% font. Screenshots confirm the added counters, retained-grant warning, batch/session labels and battery-settings control remain readable and reachable.
- Scoped independent source review found no actionable regression. No private keys, credentials or customer log contents were supplied to that review.
- Exact-release continuous-debug system test passed (73.089 seconds): visible foreground notification, real minute heartbeat while backgrounded, notification action Stop and Accessibility revocation stop both the session and recording. Its UI harness was updated to collapse a leftover shade, handle automatic notification grouping and tap the observed child expansion control; SystemUI returned success for an accessibility click without expanding that child. No production notification behavior was weakened to satisfy the test.

## Release

Release `0.2.5` (Build 7) is locally signed with the existing Android key. The exact previously published Build 6 accepted the signed candidate through its built-in installer and Android's explicit **Update** confirmation. Post-install acceptance passed, retaining the seeded bill, dirty raw editor draft (`7..25`), pending recognition record, theme, secure-screen and diagnostic settings. This used the exclusive disposable API 36 emulator `reliability36-01a151fb-20`, not a customer's physical phone.

APK: 61,603,783 bytes; SHA-256 `69176e729c368f7c197d06c952d98ea11a2aa78aad6913dad13403ee62ffbaa4`. Android signer SHA-256 remains `91275b1e64da9ff62a5da9444bcbb6e4ef42f51fb5fd9dd83e14820a10129a37`. The manifest was signed locally; private keys and passwords never leave this machine.

The existing receiver's atomic publisher has published Build 7. The public HTTPS manifest was read back, independently signature-verified and compared byte-for-byte with the local signed manifest. A bounded HTTPS APK read returned `206`, the correct full length and bytes identical to the local prefix; the server's complete canonical APK SHA-256 also matches. The exact previously delivered Build 6 then checked and downloaded the complete public APK through its unchanged updater, validating SHA-256, package, increasing version and original signer (`PublishedUpdateDownloadTest`: one test passed, 239.076 seconds). Receiver PID remains `67644`, `NRestarts=0`, with its existing 256 MiB memory limit and 20% CPU quota. No receiver restart or new service was used.

The publicly downloaded APK was then installed through Build 6's UI and Android's explicit **Update** confirmation. A fresh `UpdateAcceptanceTest` passed immediately after replacement, verifying retained bills, dirty drafts, pending records and settings. The disposable test device was reset only between the earlier local-candidate proof and this fresh public-download proof; no customer or shared-emulator data was reset. A customer's installed version and OEM-background behavior still require real-device acceptance after they choose to install the update.

Local audit evidence: `work/qa/log-review-20261020/audit-final.json`; earlier scans are retained separately. QA screenshots and build logs remain local. No customer device identifier or notification content is reproduced here.
