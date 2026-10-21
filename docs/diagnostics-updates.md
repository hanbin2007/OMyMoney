# Diagnostics and In-App Updates

Approved scope: opt-in full related-app diagnostics, authenticated upload to the
RainYun VM, and signed APK updates. Rule hot updates are explicitly deferred.
This records the in-chat design approval; it does not imply approval of unseen
documents. Implementation stays inline on `codex/diagnostics-updates`.

## Boundaries

- All accessibility event types for the selected diagnostic packages, independent
  of recognition enablement. Only the original window/content event types may
  trigger financial recognition. Existing timing, rules and accounting stay intact.
- Log event metadata, bounded safe node snapshots, filtering reasons, capture
  generation/cancellation, rule decisions, queue outcomes and application failures.
  Distinguish null, stale, truncated, dropped, unsupported and committed outcomes.
- Exclude password, editable and sensitive subtrees and password/login windows.
  Never retain passwords, OTPs, authentication tokens, pairing codes or HTTP headers.
  Payment text/amounts/merchant/order evidence may be recorded after explicit consent.
  No claim to OS-protected nodes, other apps' internal traffic, or unrestricted logcat.
- Recording defaults off. Pairing and recording are separate explicit controls.
  Pause stops new recording. Network and storage failures never break bookkeeping.
  Diagnostics and installation identity are not included in business backups.
- A bounded, off-main-thread queue writes redacted NDJSON, seals immutable gzip
  batches, and retries by batch UUID and SHA-256. Server acknowledgement requires
  durable storage; client deletion requires the matching receipt. Quota losses and
  unclean previous sessions are visible rather than described as complete coverage.
- HTTPS only, no cross-origin redirects, per-install write-only upload tokens from
  expiring one-use pairing codes. Raw logs are available through SSH administration
  only. Bound request size, decompression, rate, retention and available disk.
- Updates use an RSA-signed byte payload, pinned manifest verification key, APK
  SHA-256, package ID, installed signing certificate and monotonic version check.
  The server never holds Android or manifest signing private keys. System package
  installer confirmation remains required where Android requires it. No silent
  install promise. A first manual 0.2 installation bootstraps this capability.

## Hosting

Use `/data/omymoney` on RainYun, not its nearly full root volume. Reuse the explicitly
released old OCall HTTPS mapping: public port 58952 to internal 18443. Intended
origin: `https://omymoney.origenclub.cn:58952`. Set a dedicated unit, 256 MiB memory
limit, CPU quota 20%, low IO priority, 1 GiB log retention, bounded release storage,
and a free-disk floor. Do not restart Minecraft, MCSM or other existing services.
TLS must validate normally. DNS-01 renewal must be installed and verified before
calling the deployment unattended. Preserve the existing app signing identity.

## Implementation Ledger

1. Core contracts: redaction, crash-safe bounded spool, signed-manifest validation.
   Test real files, recovery, receipt mismatch, quota loss and hostile manifests.
2. Server: isolated receiver, pairing administration, idempotency, retention,
   bounded HTTP transport and signed release publication. Local security tests.
3. Android diagnostics: asynchronous recorder, event/snapshot/capture/rule/queue
   instrumentation, authenticated worker and explicit settings controls.
4. Android updates: check/download/cancel/verify/install, lifecycle-safe status and
   system confirmation fallback. No version success until the new package runs.
5. RainYun deployment: TLS/DNS/service limits, real authenticated upload/readback,
   signed release endpoint and health/resource checks. No unrelated restarts.
6. Acceptance: complete regression suites, API 26/36 isolated runtime checks,
   real same-key update preserving book/draft/privacy state, compact/large-font UI,
   independent final review, then signed release and evidence-based documentation.

The user's running API 34 emulator is not a test fixture. Do not clear, uninstall
or restart it. Builds and HTTP receipts alone are not visible/runtime acceptance.
Real Alipay diagnosis remains unverified until a trace from the user's phone exists.

## Continuous Debug Sessions (0.2.1)

- Start explicitly from Settings > Appearance and privacy > Diagnostic logs after
  consenting to recording and granting accessibility and visible notifications.
  A `specialUse` foreground service records runtime evidence roughly every minute,
  seals the existing 16 MiB bounded spool, and enqueues network-constrained uploads.
  This is continuous diagnostic work, not a generic keep-alive or a guaranteed
  one-minute delivery SLA. The session adds no wake lock, exact alarm, reboot
  receiver or restart loop; existing WorkManager jobs never restart the session.
