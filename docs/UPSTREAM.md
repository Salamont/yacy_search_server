# Upstream maintenance (YaCy → Scoutro)

Scoutro must be able to take over new YaCy versions with little effort. The
rules in this document keep the Scoutro delta small and isolated.

## Repositories and remotes

| Remote | URL | Purpose |
|---|---|---|
| `origin` | `https://github.com/Salamont/yacy_search_server` | Scoutro (GitHub fork of YaCy; the fork relation to upstream is kept) |
| `upstream` | `https://github.com/yacy/yacy_search_server` | YaCy |

```sh
git clone https://github.com/Salamont/yacy_search_server scoutro
cd scoutro
git remote add upstream https://github.com/yacy/yacy_search_server
git fetch upstream
```

The repository name stays `yacy_search_server` for now. GitHub keeps the fork
relation when a fork is renamed, and redirects the old URL. Renaming to
`Scoutro` is possible later, but it is not part of this phase.

## Branches

| Branch | Content | Rule |
|---|---|---|
| `master` | Exact mirror of `upstream/master` | Never commit here. Only fast-forward to upstream. |
| `main` | Scoutro main branch = `master` + all Scoutro changes | Default branch for Scoutro development and releases. Feature branches are merged in, upstream is merged in, never rebased. |
| `feat/scoutro-*` | Scoutro work (e.g. `feat/scoutro-mobile-ui`) | Branch off `main`, keep commits small and logical, merge back into `main`. |

## Base version

| | |
|---|---|
| YaCy version | 1.942 (`build.properties`: `releaseVersion=1.942`) |
| Upstream branch | `master` (default branch; upstream develops directly on `master`) |
| Upstream commit | `b50b76bd552a851f737b98683f20d8b861e137ae` (2026-09-23, "Merge pull request #835") |
| Upstream tag | none. The latest release tag is `Release_1.941` (99 commits older). 1.942 exists only as `master` plus the official Docker image. |
| Official image | `docker.io/yacy/yacy_search_server@sha256:55c5e5dd928f312cf47a57afa8d645df37366f15678e0983c8b7fdbe3b5f6475`, label `org.opencontainers.image.revision=b50b76b…` |

How to find the commit of an official image:

```sh
docker pull docker.io/yacy/yacy_search_server:latest
docker inspect docker.io/yacy/yacy_search_server:latest \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
```

## Sync process

```text
YaCy publishes a new version
→ upstream fetch
→ update Scoutro
→ resolve conflicts
→ tests
→ build Scoutro image
→ test Olares separately
→ release
```

### 1. Update the mirror

```sh
git fetch upstream
git checkout master
git merge --ff-only upstream/master      # or: git merge --ff-only <upstream commit of the release>
git push origin master
```

`master` must never diverge from upstream. If `--ff-only` fails, someone has
committed to `master`. Move that commit to a feature branch first.

### 2. Merge into `main`

```sh
git checkout main
git merge --no-ff master -m "merge: YaCy upstream <commit> (<version>)"
```

**Merge, do not rebase** `main` onto upstream. The branch is public,
merges keep the history reproducible, and every conflict resolution stays
visible in one merge commit. Feature branches that are not yet merged may be
rebased onto `main` before they are merged.

### 3. Resolve conflicts

Conflict strategy:

1. **Upstream wins for everything outside the UI layer.** Scoutro does not
   change Java sources, index, storage, ranking, crawler, P2P or API
   contracts. A conflict there means that a Scoutro change went too far.
2. **For the templates listed below**, take the upstream version first, then
   re-apply the small Scoutro hunk. Every Scoutro hunk in an upstream file is
   marked with a `Scoutro:` comment, so it is easy to find and re-apply.
3. **Scoutro-owned files** (see below) never conflict. If upstream changes
   the markup they depend on, adapt them.
4. Keep `releaseVersion` in `build.properties` exactly as upstream sets it
   (see versioning in `BUILD.md`).

### 4. Test

