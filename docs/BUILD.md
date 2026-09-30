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

## Versioning

| What | Value | Where |
|---|---|---|
| YaCy version | `1.942` | `build.properties` → `releaseVersion` (unchanged from upstream) |
| Scoutro release | `6` | `scoutro.properties` → `scoutro.release` |
| Full Scoutro version | `1.942-scoutro.6` | git tag `v1.942-scoutro.6`, image tag `1.942-scoutro.6` |

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
VERSION=1.942-scoutro.1
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

If the Ivy dependency download fails with
`unable to find valid certification path`, the build container does not trust
the proxy's certificate. Build a local base image that adds the CA to the
Java trust store and tag it as `eclipse-temurin:24-jdk-noble` for the local
build only. Never publish an image built that way.

## DATA compatibility

The Scoutro image reads and writes the same `DATA` layout as the official
YaCy 1.942 image (`SETTINGS/yacy.conf`, `INDEX`, `HTCACHE`, `LOG`, `QUEUES`,
`PACKS`, …). There is no data migration. Test performed for phase 1 (on a
**copy** of the data):

1. DATA created by the official image (uid 1000 as on Olares, with the
   Olares `init-config` step) → started with the Scoutro image: settings,
   admin account and index documents present, no errors.
2. The same DATA started again with the official image (rollback): index
   documents present, no errors.

Scoutro adds UI files under `htroot/` and the API classes (package
`net.yacy.scoutro.api`, registered in `defaults/web.xml`). It does not add
files to `DATA`, and it does not change configuration defaults or the index
schema. The API only writes the two allowlisted settings to `yacy.conf` when
`config.set` is called.

## Security notes

- No new ports, no privileged operations, same non-root user as upstream.
- No secrets in the image or in the repository. The default admin password
  (`yacy`) is the upstream default and must be changed at first start, or set
  from a secret by the deployment (as the Olares package does with
  `bin/passwd.sh`).
- All Scoutro pages under `htroot/` are static UI files. Admin pages (`*_p`)
  keep YaCy's authentication; the UI tests check that they answer 401 without
  credentials.
