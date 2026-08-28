---
paths:
  - ".claude/skills/**"
  - ".claude/agents/**"
  - ".agents/skills/**"
  - ".codex/agents/**"
---

# Codex <-> Claude skill & subagent sync

This repo is dual-tool: both Claude Code and Codex CLI are expected to work. Skills, subagents, and the root agent docs are shared where possible. Read this before creating, editing, or moving any skill or subagent in this repo.

## Layout

```
.agents/skills/<name>/SKILL.md        # single source - both tools auto-discover
.claude/skills/<name>                 # symlink -> ../../.agents/skills/<name>
.claude/agents/<name>.md              # source of truth (markdown + YAML frontmatter)
.codex/agents/<name>.toml             # GENERATED from the .md; commit it
<dir>/CLAUDE.md                       # source of truth (any dir, root or nested)
<dir>/AGENTS.md                       # symlink -> CLAUDE.md (Codex reads this)
scripts/sync_agent_config.ts          # converter (bun run)
```

Codex auto-scans `.agents/skills/` walking up from cwd to repo root. Claude auto-scans `.claude/skills/`. The symlink is the only reason both find the same file.

## Skills: the shared-frontmatter rule

Every `SKILL.md` under `.agents/skills/` MUST be readable by both tools. The overlap is narrow - stick to it:

**Always safe (both tools):**
- `name` - required, lowercase-hyphens, <=64 chars
- `description` - required, <=250 chars, written for implicit matching
- Plain markdown body

**Claude-only - DO NOT use in shared skills:**
- `allowed-tools:` - Codex has no equivalent
- `context: fork`, `agent:`, `model:`, `effort:`, `hooks:`, `paths:`, `shell:`, `argument-hint`, `disable-model-invocation`, `user-invocable`
- `$ARGUMENTS`, `$1`, `$2`, ... - Claude substitutes these at runtime; Codex passes them through literally
- `` !`shell command` `` and ```` ```! ```` blocks - Claude preprocesses; Codex does not
- `${CLAUDE_SKILL_DIR}`, `${CLAUDE_SESSION_ID}` - Claude-only interpolations

**Codex-only - OK to include (Claude ignores unknown keys):**
- Sibling `agents/openai.yaml` for Codex UI metadata, invocation policy, tool dependencies

If a skill genuinely needs Claude-only features, keep it at `.claude/skills/<name>/` as a real directory (no symlink) and do not mirror it to `.agents/skills/`. Note this with a `<!-- claude-only -->` comment at the top of the body. (This template ships `thermo-nuclear-code-quality-review` as exactly such a Claude-only skill because it sets `disable-model-invocation`.)

## Subagents: convert, don't symlink

The formats are structurally different:
- Claude: `.claude/agents/<name>.md` - YAML frontmatter (`name`, `description`, `tools`, `model`) + markdown body as system prompt
- Codex: `.codex/agents/<name>.toml` - TOML with `name`, `description`, `developer_instructions = """..."""` (body as triple-quoted string)

Rules:
- `.claude/agents/*.md` is the **source of truth**. Never hand-edit `.codex/agents/*.toml`.
- Run `make sync-agent-config` after editing a subagent. The pre-commit hook will refuse the commit if the generated TOML is out of date.
- Claude-only frontmatter keys (`tools`, `model`, `color`) don't translate - they are preserved as reference comments in the TOML. Document tool expectations in the prose body so both sides pick them up.
- Inside the body, avoid literal `"""` sequences (they'd close the TOML string); the converter escapes them but it's easier to just not use them.

## CLAUDE.md <-> AGENTS.md: mirror, don't duplicate

Claude reads `CLAUDE.md`; Codex reads `AGENTS.md`. To keep them identical without hand-syncing, `CLAUDE.md` is the **source of truth** and `AGENTS.md` is a symlink pointing at the sibling `CLAUDE.md`. This applies to **every** directory, root and nested, and `make sync-agent-config` maintains it:

- A directory with a `CLAUDE.md` gets a sibling `AGENTS.md` symlink created if missing.
- A pre-existing real `AGENTS.md` (a drifted hand-written copy) is **replaced** by the symlink - edit `CLAUDE.md`, never `AGENTS.md`.

## Do not try to sync these

- `.claude/rules/*.md` vs `.codex/rules/*.rules` - different languages (prose vs permission DSL). Maintain separately.
- `.claude/commands/*.md` - Claude-only; Codex has no slash-command runtime.

## Tooling

- `make sync-agent-config` - idempotent. Creates missing `.claude/skills/` symlinks for every shared skill under `.agents/skills/`, regenerates `.codex/agents/*.toml` from `.claude/agents/*.md`, mirrors every `CLAUDE.md` to a sibling `AGENTS.md` symlink, and auto-prunes dangling symlinks and orphan TOMLs silently. Pass `--check` (as the prek hook and CI do) to fail on drift instead of fixing it.
- Pre-commit: [`prek`](https://prek.j178.dev/installation/), configured in `prek.toml` at repo root. Register once per clone with `prek install`. Runs `bun run scripts/sync_agent_config.ts --check` and fails the commit on drift.
- TypeScript script runs via `bun run scripts/sync_agent_config.ts`. It is self-contained (no npm deps) - it parses the small single-line-scalar frontmatter itself.

## When adding a new skill or subagent

The `manage-agent-config` skill (at `.agents/skills/manage-agent-config/`) has the full decision tree and is invoked automatically when an agent touches any of these directories. The short version:

1. Shared skill (works in both tools) -> `.agents/skills/<name>/SKILL.md`. Run `make sync-agent-config`.
2. Claude-only skill (uses `$ARGUMENTS`, `allowed-tools`, `disable-model-invocation`, etc.) -> `.claude/skills/<name>/SKILL.md` as a real directory. No symlink.
3. Subagent -> edit `.claude/agents/<name>.md`. Never hand-edit `.codex/agents/*.toml`. Run `make sync-agent-config`. Commit both files.
4. Delete or rename -> edit/remove the source, then `make sync-agent-config` cleans up the mirror.
