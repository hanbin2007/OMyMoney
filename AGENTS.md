# OMyMoney Release Policy

- The user requires every future delivered version to be published through the
  app's built-in update channel, not delivered only as a standalone APK.
- This applies to release delivery, not every intermediate edit or local build.
- Retain the existing Android signing identity and increase versionCode. Test a
  same-key in-app upgrade from the previous delivered version, including retained
  local bills, drafts and settings. Android installation confirmation stays explicit.
- Sign update manifests locally. Never upload private signing keys or passwords.
  Publish only the verified APK and signed manifest with the existing receiver's
  atomic publisher, then read back the public HTTPS manifest and APK integrity.
- Respect the server's existing resource limits. Do not restart unrelated services
  or add a new service merely to publish an update.
- Do not call a release available in-app until publication and readback succeed.
  If verification or publication is blocked, report the exact boundary; a local APK
  is not a substitute for the requested in-app release.
