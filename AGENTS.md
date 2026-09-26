# Graphden instructions for Codex

Read `CLAUDE.md` and `dev/wtq/AGENT.md` before editing. They are the shared
project and worktree contracts for both assistants; keep them and all Claude
settings, hooks and skills intact. Claim an isolated worktree with `bb wt claim`
and make code changes there. Do not use the shared git stash.

Load the relevant `graphden-*` skills from `.claude/skills/` (or the adapted
copies in `~/.codex/skills/`). For Codex, read their SKILL.md files directly.
Use `bb wt test` for focused tests, `bb wt up` for an isolated live instance,
and `bb wt merge` for the serialized landing gate. These commands supersede
older `bb rebuild` and shared-port examples in individual skills.

Claude hooks do not run in Codex. Explicitly perform the package-quality
review before changing impls.clj, and run biome/stylelint after UI edits.
Verify interactive changes with the browser tools or the repository's
Playwright CLI fallback; unavailable MCP tools do not prevent CLI work.
Do not add Claude co-author trailers to work performed by Codex.

The sibling private `graphden-internal` repository contains shared project
memory in `claude-memory/MEMORY.md`. Read relevant notes there when available;
never copy private memory, credentials or internal planning into this public
repository. Treat historical task status as a lead and verify against git.

For authorized releases, read `README.md`, `DEPLOY.md` and `scripts/release.clj` in the
workspace’s `graphden-cloud` checkout (a sibling of the main checkout). `bb release --push` from graphden-cloud owns pin bumps,
tenancy CI, production deployment and the public main/prod-tag update.
Do not push tenancy main separately before the release pin is bumped.
