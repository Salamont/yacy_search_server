# Scoutro UI tests

Reproducible mobile and desktop checks for the Scoutro UI layer, written with
the Playwright library (no test runner, no extra project dependencies).

Viewports: 360 × 740, 390 × 844, 412 × 915 (mobile, touch) and 1280 × 800
(desktop).

Checked on every viewport:

- the hamburger toggle is visible and inside the viewport; it opens the
  complete administration navigation as a scrollable panel;
- all required navigation entries (Status, Use Case & Account, Accounts,
  Network Configuration, Crawl Start, Crawler Monitor, Index Administration,
  System Administration, Search Page Layout, Portal Configuration, Portal
  Design, Ranking, …) are visible, inside the viewport, not covered by an
  overlay and at least 40px high on mobile;
- Escape closes the panel, a menu link navigates, page submenus are reachable;
- desktop keeps the sidebar and the top bar; mobile-only groups are hidden;
- the public search header keeps its menu and the way to the administration;
- the "Powered by YaCy" attribution footer is present;
- Status, Accounts, Basic/Network Configuration, Crawl Start, Crawler, Index,
  Search/Portal Configuration, Performance, Settings, Network, search start
  page and search results: HTTP 200, no horizontal page overflow, no controls
  outside the viewport (unless in a horizontal scroll container), no
  JavaScript errors;
- the start page shows the tagline "Search. Crawl. Discover.";
- on the results page the search button is a round icon button (label only
  for screen readers) and the search pill shows the mascot asset;
- the search form submits; on the results page the search field and its
  button stay on one row, and a search without results shows the empty
  state with its illustration;
- admin pages still answer 401 without credentials.

Restart and Shutdown are never triggered (confirm dialogs are dismissed).

## Run

Start a **disposable** test instance (never the production instance or its
DATA), e.g. with the Scoutro image:

```sh
docker build -f docker/Dockerfile.scoutro -t scoutro:dev .
docker run -d --name scoutro-ui-test -p 8090:8090 scoutro:dev
```

Then:

```sh
npm install -g playwright          # once; uses the bundled Chromium
SCOUTRO_URL=http://127.0.0.1:8090 \
SCOUTRO_ADMIN_USER=admin SCOUTRO_ADMIN_PASSWORD=yacy \
node test/scoutro-ui/scoutro-ui-test.mjs --screenshots /tmp/scoutro-shots
```

The script exits with status 1 and lists every failed check. Against an
unmodified YaCy 1.942 it fails (hidden navigation, covered toggle, page
overflow); against Scoutro all checks pass.

## Dashboard smoke

`ant scoutro-dashboard-test` checks counts, failure isolation, existing detail
links, shared desktop/mobile navigation and the explicit administrator policy.

After `ant compile`, the disposable harness seeds a new temporary local Solr
index offline and starts YaCy on a free loopback port:

```sh
JAVA=/path/to/jdk/bin/java \
NODE_PATH=/path/to/node_modules SCOUTRO_CHROMIUM_PATH=/usr/bin/chromium \
SCOUTRO_SCREENSHOTS=/tmp/scoutro-dashboard-shots \
python3 test/scoutro-ui/dashboard-live-smoke.py
```

It checks the empty index and four collection counts, seven successful pages on
two hosts, exclusion of two failed/excluded records, and unchanged settings,
index and crawl files during cold/cached dashboard GETs (including ignored
action parameters). It then runs `dashboard-ui-test.mjs` and the existing UI
suite at 360/390/412/1280 px, captures screenshots and stops/removes the peer.
Fixtures never start a crawl or use existing DATA. `SCOUTRO_CHROMIUM_PATH` is
optional when Playwright's bundled browser is installed.

## Locale guard

### LLM selection and discovery

```sh
ant locale-refresh-test jetty12-server-test
python3 test/scoutro-ui/check-locale-identifiers.py
JAVA=/path/to/jdk/bin/java \
NODE_PATH=/path/to/node_modules SCOUTRO_CHROMIUM_PATH=/usr/bin/chromium \
SCOUTRO_SCREENSHOTS=/tmp/scoutro-llm-shots \
python3 test/scoutro-ui/llm-selection-live-smoke.py
```

