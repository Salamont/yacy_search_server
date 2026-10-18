# Scoutro checks of pull requests (CI)

## Why the YaCy workflows are skipped in this fork

| Workflow | Trigger | Why it does not run here |
|---|---|---|
| `ant-build-pull-request.yaml` | `pull_request` | job condition `github.repository == 'yacy/yacy_search_server'` |
| `build-docker-image.yaml` | push | the same condition |
| `ant-build.yaml` | push/PR to `master` | this fork's branch is `main` |
| `publish-scoutro.yml` | push to `main`, manual | publishes the image; never for a pull request |

GitHub therefore lists them as **skipped** for every pull request of `Salamont/yacy_search_server`; a skipped run is
no test result.

## The fork's own check

`.github/workflows/scoutro-checks.yml` runs for every pull request (whatever its base, so stacked pull requests are
checked too) and on demand:

- **python**: the offline suites of Discovery, the agent bridge, the MCP adapter and the API contract (including the
  check that `tools/scoutro/generate_api_description.py` reproduces `openapi.json` and `actions.json` exactly).
- **java**: `ant clean compile` from removed build directories, then every `scoutro-*-test` target the branch's
  `build.xml` defines.

Both call `tools/scoutro/ci/scoutro-checks.sh`, which runs the same locally (`all`, `java` or `python`). Read-only
token, no secrets, no deployment, no Scoutro network service, no model request. A local run is no CI result; only a
completed run of this workflow on the pull request's head is.

## Output of a failing check

Each check's output goes to a temporary file. A passing check prints its last lines; a failing one prints JUnit's
summary first (the failing tests by name, their exception and the `net.yacy` stack lines, `Tests run:`), then the last
40 lines. Ant runs without `-q`, so the forked JUnit runner's output is part of the log.
