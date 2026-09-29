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
  login (HTTP Digest). The UI only shows lock icons.

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

Load order (in `metas.template`): Bootstrap → `bootstrap-base.css` →
`base.css` → skin (`style.css`) → `brand/brand.css` → `scoutro.css` →
`theme.css` → `scoutro.js`. Scoutro overrides therefore win without `!important` in most
cases and work with every skin.

Hooks in upstream files:

| File | Hook |
|---|---|
| `metas.template` | favicon/icons, `brand.css`, `scoutro.css`, `theme.css`, `scoutro.js` |
| `header.template` | `scoutro-adminbar` class, toggle id, sidebar id, mobile-only groups (Configuration, System, Help), brand, Help and Sponsor as `type="button"` |
| `simpleSearchHeader.template` | brand logo and name, About link |
| `footer.template`, `simplefooter.template` | attribution include |
| `index.html` | hero include, search icon in the button |
| `jslicense.html` | entry for `scoutro.js` |

### Mobile navigation (below 768px)

- The toggle opens the **existing YaCy sidebar** as a full-height, scrollable
  panel below the navbar. The information architecture stays YaCy's; no new
  menu was invented.
- Additional groups in the panel only: **Configuration** (Accounts, Network
  Configuration, Search Page Layout, Language: pages that desktop users reach
  via page submenus), **System** (Search, Chat if enabled, Re-Start,
  Shutdown with the upstream confirmation) and **Help** (About Scoutro, About
  This Page, JavaScript information, YaCy forum, repository, sponsoring).
- Touch targets ≥ 44px, no hover dependency, Escape closes, choosing an entry
  closes the panel, `aria-expanded` is maintained.
- Toggle and header search share one row. On mobile, the top-bar button row
  is replaced by the panel groups.
- Desktop (≥ 768px) is unchanged: sidebar, top bar, dropdowns.

### Responsive foundation

- Outermost tables are wrapped in `.scoutro-scroll` (horizontal scrolling),
  bounded by the viewport. No table content is hidden.
- Inputs, selects, textareas, media and elements with inline widths are
  limited to the available width. Fieldsets no longer grow to min-content,
  and `dt`/`dd` form pairs stack.
- Page submenus become touch-sized chips. Buttons ≥ 40px, 16px inputs (no
  zoom on focus).
- The GitHub ribbon on `Status.html` is hidden on phones (it covered content).
- On desktop, wide tables and images are bounded by the main column
  (`Network.html`, `ConfigBasic.html` no longer overflow).

### Branding

Change the brand in one place:

- name: `htroot/env/templates/scoutro/productname.template`
- logo, favicon, icons: `htroot/env/scoutro/brand/` (keep file names)
- colors: `htroot/env/scoutro/brand/brand.css`
- footer / attribution: `htroot/env/templates/scoutro/attribution.template`
- start page hero: `htroot/env/templates/scoutro/hero.template`

Deviation from upstream configuration: the start page and the search navbar
use the brand files instead of `promoteSearchPageGreeting.largeImage`,
`.smallImage`, `.homepage` and `.imageAlt` (Portal Configuration). The
greeting text `promoteSearchPageGreeting` is still used as the tagline.

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
  page, results are shown as cards (title, green URL, snippet with a warm
  highlight, quiet meta line), and the facets appear as cards and segmented
  controls.
- **Dark mode:** the start page, results page and About page follow
  `prefers-color-scheme: dark` (night-blue surfaces, glow, light text). The
  administration intentionally stays light, because many admin pages use
  inline colors.

The theme sits on top of the selected YaCy skin. To use a plain YaCy skin
again, remove the `theme.css` line from `metas.template`.

### Search start page

`index.html`: mascot, product name and greeting above one large search field
(search icon, round search button, focus glow), with Text/Images/more
options as a quiet row below. Form fields, hidden parameters, suggest and
the search APIs are unchanged.

## Tests

- `test/scoutro-ui/scoutro-ui-test.mjs`: hard checks on 360 / 390 / 412 px
  and desktop (see `test/scoutro-ui/README.md`). YaCy 1.942: 221/318 checks
  pass (the script aborts two sections on it). Scoutro: 320/320.
- `test/scoutro-ui/scan-admin-pages.mjs`: overflow and JavaScript scan of
  all admin pages. YaCy 1.942: 37/102 pages clean. Scoutro: 96/102.
- With the German translation active (YaCy translator on the Scoutro
  templates), the navigation, layout and auth checks pass as well. The only
  failures are the upstream JavaScript error listed below.

## Known remaining issues (not part of phase 1)

| Page | Issue | Origin |
|---|---|---|
| `yacysearch.html` (results) | Not redesigned yet. Usable on phones (no overflow), but still the technical YaCy layout; facets are hidden below 768px. | Phase 3 (Scoutro search UI) |
| `Surftips.html` | 490px result blocks, overflow on phones | upstream layout |
| `ToolsConfig_p.html` | fixed-width `.tool-limit` blocks, overflow on phones | upstream layout |
| `yacychat.html` | button row wider than the screen | upstream layout, own page layout |
| `IndexPackDownloader_p.html` | HTTP 500 (needs its remote pack list) | upstream / network |
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
