# Building Scoutro

## Requirements

- Java 17 or newer to compile (`build.properties`: `javacRelease=17`). The
  container image uses Eclipse Temurin 24 (JDK), like the official YaCy image.
- Apache Ant (`build.xml`); dependencies are resolved with Ivy on the first
  build (network access to Maven Central is needed).
- `git` and a checkout with `.git`: the build records the source revision in
  `defaults/yacyBuild.properties`.
- Docker with BuildKit for the container image.

## Local build without a container

```sh
ant compile            # compile core and servlets
./startYACY.sh -f      # run in the foreground on http://localhost:8090
```

Upstream tests: `ant test` (unit tests), `ant jetty12-server-test`, and the
shell smoke tests in `test/` (e.g. `sh test/jetty-smoke-test.sh <url>`).
Scoutro UI tests: see `test/scoutro-ui/README.md`.

`ant citation-postprocessing-test` tests Citation-only reference finalization
against temporary index/DATA, including LocalParams, Collection scopes,
missing response times, restart and schema persistence failures. See
[Citation and reference postprocessing](SCOUTRO_CITATION_REFERENCES.md).

## Versioning

| What | Value | Where |
|---|---|---|
| YaCy version | `1.942` | `build.properties` → `releaseVersion` (unchanged from upstream) |
| Scoutro release | `22` | `scoutro.properties` → `scoutro.release` |
| Full Scoutro version | `1.942-scoutro.22` | git tag `v1.942-scoutro.22`, image tag `1.942-scoutro.22` |
| Image alias | `0.9.0` | image tag `0.9.0` (same immutable digest as the full version) |

Why not change `releaseVersion`: YaCy parses its version as a number
(`yacyVersion`, `Seed.getVersion()` use `Double.parseDouble`, and
`yacyBuildProperties.versionMatcher` is `\d+\.\d{1,3}`). The value is sent to
other peers and used for update checks, so a value like `1.942-scoutro.1` or
`1.942+scoutro.1` would break peer and update handling. The upstream version
therefore stays visible and numeric, and the Scoutro release is added
separately.

Why `-scoutro.N` and not `+scoutro.N`: Docker/OCI tags allow only
`[A-Za-z0-9_.-]`, so `+` cannot be used in image tags. `1.942-scoutro.1` is
valid as a git tag and as an image tag, sorts correctly per upstream version,
and shows both the YaCy version and the Scoutro release.

## Container image

`docker/Dockerfile.scoutro` builds the image from source in two stages, like
the official `docker/Dockerfile`, and keeps the same runtime layout:

| | Official YaCy image | Scoutro image |
|---|---|---|
| Base | `eclipse-temurin:24-jdk-noble` | same |
| Application | `/opt/yacy_search_server` | same |
| Data volume | `/opt/yacy_search_server/DATA` | same |
| User | `yacy`, uid 100, gid 101 (system user) | same |
| Ports | 8090, 8443 | same |
| Entrypoint / command | `/__cacert_entrypoint.sh` / `startYACY.sh -f` | same |
| Defaults | admin password `yacy`, `adminAccountForLocalhost=false`, `server.https=true` | same |
| `.git` in the image | no | no |

The Scoutro build uses `docker/Dockerfile.scoutro.dockerignore` instead of
the root `.dockerignore`: the root file excludes `test/`, but `build.xml`
needs `test/jetty` for the Solr 9 bridge (`build-solr9-bridge`).

```sh
VERSION=1.942-scoutro.22
docker build -f docker/Dockerfile.scoutro \
  --build-arg SCOUTRO_VERSION=$VERSION \
  --build-arg SCOUTRO_REVISION=$(git rev-parse HEAD) \
  -t ghcr.io/salamont/scoutro:$VERSION .
```

Labels: `org.opencontainers.image.version`, `.revision` (Scoutro commit),
`.source` (Scoutro repository), `.licenses=GPL-2.0-or-later`, and
`io.github.salamont.scoutro.upstream` (YaCy repository).

Publishing (`docker push ghcr.io/salamont/scoutro:…`) is a separate, later
step and is not part of phase 1.

### Behind a TLS-intercepting proxy

The builder honors inherited HTTP(S) proxy settings for Ant/Java. Supply a
session CA with BuildKit rather than modifying or retagging the upstream base:

```sh
docker build --secret id=proxy_ca,src="$CODEX_PROXY_CERT" \
  -f docker/Dockerfile.scoutro \
  --build-arg SCOUTRO_VERSION=1.942-scoutro.22 \
  --build-arg SCOUTRO_REVISION="$(git rev-parse HEAD)" \
  -t scoutro:0.9.0-rc-local .
```

The optional secret and temporary Java trust store exist only during the build;
they are removed before the runtime application is copied. TLS verification
remains enabled. Do not put proxy credentials or a session CA in image layers.
Build from a normal checkout with its own `.git` directory; a worktree `.git`
file referring to a path outside the build context cannot provide the revision.

## Release candidate and DATA upgrades

[Scoutro 0.9.0 release/upgrade notes](SCOUTRO_RELEASE_0.9.0.md) are the current
operator contract. [Changelog](../CHANGELOG.md) describes the user-facing changes.
The pre-existing main publication workflow will publish **both** immutable
image tags `1.942-scoutro.22` and `0.9.0` after a separately authorized merge;
its existing-tag refusal must remain enabled. A branch push/Draft PR does not
publish the image. Do not manually dispatch the workflow in addition to the
merge-triggered run, or race two publications of the same version.

For local distribution checks use `ant clean all dist`, then the unchanged
Dependency-Guard and Solr-Spike. `test/scoutro-release/check-attribution.py`
compares original upstream manifests, Maven metadata and attribution bytes with
the distribution and an exported local image root. Never infer image contents
from a successful tarball build alone.

The image retains the YaCy application/DATA paths and non-root runtime user.
**The knowledge graph now requires migrations 4 → 5 → 6.** Before a real
upgrade, stop the old peer and verify a full backup of its DATA, configuration
and compatible application/image revision. Keep it outside the live volume;
verify checksums and a restore on an isolated copy. A KG-only backup is not a
full Solr/configuration backup. Leave room for the pre-upgrade copy, WAL,
archive growth, rebuild/shadow data where used, and the configured reserve.

Startup integrity checks, a verified pre-upgrade KG copy and additive schema
migrations/backfill protect still-existing relevant evidence before new
extractor work. If an upgrade/storage hold appears, keep the protection in
place, resolve its cause and verify a backup; do not delete/archive tables,
force a schema version or clear the hold to obtain progress. Lost legacy
quotes cannot be recreated from job titles. No production upgrade is implied
by local fixture tests.

Rollback uses a compatible verified older backup. Preserve the current
schema-6 graph and complete authorized history export separately first.
B requires schema 5; pre-B versions require their supported schema (typically
4). No in-place lossless downgrade, no automatic mixing of newer history into
an older restore. Older binaries ignore the LLM schedule and may call unfinished
chunks again; disable LLM or pause the KG before such a rollback when needed.

## Security notes

- No new ports, no privileged operations, same non-root user as upstream.
- No secrets in the image or in the repository. The default admin password
  (`yacy`) is the upstream default and must be changed at first start, or set
  from a secret by the deployment (as the Olares package does with
  `bin/passwd.sh`).
- All Scoutro pages under `htroot/` are static UI files. Admin pages (`*_p`)
  keep YaCy's authentication; the UI tests check that they answer 401 without
  credentials.
