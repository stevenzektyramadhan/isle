# Isle Android build and distribution

This guide covers the Android app under `android/`. The Android module targets SDK 36, supports `arm64-v8a` and `x86_64`, and compiles Kotlin and Java bytecode for JDK 17. Android release tags build signed APKs for both ABIs. Device behavior and live Spotify/autoplay have not been verified end to end.

## Requirements

- JDK 17
- Android SDK Platform 36 and the Android SDK Build Tools installed
- Android SDK command-line tools if using `sdkmanager`

Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to your SDK installation and ensure `platform-tools` is on `PATH` when using `adb`. The project includes the Gradle 8.13 wrapper, so a separate Gradle installation is not needed. Accept the SDK licenses before building if prompted.

## Build and checks

Run these commands from the repository root:

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The test task runs local debug unit tests. `lintDebug` runs Android lint, and `assembleDebug` builds the debug APKs for the ABIs configured in `app/build.gradle.kts`. The expected output files are:

```text
android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
android/app/build/outputs/apk/debug/app-x86_64-debug.apk
```

These commands build and check the Android project. They do not establish successful installation or playback on a physical device, nor successful Spotify resolution or autoplay with live services.

## Choose an APK for a connected device

Check the device ABI with `adb`:

```bash
adb shell getprop ro.product.cpu.abi
```

Use `app-arm64-v8a-debug.apk` for `arm64-v8a` devices and `app-x86_64-debug.apk` for `x86_64` devices. Install the chosen APK with:

```bash
adb install android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Replace the filename with the x86_64 APK when appropriate. If Android reports that the package is already installed but the update is incompatible, the installed APK was signed with a different key. CI debug signing keys may differ from local keys, and Android rejects an update when the signing certificates do not match. To switch between debug keys, explicitly uninstall the existing app and then install the new APK:

```bash
adb uninstall io.github.stevenzektyramadhan.isle
adb install android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Uninstalling removes the app's local data. Back up anything needed before uninstalling. See the signing switch guidance below before moving between debug and release builds.

## Distribution

### Release signing setup

The Android workflow uses a dedicated release keystore. Configure these four **Actions secrets** in the GitHub repository before creating a release tag:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the release keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Private key alias in the keystore |
| `ANDROID_KEY_PASSWORD` | Password for that private key |

Also configure the repository Actions **variable** `ANDROID_SIGNING_CERT_SHA256` with the SHA-256 fingerprint of the public certificate for the release key. This fingerprint is public, so add it under **Settings > Secrets and variables > Actions > Variables**, not as a secret. The release workflow requires it and checks that both signed APKs use that certificate. It accepts either 64 hexadecimal digits or 32 colon-separated hexadecimal bytes.

To get the fingerprint from your existing keystore, run this in fish, replacing the filename and alias with your values:

```fish
$HOME/.local/share/isle-android-jdk17/bin/keytool -exportcert -keystore $HOME/isle-android-release.jks -alias isle-android | sha256sum | string split ' ' | string sub -l 64
```

`keytool` prompts interactively for the keystore password. Type it at the prompt; it is not part of the command or shell history. The command hashes the exported public certificate and prints the fingerprint only. Paste that output into the `ANDROID_SIGNING_CERT_SHA256` repository variable.

Keep an independently secured backup of the original keystore and its passwords, with access limited to the people responsible for releases. Preserve the same keystore for future releases. Losing it or its credentials can prevent signing updates accepted by existing installs.

On fish, set the keystore secret with `gh` reading encoded bytes from standard input. This avoids putting the encoded key in a command argument or shell history. Run this in the directory containing the keystore file, replacing the filename as needed:

```fish
base64 --wrap=0 $HOME/isle-android-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
```

Do not add `--body`, paste the encoded value into a command, or print the key or its base64. Set `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` through the GitHub repository Settings UI under Secrets and variables, Actions. In particular, enter passwords in the UI rather than a command, so they are not saved in shell history. The workflow writes the decoded keystore to a temporary runner directory with restricted permissions and removes that directory when the signing step exits.

### Create an Android release

1. In `android/app/build.gradle.kts`, set `versionName` to the release version and increment `versionCode` manually. Android requires a higher version code for an app update; the workflow checks that the tag version matches `versionName`, but does not increment either value for you.
2. Commit the version change, then create and push a tag in the `android-v*` namespace whose suffix exactly matches `versionName`. For example, `versionName = "0.2.0"` must use tag `android-v0.2.0`.
3. The Android workflow runs debug unit tests, lint, and debug APK builds on pull requests, pushes to `main`, and `android-v*` tags. For an Android tag it also builds release APKs signed with the configured secrets, verifies both APK signatures and their version names against the tag, then creates a GitHub release with the two ABI-specific signed APKs.
4. Check that the Android workflow completed and both release APK assets are present before sharing the release. Workflow checks are build and signing checks, not evidence of successful production use or end-to-end device behavior.

Keep Android tags separate from desktop `v*` tags. The desktop release workflow listens for `v*`; it does not create Android releases. CI continues to build and publish debug APK artifacts independently of release signing.

### Switching between debug and release APKs

Android treats the debug and release signing certificates as different app identities for installation updates. Installing a release APK over a debug-signed app, or a debug APK over a release-signed app, is incompatible and Android will reject the update. Do not silently uninstall or replace the existing app. If you choose to switch signing types, back up any data you need first, then explicitly uninstall the app and install the other APK. Uninstalling deletes the app's local data.

## Deferred integration checks and known behavior

Device installation and runtime behavior have not yet been verified end to end. In particular, verification on target devices and live Spotify resolution with autoplay remain deferred. Treat unit tests, lint, APK signature checks, and workflow completion as code and packaging checks, not as evidence that the app has been verified in production or that these integrations work.

Initial extraction can be slow, especially while the Android app prepares its extraction dependencies. This is a known behavior; allow time for extraction to finish during a device check.
