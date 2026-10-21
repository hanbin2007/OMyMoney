# Standalone Meituan Takeout Recognition

Native extension for `com.sankuai.meituan.takeoutnew`, now included in the published
0.2.4 (Build 6) APK. This is separate from the historical seven-package, 56-scenario
static inventory, and is not a claim that the original APK registered standalone
takeout. The signed public manifest, local APK and canonical server APK were read
back with SHA-256 `662ae3713e7c9a075ef207a12a735f0162843609a9e93b4e7eda565b34820751`.
Published code and collection capability are not live takeout compatibility proof.

## Scope

- An independent "美团外卖" bookkeeping toggle, off until explicitly selected.
  Enabling main-app financial recognition does not enable standalone financial
  recognition. Diagnostic sampling follows its separate, already-consented scope.
- `MeiTuanTakeoutPaySuccess`: explicit payment-success status, a bounded amount
  and a receipt navigation marker. Native text and WebView nodes are both eligible.
- `MeiTuanTakeoutOrderDetail`: order-detail context, order-number field and an
  actual-paid/total field. Totals alone never become actual payments.
- Extract actual payment, explicit payment time, labelled merchant, payment method
  and order identity. Discounts are not subtracted twice. Order-creation/delivery
  times never silently become payment time. A success receipt without a payment
  timestamp uses the existing, explicitly marked captured-time fallback.
- Unknown payment state, future debit, refunds, conflicting amounts and invalid
  time remain reviewable. Password and truncated snapshots remain unsupported.
- Receipt-to-order transitions enrich one saved bill. Order IDs distinguish two
  equal payments, and takeout checkout navigation ends the preceding capture.
- A nearby equal payment already saved from another accessibility package makes
  the takeout return reviewable, and the reverse order is guarded too. It is not
  silently merged or automatically posted again: amount and timing do not prove
  that two packages describe the same purchase.

## Evidence

`core/src/test/resources/recognition/meituan-takeout/scenarios.json` contains
independently written synthetic positive and negative examples for both rule IDs.
These are not captured from a particular live takeout version.

```sh
sh work/run-gradle.sh --offline :core:test :app:testDebugUnitTest
ANDROID_SERIAL=emulator-5564 sh work/run-gradle.sh --offline \
  :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.data.MeituanTakeoutRecordingTest
ANDROID_SERIAL=emulator-5564 sh work/run-gradle.sh --offline \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.MeituanTakeoutCaptureTest
```

The device tests inject synthetic snapshots into the real coordinator/Room queue;
they do not execute a third-party payment. Real page structure, target app version,
phone background delivery, and live cross-app payment navigation remain unverified.
The user selected in-app accessibility diagnostics for real evidence instead of
manual screenshots. Do not request a new purchase or payment. Do not silently
enable recording that the user has stopped, or bypass protected Android nodes.

## In-App Evidence Collection

- Reuse the existing diagnostic consent, recording switch, bounded spool and
  paired HTTPS upload. Do not add a second collector, backend service or consent
  switch. Stopping recording still stops new content immediately.
- Build 6 includes standalone takeout in `DiagnosticState.packages` via the rule
  registry. The diagnostic worker captures selected-app events independently of
  bookkeeping enablement and whether any financial rule matches the page.
- Collect readable node text/descriptions, class names, resource IDs and parent
  indices with package/window/time, truncation and prune metadata. Unknown layouts
  remain useful evidence; successful recognition is not a prerequisite.
- Existing password/login-window, editable/sensitive subtree, foreign-package,
  lock-screen, credential and size/time exclusions stay in force. System-protected
  content is not bypassed. This does not claim all personal information is masked.
- The phone must actually run Build 6 or later, have Accessibility connected and
  recording enabled. Existing pairing supplies upload credentials; no new pairing
  is required if it remains valid. Continuous debug uses its existing visible
  notification and Stop control, and does not silently resume after interruption.
- Open an existing takeout order/detail page normally. No screenshot, new order,
  fresh payment, notification-access grant or bookkeeping toggle is required for
  accessibility diagnostic collection.

