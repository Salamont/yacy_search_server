# Scoutro branding templates

Central place for the Scoutro brand in YaCy templates. Change the brand here,
not in the individual pages.

| File | Used by | Content |
|---|---|---|
| `productname.template` | admin header, search navbar, start page, footer, mobile menu | Product name (no trailing newline) |
| `attribution.template` | `footer.template`, `simplefooter.template` | "Powered by YaCy" footer with license and source links |
| `hero.template` | `index.html` | Start page logo, product name and greeting (`promoteSearchPageGreeting`) |
| `empty-results.template` | `yacysearch.html` | Illustration of the "no results" state |

Images, favicon and colors live in `htroot/env/scoutro/brand/` (see the
README there). The static About page `htroot/scoutro-about.html` is not
processed by the template engine and names the product directly.
