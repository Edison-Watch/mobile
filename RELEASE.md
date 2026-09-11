# Release: publishing to Google Play

The app publishes as an Android App Bundle (`.aab`) signed with an **upload key**.
Google Play App Signing holds the real app signing key and re-signs each upload,
so the upload key only proves the upload came from us and can be reset if lost.

Package: `ai.sealgate.mobile` · Play Console: Organisation account "SealGate".

## One-time: create the upload keystore

Run once, keep the output safe (password manager + secure backup). Never commit it.

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upload \
  -dname "CN=GPU-EVM LTD, O=GPU-EVM LTD, L=London, C=GB"
```

Then point the build at it. Copy `keystore.properties.example` to
`keystore.properties` (git-ignored) and fill in real values:

```properties
storeFile=upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` is resolved from the repo root. On a CI/release runner, instead set
`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. If none of these are present, release builds are left
unsigned (CI only ever builds debug, so its build is unaffected).

## Build the release bundle

```bash
./gradlew bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

Sanity-check it is signed with the upload key:

```bash
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/release/app-release.aab
```

## Upload to Play

1. Play Console -> the app -> **Testing > Internal testing** -> Create release.
2. On the first upload, accept **Play App Signing** (let Google generate/manage
   the app signing key; we keep only the upload key).
3. Upload `app-release.aab`, add release notes, roll out to internal testers.
4. Promote Internal -> Closed -> Production once it looks good.

## Each subsequent release

- Bump `versionCode` (must strictly increase) and `versionName` in
  `app/build.gradle.kts`.
- `./gradlew bundleRelease`, upload the new `.aab`.

## Notes

- The Play/release build excludes the Computer-Use accessibility capability
  (`COMPUTER_USE_AVAILABLE=false`; the service is not merged into the manifest).
- `private` and `enterprise` build types inherit the release signing config and
  use distinct application IDs (`.private` / `.enterprise`) for side-by-side
  installs; they are not the Play artifact.
