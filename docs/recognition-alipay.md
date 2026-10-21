# Alipay Recognition Evidence

Package: `com.eg.android.AlipayGphone`. All evidence below is static APK analysis plus independently modeled JSON fixtures, not a live Alipay compatibility claim. No original source is included in the app.

| Page Type | Extractor / Matcher | Priority | Primary Evidence |
| --- | --- | ---: | --- |
| AlipayBillDetail | j9.a / t9.b | 0 | Transaction state, payment/creation/receipt time labels, amount |
| AlipayPaySuccess | j9.e / t9.e | 1 | Payment success, amount, completion control |
| AlipayTransfer | j9.h / t9.j | 2 | Transfer success, payment method, recipient |
| AlipayTransferOut | j9.j / t9.i | 3 | Transfer-out success, currency unit, destination, completion |
| AlipayTransferIn | j9.i / t9.h | 4 | WebView, transfer-in success, button completion and frame-layout back control |
| AlipayCharge | j9.c / t9.d | 5 | Recharge success, actual payment, payment method |
| AlipayChargeDetail | j9.b / t9.c | 6 | Balance detail, balance recharge, payment time and other account |
| AlipayWithdraw | j9.l / t9.l | 7 | Withdrawal success, withdrawal amount and destination bank |
| AlipayWithdrawDetail | j9.k / t9.k | 8 | Balance detail, balance withdrawal, payment time and account pair |
| AlipaySendRedPacket | j9.g / t9.g | 9 | Sent-packet identity, claimed/total progress, return warning |
| AlipayQRCodeReceiveDetail | j9.f / t9.f | 10 | Bill detail, received state, receipt time and order amount |
| AlipayYuLiBaoIncomeDetail | j9.m / t9.m | 11 | Earnings, credited state, payment source and balance context |
| AlipayYuLiBaoTransferOutSuccess | j9.q / t9.q | 12 | Typed redemption result image, success and receiving account |
| AlipayYuLiBaoTransferOutDetail | j9.p / t9.p | 13 | Typed WebView title, completed withdrawal and account pair |
| AlipayYuLiBaoTransferInDetail | j9.n / t9.n | 14 | Typed WebView title, completed deposit and account pair |
| AlipayYuLiBaoTransferInSuccess | j9.o / t9.o | 15 | Inline success amount, payment method and earnings timeline |

Each ID has separate positive and negative JSON cases in `core/src/test/resources/recognition/alipay/scenarios.json`. Tests assert exact minor units, time, kind, matched/review status, account-direction evidence and the selected rule, not merely the number of rules.

## Differences and Limits

- Explicit actual payment has priority over order totals, balance, coupons and the original `j9.d` negative-fee adjustments. The amount is not reduced a second time. Multiple conflicting actual-payment values remain unresolved.
- Creation time is not payment time. A page with only creation time remains unknown even if its status looks completed. Full timestamps with explicit timezone context are required; date-only text and recognition time do not fill the gap.
- QR receipts are income. YuLiBao earnings are income, while movements of principal, balance recharge and withdrawal are transfers. Explicit phone/telecom recharge is spending, not movement between the user's asset accounts. Unknown account identities remain unassigned; source/destination hints are evidence, not database IDs.
- The generic bill rule does not steal balance-detail or dedicated recharge layouts. Credit-payment state produces the reserved `CREDIT_PAYMENT` kind for the later C1 ledger implementation.
- A balance movement layout without an explicit successful state remains a review candidate. Sent packets preserve the sent total, not the claimed portion, with pending status. Refund and pending-claim states are not ordinary successful expenses.
- The original withdrawal-fee transform has a suspicious empty-string branch, also visible in `j9/l$a.smali`. This rewrite parses a bounded explicit fee field, never that branch.
- Payment-password text is rejected. Type-sensitive WebView/Image/Button cases remain type-sensitive. Unknown or changed layouts are not claimed as supported.

## Capture Rounds

Original scheduling was checked in `SelectToSpeakService.java` and its Smali: 160 ms for `alipay_fast`, 450 ms for `alipay_fallback`, with fast processing restricted to `AlipayPaySuccess`. The rewrite uses an explicitly named `AlipayCapturePass` and an event-scoped `AlipayCaptureSession`, not the original service class or implementation.

One session emits at most one recognized candidate for a single window event. Unsupported fast recognition leaves fallback available; a window change rejects the stale round. Once fast emits a reviewable candidate, fallback cannot create a second one or overwrite it. A new event uses a new session and is not permanently ignored. B9 performs durable, evidence-aware transaction deduplication across events, and B10 owns the actual Android delay and cancellation scheduler. The B3 tests exercise the pure round contract, not elapsed Android timing.

Actual app versions, live UI trees, device scheduling and payment flows remain unverified.
