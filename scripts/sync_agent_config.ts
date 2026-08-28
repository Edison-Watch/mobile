#!/usr/bin/env bun
/**
 * Sync Claude <-> Codex skills, subagents, and root agent docs.
 *
 * - Symlinks `.claude/skills/<name>` -> `../../.agents/skills/<name>` for every
 *   directory under `.agents/skills/`.
 * - Regenerates `.codex/agents/<name>.toml` from each `.claude/agents/<name>.md`.
 * - Mirrors every `CLAUDE.md` to a sibling `AGENTS.md` symlink (Codex reads
 *   `AGENTS.md`; Claude reads `CLAUDE.md`).
 * - Auto-prunes dangling symlinks and orphaned TOMLs silently.
 *
 * Self-contained: parses the small, single-line-scalar YAML frontmatter used by
 * skills and subagents with a minimal parser, so the script runs with zero
 * installed dependencies (`bun run scripts/sync_agent_config.ts`).
 *
 * Pass `--check` to fail (exit 1) if anything is out of date instead of fixing
 * it. In check mode the script performs NO filesystem mutation -- used by the
 * prek hook and CI.
 */

import {
	existsSync,
	lstatSync,
	mkdirSync,
	readdirSync,
	readFileSync,
	readlinkSync,
	realpathSync,
	symlinkSync,
	unlinkSync,
	writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..");
// Canonical repo root, used to prove that no path component (including a
// symlinked parent directory) redirects a generated-TOML write outside the tree.
const REPO_REAL = realpathSync(REPO);
const SHARED_SKILLS = join(REPO, ".agents", "skills");
const CLAUDE_SKILLS = join(REPO, ".claude", "skills");
const CLAUDE_AGENTS = join(REPO, ".claude", "agents");
const CODEX_AGENTS = join(REPO, ".codex", "agents");

const FRONTMATTER_RE = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/;
const CLAUDE_ONLY_KEYS = new Set([
	"tools",
	"model",
	"color",
	"allowed-tools",
	"disable-model-invocation",
]);

// Shared skills may carry ONLY these frontmatter keys (allowlist).
const SHARED_SKILL_ALLOWED_KEYS = new Set(["name", "description"]);
// Lowercase-hyphen slug, <=64 chars.
const SKILL_NAME_RE = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

const SHARED_SKILL_FORBIDDEN_KEYS = new Set([
	"allowed-tools",
	"disable-model-invocation",
	"user-invocable",
	"context",
	"agent",
	"model",
	"effort",
	"hooks",
	"paths",
	"shell",
	"argument-hint",
]);

const SHARED_SKILL_FORBIDDEN_BODY_PATTERNS: [RegExp, string][] = [
	[/\$ARGUMENTS\b/, "$ARGUMENTS substitution"],
	[/\$[1-9][0-9]*\b/, "positional arg substitution ($1, $2, ...)"],
	[/\$\{CLAUDE_[A-Z_]+\}/, "${CLAUDE_*} interpolation"],
	[/!`[^`]+`/, "!`cmd` shell preprocessing"],
];
const SHARED_SKILL_RAW_BODY_PATTERNS: [RegExp, string][] = [
	[/^```!\s*$/m, "```! shell preprocessing block"],
];

// Directories skipped when walking the tree for CLAUDE.md files.
const MIRROR_SKIP_DIRS = new Set([
	".git",
	"node_modules",
	"target",
	"dist",
	"out",
	"build",
	".next",
	".cache",
]);

type Frontmatter = Record<string, string>;

function rel(p: string): string {
	return relative(REPO, p);
}

function die(msg: string): never {
	console.error(msg);
	process.exit(1);
}

/**
 * Refuse to follow a symlink when reading/writing generated TOML. Writing
 * through a symlink would clobber an out-of-tree target (or let a planted link
 * redirect the write); a regular file or absent path is fine.
 */
function assertNotSymlink(path: string): void {
	let st: ReturnType<typeof lstatSync>;
	try {
		st = lstatSync(path);
	} catch {
		return; // absent - writeFileSync will create a regular file
	}
	if (st.isSymbolicLink()) {
		die(
			`ERROR: ${rel(path)} is a symlink; refusing to read/write generated TOML through it. ` +
				`Remove the symlink and re-run.`,
		);
	}
}

