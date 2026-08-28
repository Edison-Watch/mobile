# Golden frames (vendored copy)

These fixtures are a vendored copy of the canonical set in the public
`Edison-Watch/app` repo at `crates/stdiod/schema/golden-frames/` - the README
there documents what they are and the compatibility rules. This copy exists so
`tests/stdio_tunnel/test_golden_frames.py` can run without a sibling checkout.

Do not edit these files here. Change the canonical set and copy it over; the
`schema-drift` workflow (`scripts/check_tunnel_protocol_schema.py` with
`STDIOD_CHECKOUT` set) fails when the two copies differ.
