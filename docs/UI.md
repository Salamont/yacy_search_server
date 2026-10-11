# Scoutro UI

How the YaCy web interface is built, why it was not usable on phones, and
what Scoutro changes.

## How the YaCy UI is built

- **Server-side templates.** Every page in `htroot/` is an HTML template. Most
  pages have a servlet class in `source/net/yacy/htroot/` with the same name
  (`Status.html` ↔ `Status.java`) that fills the template properties. Syntax:
  `#[name]#` value, `#(name)#a::b#(/name)#` alternative, `#{list}#…#{/list}#`
  loop, `#%path%#` include. Pages without a class are served as static files.
- **Template lookup order** for a page or include: `DATA/LOCALE/htroot/<lang>/`
  (translations) → `DATA/HTDOCS/` → `htroot/`.
  Generated language copies can therefore hide updated source templates.
  `LocaleRefresh` checks every generated language before HTTP startup using a
  shared Scoutro release/YaCy source-build marker, also in English or browser
  mode. Staged regeneration keeps user translation overrides and leaves old
  copies intact on failure. Language selection uses the same marker and
  translation path. See [locale documentation](../locales/README-locales.md).
- **Shared templates** in `htroot/env/templates/`:
  - `metas.template`: `<head>` for all pages (Bootstrap, jQuery, CSS, LibreJS license block),
  - `header.template`: administration header (top bar) and left navigation (sidebar),
  - `submenu*.template`: page-level submenus (e.g. `submenuUseCaseAccount.template` with Basic Configuration, Accounts, Network Configuration),
  - `simpleSearchHeader.template`: header of the public search pages,
  - `footer.template` / `simplefooter.template`: page end.
- **Framework:** Bootstrap 3.4.1 and jQuery (`htroot/env/bootstrap/`),
  `env/bootstrap-base.css` (navbar, sidebar), `env/base.css` (legacy layout),
  and the selected skin (`DATA/SKINS/<skin>.css`, copied to `env/style.css`).
- **Access control** is server-side: pages ending in `_p` require the admin
  login (HTTP Digest) or a Scoutro sign-in with the matching permission
  ([SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md)). The navigation only
  shows what the signed-in identity may open; hiding an entry is never the
  protection.

## Why the mobile navigation was incomplete (YaCy 1.942)

1. **The complete administration menu is the left sidebar** in
   `header.template` (`<div class="col-sm-3 col-md-2 sidebar">`: First Steps,
   Monitoring, Production, Administration, Search Portal Integration).
   `env/bootstrap-base.css` hides it below 768px (`.sidebar { display: none }`,
   shown only in `@media (min-width: 768px)`).
2. **The hamburger toggles only the top-bar buttons** (`data-target=".navbar-collapse"`):
   Re-Start, Shutdown, Forum, Help, Sponsor (and Chat, Search). That is
   exactly the short list seen on phones.
3. **The toggle is covered.** The header search form (`#header-search-form`)
   sits in the same `.navbar-header` and overlaps the toggle, so taps land in
   the search field.
4. **Content widens the layout viewport.** Admin pages contain elements wider
   than a phone (inputs with `size`/`cols`, tables with `width="1280"`,
   `div.welcome` 850px, inline widths). Mobile browsers then enlarge the
   layout viewport (up to 1292px on `Network.html`). The fixed navbar grows
   with it, and the toggle ends up outside the visible area (measured:
   x = 851px on a 360px screen).
5. **Touch problems.** Help and Sponsor are submit buttons in
   `<form action="#">`, so tapping reloads the page. Their dropdowns open via
   `ul.nav li.dropdown:hover` (`bootstrap-base.css`), which does not work
   reliably on touch.

Measured on YaCy 1.942 at 360px: 0 of 21 required navigation entries reachable,
37 of 102 admin pages without horizontal overflow or JavaScript errors.

## Scoutro UI layer

All Scoutro styles and scripts are in Scoutro-owned files. Upstream files only
get small hooks marked with `Scoutro:`.

