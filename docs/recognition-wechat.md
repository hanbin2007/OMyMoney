# WeChat Recognition Evidence

Status: independently implemented, synthetic-fixture verified only. No live WeChat version or real payment was tested. Package: `com.tencent.mm`. Registration follows `aa.h` in the supplied APK and the inventory in `qianji-static-inventory.json`. The APK is a behavior reference, not an implementation dependency; its code and assets are not shipped.

| Page Type | Extractor / Matcher | Priority | Evidence Used |
| --- | --- | ---: | --- |
| WechatPersonalRedPacketSend | q9.h / z9.i | 0 | Owner title and inline sent-packet amount or count/total |
| WechatGroupRedPacketDetail | q9.d / z9.d | 1 | Owner title, group progress, amount before yuan unit, image navigation |
| WechatPersonalRedPacketReceive | q9.g / z9.g | 2 | Owner title, standalone decimal and explicit yuan amount |
| WechatPersonalRedPacketReceive2 | q9.f / z9.h | 3 | Owner title, amount before yuan unit, image navigation |
| WeChatTransferDetail | q9.i / z9.j | 4 | Outgoing title, current state, transfer time and payment method |
| WeChatTransferInDetail | q9.k / z9.l | 5 | Merchant incoming title, payer, receiving method and transfer time |
| WeChatBillDetail | q9.b / z9.b | 6 | Current state, transaction-time labels, actual payment labels |
| WeChatTransferDetailWaiting | q9.j / z9.k | 7 | Waiting/received title, amount and explicit date or time label |
| WechatWithdrawSuccess | q9.m / z9.n | 8 | Withdrawal application, amount and destination bank |
| WechatWithdrawDetail | q9.l / z9.m | 9 | Withdrawal detail, application and arrival fields |
| WechatChargeDetail | q9.c / z9.c | 10 | Recharge title, completed state, recharge time and payment method |
| WeChatPaySuccess | q9.e / z9.f | 12 | Success label plus amount; excludes ordering-home surfaces |

`z9.e` at priority 11 is a password matcher, not a registered extractor. It is intentionally absent. A page containing payment-password text or all four main-home navigation labels is rejected before rule matching. The later accessibility reader must additionally remove password nodes and descendants.

## Independent Safety Decisions

- A recognition result never writes the ledger. Even `Matched` is a candidate requiring user review, account assignment and ledger validation.
- Missing transaction time remains `UNKNOWN`. The original `q9/e$b.smali` substitutes the system clock on completion; this implementation never does that. Capture time is only recognition metadata.
- All twelve rules have a positive and a negative independently authored JSON snapshot in `core/src/test/resources/recognition/wechat/scenarios.json`. The tests also cover conflicting amounts, discounts, inline evidence, description-only nodes, reordered fields, parent boundaries, exact signed currency, fees, pending receipts, refund and missing-time states. These are modeled layouts, not captures from live WeChat.
- An explicit actual-payment field wins over unlabeled numbers and discounts. Conflicting actual amounts remain unresolved. Discounts are not subtracted again or represented as negative fees. An explicit service fee is kept separately, including zero; stage C supplies the corresponding fee-posting behavior.
- The source name `WechatWithdrawSuccess` refers to submission of an application, not proof that money arrived. It is pending without explicit completion evidence. Withdrawal details use arrival time only, not application time as a substitute.
- Waiting for receipt is different from received. Refund text remains a review issue rather than a normal expense. Failed, cancelled or unpaid pages cannot become successful candidates.
- Group packet progress does not establish whether this user sent or received money. Direction stays unset unless explicit self-receipt or self-sending text supports it. The original 'snatched up' heuristic is not copied.
- Recharge and withdrawal are own-account transfer candidates. Person-to-person outgoing transfer is an expense candidate and merchant incoming transfer is income; the app cannot infer that a named counterparty belongs to the user.
- The general bill rule excludes dedicated recharge/withdrawal titles so its earlier priority does not steal their transfer semantics.
- Field values use bounded, same-parent adjacency and never chase arbitrary subsequent text. Inline captures retain the complete original node string and indices. Unsupported hierarchy/layout variants require manual review rather than a speculative fallback.
- Merchant, note, payment-state, order identifier, account hint and fee evidence is retained locally. Account hints are not fabricated account IDs. Catalog matching and queue-level evidence retention are handled in B9.

## Remaining Evidence Boundary

Actual app-version layouts, vendor-specific accessibility trees, hidden nodes and real-device background behavior are unverified. The synthetic fixtures establish parser behavior only. No numeric live-support rate is inferred from the twelve registered rules.
