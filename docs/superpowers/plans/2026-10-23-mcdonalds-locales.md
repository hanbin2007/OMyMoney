# McDonald's And App Languages Implementation Plan

**Goal:** Add the native McDonald's app as a recognition source and provide whole-app Simplified Chinese, Traditional Chinese and English selection.

**Approved Scope:** The user confirmed the standalone McDonald's app and the language design: follow the system by default, allow an explicit language choice, preserve transaction text and user-defined names. Use targeted verification and the existing in-app release channel; system-upgrade acceptance is explicitly waived for this release.

**Architecture:** Keep package-specific, evidence-based recognition within the existing registry. Use AndroidX AppCompat application locales and Android resource qualifiers rather than runtime text substitution. Preserve the merged Build 10 Material 3 Expressive interface and current privacy gates.

## Evidence Boundaries

- The bounded read-only audit verified 5,502 retained batches. No explicit McDonald's page or notification was present; the native package was not in the previous diagnostic source list.
- Confirm the native package from an official distribution source. Unknown layouts use the existing consented diagnostic path, never invented transactions. Template fixtures are not physical-device compatibility evidence.
- Do not translate recognition tokens, import/export column semantics, currency identifiers, user notes, merchants, book names or stored category/account names.
- Do not change log sampling policy, original signing identity, server resource limits or previous user edits.

## Tasks And Ownership

1. **English resources:** Translate the existing eleven values XML files into `values-en`, preserving resource IDs, format placeholders, escaping and non-translatable values. Validate XML and placeholder parity.
2. **Chinese resources:** Add Traditional Chinese in `values-b+zh+Hant` and an explicit Simplified Chinese variant in `values-b+zh+Hans`, preserving the same resource contract.
3. **Locale runtime and UI:** Add a standard language choice to Settings, native persistence and Android 13 integration. Adapt all three activities and non-activity contexts, and replace the four hardcoded UI-text hotspots. Own separate `language_strings.xml` and `runtime_strings.xml` in all language variants. Keep raw drafts and lifecycle/privacy behavior intact.
4. **McDonald's recognition:** Confirm the native package, add conservative rules and registry coverage, keep incomplete/refund/ambiguous states review-only, and validate with focused fixtures. Do not claim unseen live templates are verified.
5. **Integration and release:** Run source/resource checks, core/app unit tests and isolated API 26/36 language UI tests. Check narrow-screen translated layouts. Build the next monotonically increasing release, sign locally, atomically publish, then verify public manifest and complete APK readback.

## Review Focus

- Placeholder/type differences across translations must fail resource validation.
- Locale changes and process recreation must not erase raw amount input or saved data.
- Notifications, overlays and widgets must use the selected application language, not stale application contexts.
- An order total, pending payment or refund must never become an automatic settled expense.
- Third-party source strings and user data must remain unchanged by localization.

No broad refactoring or repeated review loop is part of this change. Run Gradle and device commands serially because workers share build outputs; translation workers own only their assigned resource folders.
