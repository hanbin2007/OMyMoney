# Stage B Decisions

Ordered implementation and review rulings. Device acceptance is recorded separately in acceptance-B.md.

- Ruling: RecognitionContext explicitly supplies active book/currency/timezone to rule factories, with an unassigned default context for isolated factory use. This resolves PageRule.extract(snapshot) producing a TransactionDraft that otherwise lacks book identity; add optional factory parameters without removing the planned no-arg API. Cost if wrong: registry construction must pass current catalog context on each recognition rather than cache a stale book.

- Ruling: Reject truncated/oversized snapshots (1024 nodes, 4096 characters per field, 65536 total) instead of silently interpreting a prefix that may omit failure/payment-state text. Unknown or invalid dates and DST gaps/overlaps remain unset; duplicate identical amount evidence is allowed but conflicting values are not guessed. Cost if wrong: oversized or unusual pages require manual entry until an explicit safe adapter is added.

- Task 2: Ruling: group-packet direction may be null; withdrawal application is pending, and absent page times stay unknown. This replaces unsafe original direction/clock assumptions with explicit review issues. Cost if wrong: more manual confirmation, never fabricated payment status or time. Dedicated recharge/withdrawal rules are not shadowed by generic bill matching.

- Task 2: Ruling: preserve explicit service fees in TransactionDraft now, without changing Stage A posting behavior in B2; fees are still rejected by the Stage A calculator until planned C1 support. Discounts are not fees and are never double-subtracted. Cost if wrong: fee-bearing candidates cannot save until C1, rather than silently losing the fee. Merchant/account/order/state evidence remains local and separate from assigned catalog IDs.

- Task 3: Ruling: B3 owns pure event-scoped AlipayCaptureSession and delay constants; B9 owns cross-event durable deduplication and B10 owns real scheduling/cancellation. At most one recognized candidate emits per event, and fallback cannot overwrite a fast candidate. Cost if wrong: a fast partial candidate may need more user correction, while no second bill or stale-window result is created.

- Task 3: Ruling: explicit payment time wins and creation-only time remains unknown; explicit actual debit wins over order total and coupon adjustments. Balance/principal recharge is a transfer, but explicitly labeled telecom recharge is spending rather than a fictional own-account movement. Cost if wrong: unknown layouts need manual correction instead of silently corrupting direction or payment time.

- Task 4: Ruling: future automatic debit remains pending even when fulfillment says transaction complete; only explicit paid/completed transaction time is used, not order/group creation time. Group price minus discounts is not a reliable replacement for actual payment. Cost if wrong: more fields require manual confirmation instead of fabricating a payment or double-discounting it. Cancellation action text is distinguished from an actually cancelled order.

- Task 5: Ruling: UiSnapshot gains optional windowClassName so the original main-activity exclusion is representable; B10 must pass event class data, and limits/identity include it. Order time is kept separately, not claimed as payment time. Cost if wrong: unknown metadata/time requires review rather than assuming homepage absence or a completed payment instant.

- Task 5: Ruling: FieldEvidence gains default-false redacted metadata; shared account-hint extraction masks full long card numbers to four trailing digits. This intentionally replaces literal raw account text before persistence. Cost if wrong: exact full-card matching is unavailable, which is preferable to logging sensitive card data.

- Task 6: Ruling: merged wallet evidence can contain card data even in the amount field, so sanitize all non-order evidence and generated notes, not only account hints; explicit account/order hints retain their extracted values rather than whole merged nodes. Cost if wrong: redacted proof loses some literal context, but marks that change and avoids persisting full cards. Explicit order IDs remain distinct for future deduplication.

- Task 6: Ruling: support plain, colon-ended and inline labels within the same parent. Actual payment beats product price/total; creation time is not payment time; fulfilled-but-unproved payment and refunds remain reviewable. Cost if wrong: unusual layouts or missing payment evidence require manual correction rather than invented money/time.

- Task 7: Ruling: prevent generic voucher/order rules from shadowing explicit groupon or wallet surfaces. Use bounded same-parent character sequences, and longest-label matching for overlapping paid-amount labels. Cost if wrong: a changed layout may require manual entry rather than selecting a wrong scenario or amount.

- Task 7: Ruling: usage/fulfillment is separate from payment state; future debit is pending, explicit automatic-debit success is settled, refund requires review. Do not turn order creation, voucher use or showtime into payment time. Cost if wrong: some supported layouts yield partial drafts instead of guessed settlement/time. Standalone takeout and the unused errand constant remain unsupported.