- Notification and in-app Stop both end the session and recording. The notification
  body opens the diagnostic controls behind the existing privacy gate. Disconnecting
  accessibility, disabling recording or blocking notifications also ends the session.
  Already-consented pending batches can still upload until pairing is removed.
- `START_NOT_STICKY` and a process-local start request prevent service auto-resume.
  A persisted interruption marker disables recording before a replacement process
  initializes capture. Stop disables in-memory recording even if a preference write
  fails. There is no promise to survive force-stop, OS/OEM kill or lost permissions.
- Added lifecycle, screen/lock, service start/stop, task removal, permission/power,
  process importance, queue/drop counts and latest transfer outcome evidence.
  Transfer summaries do not produce recursively self-uploading batches. Manual
  upload includes a fresh runtime snapshot and reports confirmed batch count.
- On API 30+, query at most five historical exits with numeric reason/status,
  timestamp, importance and memory sizes. These bounded records can repeat across
  sessions; identify duplicates by timestamp/reason. Missing OS history is not proof
  of a clean exit. Do not collect OS trace streams or free-form exit descriptions.
  API 26-29 explicitly records unsupported exit-history coverage.
- Existing protected-window, password/editable-node and lock-screen exclusions
  remain in force. This is not all-device logcat or other apps' private internal logs.