/**
 * Refuse to operate under a directory whose path escapes the repo through a
 * symlinked component. The final-path `assertNotSymlink` check only inspects the
 * last path element, so a symlinked PARENT (e.g. `.codex` or `.codex/agents`
 * pointing outside the tree) could still redirect a write out of the repo. Walk
 * every existing component from the repo root down to `dir`; if any is a symlink
 * whose real target lands outside the repo, abort. Absent components are fine -
 * `mkdirSync` will create ordinary directories for them.
 */
function assertDirInsideRepo(dir: string): void {
	const relPath = relative(REPO, dir);
	if (relPath === "") return;
	if (relPath.startsWith("..") || resolve(REPO, relPath) !== dir) {
		die(`ERROR: ${dir} is outside the repo root; refusing to continue.`);
	}
	let current = REPO;
	for (const part of relPath.split(sep).filter(Boolean)) {
		current = join(current, part);
		let st: ReturnType<typeof lstatSync>;
		try {
			st = lstatSync(current);
		} catch {
			return; // component absent - mkdirSync will create a real directory
		}
		if (st.isSymbolicLink()) {
			const real = realpathSync(current);
			if (real !== REPO_REAL && !real.startsWith(REPO_REAL + sep)) {
				die(
					`ERROR: ${rel(current)} is a symlink escaping the repo root ` +
						`(resolves to ${real}); refusing to read/write generated TOML ` +
						`beneath it. Remove the symlink and re-run.`,
				);
			}
		}
	}
}

/**
 * Minimal frontmatter parser: one `key: value` per line, scalar values only.
 * Strips matching surrounding quotes. Skips blank lines and `# comments`.
 * This is all skill/subagent frontmatter needs; anything richer is rejected by
 * the shared-skill validators below or simply ignored for Codex generation.
 */
function parseFrontmatter(raw: string, source: string): Frontmatter {
	const meta: Frontmatter = {};
	for (const line of raw.split(/\r?\n/)) {
		const trimmed = line.trim();
		if (!trimmed || trimmed.startsWith("#")) continue;
		const idx = trimmed.indexOf(":");
		if (idx === -1) die(`${source}: unparseable frontmatter line: ${line}`);
		const key = trimmed.slice(0, idx).trim();
		let value = trimmed.slice(idx + 1).trim();
		if (
			(value.startsWith('"') && value.endsWith('"') && value.length >= 2) ||
			(value.startsWith("'") && value.endsWith("'") && value.length >= 2)
		) {
			value = value.slice(1, -1);
		}
		if (key) meta[key] = value;
	}
	return meta;
}

function parseMd(path: string): { meta: Frontmatter; body: string } {
	const text = readFileSync(path, "utf-8");
	const m = text.match(FRONTMATTER_RE);
	if (!m) die(`${path}: missing YAML frontmatter`);
	const meta = parseFrontmatter(m[1], path);
	const body = m[2].replace(/^[\r\n]+/, "");
	return { meta, body };
}

function uEscape(ch: string): string {
	return `\\u${ch.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0")}`;
}

// TOML basic strings forbid raw control chars (U+0000-U+001F and U+007F) other
// than tab. We first render tab/CR/LF with their short escapes, so any leftover
// forbidden control char is caught here and encoded as `\uXXXX`.
const TOML_BASIC_FORBIDDEN_CTRL_RE =
	// biome-ignore lint/suspicious/noControlCharactersInRegex: matching forbidden control chars is the point
	/[\u0000-\u001F\u007F]/g;

// Multiline strings additionally allow raw tab (U+0009), LF (U+000A), and CR
// (U+000D). Every other control char (form-feed U+000C, vertical-tab U+000B,
// etc.) remains forbidden and must be encoded as `\uXXXX`.
const TOML_MULTILINE_FORBIDDEN_CTRL_RE =
	// biome-ignore lint/suspicious/noControlCharactersInRegex: matching forbidden control chars is the point
	/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

