# Mobile Bash runtime bundle

This directory builds the pinned `just-bash` browser runtime embedded in the
Android APK. The generated `app/src/main/assets/mobile-bash-runtime.js` is
checked in so Android builds do not require Node or network access.

```sh
npm ci
npm run build
```

The wrapper uses only an in-memory filesystem, an explicit safe-command list,
and five host commands that cross the Kotlin bridge. Network access and the
Python, JavaScript, SQLite, compression, and real-filesystem integrations are
not bundled into the exposed shell surface.

The restricted `sleep` command accepts decimal seconds and the usual `s`, `m`,
`h`, and `d` suffixes. Multiple operands are summed. A single invocation is
limited to 30 seconds so malformed animation scripts fail before the 60-second
Mobile Bash tool deadline instead of silently timing out.

Timed GATT animations can be kept compact and executed entirely on the phone:

```bash
bluetooth gatt write-sequence '[
  {"packets":["FRAME_ONE_CHUNK_A","FRAME_ONE_CHUNK_B"],"hold_ms":1000},
  {"packets":["FRAME_TWO_CHUNK_A","FRAME_TWO_CHUNK_B"],"hold_ms":1000}
]' --repeat 3
```

The sequence uses the most recently connected GATT address and last-used
service/characteristic, stops at the first failed write, and returns one
aggregate result instead of one JSON record per packet.