The LLM smoke creates a new temporary peer and local fake Ollama server. It
checks real admin `/api/tags?hoststub=...` forwarding/statuses, public virtual
usage names, model selection in English and all 14 translations, German
desktop/mobile, empty/invalid responses, 401/502/503, network failure and
retry. Discovery errors retain the production matrix and expose no upstream
body. Browser saves are intercepted, so the peer's AI settings remain
unchanged. No inference, model pull/delete, discovery processing or crawl is
started; disposable DATA is removed. `TranslatorTest` additionally guards
the page's identifiers and scoped labels using the actual locale dictionaries.

### Locale refresh regression

```sh
ant locale-refresh-test scoutro-dashboard-test jetty12-server-test
JAVA=/path/to/jdk/bin/java \
NODE_PATH=/path/to/node_modules SCOUTRO_CHROMIUM_PATH=/usr/bin/chromium \
SCOUTRO_SCREENSHOTS=/tmp/scoutro-locale-refresh-shots \
python3 test/scoutro-ui/locale-refresh-live-smoke.py
```

`LocaleRefreshTest` uses temporary application/DATA directories and real Scoutro
templates/dictionaries. It covers legacy/missing/current markers, Scoutro release
and source-build changes, all three language modes (`de`, `default`, `browser`),
relative/absolute cache paths, preserved user overrides, write/read failures and
per-language failure isolation. Navigation, Wizard and template markers are
checked against the source. Unchanged copies must retain their modification time;
failed copies must not acquire the new marker or lose the old files.

The live harness starts disposable offline peers on free loopback ports in each
mode with stale German and unmarked French copies. It checks real startup,
German Dashboard/Agents/Wizard pages and desktop/mobile navigation, language
management's shared marker, a second unchanged startup, and a startup with a
broken translation source. It warms YaCy's existing first-visit responder history
before comparing index/crawl/settings files and all configuration values around
page GETs, keeps user translation overrides, restores any temporary language selection,
and removes all temporary DATA in `finally`. It accepts no existing DATA or
production URL, starts no crawls and creates no agents. Screenshots are optional.

YaCy translates the raw HTML by word replacement. A locale key that equals a
class, id or name token renames it in the translated copy (e.g. `search` turned
`class="search"` into `class="suchen"` and broke the results search pill in all
languages). Check the search pages with:

```sh
python3 test/scoutro-ui/check-locale-identifiers.py
```

## Discovery Automation V1

`ant scoutro-agents-test` includes the Discovery catalog/store/scheduling/recovery and real Java–Python RPC tests (network-free fake backend). `python3 -m unittest discover -s test/scoutro-discovery` includes persistent-backlog/provenance/cooldown/lock and provider tests.

After `ant compile`, run `discovery-live-smoke.py` with the same JAVA/NODE_PATH/SCOUTRO_CHROMIUM_PATH settings as above. It creates a new offline index, synthetic runtime profiles/regions and State V2 in temporary DATA. It verifies admin auth, JSON/origin/revision guards, CRUD, default disabled state, explicit **paused temporary** heartbeat controls, token-protected tick, export, dynamic German/mobile editor and non-mutation of candidate state, runtime config, agents, index and queues. It accepts no production URL or DATA root. No crawl, Classification or LLM is started. Optional SCOUTRO_SCREENSHOTS saves desktop/mobile screenshots.

## SEO / Host Analysis smoke

After compiling, run `python3 test/scoutro-ui/seo-live-smoke.py` with `JAVA`,
`NODE_PATH` and `SCOUTRO_CHROMIUM_PATH` configured for the local JDK/Playwright/
Chromium installation. Optional `SCOUTRO_SCREENSHOTS` selects the artifact folder.
The harness refuses existing/unmarked DATA, seeds 30 offline URL records and
starts/stops a new temporary peer. It creates its German translated copies
through normal startup language refresh. No URL fetch/crawl occurs.

Checks: English/German, 360/390/412/1280 px, native navigation, host search,
Overview/Pages/Links/Technology, bounded pagination/sorts, URL details, literal
indexed markup, visible backend errors, real zero versus missing references,
admin auth and agent grant/collection isolation. Agent/token creation for scope
verification occurs only in that disposable DATA. Before it, authenticated
SEO GETs must leave settings, index and crawl-queue file hashes unchanged.
