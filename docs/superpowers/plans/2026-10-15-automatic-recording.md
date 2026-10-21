# Automatic Recording Implementation Plan

> **For agentic workers:** Implement natively, task by task, using test-first changes and an independent final review. The user approved implementation and direct built-in publication on 2026-10-15.

**Goal:** Save successful recognized payments without confirmation, with one durable bill per payment screen, accurate lock status, detailed payment metadata, and a passive editable receipt capsule.

**Architecture:** Recognition retains conservative success/amount validation. A transactional capture entry point owns persistent window continuity, queue identity, automatic ledger writes, and missing-field enrichment. Unknown funding accounts remain unassigned (no invented cash posting); missing page time uses the observed capture time with explicit provenance. Saved receipts, not pending drafts, drive the capsule.

**Tech Stack:** Existing Kotlin, Room, coroutines, Jetpack Compose, signed HTTPS update channel. No new dependency or server service.

**Spec:** User approval in this document; diagnostic evidence in local `work/qa/log-review-20261015`.

## Global Constraints

- Missing payment time or funding account must not block normal recording.
- Source channel, counterparty, and funding account are separate fields. Never infer a cash account or a precise page time.
- Only succeeded payments with a positive unambiguous amount may be automatically saved. Pending, failed, refund-state, and ambiguous captures remain unsaved.
- Coalesce repeated reads of one active payment window, not all equal-amount transactions. A non-payment transition or conflicting order/time/amount starts a new payment.
- Display success only after the outer Room transaction commits. Lock/screen-off/privacy lock hides financial overlay content.
- Preserve old bills, drafts, settings, signing identity, and unrelated work. No destructive migration, unrelated restart, or secret upload.
- Publish via the existing atomic receiver only after same-key in-app upgrade and public manifest/APK readback.

## Review Focus

- Distinct same-amount payments in a reused window must both survive; repeated repaint events must not double post.
- An early incomplete receipt can gain metadata, but must not overwrite a user's edits or resurrect a deleted bill.
- Storage failure/cancellation/retry and process restart must never report an uncommitted success.
- Unknown-account bills must affect expense statistics without silently changing any asset balance; assigning an account later posts exactly once.
- Privacy transitions, large fonts, narrow/landscape displays, and multiple receipts must not expose locked content or steal payment input focus.

## Tasks

### 1. Payment Metadata and Unassigned Ledger Entries

Files: `core/.../model/Transaction.kt`, `core/.../ledger/PostingCalculator.kt`, `ExchangeRules.kt`, `core/.../recognition/DraftExtractor.kt`, `rules/AlipayRules.kt`, `data/.../LedgerMapping.kt`.

- [ ] Write failing core tests for unassigned recognized accounts, strict manual validation, payee extraction, and metadata round-trip.
- [ ] Run `:core:test`; verify the new assertions fail for the intended missing behavior.
- [ ] Add optional payment details and capture-time provenance with legacy-compatible defaults. Allow missing recognized funding accounts without creating synthetic assets or postings. Extract only bounded, evidenced counterparties.
- [ ] Run the core suite and backup/edit tests; verify an unassigned bill can be assigned, edited, deleted, and restored without incorrect balance effects.

### 2. Transactional Automatic Recording and Capture Continuity

Files: `data/.../RecognitionQueue.kt`, new `data/.../AutomaticRecording.kt`, `app/.../accessibility/CaptureCoordinator.kt`, `LedgerAccessibilityService.kt`.

Interfaces: `recordCapture(result: RecognitionResult, windowKey: String): QueueEntry`; `closeCaptureWindow(windowKey: String)`; existing `enqueue` remains the manual-review path for screenshot/import workflows.

- [ ] Add failing database/capture tests for repeated page repaint, restart, atomic failure, incomplete-time/account save, and two real equal payments.
- [ ] Persist bounded active-window pointers; compare semantic evidence rather than full node hashes. Store queue and bill in one transaction. Retain uncertain captures for review and use uniquely evidenced account matches only.
- [ ] Enrich missing saved metadata without changing user-edited bills. Never auto-save historical pending entries on upgrade.
- [ ] Run database and capture regression tests, including actual fixture snapshots and storage fault injection.

### 3. Accurate Runtime State and Stable Configuration

Files: `AccessibilityStatus.kt`, `LedgerAccessibilityService.kt`, `OMyMoneyApplication.kt`, `CatalogRepository.kt`, `AutomationSettingsRepository.kt`.

- [ ] Reproduce stale false lock status and repeated preference invalidation in tests.
- [ ] Refresh actual lock/interactive state on accessibility events and activity resume; defer screen-on rechecks without removing privacy checks.
- [ ] Avoid writing unchanged defaults and emitting/reapplying unchanged capture configuration.
- [ ] Verify unlocked recovery, true locked suppression, and idle configuration stability.

### 4. Saved Receipt Capsule and Detailed Bill UI

Files: `platform/overlay/DraftOverlayHost.kt`, new receipt presentation component, `BillDetailScreen.kt`, `BillEditorScreen.kt`, `MainActivity.kt`, `OMyMoneyApp.kt`, strings and UI tests.

- [ ] Add UI tests for committed receipt details, edit navigation, time provenance, unassigned funding, dismissal, and lock handling.
- [ ] Show compact non-focusable capsule after save, auto-dismiss with accessibility timeout, tap to edit the saved bill. Keep uncertain-review workflows separate.
- [ ] Display channel, counterparty, observed funding hint, selected book, and explicit estimated time. Missing fields do not require confirmation.
- [ ] Inspect actual emulator screenshots at normal/large fonts and portrait/landscape sizes; run independent UX and code reviews and fix actionable findings.

### 5. Verify and Publish

- [ ] Run full core, app unit, data/device, app/device, lint, offline, and release-verifier suites. Record skips and boundaries explicitly.
- [ ] Read current public stable version; increment versionCode and retain local signing identity.
- [ ] Build and verify release; exercise previous delivered release -> candidate through the in-app update/install flow, retaining bills, drafts, and settings.
- [ ] Locally sign the final manifest, atomically publish with existing resource-limited receiver, then read back HTTPS manifest and full APK hash/signature.
- [ ] Deliver concise release status, actual verification, and remaining real-device boundary.
