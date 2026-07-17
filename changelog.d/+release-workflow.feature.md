Added towncrier + bump-my-version release tooling (`changelog.d/README.md`
for the recipe) and a GitHub Actions CI workflow that runs the test suite on
every push/PR to `master` — previously this project had no CI at all, and
only one git tag (`v1.0.0`) despite 18 versions of history since.
