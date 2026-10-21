# JD Recognition Evidence

Package `com.jingdong.app.mall`, registration `aa.c`. Evidence is static inspection of the provided APK plus independent synthetic node fixtures. No live JD version was exercised.

| Page Type | Extractor / Matcher | Priority | Layout Evidence |
| --- | --- | ---: | --- |
| JingDongWalletBillDetail | l9.f / v9.f | 0 | WebView bill title, separate state and preceding amount, payment method, creation time, total-order ID/count |
| JingDongWalletBillDetailV2 | l9.g / v9.g | 1 | WebView bill title and merged text beginning with payment method or discount; embedded state/amount and order metadata |
| JingDongAihuishouOrderDetail | l9.b / v9.b | 2 | WebView order, recycling success, total received, order ID and receiving method |
| JingDongOrderDetail | l9.c / v9.c | 3 | Native fulfillment/payment state, quantity, actual payment, total, order/copy/time controls |
| JingDongOrderWeb | l9.d / v9.d | 4 | WebView order, colon-terminated labels, actual payment versus product total |
| JingDongPaySuccess | l9.e / v9.e | 5 | Typed TextView success and payment-method/amount line, optional coupon amount |

The twelve independent JSON fixture cases assert the exact selected ID, amount, time, kind and review state. Edge tests exercise both discounted order layouts, conflicts, both refunded wallet versions, recycling income, pending web orders, date-only text, typed payment controls, WeChat handoff exclusion and long-card redaction inside merged amount evidence.

## Behavioral Decisions

- Native and web orders select explicit actual payment, not the largest value, product original price or total. Coupons are neither subtracted again nor stored as negative fees.
- Wallet V1 takes the bounded same-parent amount immediately preceding its transaction state when no explicit actual-paid field exists. Wallet V2 parses the amount directly attached to the embedded transaction state. They are separate rules with their original registration order.
- Creation/order timestamps are not payment timestamps. Wallet creation time remains unknown transaction time. Explicit payment/receipt/transaction time is preferred and must include a valid full date and time.
- Recycling success with total received is income, not a negative shopping expense. Account IDs still require explicit catalog assignment.
- Refund text is a review issue even when a successful state also appears. A web `等待处理` state is pending. Fulfillment-only native states without payment evidence stay reviewable.
- The payment-result parser excludes WeChat payment handoff and requires typed TextView controls. It keeps the actual debit and does not turn a coupon into a second financial movement.
- The source's merged-node format makes a full original amount-evidence string potentially contain a card number. Long card-like digit sequences are therefore sanitized across all evidence and recognized notes, with `redacted=true`; non-sensitive full raw evidence is preserved exactly. Account/order hints retain only their extracted value rather than the entire merged page. Explicit order identifiers remain available for later deduplication; they are not inferred from arbitrary long numbers.
- Shared label reading now supports a label node ending in a colon as well as inline values and plain label/value siblings, while maintaining parent and size bounds.

Live layout support, real payment completion and device background/accessibility behavior remain unverified. The registry count is not a live-support percentage.
