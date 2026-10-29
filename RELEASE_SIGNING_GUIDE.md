# Ammu 1.4 official APK signing guide

Android treats the signing certificate as the identity of an installed app. `versionCode` alone does not let a differently signed APK update an existing installation. Create and protect one release key and reuse it for all future official updates.

## 1. Create your own key on a trusted computer

Install a trusted JDK, then run:

```bash
keytool -genkeypair -v \
  -keystore ammu-release.jks \
  -alias ammu \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Choose strong passwords and store them safely. Keep at least two offline backups of `ammu-release.jks` in secure locations. Losing the release key can prevent Android from accepting future updates signed with a replacement key. Do not use online keystore generators. Do not share the keystore or passwords in chat, email, screenshots, or source control.

## 2. Convert the key file to Base64

On Linux:

```bash
base64 -w 0 ammu-release.jks > ammu-release-base64.txt
```

On macOS:

```bash
base64 < ammu-release.jks | tr -d '\n' > ammu-release-base64.txt
```

The text file contains the entire private key in encoded form. Treat it exactly like the keystore itself and delete it from shared/download folders after configuring the secret.

## 3. Add GitHub repository secrets

In the correct GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**. Add these exact names and values:

| Secret name | Value |
|---|---|
| `AMMU_KEYSTORE_BASE64` | Entire Base64 text from the generated file |
| `AMMU_KEYSTORE_PASSWORD` | Keystore password |
| `AMMU_KEY_ALIAS` | `ammu` or the alias you selected |
| `AMMU_KEY_PASSWORD` | Password for the key alias |

Do not create ordinary repository variables for passwords. Do not commit a keystore, secret file, or Base64 text into GitHub.

## 4. Run the official release workflow

Open **Actions → Build Ammu 1.4 Official Signed APK → Run workflow**. The workflow checks required secrets, runs tests and source/model validation, builds `assembleRelease`, verifies the signature using `apksigner`, and uploads the APK with a SHA-256 checksum. A missing secret, failed test, failed Gradle build, or invalid signature should fail the workflow. This workflow is part of the Ammu 1.4 offline Qwen release.

The source package does not contain a private signing key. The key belongs to you and should persist independently of the repository and build runners.
