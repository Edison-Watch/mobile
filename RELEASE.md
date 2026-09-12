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

## GitHub Releases (signed APK via CI)

For direct distribution / sideloading (not Play), `.github/workflows/release.yml`
publishes a **signed APK** to a GitHub Release when a `v*` tag is pushed:

```bash
# bump versionCode / versionName in app/build.gradle.kts first, then:
git tag v0.1.0
git push origin v0.1.0
```

The workflow runs `assembleRelease` and reuses the same release signing config
(the `ANDROID_*` env vars), so the APK is signed with the upload key. It then
creates a Release named after the tag with `sealgate-v0.1.0.apk` attached and
auto-generated notes.

This is the APK path; the Play upload above is still the `.aab` produced by
`bundleRelease`. Both share one signing config.

### Required repository secrets

The workflow feeds the signing config from these secrets (Settings -> Secrets
and variables -> Actions). Names are `SG_MOBILE_`-prefixed; the workflow maps
them onto the `ANDROID_*` env vars the build reads.

| Secret                              | Maps to / value                                          |
| ----------------------------------- | -------------------------------------------------------- |
| `SG_MOBILE_RELEASE_KEYSTORE_BASE64` | The upload keystore, base64-encoded (`base64 -w0 upload-keystore.jks`) |
| `SG_MOBILE_RELEASE_STORE_PASSWORD`  | `ANDROID_KEYSTORE_PASSWORD`                              |
| `SG_MOBILE_RELEASE_KEY_ALIAS`       | `ANDROID_KEY_ALIAS`                                      |
| `SG_MOBILE_RELEASE_KEY_PASSWORD`    | `ANDROID_KEY_PASSWORD`                                   |

## Notes

- The Play/release build excludes the Computer-Use accessibility capability
  (`COMPUTER_USE_AVAILABLE=false`; the service is not merged into the manifest).
- `private` and `enterprise` build types inherit the release signing config and
  use distinct application IDs (`.private` / `.enterprise`) for side-by-side
  installs; they are not the Play artifact.