| File | Role |
|---|---|
| `htroot/env/scoutro/scoutro.css` | mobile navigation, responsive foundation, branding styles, start page |
| `htroot/env/scoutro/scoutro.js` | navigation panel (toggle, Escape, close on navigation), scroll wrapper for wide tables, marks the current page in the navigation |
| `htroot/env/scoutro/theme.css` | Scoutro theme: modern look matching the brand artwork, dark mode for the public search pages |
| `htroot/env/scoutro/brand/` | logo, favicon, icons, color tokens (`brand.css`) |
| `htroot/env/templates/scoutro/` | product name, attribution footer, start page hero |
| `htroot/scoutro-about.html` | static About page (origin, license, sources) |
| `htroot/ScoutroAgents_p.html`, `htroot/ScoutroAgentWizard_p.html` | Settings → Agents: agent list and management, six-step wizard (`help/ScoutroAgents_p.md`) |
| `htroot/env/scoutro/access.css` | shared page styles of the account pages: cards, facts, forms, status lines (loading, empty, error, success), tables, badges, dialogs |
| `htroot/scoutro-login.html`, `htroot/scoutro-digest.html` | sign-in page and the browser sign-in for the built-in administrator (`help/scoutro-login.md`) |
| `htroot/scoutro-account.html` + `env/scoutro/account.js` | My account: profile, password change, own sessions |
| `htroot/ScoutroUsers_p.html` + `env/scoutro/users.js` | Settings → Users: access mode, guest access, accounts, audit log |
| `htroot/ScoutroCollections_p.html` | Settings → Collections: catalog and "New collection" |
| `htroot/ScoutroSystem_p.html` | Settings → System: version, running time, memory, Re-Start, Shutdown |
| `htroot/scoutro-forbidden.html` | 403 page for a signed-in identity without the right |

Load order (in `metas.template`): Bootstrap → `bootstrap-base.css` →
`base.css` → skin (`style.css`) → `brand/brand.css` → `scoutro.css` →
`theme.css` → `scoutro.js`. Scoutro overrides therefore win without `!important` in most
cases and work with every skin.

Hooks in upstream files:

| File | Hook |
|---|---|
| `metas.template` | favicon/icons, `brand.css`, `scoutro.css`, `theme.css`, `scoutro.js` |
| `header.template` | replaced by the Scoutro header: brand, search, account menu, toggle; navigation groups of [SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md) section 7 with the YaCy tools under "Advanced tools" |
| `YaCyDefaultServlet.java` | fills the header switches (`scoutroAccount`, `scoutroRole`, `scoutroSearch`, `scoutroChat`, `scoutroRead`, `scoutroCollect`, `scoutroAdmin`) and the CSRF meta from `PageNav` |
| `Jetty12HttpServer.java` | 403 answers of HTML pages show `scoutro-forbidden.html` |
| `submenuUseCaseAccount.template` | "Agents & Access" next to Accounts |
| `simpleSearchHeader.template` | brand logo and name, About link |
| `footer.template`, `simplefooter.template` | attribution include |
| `index.html` | hero include, search icon in the button |
| `jslicense.html` | entry for `scoutro.js` |

### Header and navigation

One header for every administration page (`header.template`):

- **Top bar:** brand (links to the Overview, or to the search page without
  the read right), header search, account menu, navigation toggle.
- **Account menu:** "Sign in" without a sign-in; otherwise name, role,
  "My account" and "Sign out" (`POST /scoutro/api/v1/auth/logout`, then the
  sign-in page). A browser Digest sign-in shows "Administrator (browser
  sign-in)"; it ends when the browser is closed.
- **Navigation groups**, each shown by the server only with its right
  (docs/SCOUTRO_USERS_ACCESS.md, section 7):

  | Group | Entries | Right |
  |---|---|---|
  | Overview | Overview | read |
  | Research | Search, Chat, Websites, Host analysis | search; chat; read |
  | Knowledge | Knowledge overview, Organisations, Services, Comparisons, History | read |
  | Data collection | Crawls, Discovery, Reports | collect |
  | Settings | Collections, Users, Agents, Models, Knowledge graph operations, System | admin |
  | Advanced tools (collapsed) | AI Lab, First Steps, Monitoring, Production, Administration, Search Portal Integration, Configuration: YaCy's own pages, unchanged | admin |
  | Help | About, About This Page (admin), JavaScript information, YaCy links | everyone |

- **Re-Start and Shutdown** are no longer in the header; they are on
  Settings → System, with a confirmation dialog and the transaction token of
  `Steering.html`.
- "Advanced tools" opens by itself when the current page is one of its
  entries; `scoutro.js` marks the current entry, also for `?view=` links.

