# Scoutro

## What is Scoutro?

Scoutro is an independent community project: a mobile-first search, crawling
and indexing application **based on [YaCy](https://yacy.net/)**. It keeps the
complete technical functionality of YaCy (crawler, index, peer-to-peer
network, search APIs, administration) and adds its own user interface layer
and branding:

- a simple, modern search interface for people and local AI agents,
- an administration that is fully usable on smartphones,
- its own name and visual identity.

Scoutro does **not** hide where it comes from. The visible product name is
Scoutro. YaCy is named as the technical foundation in the footer and on the
About page.

> Scoutro is an independent community project based on YaCy.
> YaCy is developed by the YaCy project.
> Scoutro is not affiliated with or endorsed by the YaCy project.

## YaCy as the technical foundation

| | |
|---|---|
| Upstream project | YaCy, <https://yacy.net/> |
| Upstream repository | <https://github.com/yacy/yacy_search_server> |
| Upstream maintainer | Michael Peter Christen and the YaCy contributors (see `AUTHORS`) |
| Scoutro repository | <https://github.com/Salamont/yacy_search_server> (GitHub fork of the upstream repository) |
| Base version | YaCy 1.942, upstream `master` at commit `b50b76bd552a851f737b98683f20d8b861e137ae` (2026-09-23) |

The base commit is the exact revision of the official image
`docker.io/yacy/yacy_search_server@sha256:55c5e5dd…6475` (OCI label
`org.opencontainers.image.revision`), which the existing Olares installation
runs. Keeping this base means the existing `DATA` directory stays compatible.

Synchronisation with upstream is described in [UPSTREAM.md](UPSTREAM.md).

## License

- YaCy is licensed under the **GNU General Public License, version 2 or (at
  your option) any later version** (`gpl.txt`, `LICENSES/GPL-2.0-or-later.txt`,
  `COPYRIGHT`). Parts in `source/net/yacy/cora` are LGPL-2.1-or-later
  (`lgpl21.txt`). Third-party notices are in `NOTICE` and in the license
  headers of the bundled libraries.
- Scoutro is a modified version of YaCy and is distributed under the **same
  license, GPL-2.0-or-later**. All Scoutro changes in this repository are
  licensed under GPL-2.0-or-later.
- The complete source code of every Scoutro release is this repository at the
  corresponding tag. Container images built from it point to the source via
  the OCI labels `org.opencontainers.image.source` and
  `org.opencontainers.image.revision`.
- Existing license files, copyright notices and headers of YaCy are kept
  unchanged. They must not be removed. This includes the LibreJS license block
  in `htroot/env/templates/metas.template` and the list in `htroot/jslicense.html`.

## Community status

Scoutro is a private community development. It is **not** an official YaCy
product, it is not published in the Olares Market, and it is not affiliated
with or endorsed by the YaCy project. The name "YaCy" is used only to
describe the technical origin.

## Differences from upstream

Scoutro keeps the YaCy core (Java sources, index format, storage, ranking,
crawler, P2P protocol, authentication, API contracts) unchanged. The changes
are limited to the web interface, assets, documentation, tests and build
files:

| Area | Change | Files |
|---|---|---|
| Mobile navigation | The hamburger menu opens the complete administration menu. Before, the whole left navigation was hidden below 768 px and the toggle was covered by the search field. | `htroot/env/templates/header.template`, `htroot/env/scoutro/` |
| Responsive admin | Wide tables scroll, form controls fit the screen, form labels stack, touch-sized controls. | `htroot/env/scoutro/scoutro.css`, `htroot/env/scoutro/scoutro.js` |
| Branding | Product name, logo and favicon configured in one place, Scoutro start page, attribution footer, About page. | `htroot/env/templates/scoutro/`, `htroot/env/scoutro/`, `htroot/scoutro-about.html` |
| Theme | Modern design derived from the Scoutro artwork for search and administration, dark mode for the public search pages. | `htroot/env/scoutro/theme.css`, `htroot/env/scoutro/brand/brand.css` |
| Tests | Reproducible mobile UI checks (Playwright). | `test/scoutro-ui/` |
| Build | Scoutro container image, Scoutro version file. | `docker/Dockerfile.scoutro`, `scoutro.properties` |
| Docs | This document, upstream workflow, build, UI structure. | `docs/`, top of `README.md` |

`docs/UI.md` describes the UI structure and each adjustment in detail.

## Attribution

Required and kept visible:

- **In the UI:** "Powered by YaCy" in the footer of every page, with a link to
  the About page. The About page (`scoutro-about.html`) states the origin, the
  license, the upstream and Scoutro source links and the disclaimer above.
  The "Help" menu keeps the links to the YaCy project, the YaCy community
  forum, the YaCy repository and YaCy sponsoring.
- **In the repository:** `COPYRIGHT`, `gpl.txt`, `lgpl21.txt`, `LICENSES/`,
  `NOTICE`, `AUTHORS` and all file headers stay as they are. This document
  lists the upstream origin and the differences.
- **In the container image:** OCI labels name the Scoutro source revision and
  the license `GPL-2.0-or-later`. The image contains the full license files.
