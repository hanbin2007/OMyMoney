# UnionPay Recognition Evidence

Package `com.unionpay`. `aa.g` registers the following five scenarios and rejects `com.unionpay.activity.UPActivityMain`. The rewrite's optional `UiSnapshot.windowClassName` preserves that event metadata; B10 must populate it from accessibility events. The rule also rejects the modeled full home-navigation surface. Missing activity metadata is not evidence of a verified live page.

| Page Type | Extractor / Matcher | Priority | Field Basis |
| --- | --- | ---: | --- |
| UnionpayBillDetail | p9.b / y9.b | 0 | Typed customer-service view, bill title, success, payment method, order amount/time |
| UnionpayPaySuccess | p9.d / y9.d | 1 | Payment success, method and completion control |
| UnionpayTransferSuccess | p9.f / y9.f | 2 | Transfer success/detail, continue-transfer and completion controls |
| UnionpayTransferDetail | p9.e / y9.e | 3 | Typed customer-service view, successful transfer amount, source/destination and order context |
| UnionpayMessageOrderDetail | p9.c / y9.c | 4 | Transaction title, card, transaction timestamp, category and type |

Ten independent JSON fixtures cover a positive and negative for each ID. Edge tests cover all five missing-time cases, explicit actual payment versus order total, incoming versus cash-withdrawal direction, unsupported message categories, home-activity filtering and full-card-number redaction.

## Differences and Privacy

- Actual payment has priority over order total. An order total with discount information but no actual-paid field is unresolved; the original `p9.b` subtracts a parsed discount, which can double-count a discount if the displayed total is already net.
- `订单时间` is retained as separate order-time evidence, not asserted to be payment time. Only explicit payment, transaction, transfer or arrival timestamps fill transaction time. Unknown time is never the capture time.
- Message transaction categories map consumption to expense, credit to income and cash withdrawal to transfer. Other categories are rejected, matching the original's unsupported-type boundary. A message without explicit success still carries a payment-state review issue.
- Transfer candidates retain source/destination hints but never assume the destination belongs to the user or assign actual account IDs. User review may change the kind for a payment to someone else.
- Account evidence is normalized and any sequence of at least twelve digits, including spaced or hyphenated card text, is reduced to a four-digit tail. `FieldEvidence.redacted` explicitly distinguishes sanitized hints from full raw text. The shared hint path applies this to previous app rules as well. Full page snapshots are transient, not persistent logs.
- The optional window class participates in deterministic capture identity and both per-field and total size limits. It does not grant extra permissions or simulate a system event.

All coverage is static plus synthetic. No live UnionPay version, real card/payment, vendor-specific accessibility behavior or background execution was tested.
