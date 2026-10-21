# Recognition Queue Contract

Recognition is not a ledger write. A recognized result receives a durable queue identity and a `RECOGNIZED` event followed by `PENDING`. Editing, cancellation back to pending, failed validation, ignoring, expiry and successful saving are separate recorded states. Only an explicit `Save` outcome calls the ledger, in the same Room transaction that writes the receipt and removes the associated editor draft. Concurrent repeated confirmations share one submission ID.

## Duplicate Evidence

- The same submission ID in the same book is an exact repeat. An explicit, unambiguous order identifier in the same source package and book is exact only when amount, kind and merchant do not conflict.
- Same amount and kind within 60 seconds is only similar. Different merchants and identifiers are displayed as differences, never silently suppressed.
- When either transaction time is unknown, two known capture times within one hour can establish similarity, not identity. A missing capture time cannot create a permanent cache entry.
- Pending, ignored, expired and saved queue entries remain available as history. Nearby saved manual bills are also checked. Exact repeat events return the original entry without overwriting current editing or review state. Later page evidence does not silently upgrade a pending-payment candidate into a saved bill.
- Raw accessibility trees are not stored. Bounded field evidence is stored locally, with card-like long numbers redacted; explicitly identified order numbers remain available as scoped identity evidence.

## Bounds And Recovery

Defaults: 1,000 active entries, 10,000 total entries, 256 KiB per entry, 256 evidence fields, 4,096 characters per field and 512 state events per entry. Capacity errors are explicit; no entry is evicted to make room. Pending and failed entries can be marked expired after 30 days without deleting them. Open editing is not expired underneath the user. Expired and ignored records require an explicit reopen before saving, subject to active capacity. Process restart preserves all states and original evidence.

Account matching returns suggestions, never account assignments. Exact names/aliases, card tails, similar names and weak source-app wallet hints carry separate reason codes. Conflicting card tails remain visible. Archived and incompatible-currency accounts are excluded; ties remain ambiguous.

Room version 4 adds `recognition_queue`; archive version 2 includes its full state and attachment references. Version 1 archive imports explicitly migrate to an empty queue. Queue conflicts block merge, and a confirmed replacement retains the old queue in the recovery archive.

Evidence: `DeduplicatorTest`, `AccountMatcherTest`, `RecognitionQueueTest`, `LedgerMigrationTest`, and `BackupRoundTripTest`. These are synthetic/JVM and dedicated-emulator tests, not compatibility claims about third-party payment applications.