### Mobile navigation (below 768px)

- The toggle opens the **same groups** as a full-height, scrollable panel
  below the top bar; there are no mobile-only groups any more.
- Brand, search, account menu and toggle share one row (search wraps below
  on narrow screens).
- Touch targets ≥ 44px, no hover dependency, Escape closes, choosing an entry
  closes the panel, `aria-expanded` is maintained.

### Responsive foundation

- Outermost tables are wrapped in `.scoutro-scroll` (horizontal scrolling),
  bounded by the viewport. No table content is hidden.
- Inputs, selects, textareas, media and elements with inline widths are
  limited to the available width. Fieldsets no longer grow to min-content,
  and `dt`/`dd` form pairs stack.
- Page submenus become touch-sized chips. Buttons ≥ 40px, 16px inputs (no
  zoom on focus).
- The GitHub ribbon on `Status.html` is hidden on phones (it covered content).
- Block containers in the content column are limited to its width (catches
  fixed widths from `base.css`, e.g. `body#Surftips div.searchresults`),
  flex rows may wrap (`:where()`, specificity 0, so explicit rules win), and
  button labels may wrap. The results search field is explicitly kept on one
  row.
- On every width, form controls with `size`/`cols` never exceed the content
  column, and `tt` (thread dumps) wraps.
- Images inside buttons are shown at icon size (upstream embeds a 412px lock
  image in the Surftips buttons).
- On desktop, wide tables and images are bounded by the main column
  (`Network.html`, `ConfigBasic.html` no longer overflow).

### Branding

Change the brand in one place:

- name: `htroot/env/templates/scoutro/productname.template`
- tagline ("Search. Crawl. Discover."): `htroot/env/templates/scoutro/tagline.template`
- logo, favicon, icons: `htroot/env/scoutro/brand/` (keep file names)
- colors: `htroot/env/scoutro/brand/brand.css`
- footer / attribution: `htroot/env/templates/scoutro/attribution.template`
- start page hero: `htroot/env/templates/scoutro/hero.template`

Deviation from upstream configuration: the start page and the search navbar
use the brand files instead of `promoteSearchPageGreeting.largeImage`,
`.smallImage`, `.homepage` and `.imageAlt` (Portal Configuration). The start
page tagline comes from `tagline.template`, not from `promoteSearchPageGreeting`:
existing DATA directories store the upstream greeting ("Your Own Search Engine")
in `yacy.conf`, so a changed default would not reach upgraded installations.
`promoteSearchPageGreeting` is still used where upstream uses it (search field
placeholder, RSS/Atom titles, OpenSearch description).

### Theme (design)

`env/scoutro/theme.css` gives the whole interface a modern look derived from
the Scoutro artwork. All colors, radii and shadows come from the tokens in
`brand/brand.css`:

- **Palette:** magnifier blue (`--scoutro-primary`), glow cyan
  (`--scoutro-accent`), the night blue of the dark search bars
  (`--scoutro-night`), light blue page background, and the warm meerkat fur
  (`--scoutro-warm`) only as a sparse accent (search term highlight).
- **Shapes:** pill search fields and buttons, rounded cards (18px), soft
  shadows and a subtle cyan glow instead of hard colored bars.
- **Administration:** night-blue top bar with calm pill buttons, a light
  sidebar with small uppercase group titles and the current page highlighted,
  page submenus as tabs, fieldsets as cards (the legend becomes the card
  title), modern form controls with a cyan focus ring, data tables as cards
  with light header rows, and the Use Case chooser in night blue.
- **Search:** the results page uses the same pill search field as the start
  page: the small magnifier mascot (`brand/mascot-64.png`) sits inside the
  pill on the left and the submit button is a round search icon
  (`glyphicon-search`; its "Search" label stays for screen readers and is
  translated). The theme targets the form by `form[name="searchform"]` and
  `input[name="query"]`, because the locale files translate plain words and
  must never rename `class`/`id` values (`test/scoutro-ui/check-locale-identifiers.py`
  guards this). Results are shown as cards (title, green URL, snippet with a warm
  highlight, quiet meta line), and the facets appear as cards and segmented
  controls.
- **Dark mode:** the start page, results page and About page follow
  `prefers-color-scheme: dark` (night-blue surfaces, glow, light text). The
  administration intentionally stays light, because many admin pages use
  inline colors.

