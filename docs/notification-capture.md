# Transaction Notifications

The Android notification listener covers every package in `RuleRegistry.supportedPackages`, including newly registered sources. It is separate from posting OMyMoney notifications and from Accessibility access. The user must grant notification access in Android settings. There is no background permission grant, history scraping, automatic notification dismissal, or replay of active notifications on connection.

## Recognition

- Bookkeeping follows the existing enabled, paused and selected-package settings. Notification processing does not require Accessibility to be connected or a payment page to remain open.
- Read only standard title, text, expanded text and up to 16 text lines, bounded to 4096 characters per field and 16384 total. Ignore other users, unsupported packages, group summaries and ongoing notifications. Oversize, credential-bearing, promotional and unrelated content is not parsed.
- The initial generic parser extracts explicit money/currency, direction and labeled merchant evidence. Multiple distinct amounts and ambiguous currencies remain unresolved. Notification text is untrusted: a forwarded message or third-party notification is not settlement proof.
- Every notification draft requires review. Notification posting time is not substituted for transaction time, accounts are not guessed, and no notification automatically changes balances. Real templates collected from users will guide later source-specific rules; synthetic test cases are not compatibility evidence.
- Build 8 excludes ordinary WeChat chat titles and no longer treats the Alipay brand name alone as a payment keyword. Review drafts require an explicit completed-payment or supported transaction declaration. Unknown financial templates can remain consented diagnostic samples without becoming pending drafts. Pending history never automatically opens an overlay, including after unlock, resume or service reconnection; manual review remains available in the app. Only a fresh, committed automatic bill can show the existing short receipt.
- Durable submission identity includes package, hashed notification identity/posting time and book. Exact replay reuses the queue entry. Equal payments without a transaction identity remain distinct, similar candidates. A page capture similar to a notification must remain reviewable rather than automatically creating another bill. Existing duplicate-confirmation policies still apply to explicit manual saves.

## Diagnostic Samples

The existing diagnostic-recording consent and on/off state govern sampling. As authorized, there is no additional collection-consent switch. Android notification access is still a separate mandatory system authorization. Sampling includes all currently supported packages even when bookkeeping is disabled or a package is not selected for bookkeeping; additional diagnostic-only packages do not expand notification sampling.

`notification_sample` contains package, hashed sample ID, posting time, bounded title/body/expanded text and recognition outcome, including unrecognized financial-looking templates. It never stores the Android notification key, intents, arbitrary extras, attachments, or messaging history. Credential-bearing notifications are excluded, long numeric account identifiers are masked, and the existing redactor runs again before persistence. Financial-looking messages may include personal information; neither the heuristic nor consent makes them authenticated payment evidence.

Samples use the existing bounded spool and paired HTTPS upload worker, with unchanged local/server limits and retention. No new backend service or endpoint is needed. Turning off recording stops new samples immediately; existing batches follow the existing upload/disconnect semantics. Runtime snapshots report actual notification access, connection and capture errors. Protected or empty system payloads are not bypassed.

Build 7 adds private numeric processing-event counters (received, candidates, filtered, dropped, suppressed and failed). The UI exposes event/candidate/filter/queue-drop counts; runtime diagnostics include all six. These are not unique notifications, settled transactions or upload batches, and asynchronous persistence may lose the last telemetry updates during abrupt process termination. Permission navigation targets this app's notification-listener detail page with a fallback; a disconnected listener requests a rebind only while its system grant remains. See the [October 20 reliability audit](reliability-2026-10-20.md) for actual customer coverage and its limits.

## Verification

`NotificationRulesTest`, `NotificationCaptureCoordinatorTest`, `NotificationPipelineDeviceTest` and `NotificationQueueTest` cover source registration, credential filtering, unknown templates, export/consent, field limits, replay, bursts, failures, pending-only writes, backup restoration and cross-source duplicate protection. Device results and release publication are separate claims; a local build is not an in-app release.

