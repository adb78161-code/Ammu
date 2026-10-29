# Ammu 1.4 — Conversational and offline source upgrade

This checkout contains the Ammu 1.4 offline Qwen release (`versionCode 24`). This task does not create or publish an official release: **no signed APK is built here**, and the source has **not been tested on a physical device**. Do not distribute a CI/debug APK as an official app.

## App icon and branding

The launcher icon now uses the user-provided illustrated logo. Density-specific launcher PNGs are included, and the adaptive icon foreground uses a safe inset so Android's circular or rounded masks do not crop the artwork too aggressively. The full-resolution source is at `app/src/main/res/drawable-nodpi/ammu_logo.png`.


## Product principles

- Custom neural intent classifier and tokenizer trained from scratch; no pretrained LLM, cloud AI API, online model endpoint, or downloaded model is used.
- Deterministic conversation rules distinguish greetings, identity/wellbeing questions, corrections, emotional statements, follow-ups, and ordinary questions. The classifier predicts intents; it does not generate replies or establish facts.
- Local command handling, notes, tasks, memory, journal, mood log, aliases, voice clips, learning examples, and backups.
- Explicitly taught memories are stored locally as stable facts, preferences, or expiring temporary context. Corrections can update matching facts; sensitive facts require confirmation. Memory retrieval does not update neural weights.
- Inspect memories with `what do you remember about me` or `search memory for ...`; correct one with `correct memory 1 to ...`; delete one with `forget memory 1`. Clearing conversation history leaves saved memories intact.
- No Android `INTERNET` permission and no cloud AI endpoints in Ammu itself.
- Allow-listed Android app launching, guarded mutations, and confirmation before destructive or consequential changes.
- English, Hindi and Kannada command normalization for supported intents.
- Training corrections are stored locally and do not change live neural weights until a candidate is separately trained, evaluated and manually promoted.

## Important privacy details

Ammu's core does not request internet access. Voice input uses Android's on-device `SpeechRecognizer` only after checking on-device availability. On Android 13 and later, Ammu also checks for the selected installed language model. If the device or language is unsupported, the app explains the limitation and typed chat remains available. Ammu never launches Google's speech-recognition activity or falls back to online recognition. Open **TOOLS → Privacy & offline details** or type `privacy and offline info` for the in-app explanation.

Ammu's app-private data is lost if the app is uninstalled. Export a complete backup and verify that the exported file can be found before uninstalling or changing app-signing keys. Backup files can contain private information and custom voice clips; keep them private.

## Build and verify the source

From a checkout with Python 3, NumPy, Java 17, an Android SDK, and Gradle available:

```bash
python3 -m pip install -r requirements-train.txt
python3 -m unittest discover -s tests -v
python3 tools/test_java_logic.py
python3 tools/validate_brain.py
python3 tools/validate_release_source.py
python3 tools/evaluate_brain.py
python3 tools/evaluate_brain.py --benchmark app/src/main/assets/brain-benchmark-v2.json
gradle --no-daemon assembleDebug
```

The `Validate Ammu 1.4 (CI debug APK only)` workflow runs those checks and can build a debug APK. Its artifact is explicitly marked **not for official distribution**.

## Build the signed official APK

1. Read `RELEASE_SIGNING_GUIDE.md` before setting up signing.
2. Create a signing keystore you control and store backups offline.
3. Add the four signing values as **GitHub Actions repository secrets**. Never put the keystore, password, or encoded keystore text into the repository/source ZIP.
4. In GitHub, open **Actions → Build Ammu 1.4 Official Signed APK → Run workflow**.
5. The workflow runs validation, builds the release variant, verifies the APK signature with `apksigner`, and uploads `ammu-1.4-official-signed-apk` plus a SHA-256 checksum.
6. If a secret is missing or signature verification fails, no official artifact should be distributed.

The four required repository secrets are:

- `AMMU_KEYSTORE_BASE64` — Base64-encoded `.jks` or `.keystore` file.
- `AMMU_KEYSTORE_PASSWORD` — keystore password.
- `AMMU_KEY_ALIAS` — key alias.
- `AMMU_KEY_PASSWORD` — key password.

Keep the same signing key for every future official update. Losing it can prevent Android from accepting updates signed with a replacement key.

## Updating from an earlier testing APK

The Android application ID stays `com.ammu.assistant`; this source is version `1.4` with Android `versionCode 24`. **Do not assume a locally installed debug APK can be upgraded in place**: Android requires the signing certificate to match too. Before replacing any testing build, export Ammu's complete backup and check that the file exists. If Android reports a signature mismatch, uninstalling the old build may be necessary; restore your backup after installing the signed APK.

## Voice cloning

The custom recorded-voice feature remains optional. Use only voice clips you have permission to use. Voice cloning improvements and a wake word are not part of this release milestone.

## Current verification status

The validation report lists checks run on the source bundle. This workspace does not have an Android SDK/Gradle installation available, so it cannot claim the official APK was built here. The official signed APK only exists after the GitHub Actions workflow completes successfully; physical-device testing on the Vivo T2X remains a required follow-up.
## App logo

The app uses the exact user-provided illustration stored at `app/src/main/res/drawable-nodpi/ammu_logo.png`. Launcher icons are generated from that same artwork; no substitute illustration is used.
