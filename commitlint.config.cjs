// commitlint config — enforces Conventional Commits.
// Each non-default rule has a `// why:` line directly above it.
// Project policy: no rule disabled without rationale.

module.exports = {
  extends: ['@commitlint/config-conventional'],
  // why: one-off pre-existing commit (`1a8fa2fc`) used the
  // compound type `docs+css(editor):` which isn't in the type
  // enum below. The commit is already on origin and amending
  // would require force-push; the bad subject is documented
  // here as a tolerated historical artefact. New commits use
  // the standard single-type form.
  ignores: [(message) => message.startsWith(
    'docs+css(editor): Phase D — drop dead CSS')],
  rules: {
    // why: Conventional Commits default caps body lines at 100; this
    // repo writes prose-heavy commit bodies (architectural decisions,
    // lessons learned), wrapping at ~72 for `git log` readability.
    // Bump cap to 200 so long URLs / quoted error messages aren't
    // split mid-word.
    'body-max-line-length': [2, 'always', 200],

    // why: same as body — footers carry Co-Authored-By lines +
    // occasional URLs; 200 covers both.
    'footer-max-line-length': [2, 'always', 200],

    // why: permit any case in subject — graphden mixes lowercase
    // prose ('feat(editor):') with proper-noun caps ('docs(README)')
    // depending on what reads cleaner.
    'subject-case': [0],

    // why: default Conventional Commits types + a few graphden uses
    // already (`skill` for .claude/skills/ edits). Enumerated
    // explicitly so the linter recognises them.
    'type-enum': [2, 'always', [
      'build', 'chore', 'ci', 'docs', 'feat', 'fix', 'perf', 'refactor',
      'revert', 'style', 'test', 'skill',
    ]],
  },
};
