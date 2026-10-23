# McDonald's Native App Recognition

The China native app package is `com.mcdonalds.gma.cn`, independently registered from WeChat, Alipay and Meituan. It appears in the bookkeeping source list; existing per-app selections are preserved, so enable McDonald's there to capture review drafts. Existing diagnostic consent includes the new supported package without a second recording switch.

## Evidence

The read-only audit on 2026-10-23 verified all 5,502 retained batches in scope, but found no explicit McDonald's page or notification. The previous source list did not include the native package. This absence is not proof that the app was unused: dropped/expired records and unsupported-package coverage remain separate limits.

The [official website](https://www.mcdonalds.com.cn/) links to `m.mcd.cn/app`; its public download script identifies the package and links to `https://cdn.mcd.cn/cma/download/app-release.apk`. The downloaded manifest confirms the package. That official download was version `6.0.12.0`, SHA-256 `b799b7de91b72bb7cf49a0881de8faaf2ab441cf715d12af944a1f8468a0b654`, not necessarily the currently installed version. Its resources confirm native labels for actual paid/subtotal, order ID/details, payment time/method and payment success in Chinese and English. No third-party APK, proprietary assets, credentials or user data are included in this repository.

Static labels guide the first conservative rules; they are not a captured current UI hierarchy or live-payment acceptance. Current layouts continue to be collected through the existing privacy-gated diagnostics during normal use.

## Behavior

- `McDonaldsOrderDetail` requires order details/information, an actual order ID and a labeled amount. Pickup numbers are not transaction identities.
- `McDonaldsPaymentResult` requires completed-payment text, an explicit paid-amount field and receipt context. Ads, checkout prompts and isolated prices stay unsupported.
- Actual-paid fields take priority over totals and discounts. An invalid explicit paid amount does not fall back to a guessed order total. Multiple differing amounts remain unresolved.
- Every initial McDonald's result stays `NeedsReview` with `unverified_template`; no native McDonald's template automatically posts a bill yet. Order totals receive an additional confirmation issue.
- Only explicit payment time is parsed. Capture time, order time and expected pickup/delivery time are not substituted for payment time.
- Failed, unpaid, cancelled and refund pages do not become ordinary expense drafts. Unknown layouts remain diagnostics only.
- After a native order is manually saved, a similar external payment-app return stays pending rather than automatically creating a second bill. Repeated native order identities reuse the existing queue record.

Tests use synthetic, independently checked values, including native resource-style nonbreaking spaces and English labels. JVM recognition tests and isolated database tests distinguish manual confirmation, duplicate prevention and explicit per-app consent from real customer-device compatibility.
