# Contributing

## Getting started

1. **Prerequisites**
   - Android Studio (Ladybug / 2024.2+ recommended)
   - JDK 17+
   - Android SDK Platform 35 (min SDK 26)

2. **Setup**

   Open the project in Android Studio and let Gradle sync, or use the wrapper
   directly:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Run tests**
   ```bash
   ./gradlew testDebugUnitTest
   ```

## Development workflow

1. Create a branch for your feature/fix.
2. Make your changes; keep the app buildable and installable.
3. Before opening a PR, make sure these pass locally:
   ```bash
   ./gradlew assembleDebug testDebugUnitTest lintDebug
   ```
4. Open a pull request against `main`.

## Conventions

- Kotlin, Android Views + View Binding (no Compose in this template).
- Add dependencies to the version catalog (`gradle/libs.versions.toml`) and
  reference them as `libs.*`; avoid hard-coded versions in module build files.
- Do not commit signing material (`*.jks`, `*.keystore`) or `local.properties`.