Platform contracts: [foreground service special use](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[user stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping),
and [process exit evidence](https://developer.android.com/reference/android/app/ApplicationExitInfo).

### 0.2.1 Verification, 2026-10-13

- 181 JVM tests passed. API 36 full device regression: app 207 passed / 15 opt-in
  tests skipped; data 127 passed. Debug and release lint passed.
- The opt-in continuous-session native workflow passed separately at default
  sizing and 360dp / 200% text. It covers start followed immediately by stop,
  current-session background heartbeats, notification navigation and stop,
  and real accessibility grant/revocation through Android Settings.
- Inspected native active-session and expanded-notification screenshots, including
  large text. Stop remains visible in the first app viewport. TalkBack and the
  user's physical OEM battery manager have not been independently verified.
- Final signed APK, API 36: swiping away the recent-task card left foreground
  service 1102 running. `cmd activity stop-app` removed it without restarting it.
  Manually reopening showed recording off and an interruption notice. Exporting
  through Android's document picker and reading back the ZIP confirmed
  `debug_task_removed`, `debug_session_interrupted(recordingResumed=false)` and
  `process_exit(reason=10)` matching the OS USER REQUESTED exit.
- API 26: same-signature `adb install -r` upgraded 0.2.0 to 0.2.1 without uninstall,
  retained the original install timestamp, and launched the visible diagnostic
  screen. This is not acceptance of the separate in-app update installer flow.
- Delivery: `outputs/OMyMoney-0.2.1-test.apk`, version code 3, SHA-256
  `f4171d0d590bfc5febc8f9cfa249088d262ff2e15e2d2491425fdc3d6dd64a6b`.
  Native evidence and logs are in `work/build-logs/debug-session/`. Publication
  and the later in-app upgrade acceptance are recorded below.

### In-App Publication, 2026-10-13

The user requires all future delivered versions to use the built-in update
channel. This is now a repository instruction in `AGENTS.md`, not an optional
post-delivery step. Installation still requires Android's explicit confirmation.

- Published the exact version-code-3 APK above with the existing atomic publisher,
  original Android signer and locally signed manifest. Both private keys stayed on
  the build machine. No service restart was requested or performed.
- Public `https://omymoney.origenclub.cn:58952/updates/stable.json` was read back and
  matched the signed local envelope byte-for-byte; signature, identity and expiry
  verified. The APK is `/updates/omymoney-3-f4171d0d590bfc5f.apk`, 61,530,055 bytes.
  Server-side SHA-256 matched, and a public 206 range response matched local bytes.
- API 26: the previously delivered, original-signature 0.2.0 client validated the
  candidate. Actual in-app PackageInstaller permission, confirmation, cancellation,
  retry and successful replacement were exercised, not substituted by `adb install`.
  The candidate was preloaded for this installation-stage test.
- API 36: a clean 0.2.0 client discovered the public release without an injected
  manifest or APK. Public download, pause and resume were exercised. Two attempts
  encountered recoverable network errors; the remaining transfer completed through
  the unchanged installed downloader using the opt-in diagnostic probe, with full
  SHA-256 verification. The normal UI then verified package identity/signature and
  completed the real Android update confirmation to 0.2.1. This does not claim the
  network path is interruption-free.
- Both post-upgrade acceptance checks passed: installed version 3, cleared persisted
  installer transaction, retained test bill (CNY 12.34), raw dirty draft (`7..25`),
  dark theme, dynamic-color setting, secure-screen setting and diagnostic scope.
  The API 36 verification originally waited for a transient UI state; it was fixed
  to wait for persisted transaction cleanup, since a callback can already have
  launched the new version before the test process starts.
- All 19 receiver/publication/TLS tests passed locally. Final public manifest
  verification and HTTPS health check succeeded. System confirmation screenshots
  and `upgrade26-*`, `upgrade36-*`, `live-download-probe-resumed.log` are in the
  evidence directory above. App screenshots during the upgrade fixture are blocked
  intentionally by its retained secure-screen setting.
- Operational follow-up: the administrative SSH endpoint subsequently timed out while public HTTPS
  stayed available. The private temporary publication inputs in
  `/data/omymoney-release-20261013-v3` therefore remain pending cleanup (about 62 MB,
  containing only the APK and signed public manifest). Do not confuse them with
  canonical served releases. Post-publication host/PID readback remains unverified;
  do not restart other services to recover management access. Certificate-renewal
  automation remains the previously deferred operational item.

### 0.2.2 Recognition Fix And Publication, 2026-10-13

- Fixed the captured Alipay merged description (`支付成功￥0.01`) and shared
  text/description, status, amount and structural-field handling across the seven
  supported rule families. See [the recognition evidence](accessibility-capture.md).
  Independent review findings were reproduced with failing tests before fixes;
  the final narrow re-review found no additional critical issues.
- Final JVM regression: core 201 and app 5 passed. API 36: app 208 passed / 16
  opt-in or conditional cases skipped; data 127 passed. Both lint variants and
  the 19 receiver/publisher/TLS tests passed. Counts come from XML reports rather
  than the Android runner's duplicate progress lines.
- The anonymized 27-node captured page passed through `CaptureCoordinator`, the
  durable pending queue and the actual review UI. Screenshots show CNY 0.01 and
  unconfirmed time/account; no bill was written until explicit selection and save.
  This is not a new physical-phone Alipay transaction test.
- Signed artifact: version code 4 / 0.2.2, 61,546,439 bytes, SHA-256
  `3fa448634fb3140d0ab6b787011d2d953a7c18a40fb24b6e9b4a14985c6647da`.
  The original Android signing identity and manifest key were retained locally.
- API 26 pre-publication acceptance used the exact previously delivered 0.2.1
  client, the locally signed candidate and the real in-app Android installer.
  System confirmation was inspected. Post-upgrade assertions passed for version 4,
  retained bill, raw dirty editor draft, theme/privacy settings, diagnostic scope
  and cleared installer transaction state. This step preloaded the candidate and
  does not itself prove public network downloading.
- Published with the existing atomic publisher. The public HTTPS manifest matches
  the locally signed bytes, with signature, identity and expiry verified. The
  canonical server APK has the expected full hash; its public 206 range response
  matches the corresponding signed local bytes. The old API 36 client independently
  discovered 0.2.2 through its normal update screen without injected files.
- SSH bulk uploads stalled. A standard protocol-31 rsync batch was replayed locally
  to the exact signed APK, transferred in bounded hash-checked chunks, and replayed
  only in private server staging. Both the full reconstructed hash and the signed
  manifest were checked before publication. No package/service was added, no
  private key was uploaded, and no global network setting was changed.
- Version-3 and version-4 temporary staging inputs were removed individually after
  publication; canonical releases remain. This resolves the older staging-cleanup
  item above. Receiver PID 67644 and restart count 0 were unchanged at publication.
- API 36 public-network acceptance passed from the exact old 0.2.1 APK. The first
  UI download encountered a recoverable network error. The opt-in probe then used
  that installed client's unchanged downloader and existing partial-file path to
  fetch and verify the entire public APK in 244.219 seconds. No local candidate was
  injected. The normal UI independently validated package/signature and completed
  Android's visible Update confirmation. Post-upgrade version-4 and all retained
  state assertions passed, including installer cleanup. This does not claim that
  the network is interruption-free or that its underlying fault has been fixed.
- Evidence: `work/build-logs/alipay-v4/`; signed local artifacts:
  `outputs/alipay-v4/`. Both API 26 and API 36 system-confirmation screenshots were
  inspected; app screenshots in the upgrade fixture remain intentionally blocked
  by the retained secure-screen preference.