- Task 8: Ruling: named-other receipt is outgoing, explicit self-receipt is incoming, unqualified receipt has unknown direction. Invalid receipt time is not replaced by transfer/capture time. Parsing removes zero-width formatting while literal nonsensitive evidence stays intact. Cost if wrong: ambiguous direction/time requires user review rather than inventing income.

- Task 8: Ruling: the production registry requires an explicit enabled-package set and fresh RecognitionContext. All 56 registered IDs have fixture pairs, but the generated index records source/fixture provenance, not live support or runtime-save success. Cost if wrong: unregistered layouts remain manual; no count inflation or false device-acceptance claim.

- Task 9: Ruling: AccountMatcher accepts optional field evidence because TransactionDraft has no card/name hint property; two-argument callers remain valid. Suggestions never assign accounts, including a unique highest score. This preserves evidence boundaries; cost if wrong is an extra manual account choice.

- Task 9: Ruling: Exact order repeats retain the first queued candidate and do not overwrite editing or payment-state warnings. Similarity is never automatic ignoring, including previously ignored unknown-time candidates. Queue defaults are 1000 active, 10000 total, 30-day explicit expiry without deletion, 256 KiB per entry and 512 state events. Over-limit work raises a visible error rather than evicting records; cost is explicit review/cleanup at capacity rather than silent loss.

- Task 9: Ruling: Adding recognition persistence also increments complete-backup format to version 2 and Room to version 4, validates queue receipts, includes queue attachments and conflicts, and explicitly migrates schema 1 archives to an empty queue. B10 handles short-lived event coalescing and B11 presentation policies; queue-level identity and save idempotency remain mandatory regardless of presentation preferences. Cost is a larger local backup and additional migration coverage.

- Task 10: Ruling: RecognitionContext is loaded without manual-editor account/time defaults, persistent settings default all capture OFF, and node reading excludes editable/sensitive/invisible/foreign subtrees in addition to password fields. Cost if wrong: some custom payment layouts yield no candidate, never silent account/time selection or insecure reads.

- Task 10: Ruling: service scheduling uses a single worker and a conflated stream, with immediate event-generation invalidation on the callback thread. A faithful single-worker blocked-IPC test reproduced two emissions instead of one; it passes after invalidation. Time, node and character bounds plus fresh before/after policy checks fail closed. Cost if wrong: very fast page changes may need reopening the page instead of reusing stale content.

- Task 10: Ruling: native permission tests are explicit opt-in and restore the service to revoked; ordinary regression skips them. Synthetic payment cases and real Android service binding are separate evidence, not live third-party payment-app acceptance. Cost if wrong: vendor/device behavior remains unverified until dedicated real-device tests. Detailed local evidence and Android API references are in docs/accessibility-capture.md.

- Task 11: Ruling: reuse BillEditorScreen/ViewModel for recognition instead of implementing a second editor. Queue input is persisted atomically with raw EditorDraft, preserving incomplete amount text; explicit save uses the queue's idempotent transaction. Close returns to pending, Ignore keeps recoverable edits, and owner disposal releases EDITING without deleting the candidate. Cost if wrong: shared editor regressions require the full Stage A test suite, now included in the gate.

- Task 11: Ruling: the auto-presented surface is a nonfocusable, amount-free count banner; editing expands only after user action. New candidates never replace the current owner/editor. The service window uses FLAG_SECURE and embedded account/date panels, not Activity-only dialogs or background Activity launch. Attachment picking is available via an explicit in-app handoff. Cost if wrong: one extra tap for attachments, rather than invalid window tokens or unsolicited focus capture.

- Task 11: Ruling: ignore-prompt and skip-similarity are presentation policies only. Exact identity and save idempotency cannot be disabled; default comparison requires an explicit new-bill checkbox. In-app queue remains available even with denied window/notification permission. Similar comparisons show source, merchant/order evidence and account names, limited to twenty on-screen comparisons. Cost if wrong: crowded history needs opening recognition records, never silent duplicate saving.

- Task 11: Ruling: first explicit notification request uses the runtime dialog, subsequent attempts open system settings; current notification grant and channel state are re-read on return. The local asked flag is permission UX metadata, not ledger data, and is deliberately not part of portable backup. Cost if wrong: reinstall/restore may show one extra permission prompt, never grant access automatically.

- Task 12: Ruling: use the official bundled Chinese ML Kit 16.0.1 dependency (verified against current primary docs), with all OCR models in the APK and INTERNET explicitly removed from merged Manifest. No remote provider, original backend, AI UI, model download or dynamic-install path is introduced. Cost if wrong: larger universal APK and library licensing/version maintenance; runtime model availability is additionally checked with no active network.