function tomlBasicString(s: string): string {
	// Only used for `name` and `description`. Escape backslash, quote, the common
	// whitespace escapes, and every remaining TOML-forbidden control char.
	const escaped = s
		.replace(/\\/g, "\\\\")
		.replace(/"/g, '\\"')
		.replace(/\t/g, "\\t")
		.replace(/\r/g, "\\r")
		.replace(/\n/g, "\\n")
		.replace(TOML_BASIC_FORBIDDEN_CTRL_RE, uEscape);
	return `"${escaped}"`;
}

function tomlMultilineString(s: string): string {
	// Triple-quoted; escape sequences of 3+ double quotes so the string can't
	// close prematurely. A literal `"""` becomes `""\"`. Raw tab/CR/LF are legal
	// here, but other TOML-forbidden control chars (form-feed, vertical-tab, ...)
	// must be encoded as `\uXXXX`.
	const escaped = s
		.replace(/\\/g, "\\\\")
		.replace(/"""/g, '""\\"')
		.replace(TOML_MULTILINE_FORBIDDEN_CTRL_RE, uEscape);
	// Leading newline right after the opening """ is stripped by TOML, so add one
	// so the content starts on its own line for readability.
	return `"""\n${escaped}"""`;
}

function renderToml(meta: Frontmatter, body: string, source: string): string {
	if (!meta.name) die(`${source}: missing \`name\` in frontmatter`);
	const name = String(meta.name);
	const description = String(meta.description ?? "");
	const instructions = `${body.replace(/\s+$/, "")}\n`;

	let out = "";
	out += `name = ${tomlBasicString(name)}\n`;
	out += `description = ${tomlBasicString(description)}\n`;
	out += `developer_instructions = ${tomlMultilineString(instructions)}\n`;

	const extras = Object.entries(meta).filter(([k]) => CLAUDE_ONLY_KEYS.has(k));
	if (extras.length > 0) {
		out +=
			"\n# Claude-only frontmatter (preserved for reference, not used by Codex):\n";
		for (const [k, v] of extras) {
			out += `# ${k} = ${JSON.stringify(v)}\n`;
		}
	}
	return out;
}

function scanBacktickSpan(
	t: string,
	i: number,
	precededByBang: boolean,
): { emit: string; next: number } {
	const n = t.length;
	let run = 0;
	while (i + run < n && t[i + run] === "`") run++;
	const closer = "`".repeat(run);
	const close = t.indexOf(closer, i + run);
	const unterminated = close === -1 || t.slice(i + run, close).includes("\n");
	if (unterminated) {
		return { emit: t.slice(i, i + run), next: i + run };
	}
	if (precededByBang && run === 1) {
		// Preserve `!`cmd`` verbatim so the shell-preprocessing pattern still matches.
		return { emit: t.slice(i, close + run), next: close + run };
	}
	return { emit: "", next: close + run };
}

function stripCode(text: string): string {
	const t = text.replace(/^[ ]{0,3}(`{3,})[\s\S]*?^[ ]{0,3}\1`*/gm, "");
	const out: string[] = [];
	let i = 0;
	while (i < t.length) {
		if (t[i] === "`") {
			const precededByBang = out.length > 0 && out[out.length - 1] === "!";
			const { emit, next } = scanBacktickSpan(t, i, precededByBang);
			if (emit) out.push(emit);
			i = next;
		} else {
			out.push(t[i]);
			i++;
		}
	}
	return out.join("");
}

function validateSharedSkill(skillDir: string): string[] {
	const skillMd = join(skillDir, "SKILL.md");
	if (!existsSync(skillMd)) {
		return [`${rel(skillDir)}: missing SKILL.md`];
	}
	let parsed: { meta: Frontmatter; body: string };
	try {
		parsed = parseMd(skillMd);
	} catch (e) {
		return [String(e)];
	}
	const { meta, body } = parsed;
	const errs: string[] = [];

	// Allowlist: shared-skill frontmatter may ONLY carry `name` and `description`.
	// Anything else (Claude-only keys, typos, Codex-only keys) is rejected so the
	// file stays portable across both tools.
	const extraKeys = Object.keys(meta)
		.filter((k) => !SHARED_SKILL_ALLOWED_KEYS.has(k))
		.sort();
	if (extraKeys.length > 0) {
		const claudeOnly = extraKeys.filter((k) =>
			SHARED_SKILL_FORBIDDEN_KEYS.has(k),
		);
		const label =
			claudeOnly.length > 0
				? "Claude-only frontmatter keys"
				: "disallowed frontmatter keys";
		errs.push(
			`${rel(skillMd)}: ${label} in shared skill (only 'name','description' allowed): [${extraKeys.map((k) => `'${k}'`).join(", ")}]`,
		);
	}

	for (const [pat, label] of SHARED_SKILL_RAW_BODY_PATTERNS) {
		if (pat.test(body))
			errs.push(`${rel(skillMd)}: body uses Claude-only feature: ${label}`);
	}
	const scan = stripCode(body);
	for (const [pat, label] of SHARED_SKILL_FORBIDDEN_BODY_PATTERNS) {
		if (pat.test(scan))
			errs.push(`${rel(skillMd)}: body uses Claude-only feature: ${label}`);
	}

	if (!meta.name) {
		errs.push(`${rel(skillMd)}: missing \`name\` in frontmatter`);
	} else if (!SKILL_NAME_RE.test(meta.name) || meta.name.length > 64) {
		errs.push(
			`${rel(skillMd)}: \`name\` must be a lowercase-hyphen slug of <=64 chars (got '${meta.name}')`,
		);
	}

	if (!meta.description) {
		errs.push(`${rel(skillMd)}: missing \`description\` in frontmatter`);
	} else if (meta.description.length > 250) {
		errs.push(
			`${rel(skillMd)}: \`description\` must be <=250 chars (got ${meta.description.length})`,
		);
	}
	return errs;
}

function validateAllSharedSkills(names: string[]): void {
	const errors: string[] = [];
	for (const n of names)
		errors.push(...validateSharedSkill(join(SHARED_SKILLS, n)));
	if (errors.length > 0) {
		for (const e of errors) console.error(`ERROR: ${e}`);
		process.exit(1);
	}
}

// Create `dir` when absent; in check mode record the intent instead of writing.
// Returns whether the dir already existed (callers gate reads on the result).
function ensureDir(dir: string, check: boolean, changes: string[]): boolean {
	const existed = existsSync(dir);
	if (!existed) {
		if (check) changes.push(`create ${rel(dir)}`);
		else mkdirSync(dir, { recursive: true });
	}
	return existed;
}

// When `check` is true this performs NO filesystem mutation: it computes the
// change that *would* be made and returns its description (the read-only
// collision assert still runs). When false it actually creates/repoints the
// symlink.
function materializeSymlink(name: string, check: boolean): string | null {
	const link = join(CLAUDE_SKILLS, name);
	const target = join("..", "..", ".agents", "skills", name);
	let exists = false;
	let isSymlink = false;
	try {
		const st = lstatSync(link);
		exists = true;
		isSymlink = st.isSymbolicLink();
	} catch {
		// not present
	}
	if (isSymlink) {
		const current = readlinkSync(link);
		if (current === target) return null;
		if (!check) unlinkSync(link);
	} else if (exists) {
		die(
			`ERROR: name collision - .claude/skills/${name} is a real directory (Claude-only skill) ` +
				`but .agents/skills/${name} also exists (shared skill). Resolve by removing one of them.`,
		);
	}
	if (!check) symlinkSync(target, link);
	return `symlinked ${rel(link)}`;
}

function syncSkillSymlinks(check: boolean): string[] {
	const changes: string[] = [];
	const sharedExisted = ensureDir(SHARED_SKILLS, check, changes);
	const claudeSkillsExisted = ensureDir(CLAUDE_SKILLS, check, changes);

	// In check mode the shared dir may not exist yet (we didn't create it), so
	// treat a missing dir as empty rather than reading through it.
	const wanted =
		sharedExisted || !check
			? readdirSync(SHARED_SKILLS, { withFileTypes: true })
					.filter((e) => e.isDirectory())
					.map((e) => e.name)
			: [];
	const wantedSet = new Set(wanted);
	validateAllSharedSkills(wanted);

	for (const name of wanted) {
		const change = materializeSymlink(name, check);
		if (change) changes.push(change);
	}

	// If .agents/skills/ was missing entirely (sparse checkout, manual rm) and we
	// just created it empty, refuse to prune -- otherwise we'd silently delete every
	// Claude symlink. User-created symlinks elsewhere are unaffected either way.
	if (!sharedExisted && wanted.length === 0) return changes;

	if (claudeSkillsExisted || !check) {
		for (const entry of readdirSync(CLAUDE_SKILLS, { withFileTypes: true })) {
			if (entry.isSymbolicLink() && !wantedSet.has(entry.name)) {
				const p = join(CLAUDE_SKILLS, entry.name);
				if (!check) unlinkSync(p);
				changes.push(`pruned dangling ${rel(p)}`);
			}
		}
	}
	return changes;
}

function syncAgents(check: boolean): string[] {
	const changes: string[] = [];
	// Guard against a symlinked parent dir redirecting TOML writes out of the
	// repo before we create/populate `.codex/agents/`. Read-only, safe in check mode.
	assertDirInsideRepo(CODEX_AGENTS);
	const codexExisted = ensureDir(CODEX_AGENTS, check, changes);
	const claudeAgentsExisted = ensureDir(CLAUDE_AGENTS, check, changes);

	const wanted = new Set<string>();
	const mdFiles =
		claudeAgentsExisted || !check
			? readdirSync(CLAUDE_AGENTS, { withFileTypes: true })
					.filter((e) => e.isFile() && e.name.endsWith(".md"))
					.map((e) => e.name)
			: [];
	for (const mdName of mdFiles) {
		const mdPath = join(CLAUDE_AGENTS, mdName);
		const { meta, body } = parseMd(mdPath);
		const tomlName = `${mdName.slice(0, -3)}.toml`;
		const tomlPath = join(CODEX_AGENTS, tomlName);
		assertNotSymlink(tomlPath);
		const fresh = renderToml(meta, body, rel(mdPath));
		const current = existsSync(tomlPath)
			? readFileSync(tomlPath, "utf-8")
			: null;
		if (current !== fresh) {
			if (!check) writeFileSync(tomlPath, fresh, "utf-8");
			changes.push(`wrote ${rel(tomlPath)}`);
		}
		wanted.add(tomlName);
	}

	if (codexExisted || !check) {
		for (const entry of readdirSync(CODEX_AGENTS, { withFileTypes: true })) {
			if (
				entry.isFile() &&
				entry.name.endsWith(".toml") &&
				!wanted.has(entry.name)
			) {
				const p = join(CODEX_AGENTS, entry.name);
				if (!check) unlinkSync(p);
				changes.push(`pruned orphan ${rel(p)}`);
			}
		}
	}
	return changes;
}

function findClaudeMds(dir: string, acc: string[]): void {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		if (entry.isDirectory()) {
			if (MIRROR_SKIP_DIRS.has(entry.name)) continue;
			findClaudeMds(join(dir, entry.name), acc);
		} else if (entry.isFile() && entry.name === "CLAUDE.md") {
			acc.push(join(dir, entry.name));
		}
	}
}

// Every directory holding a CLAUDE.md gets a sibling AGENTS.md symlink so Codex
// (which reads AGENTS.md) sees the same doc Claude reads. In check mode this
// performs NO filesystem mutation -- it records the would-be change and returns.
function mirrorAgentsMd(check: boolean): string[] {
	const changes: string[] = [];
	const claudeMds: string[] = [];
	findClaudeMds(REPO, claudeMds);

	for (const claudeMd of claudeMds) {
		const agentsMd = join(dirname(claudeMd), "AGENTS.md");
		let isSymlink = false;
		let exists = false;
		try {
			const st = lstatSync(agentsMd);
			exists = true;
			isSymlink = st.isSymbolicLink();
		} catch {
			// not present
		}
		if (isSymlink) {
			if (readlinkSync(agentsMd) === "CLAUDE.md") continue;
			if (!check) unlinkSync(agentsMd);
		} else if (exists) {
			// A drifted hand-written AGENTS.md -- replace it with the symlink.
			if (!check) unlinkSync(agentsMd);
		}
		if (!check) symlinkSync("CLAUDE.md", agentsMd);
		changes.push(`symlinked ${rel(agentsMd)} -> CLAUDE.md`);
	}
	return changes;
}

function main(): number {
	const check = process.argv.includes("--check");
	// In check mode nothing is written: the sync functions compute the would-be
	// changes and mutate nothing, so the drift gate is a read-only run.
	const changes = [
		...syncSkillSymlinks(check),
		...syncAgents(check),
		...mirrorAgentsMd(check),
	];
	for (const c of changes) console.log(c);
	if (check && changes.length > 0) {
		console.error(
			"sync-agent-config would introduce changes; run `make sync-agent-config`, stage them, and commit again.",
		);
		return 1;
	}
	return 0;
}

process.exit(main());
