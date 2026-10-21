# Local Backup Format

OMyMoney archive schema 8 is a ZIP containing `manifest.json`, `data.json`, and referenced private image files. It is not encrypted. Exports are initiated through Android's Storage Access Framework; the selected document provider may independently synchronize its files. The app does not request network permission or schedule cloud backups.

The manifest records the schema version, export timestamp, and SHA-256 and uncompressed size of every payload file. `data.json` contains the complete current set of books, accounts, categories, bills including soft-deleted history, postings, submission identifiers, deletion/undo records, preferences, editor drafts, and recognition queue entries including their evidence and state history. Images referenced by saved or deleted bills, raw drafts and queue entries are included. Unreferenced files, temporary exports, previous recovery archives, raw accessibility captures and diagnostic logs are not exported.

Schema 1 imports are explicitly migrated to an empty recognition queue. Schema 2 requires the queue field; a version 1 manifest cannot carry version 2 queue data. Queue identities, state projections, submission receipts, bounds and references are validated. Conflicting queue records prevent merge instead of losing unconfirmed work.

Schema 3 adds nullable credit-cycle configuration to accounts. Schema 1/2 archives without those settings remain readable; settings mislabeled as an older schema are rejected. Room schema 5 independently adds the nullable account column. Credit terms, account roles and active principal-settlement limits are validated. Different account settings conflict during merge. Disjoint settlements exceeding the shared source principal also conflict; replacement stays available for an individually valid archive.

Schema 4 adds directed manual exchange rates and per-bill historical exchange snapshots and fees. Room schema 6 adds `exchange_rates`; migration from schema 1 through 6 retains the ledger and starts with an empty rate table. Old archives may not carry new rates or saved foreign/fee postings under an older manifest version; raw recognized fee drafts remain supported. All actual postings retain their own currency and scale and must match the referenced account. A shared rate pair with different values or dates is an explicit merge conflict, never a silent last-writer choice. Null exchange fields are omitted from bill/draft transaction serialization to preserve legacy payload projections, submission hashes and saved recognition receipts. Editor raw fee/rate/actual/date text is included in drafts and may remain invalid without entering the ledger.

Schema 5 adds monthly/yearly budget plans and their persistent deletion markers, keyed by book and canonical period. Room schema 7 independently adds the `budgets` table. Schema 1-4 archives import with no budgets; budget records cannot be mislabeled with an older manifest. Validation checks the book, currency, expense category hierarchy, nonnegative limits and allocations against the unchanged total. Shared period records with different plans or deletion states conflict on merge. Replacement retains deletion markers, so restoring a deleted budget does not trigger automatic copying on next open.

Schema 6 adds recurring/installment plans and their pending/posted/skipped execution records. Room schema 8 independently adds `schedules` and `schedule_executions`. Schema 1-5 imports have no plans; older manifest versions cannot carry schedule data. Plan identities, catalog projections, immutable calendar anchors, contiguous execution cursors, deterministic plan/time occurrence IDs, pending editor drafts and posted submission receipts are validated together. Referenced plan/execution attachments are included. Divergent plans or execution states conflict on merge; replacement restores plans, cursors, drafts and bills in the same transaction, preventing already-executed installments from being generated again.

## Validation

Schema 7 adds savings plans and their manual or linked-transfer deposits. Room schema 9 adds `savings_plans` and `savings_deposits`. Older archives default to empty savings records and cannot carry them under an older manifest. Targets, currency precision, calendar bounds, canonical identities, projections and references are checked. Each transfer can be assigned to only one deposit across all plans; independently valid allocations of the same transfer cause an explicit merge conflict. Existing links to subsequently changed or deleted bills remain recoverable, but invalid live contributions are visibly excluded from progress. Savings records never add ledger postings or change account balances.

