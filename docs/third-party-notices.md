# Third-Party Notices

The adaptive launcher icon uses Google's outlined `account_balance_wallet` Material Icon, with white coloring and adaptive-icon positioning. It is licensed under Apache License 2.0. The full license is bundled in `app/src/main/assets/licenses/material-icons-Apache-2.0.txt`.

- [Icon source](https://github.com/google/material-design-icons/blob/master/android/action/account_balance_wallet/materialiconsoutlined/black/res/drawable/outline_account_balance_wallet_24.xml)
- [License](https://github.com/google/material-design-icons/blob/master/LICENSE)

In-app icons use the AndroidX Material Icons library. OMyMoney is an independent application and is not affiliated with Google or the reference application.

## Release Inventory

`release-dependencies.json` is the current resolved release-runtime inventory, including platform/BOM metadata. It records 167 modules, inherited publisher POM license declarations, cached original artifact SHA-256 values, and 101 license/notice references to 100 distinct original files. Two lifecycle-viewmodel artifacts reference the same notice path. Reproduce it with `scripts/dependency-inventory.init.gradle` and `scripts/collect-licenses.rb`; the build instructions give the exact commands. Test-only JUnit, Espresso and test providers are not part of this release-runtime inventory.

The APK includes `assets/licenses/dependencies.json`, the original notices under `assets/licenses/third-party/`, and a human-readable `README.txt`. This compensates for duplicate generic `META-INF/AL2.0` / `LGPL2.1` packaging exclusions rather than silently omitting attribution.

- AndroidX, Kotlin, kotlinx libraries, Apache Commons, fastexcel, Aalto and OPC ZIP declare Apache 2.0 in their direct or inherited metadata. Component-specific notices remain preserved, not replaced with one generic assertion.
- `org.codehaus.woodstox:stax2-api:4.3.0` declares BSD 2-Clause; its original license is included.
- `javax.xml.stream:stax-api:1.0-2` declares GPL/CDDL alternatives. This distribution uses its CDDL 1.0 option and includes the license and unmodified publisher source JAR inside the APK. The source remains available from [Maven Central](https://repo.maven.apache.org/maven2/javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2-sources.jar). See the [CDDL 1.0 text](https://opensource.org/license/CDDL-1.0).
- Google ML Kit Chinese OCR, its model, and Google SDK components have separate [ML Kit terms](https://developers.google.com/ml-kit/terms) or [Android SDK terms](https://developer.android.com/studio/terms). They are not represented as entirely Apache-licensed or independently authored source.

ML Kit's general terms describe on-device image processing and possible SDK metrics traffic. This app bundles the OCR model; offline OCR is verified separately. Since version 0.2, the app declares `INTERNET` for diagnostic uploads and updates, so upstream SDK metrics traffic is possible. This is not a claim that all network traffic is disabled or restricted to the app-owned HTTP client. External document providers selected by the user can have their own synchronization behavior.

This is a component/notice inventory for the delivered personal build, not a legal opinion or a claim of completed public app-store compliance. No original-app proprietary source, credentials, advertising SDK or server integration is included.

Bundled `stax-api-1.0-2-sources.jar` SHA-256: `70b50265565dbbeb70ee3368c50e00220f8644da7a48bd67952c404e2bb0fd16`.