The theme sits on top of the selected YaCy skin. To use a plain YaCy skin
again, remove the `theme.css` line from `metas.template`.

### Mascot poses

The poses from the Scoutro artwork sheet are used where they explain a state
and do not get in the way:

| Pose | File | Where |
|---|---|---|
| Magnifier mascot | `brand/mascot-*.{png,webp}` | start page hero, logo in the admin bar and the search navbar; the 64 px mascot inside the results page search pill |
| Thinking, with question mark | `brand/pose-question.webp` | results page when a finished search has no results (`#scoutro-empty-results`, shown by `scoutro.js`; also the static "No Results." branches) |
| Waving | `brand/pose-wave.webp` | About page header |
| With laptop | `brand/pose-laptop.webp` | About page, next to the administration links |

The upstream 404 page is generated in Java (`Jetty12HttpServer.ErrorPageHandler`)
and is therefore not branded, so that the core stays untouched.

### Search start page

`index.html`: mascot, product name and greeting above one large search field
(search icon, round search button, focus glow), with Text/Images/more
options as a quiet row below. Form fields, hidden parameters, suggest and
the search APIs are unchanged.

## Tests

- `test/scoutro-ui/scoutro-ui-test.mjs`: hard checks on 360 / 390 / 412 px
  and desktop (see `test/scoutro-ui/README.md`). YaCy 1.942: 221/318 checks
  pass (the script aborts two sections on it). Scoutro: 332/332.
- `test/scoutro-ui/scan-admin-pages.mjs`: overflow and JavaScript scan of
  all 102 admin pages.
- Browser navigations without a sign-in go to the sign-in page. The tests
  that use Digest credentials (`httpCredentials`) therefore call
  `withDigestSignIn(browser)` from `test/scoutro-ui/digest-signin.mjs`: every
  new context with credentials opens `scoutro-digest.html` once, then the
  browser sends Digest itself.

  | Width | YaCy 1.942 | Scoutro |
  |---|---|---|
  | 360px | 37/102 clean | 99/102 clean |
  | 1280px | 85/102 clean | 100/102 clean |

  The remaining Scoutro findings are the upstream errors listed below.
- With the German translation active (YaCy translator on the Scoutro
  templates), the navigation, layout and auth checks pass as well. The only
  failures are the upstream JavaScript error listed below.

## Known remaining issues

Only upstream issues remain; they also occur in YaCy 1.942 and are documented, not patched.

| Page | Issue | Origin |
|---|---|---|
| `yacysearch.html` (results) | Themed (cards, pill search, empty state); facets are hidden below 768px as upstream does. | phase 3 could add a mobile filter sheet |
| `IndexPackDownloader_p.html` | HTTP 500 (needs its remote pack list) | upstream / network (also in YaCy 1.942) |
| 404 error page | Generated in Java, still YaCy-branded | upstream core, intentionally not changed |
| `ConfigAppearance_p.html`, `WatchWebStructure_p.html` | JavaScript error `$(...).ColorPicker is not a function` | upstream (also in YaCy 1.942) |
| `yacysearch.html` (also embedded in `ConfigPortal_p.html`) with the German translation | JavaScript error `Cannot read properties of undefined (reading 'substring')` | upstream (same error in YaCy 1.942 with German UI) |
| All admin pages | `<title>` still reads `YaCy '<peer>': …`; `Status.html` says "Welcome to YaCy!" | upstream texts, kept for now (attribution is not affected) |
| Large admin forms (Crawl Start Expert, Settings) | usable, but long single-column forms; could use collapsible sections | phase 2 |

## Dark / light mode

- **Status:** automatic dark mode (`prefers-color-scheme`) is implemented for
  the public search pages (start page, results, About). The administration
  stays light. Upstream also ships dark skins for it (`dark`, `dark-blue`,
  `dark-green`, `phosphor`; Portal Design), but the Scoutro theme sits on top
  of them.
- **Effort for automatic `prefers-color-scheme`:** medium. The Scoutro parts
  (brand tokens, mobile panel, start page, footer, About page) can switch via
  the CSS variables in `brand.css` with little effort. The admin pages get
  their colors from `base.css`, the skin and many inline styles; a complete
  automatic dark mode would mean a Scoutro dark skin plus overrides for
  inline colors.
- **Recommendation:** offer a dark variant of the admin theme as an option
  later, instead of forcing it.
