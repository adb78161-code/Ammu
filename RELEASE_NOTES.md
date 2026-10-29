# Ammu 1.4 — Offline Qwen release notes

This source upgrade is not an official release; no signed APK or physical-device validation was produced.

- Added deterministic conversational intent handling for greetings, identity and wellbeing questions, corrections, follow-ups, ordinary questions, and emotional statements.
- Added a separately labeled `conversation` category to the from-scratch intent training corpus and two held-out benchmarks.
- Added typed local memories for stable facts, preferences, and expiring temporary context; explicit corrections update matching facts, while sensitive facts require confirmation.
- Added commands to inspect, search, correct, and delete memories. Clearing conversation history continues to preserve memories.
- Replaced the generic speech activity with Android's on-device `SpeechRecognizer`, including service and language-model checks, with no online fallback.
- Kept neural model predictions, deterministic responses, and retrieved memories distinct. Memory writes do not alter model weights.

## Ammu 1.0 — Historical release notes

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