### Initial Development Verification

- Core JVM suite: 229 tests, zero failures/errors/skips, including 12 takeout tests.
- App JVM suite: 10 tests, zero failures/errors/skips.
- API 36 `ledger36` data suite: 150 tests, zero failures/errors/skips, including
  three takeout recording/consent/cross-app duplicate tests.
- API 36 capture instrumentation: `MeituanTakeoutCaptureTest` and the existing
  `AutomaticCapturePipelineTest` returned `OK (2 tests)`. The takeout test covers
  all four checkout markers and repeated equal amounts.
- Development app/instrumentation APK compilation and Android lint succeeded. The complete
  app instrumentation suite, live third-party payment and same-key update
  acceptance were not run for this development extension.

### Published-Build Collection Verification, 2026-10-19

`MeituanTakeoutDiagnosticSystemTest` returned `OK (1 test)` on two consecutive
final runs against the exact public Build 6 APK, not a replacement development
APK. The fresh dedicated emulator used Android API 36.

- A visibly labelled synthetic fixture emitted real Android accessibility events
  under the standalone package. Its deliberately unrecognized page was captured
  while bookkeeping was disabled, with text, class, view ID and parent indices
  linked to diagnostic snapshot metadata and exportable through the existing spool.
- Closing recording prevented a second page's sentinel from entering exported
  diagnostics. Queue and bill counts stayed unchanged. The emulator was unpaired,
  so no synthetic sample was sent to production.
- Both page sentinels were independently checked in the visible accessibility
  tree. The harness restarts only the version-labelled synthetic fixture for each
  page, so Activity reuse cannot make the stopped-recording assertion vacuous.
- The isolated harness pre-authorized only its own accessibility service. Android
  instrumentation force-stops its target, so the test rebinds only that already
  authorized service. This is not a permission grant or lifecycle change on a phone.
- The public signed manifest matched the local release envelope. The local and
  canonical server APK hashes matched; a public HTTPS 206 range matched the same
  local bytes. No APK, key, credential or service configuration was published or
  modified in this follow-up. Build 6's in-app upgrade evidence is recorded in
  `notification-capture.md`.
- The matching debug target then passed all 21 collection/privacy/runtime/recovery
  checks (`OK (21 tests)`). Core 229 and app JVM 10 tests also passed.

Initial verification attempts exposed harness issues, not a production-code fix:
the target's instrumentation force-stop required a pre-authorized service rebind;
the fixture reused an Activity without changing text; and Android package
visibility prevented an app-context fixture-version lookup. The final test checks
the fixture version through the test shell and checks the actual displayed page.

An exploratory mixed-variant run also failed these `AccessibilityCaptureTest`
cases with `NoSuchMethodError` because debug-compiled Kotlin internal helpers were
run against release-mangled methods. All were rerun successfully on the matching
debug target, without changing production visibility or privacy filtering:

- `restrictedWindowPreservesRecognitionSnapshotButNeverSerializesItsNodes`
- `passwordEditableSensitiveAndForeignSubtreesAreNeverRead`
- `privacyChangedDuringTraversalIsRecheckedBeforeSerializingNodes`
- `prunedNodesHaveReasonsButNeverSecretValues`
- `boundedReadsFailClosedInsteadOfRecognizingPartialPages`

A read-only, time-bounded check of the latest 12 receiver batches found 701 Build 5
records and no standalone takeout package records in that scope. The newest batch
was received at `2026-10-18T06:09:40Z`. A sampled old runtime at
`2026-10-18T05:38:36Z` reported Accessibility disconnected. This is historical
server evidence, not the phone's current permission/connection state. New real
takeout layouts and successful recognition still await phone evidence.

## Release Gate

For future production changes, publish only after regression checks and a same-key, in-app upgrade preserving
bills, drafts and settings. Sign manifests locally, use the existing atomic
publisher, and read back the public HTTPS manifest and APK integrity. Development
APK compilation or these fixtures alone are not in-app delivery.

This follow-up reuses Build 6's existing collector and changes only verification
and documentation. It does not create a redundant Build 7 or claim a new release.