- Task 12: Ruling: only content URIs, PNG/JPEG/WebP, ten images per batch, twenty MiB, thirty-two million source pixels and bounded downsampling/OCR text. Explicit Start, rotate, cancel, failure and queue outcomes are separate. No screenshot attachments or persistent grants by default; temporary files are deleted, including interrupted files on next application startup. Cost if wrong: some very long screenshots require cropping before import rather than unsafe memory growth.

- Task 12: Ruling: exported share Activity accepts image URI data only and clears external extras before creating saved-state defaults. Existing queue IDs alone may open the main review, never inject transaction fields. Cold-start UI test reproduced navigation before the graph existed; it now waits for a back-stack entry and dispatches navigation on Main. Cost if wrong: one frame of startup latency rather than an invalid route/crash.

- Task 12: Ruling: native OCR misread the two-line synthetic label as "实付金額颜12.30". The draft correctly retained unknown amount; use the five-line known fixture for success and retain the observed misspelling as a core negative case. Generic "交易成功" also leaves type unknown. Cost if wrong: more manual correction, never invented success/direction or fuzzy amount selection.

- Task 13: Ruling: reserve only minimized existing fields and redacted field evidence, with a strict JSON adapter and a pure validated-copy result. Unknown/ambiguous amounts cannot be completed by enhancement, nor can direction, payment status or date be changed. Allowed IDs are rechecked against the latest caller-supplied catalogue, versions, identity and manual-field masks. Cost if wrong: a future provider needs a deliberately reviewed contract extension, not permission to fabricate ledger fields.

- Task 13: Ruling: coroutine timeout/cancellation protects caller state but cannot forcibly terminate arbitrary blocking provider code; provider implementations must cooperate and own resource cleanup. NoOp is the only installed provider, and no network/model dependency was added. Cost if wrong: a future non-cooperative provider could delay cancellation and must not be shipped without dedicated resource tests.

- Final: Ruling: fee-bearing drafts/advanced kinds remain reviewable but cannot save until Stage C enables their validated postings; the current app never guesses fee handling. Cost if wrong: one extra manual/deferred step, not a wrong balance.

- Final: Ruling: live third-party layouts, OEM restrictions, API 26/36, physical devices, TalkBack and hardware keyboards are not independently covered by this read-only review; Stage B claims only actual API 34 emulator/native gates plus fixtures, broader runtime audit remains C10. Cost if wrong: untested vendor behavior may need follow-up rather than being mislabeled compatible.

- Final: Ruling: non-cooperative future providers are outside the installed NoOp implementation and carry an explicit cancellation contract; no SDK is shipped. Cost if wrong: any future provider still needs a separate resource/cancellation gate.

- Final: Ruling: root owns new B14 acceptance/coverage artifacts beyond reviewed HEAD and will run full suite/native/readback gates after fixes; no second reviewer is dispatched. Cost if wrong: final acceptance additions have author testing rather than a second independent source review.

- Final: Ruling: exact source identity is evaluated against immutable capture evidence, while similarity uses the latest pending draft or current undeleted saved bill. Deleted saved entries remain durable identity receipts but are not similarity candidates. Cost if wrong: old ambiguous identity receipts may still require explicit manual review; deleted bills are never silently resurrected.

- Final: Ruling: provider transaction, platform order and merchant order evidence now have separate keys; merchant-only exact matching also requires matching known merchant. Conflicting values within one namespace remain ambiguous. Old backup evidence is preserved as recorded rather than guessing its missing namespace. Cost if wrong: pre-change ambiguous records can yield a reviewable extra candidate, never a guessed identity merge.

- Final: Ruling: explicit currency markers are checked against original evidence and referenced nodes even if extraction removed the symbol. Conflicting/unsupported markers leave amount unknown; unmarked amounts still use the caller's selected currency. Cost if wrong: conservative rejection can require manual correction, rather than silently relabeling explicit money.

## Deferred Minors

- Task 11: minor (deferred): inline date editor reports format/range failures with the same message as a DST gap; all invalid values remain rejected. Independent review found no overlapping save/date controls at 200-percent text. TalkBack, hardware keyboard and complete system-channel-disabled recovery remain unmeasured, not claimed as passed.

- Final: minor (deferred): screenshot list has sequence numbers but no thumbnail/file-name preview, and rotation has textual angle feedback only. Existing native images show no overlap; source images are not retained by default.

- Final: minor (deferred): OCR storage failure shares the settings-update failure wording. Failure remains visible and does not mark the item saved.

- Final: minor (deferred): overlay minute-level time editor confirmation resets existing seconds to zero; review/comparison already displays seconds. Explicit minute editing remains valid but timestamp-preserving polish is not included in this fix pass.
