# Application Languages

The language chooser is in Settings > Appearance and privacy > Language. It offers Follow system, 简体中文, 繁體中文 and English. There are 905 localized UI strings in the default/Simplified Chinese, Traditional Chinese and English resource sets; `scripts/check-translations.rb` checks key and format-placeholder parity.

Application locale tags are `zh-Hans`, `zh-Hant` and `en`. AndroidX AppCompat 1.7.1 handles legacy persistence and Android 13+ system application-language synchronization. All activity entry points use AppCompatActivity; Compose observes standard configuration changes. Non-activity UI such as recognition notifications, overlays and widgets uses the selected-language context. Runtime refresh waits for the legacy locale store for at most five seconds and retries on later resume/configuration changes; it does not start or restart a debug session.

Language selection changes interface text, not accounting data. Existing notes, merchant names, user-defined books/accounts/categories, currency codes, amounts and raw unfinished draft input remain unchanged. Recognition tokens and third-party import columns are not translated. Existing privacy locks, screenshot restrictions and data-backup exclusions are preserved.

The implementation follows the [Android application-language guide](https://developer.android.com/guide/topics/resources/app-languages) and [AppCompat locale APIs](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate). Lifecycle and device observations are recorded in the release notes rather than inferred from the API documentation.

## Build 11 Verification

- All 245 core JVM tests and 15 app JVM tests passed. Resource checks cover 905 keys and matching format placeholders per language; Android debug lint passed.
- Isolated Android 26 and Android 36 tests switched all three languages, restored follow-system, recreated the activity, and preserved unfinished raw amount input (`7..25`), mixed-language notes and custom book names. Separate prepare/force-stop/verify phases passed on both APIs.
- Non-activity widget text and actual RemoteViews rendering used the selected language. Live launcher refresh and an already-running debug notification were reviewed in code, not exercised as complete system workflows.
- A short landscape window at 200% font reproduced inaccessible final choices. A scrollable choice container fixed it; the focused native test and before/after screenshots verified the English choice could be reached and selected.
- Six isolated database regressions covered native McDonald's pending/manual-save/replay and cross-payment protection, plus existing Meituan Takeout behavior.
- The user explicitly waived same-key system-upgrade/data-retention acceptance for this release. These targeted debug/device checks do not claim a completed production APK upgrade or every OEM layout.
