# Ammu 1.0 — Release notes

## Official version source

- Official product name/version: **Ammu 1.0**.
- Android package remains `com.ammu.assistant`.
- Android build number is `22`.
- App label and welcome message no longer expose a testing-build number.
- Added a custom adaptive launcher icon for the official version.
- Added an in-app **Privacy & offline details** panel and `privacy and offline info` command.
- The privacy panel explains local storage, backup sensitivity, the absence of an app internet permission/cloud AI endpoints, and the limitation that Android's selected speech recognizer may still use network services.
- Added repository hygiene rules to exclude build outputs, local settings, keystores and secret text files.
- Added a separate official signed-release workflow that requires a user-owned signing key in GitHub Secrets and verifies the APK signature.
- Kept a separate CI/debug workflow whose artifact is explicitly marked not for official distribution.
- Preserved the custom-from-scratch neural intent classifier, local correction queue, benchmark datasets and manual model-promotion gate.

## Release gates

The package is source-complete enough for the configured workflows, but no signed APK is included here. Do not claim device compatibility until GitHub Actions builds and signature-verifies the release APK, then a clean install/update and backup/restore flow are tested on a real Android device.