Schema 8 adds import receipts and batch action history. Room schema 10 adds `import_receipts` and `batch_actions`. Import receipts retain the original columns and parsed field text, original file hash, logical row number, target book, saved bill and import timestamp. Their deterministic identities preserve repeat-file suppression even for subsequently deleted bills. Batch history retains each member's original bill, post-action revision and any deletion tokens, with a 24-hour single-use undo boundary. Attachments referenced by historical batch members are included. Validation checks identities, projections, dimensions, referenced bills and revision/token consistency. Conflicting receipts and actions have separate merge conflict kinds. Replacement clears and restores these tables transactionally with the ledger; older archives default to empty lists, and cannot smuggle these records under an older manifest version. Unconfirmed import previews are private temporary staging, not business backup data.

- Maximum 16 MiB per JSON file, 20 MiB per image, 512 MiB total uncompressed data, and 10,000 ZIP entries.
- Only the two known JSON filenames and `attachments/<lowercase UUID>.<allowed extension>` are accepted. Duplicate entries, directory entries, traversal paths, absolute paths, unlisted payloads, missing files and checksum mismatches are rejected.
- Image MIME type and dimensions are decoded and checked against the attachment reference; maximum decoded dimensions total 64 million pixels.
- The typed payload is checked for duplicate identities, category depth/cycles, foreign references, indexed bill projections, exact posting calculations, account balances, submission and undo metadata, and the active-book preference.
- Entity identities and references use canonical lowercase UUIDs. Preference keys and composite draft/posting keys are not entity identities. Imported identifiers cannot introduce route separators, query arguments or reserved navigation names.
- A preview is a private, validated copy, not a retained provider URI. It expires after 24 hours. Restore revalidates the payload and checks that its digest and the current database snapshot have not changed since preview.

## Restore Semantics

Merge skips identical records and adds disjoint records. Existing local preferences win. Shared account balances are recalculated from opening balances and active bill postings. Different content under an existing ID prevents the entire merge; the preview identifies the affected records without choosing a winner.

A valid archive remains available for replacement when its merge would overflow an account balance. The preview reports the affected account and disables merge, without weakening exact balance validation.

Replace requires explicit user confirmation. Before modifying any row, it writes and syncs a private recovery ZIP containing the current data. The latest recovery can be exported from the same settings page and passed through the normal preview/restore flow. A subsequent replacement updates that one recovery archive; uninstalling the app removes it.

All row changes occur in one Room transaction. After the base snapshot and selected mode are checked, incoming attachment files are installed before committing references. Valid existing files are never overwritten or removed. A corrupt file referenced by current rows may be repaired only with validated incoming bytes bearing the exact same identity, path, MIME and expected digest. The corrupt bytes are first preserved in private quarantine; a synced temporary file atomically replaces them. This repair survives database rollback because the old rows already require those same correct bytes. Different expected content under an existing attachment identity, and mismatching unreferenced historical files, are rejected. Quarantine is not exported.

An interrupted restore may leave unreferenced private images, but rollback never deletes an image required by a committed record or the recovery archive. Reclaiming orphans requires a separate reference-aware operation.

Read, validation, and export operations can be cancelled. The confirmed restore transaction cannot be cancelled from the UI. Database transactions roll back on errors or process death; an interrupted external export can leave an incomplete document and is not reported as successful.

## Extending the Format

Device-local appearance, screenshot protection, privacy-lock preference and widget-to-book mappings are deliberately excluded from the business archive. Restore does not disable or replace the current device's privacy lock. A new device requires its own system credential and opt-in; the lock is not database or archive encryption. See `privacy-and-widgets.md`.

Room schema versions and archive schema versions are independent. Any new persisted record family added in stages B or C must be added to the snapshot, validators, merge policy and round-trip tests. A format change must explicitly preserve or migrate schema 1 imports rather than silently dropping new tables. Unknown archive versions are rejected before modification.

## Evidence

`BackupRoundTripTest`, `BackupPreviewTest`, `BackupStateTest`, and `BackupFlowTest` cover the contract. Emulator-only visual and actual system file-picker evidence is under `work/qa/a8`; this is not physical-device coverage.

Platform references: [Android Auto Backup](https://developer.android.com/identity/data/autobackup) and [ZIP path traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal).
