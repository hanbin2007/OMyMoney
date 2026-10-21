# Douyin and Registry Evidence

Package `com.ss.android.ugc.aweme`. The exact original `Douying` spelling is retained in IDs for traceability; it is not corrected by inventing new IDs. Registration comes from `aa.b` and names from `g9.a`.

| Page Type | Extractor / Matcher | Priority | Evidence |
| --- | --- | ---: | --- |
| DouyingTransferWaiting | k9.d / u9.d | 0 | Transfer page, waiting recipient, transfer timestamp/note, reminder and return-warning controls |
| DouyingTransferReceived | k9.c / u9.c | 1 | Transfer page, received state/typed recipient title, receipt and transfer timestamps |
| DouyingWalletBillDetail | k9.e / u9.e | 2 | Wallet WebView/electronic-receipt/expand marker, payment success, time/method/order fields |
| DouyingPaySuccess | k9.b / u9.b | 3 | Lynx `FlattenUIText` success, amount, method, payment time and completion fields |

Eight independent JSON fixtures plus edge tests cover every ID. Tests verify pending versus received, sender/recipient direction, explicit receipt-time preference, unknown dates, zero-width formatting, literal raw evidence, wrapped account hints, refunds and conflicting amounts.

## Decisions

- Waiting for another person is an outgoing pending candidate, not received income. A named other person having received money is outgoing spending. Explicit self-receipt is income; an unqualified `已收款` does not establish direction and remains unset.
- The received page uses its explicit receipt timestamp. An invalid/missing receipt timestamp is not replaced by the transfer timestamp or capture time.
- Zero-width formatting marks are removed for parsing, as observed in the original payment extractor. Non-sensitive raw evidence remains literal. Alphabetic account-name content is not indiscriminately removed.
- Wallet actual debit is separate from the discount. A complete `抖音支付(...)` account wrapper can be unwrapped as a hint, never as an automatically assigned account.
- Type-specific native payment controls remain type-specific; a loose success string from an unrelated view is not sufficient.

## Whole Registry

`RuleRegistry.enabledRules(packages, context)` uses explicit user-enabled packages and a fresh book/currency/timezone context. Empty consent enables nothing. Unknown packages, disconnected Taobao extractors, the WeChat password matcher and unused page-name constants are excluded.

The registry test compares every rule's ID, package and priority directly against the static connected inventory. It then runs every app's independent positive and negative cases through the production registry. All 56 connected analysis targets have a distinct implementation and a passing modeled fixture pair after B8, totaling 112 scenario cases. A valid positive may intentionally yield `NeedsReview`; fixture acceptance is not a claim that all fields or payment state can be known automatically.

`recognition-rule-index.json` is a mechanically generated source/fixture index, not a test-result generator. Its per-ID entries name the static source, fixture cases and verification test. Regenerate with `node scripts/recognition-index.mjs --write`, then run the core suite. Stage B14 adds pipeline/device acceptance separately.

No live version of any of the seven payment apps was tested. The 56 registered rules establish neither a live support percentage nor successful queue insertion or ledger save. Android capture, queue, overlay and OCR integration follow in B9-B14.
