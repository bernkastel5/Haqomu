# CI workflow (pending permission)

`build.yml` in this directory is the CI workflow for the repository. It is NOT in
`.github/workflows/` yet because the GitHub App used to push this branch does not
have the `Workflows` permission; the server rejects pushes that create or update
files under `.github/workflows/`.

To enable CI:

1. A user with workflow permission copies `docs/ci/build.yml` to
   `.github/workflows/build.yml` and pushes, or creates that file in the GitHub UI
   with the same content.
2. Or grants the pushing GitHub App the `Workflows` permission (repository settings /
   organization settings, depending on how the app is installed), then asks the agent
   to push it.

The workflow runs: `gradle :app:assembleDebug :app:testDebugUnitTest` with JDK 17
and Android platform 35, plus a `python3 -m py_compile tools/*.py` job.
