# Stage A Decisions

Ordered implementation and review rulings from the Stage A ledger. These are engineering tradeoffs, not additional claims of device acceptance.

- Ruling: This is a new project in an otherwise empty, user-approved output directory; initialize an independent repository on codex/local-ledger rather than creating a linked worktree of a nonexistent upstream. No existing main branch or unrelated checkout is modified. Cost if wrong: moving the new repository only.

- Ruling: Normalize A1/B1/C1 task headings to Task 1: A1/B1/C1 without changing their content, so the skill's task-brief helper can read them. Cost if wrong: document heading restoration only.

- Ruling: User explicitly selected the existing OMyMoney project at PROJECT_ROOT. Move source, plans, evidence, and isolated working data there; preserve target Git metadata and import the initial source commit. Rename label/root project/package to OMyMoney/app.omymoney.android. Cost if wrong: install identity changes; no user ledger data exists in this launch-only build.

- Ruling: Moving the plan into docs changed its workspace identity, so the helper created the -docs directory. This ledger carries the same plan's pre-migration rulings and verified A1 completion; continue in -docs without redoing A1. Cost if wrong: bookkeeping duplication only.

- Ruling: Persist each Bill's complete serialized payload alongside indexed Room projections; explicit ISO serializers preserve Instant nanoseconds and ZoneId, verified by file-database reopen. Account balances and operation records remain transactionally stored and foreign-key protected. Cost if wrong: future schema migration must reconcile projections with payloads.

- Ruling: Retain deleted bills and postings as history, reverse their active balance effect, and use a persisted single-use 24-hour undo token with revision checks. Cost if wrong: storage grows until a separately specified permanent-purge policy exists; no user history is silently removed.

- Ruling: Use stable Navigation Compose 2.9.8 (official release notes checked) for the growing multi-screen back stack rather than hand-rolling restoration. Preserve the approved SDK/Compose baseline, pin and verify the added dependency. Cost if wrong: a later dependency migration rather than bespoke navigation repair.

- Ruling: Catalog UI uses explicit archive/restore, preserving all history; account opening balance and currency cannot be changed after reference by a bill, and metadata edits never trust a stale UI balance. New books get editable baseline categories, not sample transactions. Cost if wrong: users must use the later balance-adjustment workflow instead of rewriting historical opening values.

- Ruling: Persist raw editor input separately from validated TransactionDraft and remove a submitted draft in the same Room transaction as LedgerRepository.save. Keep each book's manual draft separate, and serialize autosave jobs in application lifetime so navigating back does not cancel queued writes. Cost if wrong: extra draft schema/table and explicit persistence error states rather than silently dropping malformed input.

- Ruling: A7 uses the selected book's timezone for list grouping and inclusive calendar-day filters, converting boundaries to instants; details retain and show each bill's original recorded timezone. Cost if wrong: later accounting queries must keep the same display/filter convention when extending statistics.

- Ruling: Import only content URIs selected by the user, copy to private UUID files, cap at 20 MiB and 64 million pixels, whitelist decoded PNG/JPEG/WebP/GIF, and verify SHA-256 on read. Keep existing attached files when removing references so deleted-bill history/undo is not broken; orphan cleanup requires a later reference-safe operation, not eager physical deletion. Cost if wrong: unused private images can occupy storage, but referenced history is not destroyed.

- Ruling: A8 archive schema 1 is independent of Room schema 3. Export every current table as deterministic JSON plus a SHA-256/size manifest and only referenced private images, with 16 MiB JSON, 20 MiB per image, 512 MiB total and 10,000 ZIP-entry caps. Future B/C persisted entities must extend the archive schema and validation, rather than silently omit new data. Cost if wrong: version migration must be supplied for newly added record families.

- Ruling: MERGE accepts identical existing records and disjoint new records, keeps current preferences, recalculates shared account balances, and rejects any conflicting same-ID content. REPLACE requires an unchanged database snapshot since preview and writes a private before-replace ZIP before one Room transaction replaces all rows. Previews expire after 24 hours, are app-private and checksum-revalidated. No physical image deletion on replace or failed commit: interrupted operations may leave unreferenced files but never delete old recovery/undo images. Cost if wrong: private orphan storage can grow until reference-safe cleanup is implemented.

- Final: Ruling: reviewer set aside independent runtime/build/screenshot claims and physical/API26/API36/TalkBack/predictive-back coverage. Parent owns the current emulator gates; untested environments remain explicitly pending C10. Cost if wrong: platform-specific behavior is not inferred from API34 evidence.

- Final: Ruling: B/C feature gaps and advanced types are outside this stage, not outside the overall task. Continue subsequent plans after A9. Cost if wrong: treating this staged APK as complete would misrepresent coverage; manifests and acceptance notes explicitly prevent that.

- Final: Ruling: exhaustive third-party licensing and dependency provenance were not re-audited by the reviewer; checksum pinning and the known launcher asset license are present, with final distribution audit retained for C10. Cost if wrong: a pinned dependency can still have licensing or supply-chain issues; checksums are not a legal/security certification.

- Final: Ruling: power-loss durability across filesystem operations/SQLite was not fault-injected; transaction rollback and process-restart tests are evidence, not a claim of hardware power-loss testing. Cost if wrong: extreme storage failure may require external backups.

- Final: Ruling: partial-image decoder behavior beyond bounded format/dimension checks has no concrete reproduction; retain current bounded validation and runtime decode error handling, without claiming a full image-content security audit. Cost if wrong: a malformed supported image could still be rejected only when viewed.

- Final: Ruling: stale book timezone was investigated and not a finding because BillListScreen.updateBook runs on changed Book values. Orphan retention, soft-deleted history and one replace snapshot remain the documented policies. Cost if wrong: retained unreferenced files consume storage and a subsequent replacement supersedes the prior recovery snapshot.

- Final: Ruling: unknown historical image collisions and differing expected content under one attachment UUID are rejected rather than overwritten. Only corruption matching current references is repaired, and repair is deliberately not rolled back with rows. Cost if wrong: a colliding historical file needs an explicit future recovery workflow; preserving existing data takes precedence.

- Final: Ruling: finishing/integration is deferred until the overall A/B/C request is complete; keep the codex/omymoney branch in place and do not merge, push or stop at A. Cost if wrong: staged code remains on its named local branch rather than being prematurely integrated.

Deferred Minor findings from the final whole-branch review: none.
