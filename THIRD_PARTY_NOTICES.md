# Third-party notices

## just-bash

- Project: <https://github.com/vercel-labs/just-bash>
- Embedded version: 3.4.2
- License: Apache License 2.0
- License copy: `app/src/main/assets/just-bash-LICENSE.txt`

The browser build is bundled into `mobile-bash-runtime.js` with an explicit
restricted command list and in-memory filesystem.

## quickjs-kt

- Project: <https://github.com/dokar3/quickjs-kt>
- Version: 1.0.5
- License: Apache License 2.0

The Android dependency embeds the QuickJS JavaScript engine. It is used only to
execute the application-packaged just-bash bundle; runtime code is never
downloaded.
