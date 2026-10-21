# Pending Popup Fix: Build 8

## Evidence and Scope

The uploaded-log audit read 1,823 eligible retained batches (191,238 records), validating each batch's length, SHA-256 and record count. Record timestamps span 2026-10-20 10:55:38 through 2026-10-21 03:45:54 UTC. This is retained server coverage, not all historical phone events. No private notification content or contact identifiers are reproduced here.

Ordinary WeChat chat/link messages, an Alipay promotion and a security notice incorrectly became pending review drafts. The generic keyword gate matched chat wording and even the payment characters in the Alipay brand name. Unknown templates also became drafts without a completed-transaction declaration. Separately, the overlay host automatically offered old pending entries after unlock, leaving the app or reconnecting the service. The inspected notification samples were each received once; they do not establish repeated delivery of the same notification. A genuine small-amount notification was present, so an old pending review must not be described as proof of a newly occurring transaction.

## Changes

- Exclude ordinary WeChat chat titles and the Alipay brand name alone from financial candidates. Continue existing secret redaction and consent limits.
- Require completed-payment wording or the observed explicit transaction declarations before creating a review draft. Unknown financial templates may remain diagnostic samples, but do not enter the review queue.
- Recognize the observed WeChat paid, Alipay expense and automatic-debit forms. Notifications remain manual-review-only and never use notification posting time as transaction time.
- Remove automatic pending-draft overlay selection. Existing pending history and local bills are not deleted or rewritten. Manual review remains available under recognition history; fresh automatically saved bills retain their compact receipt.

## Verification and User Waiver

The new core/coordinator regressions failed against the old implementation. A real Accessibility overlay device regression also failed because a pending draft appeared automatically. After the fix, 232 core JVM tests and 14 app JVM tests passed. Two API 36 device tests passed, covering quiet pending history across unlock, pause/resume, service reconnect and app return; manual review access; unchanged balances; and the retained saved-bill receipt, lock hiding and edit action.

The signed release build and its ten manifest guard cases passed. The already-running debug lint also completed successfully. Build 8 uses the existing Android signer, and its update manifest was signed locally. The previous public Build 7 accepted the candidate as verified in its update UI after the retention fixture was prepared. Android installation permission was not changed: the security reviewer blocked the temporary-emulator permission toggle, and the user explicitly requested skipping the remaining tests and publishing directly. Therefore no completed Build 7 to 8 system installation or post-upgrade data-retention acceptance is claimed for this release. The exclusive emulator was shut down without enabling that permission.

APK: 61,603,783 bytes. SHA-256: `9bdea9970d08ca1350661c0385dff09cf91d86043c04a616eadc71ce966ecded`.

## Publication

The existing receiver's atomic publisher published Build 8. The public HTTPS manifest matches the locally signed envelope byte-for-byte, and an independent signature check passed. A complete public HTTPS APK download returned HTTP 200 with 61,603,783 bytes and the exact expected SHA-256, matching the canonical server APK. This is release-file integrity, not a completed client installation. Receiver PID remains `67644`, `NRestarts=0`, with its existing 256 MiB memory limit and 20% CPU quota; no service was restarted or added.

Evidence remains local under `work/qa/popup-20261020/`; no private keys or customer log contents are included in release uploads.
