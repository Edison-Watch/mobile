#!/usr/bin/env bun
/**
 * Crate-boundary import lint.
 *
 * The platform-agnostic core crate `crates/engine` must have NO dependency on
 * any transport crate. Transports (CLI + HTTP API) live in `crates/cli`
 * (package `appctl`); the engine is meant to be reusable by every transport, so
 * a dependency edge from engine -> a transport crate would invert the layering
 * described in CLAUDE.md ("The `engine` core has no transport dependency").
 *
 * Detection is a deterministic Cargo.toml text parse -- no network, no cargo
 * invocation. Two independent signals count as a violation:
 *
 *   1. A dependency that resolves to a forbidden transport PACKAGE name, matched
 *      by real package name (a bare dependency key, an inline-table
 *      `package = "X"` rename, or a `[dependencies.foo]` subtable key/rename).
 *      A third-party crate that merely shares a transport DIRECTORY name (e.g.
 *      an unrelated crate literally named `cli`) is NOT a violation.
 *   2. A `path = "..."` dependency whose final path segment points into a
 *      transport crate directory (e.g. `../cli`), regardless of the dep key.
 *
 * Both TOML quote styles (`"` and `'`) are handled everywhere.
 */

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// The core crate that must stay transport-free.
const CORE_CRATE = "crates/engine";

// Forbidden transport PACKAGE names. Matched against real package names only
// (dependency key, `package = "X"` rename, or subtable key). Do NOT list bare
// directory names here -- an unrelated crate named `cli` must not trip the gate.
const FORBIDDEN_PACKAGES: string[] = ["appctl"];

// Final path segments that identify a transport crate directory. Any
// `path = "..."` whose last segment matches is a violation regardless of key.
const FORBIDDEN_PATH_SEGMENTS: string[] = ["cli"];

// A plain dependency table: `[dependencies]`, `[dev-dependencies]`,
// `[build-dependencies]`, and their `[target.'cfg(...)'.<table>]` variants.
const DEP_TABLE_RE =
	/^\[(?:target\..*\.)?(?:dependencies|dev-dependencies|build-dependencies)\]\s*$/;
// A `[dependencies.foo]` (or target-conditional) subtable header; captures `foo`.
const DEP_SUBTABLE_RE =
	/^\[(?:target\..*\.)?(?:dependencies|dev-dependencies|build-dependencies)\.([A-Za-z0-9_-]+)\]\s*$/;