```sh
# optional: compile and run upstream unit/smoke tests
ant compile
sh test/jetty-smoke-test.sh              # see test/ for further upstream smoke tests

# build the Scoutro image and start it with a fresh DATA directory
docker build -f docker/Dockerfile.scoutro -t scoutro:dev .
docker run -d --name scoutro-test -p 8090:8090 scoutro:dev

# mobile/desktop UI checks (360 / 390 / 412 px and desktop)
SCOUTRO_URL=http://127.0.0.1:8090 node test/scoutro-ui/scoutro-ui-test.mjs
```

Additionally start the new image once with a **copy** of an existing `DATA`
directory (never the live one) and check that settings, accounts and the
index are still there.

### 5. Release

1. Update `scoutro.properties` (`scoutro.upstream.*`, `scoutro.release`).
2. Tag: `git tag -a v<yacyVersion>-scoutro.<n>`, e.g. `v1.942-scoutro.1`.
3. Build and push the image `ghcr.io/salamont/scoutro:<yacyVersion>-scoutro.<n>`.
4. Test the image on Olares separately (Olares packaging lives in its own
   repository and is updated independently).

## Conflict-prone files

Upstream files that Scoutro changes. Keep every change here small and
marked with `Scoutro:`.

| File | Scoutro change | Upstream change rate |
|---|---|---|
| `htroot/env/templates/header.template` | toggle id, sidebar id, mobile-only groups, brand | medium (5 changes since 2023) |
| `htroot/env/templates/metas.template` | include `brand/brand.css`, `scoutro.css`, `theme.css`, `scoutro.js`, favicon | low (1 change since 2023) |
| `htroot/env/templates/footer.template`, `simplefooter.template` | attribution include | low |
| `htroot/env/templates/simpleSearchHeader.template` | brand logo | low (2 changes since 2023) |
| `htroot/index.html` | Scoutro start page markup | low (2 changes since 2023) |
| `htroot/yacysearch.html` | empty state markup ("No Results." branches and hidden block after the result count) | low (2 changes since 2023) |
| `htroot/jslicense.html` | entry for `scoutro.js` | low |
| `README.md` | Scoutro block at the top | medium |
| `defaults/web.xml` | Scoutro API servlet mapping and security constraints (two marked blocks) | low (servlet list changes when upstream adds servlets) |

Scoutro-owned files (never touched by upstream):

- `htroot/env/scoutro/` — CSS, JS, images
- `htroot/env/templates/scoutro/` — central branding templates
- `htroot/scoutro-about.html`
- `docs/SCOUTRO.md`, `docs/UPSTREAM.md`, `docs/BUILD.md`, `docs/UI.md`
- `test/scoutro-ui/`, `test/scoutro-api/`
- `docker/Dockerfile.scoutro`, `docker/Dockerfile.scoutro.dockerignore`, `scoutro.properties`
- `source/net/yacy/scoutro/` — Scoutro API (new package, no upstream class changed)
- `htroot/env/scoutro/api/` — generated `openapi.json`, `actions.json`
- `tools/scoutro/` — `scoutroctl`, API description generator
- `docs/API.md`, `docs/ACTIONS.md`

Upstream files Scoutro depends on without changing them (watch them during a
sync): `htroot/env/bootstrap-base.css` (`.sidebar` rules), `htroot/env/base.css`
(`dl`/`dt`/`dd`, `SubMenu`), `htroot/yacysearch.html`, and the Bootstrap 3
version in `htroot/env/bootstrap/`.

The Scoutro API depends on these YaCy HTTP contracts (checked by
`test/scoutro-api/test_api.py`; run it after every sync):
`yacysearch.json`, `Crawler_p.json` (parameters of the site crawl start,
`terminate`/`handle`), `CrawlProfileEditor_p.xml`, `api/status_p.xml`,
`api/version.xml`, `solr/select` (fields `sku`, `host_s`, `title`,
`last_modified`, `collection_sxt`), and the admin role `adminRight` with the
localhost password-hash login used by `bin/apicall.sh`.

## Checking the current delta

```sh
git diff --stat master...HEAD                       # all Scoutro changes
# no upstream Java class, library or default changed (only the Scoutro package
# and the marked web.xml blocks are allowed):
git diff --stat master...HEAD -- source/ lib/ defaults/ ':!source/net/yacy/scoutro' ':!defaults/web.xml'
git diff master...HEAD -- defaults/web.xml           # only the two Scoutro blocks
```
