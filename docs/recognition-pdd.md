# Pdd Recognition Evidence

Package `com.xunmeng.pinduoduo`, registered by `aa.e`. Source evidence is the supplied APK, not live Pdd access. The rewrite uses no original code at runtime.

| Page Type | Extractor / Matcher | Priority | Field Basis |
| --- | --- | ---: | --- |
| PddOrderDetail | n9.d / x9.b | 0 | Order timestamps, fulfillment state, product name and order actions; explicit actual-payment text |
| PddPayFirstDialog | n9.e / x9.c | 1 | Pay-later order-success dialog, future automatic-debit amount, wait/completion controls |
| PddWalletBillDetail | n9.f / x9.d | 2 | Wallet bill, success state, product, payment time/method and transaction identifiers |

All three have independent positive and negative JSON fixtures. Additional tests cover delivered-but-unpaid orders, discount text, conflicting actual amounts, cancelled wallets, password pages and missing payment timestamps.

## Deliberate Differences

- `n9.d` and `n9.e` extract a future automatic payment as an amount; the latter behavior was also checked in Smali. The rewrite retains that amount only as a pending candidate. Order submission, shipment and receipt do not turn a future debit into a completed payment.
- An explicit pay-later signal takes precedence over a general `交易成功` fulfillment label. No payment timestamp is invented for a pending debit.
- Payment/completion time is distinct from order or group-purchase creation time. Only explicit payment or transaction-completion timestamps populate the transaction time; absent timestamps remain unknown.
- Original `n9.d` can derive an amount from group price and discounts. This implementation requires explicit actual-payment text instead. The two modeled inline actual-payment formats preserve full-node evidence. Multiple actual amounts stay ambiguous.
- Coupons are not stored as negative fees or subtracted twice. A bare listed price is not a payment amount.
- `取消订单` can be an action on a valid order; it is not treated as cancellation. `订单已取消` is cancellation and cannot become a successful candidate. Likewise, an `申请退款` action is not itself proof of a refund.
- All recognized results still require review and the shared ledger save path. Account and order hints are local evidence, not fabricated account assignments.

Fixture coverage does not establish compatibility with any installed Pdd version or real payment flow. Live layouts, accessibility availability and manufacturer background behavior remain unverified.
