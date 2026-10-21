# Meituan Recognition Evidence

Package `com.sankuai.meituan`, registered by `aa.d`. `g9.b` supplies the page-type names. Its unused `MeiTuanErrandDetail` constant does not create an extra registered scenario. The standalone takeout package `com.sankuai.meituan.takeoutnew` has a separate [native development extension](recognition-meituan-takeout.md), not an alias enabling these ten main-app rules.

| Page Type | Extractor / Matcher | Priority | Field and Layout Evidence |
| --- | --- | ---: | --- |
| MeiTuanOrderDetail | m9.g / w9.g | 0 | Consumed order, actual paid, order-information and ID/time labels |
| MeiTuanVoucherDetail | m9.i / w9.i | 1 | Used or awaiting-use voucher, actual paid and usage/validity labels |
| MeiTuanVoucherSuccessDetail | m9.j / w9.j | 2 | Payment-success receipt and split-character actual-amount/order/consumption-time labels |
| MeiTuanGrouponDetail | m9.e / w9.e | 3 | Group purchase, used state, actual paid and usage details |
| MeiTuanMovieDetail | m9.f / w9.f | 4 | Showtime/ended state, ticket control, actual paid and order labels |
| MeiTuanChargeOrderDetail | m9.c / w9.c | 5 | Returned power bank, completed order, duration and amount/unit |
| MeiTuanBikeOrderDetail | m9.b / w9.b | 6 | Explicit paid amount, complaint/repair controls, ride duration and full page timestamp |
| MeiTuanPaySuccess | m9.h / w9.h | 7 | Typed WebView success title and inline paid amount |
| MeiTuanWalletBillDetail | m9.k / w9.k | 8 | Bill-detail title, payment/automatic-debit/refund state, method and order context |
| MeiTuanDeliveryDetail | m9.d / w9.d | 9 | Delivery completion/estimate context, actual paid or total label and currency unit |

Twenty independent JSON fixtures and additional edge tests cover all ten IDs. They verify exact amounts, timestamps, payment states, rule precedence, fragmented-label bounds and unsupported packages. These are synthetic cases, not live payment captures.

## Safety and Semantic Differences

- Voucher face value is never actual payment. Usage state is retained separately. A voucher awaiting use is not a completed consumption, and an already-used voucher does not by itself prove when payment occurred.
- The broader voucher rule excludes explicit group-purchase details; otherwise its earlier priority would hide the dedicated scenario. All non-wallet rules exclude a bill-detail surface so incidental order/voucher text cannot steal a wallet result.
- Split-character sequences are checked in order, contiguously, within one parent and at a bounded length. Evidence includes the sequence and value indices. Broken sequences do not fall through to unrelated amounts.
- The shared field reader prefers the longest matching label, so `实付金额` is not misread as a value after the shorter `实付` prefix.
- Actual-paid labels beat face value, original price, total and discounts. Existing discounts are not subtracted again or stored as negative fees. A delivery total without actual-payment evidence can prefill a manual-review draft, but must not become an automatic bill.
- Explicit automatic-debit success differs from a future pay-later obligation. Future-debit markers remain pending even on a delivered order. Refunds remain review issues rather than ordinary expenses.
- Order creation, voucher use and movie showtimes do not silently become payment timestamps. A checkout-success receipt can use its explicit consumption timestamp; a paid ride can use its full ride-page timestamp. Missing or conflicting time stays unknown.
- Returning a power bank proves return, not payment settlement. Without an explicit successful payment signal its amount remains a review candidate.

## Delivery Total Fix (2026-10-21)

A retained customer diagnostic sample matched `MeiTuanDeliveryDetail` but showed `合计`, `¥` and `25.5` in separate sibling nodes. The matcher accepted this layout while the extractor searched only actual-paid labels. Its complete, non-truncated snapshot therefore produced a pending draft with an empty amount. No payment timestamp or explicit completed-payment signal was present in that sample.

Build 9 (`0.2.7`) changes only the main Meituan delivery rule: actual-paid fields remain preferred, including refusing an invalid paid amount instead of falling back to a total. When no actual-paid field exists, a structurally associated total prefills the amount and adds `order_total_unconfirmed`, preventing automatic recording even if success text also appears. Conflicting totals remain unresolved; future debit, refunds and unpaid states retain their protections. Delivery estimates and order creation do not become payment times. Existing bills and edited drafts are not rewritten, and diagnostic sampling is unchanged.

An anonymized, structurally reduced fixture reproduces the captured layout without customer address, contact or merchant data. The new regressions failed before the fix and pass afterward, including manual posting validation after explicit time confirmation with an unassigned account. All 236 core JVM tests and 14 app JVM tests passed. The user explicitly selected targeted regression followed by direct in-app publication, waiving the system-upgrade test and installation-permission change for this release. No device-installation or post-upgrade retention acceptance is claimed for Build 9.

This evidence supports the observed layout, not every live Meituan version, transaction state or OEM background behavior. Standalone takeout development and release boundaries remain separate.

### Build 9 Publication

The original Android signer and locally signed update manifest were retained. The existing receiver's atomic publisher published `0.2.7` / Build `9`; public HTTPS manifest readback matches the local envelope and passed independent signature verification. The full public APK download returned HTTP 200, 61,603,783 bytes, SHA-256 `7dfdc663a2c2023e8a35f279a39d6e5a488e08e7393235ddeadf0c7cf001dbf9`, exactly matching the signed release. Receiver PID `67644`, zero restarts, 256 MiB memory limit and 20% CPU quota are unchanged. No private signing material was uploaded. Local evidence: `work/qa/save-meituan-20261021/`; release inputs: `outputs/meituan-v9/`.