const TABLE_RE = /^\[/;
// A dependency line: `key = ...` or `key.feature = ...`. Captures the crate key.
const DEP_KEY_RE = /^([A-Za-z0-9_-]+)(?:\s*\.\s*[A-Za-z0-9_-]+)?\s*=/;
// A renaming `package = "X"` / `package = 'X'` assignment, either standalone (in
// a subtable body) or inside an inline table (`foo = { package = "X", ... }`).
const PACKAGE_RE = /(?:^|[{,]\s*)package\s*=\s*(?:"([^"]+)"|'([^']+)')/;
// A `path = "..."` / `path = '...'` assignment. Captures the path value.
const PATH_RE = /(?:^|[{,]\s*)path\s*=\s*(?:"([^"]+)"|'([^']+)')/;

interface Violation {
	line: number;
	text: string;
	reason: string;
}

const forbiddenPackages = new Set(FORBIDDEN_PACKAGES);

// Final segment of a path value, trailing slash stripped (`../cli/` -> `cli`).
function finalSegment(pathValue: string): string {
	const trimmed = pathValue.replace(/\/+$/, "");
	const idx = trimmed.lastIndexOf("/");
	return idx === -1 ? trimmed : trimmed.slice(idx + 1);
}

// Pull the aliased package out of a `package = "X"`/`'X'` line, if present.
function packageAlias(line: string): string | undefined {
	const m = line.match(PACKAGE_RE);
	if (!m) return undefined;
	return (m[1] ?? m[2]).trim();
}

// Pull the final segment out of a `path = "..."`/`'...'` line, if it points at
// a forbidden transport directory.
function forbiddenPathSegment(line: string): string | undefined {
	const m = line.match(PATH_RE);
	if (!m) return undefined;
	const seg = finalSegment((m[1] ?? m[2]).trim());
	return FORBIDDEN_PATH_SEGMENTS.includes(seg) ? seg : undefined;
}

function checkManifest(manifestPath: string): Violation[] {
	const violations: Violation[] = [];
	const text = readFileSync(manifestPath, "utf-8");
	const lines = text.split("\n");

	let inDepTable = false;
	// True while inside a `[dependencies.foo]` subtable, whose body may carry a
	// `package = "X"` rename and/or a `path = "..."` onto a transport dir.
	let inDepSubtable = false;

	for (let i = 0; i < lines.length; i++) {
		const raw = lines[i];
		const line = raw.replace(/#.*$/, "").trim();
		if (line === "") continue;

		// `[dependencies.foo]` subtable header: `foo` itself may be a forbidden
		// package, and its body (scanned below) may rename or path onto one.
		const sub = line.match(DEP_SUBTABLE_RE);
		if (sub) {
			inDepTable = false;
			inDepSubtable = true;
			if (forbiddenPackages.has(sub[1])) {
				violations.push({
					line: i + 1,
					text: raw.trim(),
					reason: `depends on transport package '${sub[1]}'`,
				});
			}
			continue;
		}
		if (DEP_TABLE_RE.test(line)) {
			inDepTable = true;
			inDepSubtable = false;
			continue;
		}
		if (TABLE_RE.test(line)) {
			inDepTable = false;
			inDepSubtable = false;
			continue;
		}

		// Subtable body: check BOTH a `package = "X"` rename AND a `path = "..."`
		// (the two are not mutually exclusive; never early-`continue` between them).
		if (inDepSubtable) {
			const alias = packageAlias(line);
			if (alias && forbiddenPackages.has(alias)) {
				violations.push({
					line: i + 1,
					text: raw.trim(),
					reason: `renamed dependency pulls in transport package '${alias}'`,
				});
			}
			const seg = forbiddenPathSegment(line);
			if (seg) {
				violations.push({
					line: i + 1,
					text: raw.trim(),
					reason: `path dependency onto transport crate dir '${seg}'`,
				});
			}
			continue;
		}
		if (!inDepTable) continue;

		// Direct dependency key: `appctl = ...`, `appctl.workspace = ...`.
		const key = line.match(DEP_KEY_RE);
		if (key && forbiddenPackages.has(key[1])) {
			violations.push({
				line: i + 1,
				text: raw.trim(),
				reason: `depends on transport package '${key[1]}'`,
			});
			continue;
		}

		// Inline-table rename: `ui = { package = "appctl", version = "..." }`.
		const alias = packageAlias(line);
		if (alias && forbiddenPackages.has(alias)) {
			violations.push({
				line: i + 1,
				text: raw.trim(),
				reason: `renamed dependency pulls in transport package '${alias}'`,
			});
			continue;
		}

		// Path dependency onto a transport crate dir: `ui = { path = "../cli" }`.
		const seg = forbiddenPathSegment(line);
		if (seg) {
			violations.push({
				line: i + 1,
				text: raw.trim(),
				reason: `path dependency onto transport crate dir '${seg}'`,
			});
		}
	}
	return violations;
}

function main(): number {
	const manifest = join(REPO, CORE_CRATE, "Cargo.toml");
	if (!existsSync(manifest)) {
		console.error(
			`import_lint: core crate manifest not found at ${CORE_CRATE}/Cargo.toml`,
		);
		return 1;
	}

	const violations = checkManifest(manifest);
	if (violations.length > 0) {
		console.error(
			`import_lint FAILED: '${CORE_CRATE}' must not depend on any transport crate.`,
		);
		for (const v of violations) {
			console.error(
				`  ${CORE_CRATE}/Cargo.toml:${v.line}: ${v.text}  (${v.reason})`,
			);
		}
		console.error(
			"The engine core is transport-agnostic; move transport-specific code into crates/cli.",
		);
		return 1;
	}

	console.log(
		`import_lint passed: '${CORE_CRATE}' has no transport-crate dependencies.`,
	);
	return 0;
}

process.exit(main());
