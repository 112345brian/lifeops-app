# Changelog fragments

One file per change, added in the same commit as the change itself,
compiled into `CHANGELOG.md` at release time by `towncrier build`.

**Filename**: `+<short-slug>.<type>.md` — the leading `+` marks it as an
orphan fragment (no issue number; this project has no issue tracker).
`<type>` is one of:

- `feature` — new capability (renders under "Added")
- `fix` — bug fix (renders under "Fixed")
- `misc` — everything else worth mentioning — rename, refactor, config,
  internal tooling (renders under "Changed")

**Content**: one or two sentences, same voice as existing CHANGELOG.md
entries — lead with what changed, not why.

Example: `changelog.d/+add-retry-logic.fix.md`
```
API calls now retry with exponential backoff on 5xx responses instead of
failing outright on the first transient error.
```

**Release recipe** (run from the repo root — both tools auto-discover their
standalone config files there):
```
towncrier build --version X.Y.Z --yes   # compiles fragments into CHANGELOG.md, deletes them
git add CHANGELOG.md changelog.d/
git commit -m "Update changelog for vX.Y.Z"
bump-my-version bump patch|minor|major   # bumps VERSION + lifeops/__init__.py, commits, tags
git push origin master --tags
```

Two tools stay decoupled on purpose: `bump-my-version` refuses to run on a
dirty tree, so the changelog commit happens first and cleanly, then the
version-bump commit is its own atomic step. Both tools live in
`requirements-dev.txt`, not `requirements.txt` — they're release tooling,
not something the running app needs.

Note: this project has no `pyproject.toml`, so both tools use standalone
config files instead — `.bumpversion.toml` and `towncrier.toml`, both
auto-discovered from the current directory, same as a `pyproject.toml`
section would be.