API contract: [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [standard notification extras](https://developer.android.com/reference/android/app/Notification).

## Verification Record (2026-10-18, America/New_York)

- Core: 229 tests, zero failures/errors. App JVM: 10 tests, zero failures/errors. Debug build and Android lint passed; existing deprecation warnings are not counted as new feature failures.
- Focused data-device regression: 30 tests passed across notification queue, automatic recording and recognition queue behavior, including notification backup restoration and page/notification duplicate protection.
- The exact signed Release passed three device checks for standard/oversize notification fields, consent-controlled sample export and the diagnostic UI at font scale 1.3. The OS delivered a synthetic notification from the isolated fixture through the real listener into the durable pending queue, without Accessibility and without sending the synthetic sample to production.
- The exact previous public Build 5 upgraded to the candidate Build 6 through its built-in installer, including Android's explicit Update confirmation. Post-install acceptance verified retained bills, raw editor drafts, pending recognition records, theme, secure-screen settings and diagnostic settings.
- Isolated device: `notification36-01a151fa`, not a physical phone. A shared emulator was not used for final upgrade acceptance. The graphics backend was restarted without Vulkan after an emulator crash. Instrumentation force-stops the target and Android delayed listener rebinding by about ten seconds; the system test waits on actual connection, not a five-second assumption.
- Independent external-model source review was blocked by the approval reviewer because it would disclose source code to an external Codex endpoint without separate authorization. No workaround was used. Local manual review and the runtime tests above were completed; a separately authorized external review remains optional.
- Publication and public HTTPS download readback are recorded separately below. No real third-party notification template or OEM-background compatibility is claimed from synthetic tests.

## Published Releases

### Build 8

Version `0.2.6`, Build `8`, fixes false notification candidates and automatic reappearance of pending history. It retains manual review and the committed-bill receipt. The original signer and atomic update channel are unchanged. The user explicitly waived the remaining system-upgrade and retention acceptance before publication; no completed Build 7 to 8 upgrade is claimed. See the [pending popup fix record](pending-popup-fix-2026-10-21.md) for evidence and publication readback.

### Build 7

Version `0.2.5`, Build `7`, is published through the same atomic update channel, with locally signed manifest and the original Android signer. Public HTTPS manifest readback, complete canonical APK hash, and the previous Build 6 client's full public APK download/verification passed (239.076 seconds). The same-key in-app Build 6 to 7 upgrade retained seeded bills, raw drafts, pending entries and settings. See the [October 20 reliability audit](reliability-2026-10-20.md) for service-state changes, event counters, test evidence and the remaining real-template/OEM limits.

### Build 6

Version `0.2.4`, Build `6`, was published through the existing receiver's atomic publisher. The public HTTPS manifest was read back, independently signature-verified and compared byte-for-byte with the locally signed envelope. The exact previously delivered Build 5 then checked the public channel and downloaded the complete APK through its unchanged built-in updater, passing SHA-256, package, increasing-version and same-signer checks (`PublishedUpdateDownloadTest`: one test passed, 239.259 seconds).

- APK: 61,603,783 bytes; SHA-256 `662ae3713e7c9a075ef207a12a735f0162843609a9e93b4e7eda565b34820751`.
- Android signer SHA-256: `91275b1e64da9ff62a5da9444bcbb6e4ef42f51fb5fd9dd83e14820a10129a37`.
- Public manifest: [stable update channel](https://omymoney.origenclub.cn:58952/updates/stable.json).
- The publicly downloaded APK was installed through the old client's UI and Android's explicit Update confirmation. A fresh post-install `UpdateAcceptanceTest` passed, preserving the seeded bills, raw drafts, pending recognition record and settings.
- Receiver PID remained `67644`, with `NRestarts=0`, a 256 MiB memory limit and 20% CPU quota. No new server service or service restart was used. Only the signed manifest and verified APK were published; private keys/passwords stayed local. Slow SSH transfer was replaced by the existing bounded, checksummed APK-delta workflow.
- Users still need to update and manually grant Android notification access. Existing diagnostic consent/recording and pairing govern future real notification samples. This release verification does not mean real user templates have already been received or validated.

Local evidence is in `work/build-logs/notification-*` and `work/build-logs/notifications-v6-release.log`; the signed release inputs are in `outputs/notifications-v6/`. These QA paths are not standalone substitutes for the in-app release.
