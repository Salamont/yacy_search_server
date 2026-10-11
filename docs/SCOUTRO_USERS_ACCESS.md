# Scoutro users, roles and navigation

Design and operator guide for Scoutro's own sign-in, user management,
permissions and navigation. The baseline it starts from, with file and line
evidence, is in [SCOUTRO_ACCESS_BASELINE.md](SCOUTRO_ACCESS_BASELINE.md).

> **Rule of thumb.** The role decides which *actions* an identity may take.
> The collection access decides which *data* it may see. Both are checked on
> the server for every request; hiding a menu entry is never the protection.

Collections are logical data areas inside one index. They are not tenants:
Scoutro does not claim complete tenant isolation (shared index, shared
dictionary, shared crawler, shared logs).

## 1. Architecture and why

Scoutro keeps YaCy as the engine (crawler, index, Solr, P2P, administration)
and extends the existing layers instead of adding a second application:

| Layer | Choice | Reason |
|---|---|---|
| Pages | Server-side YaCy templates (`htroot/*.html`) plus the Scoutro scripts in `htroot/env/scoutro/` | All Scoutro pages, translations (`locales/*.lng`), help files and UI tests already use this; no build step, no framework change. |
| Look | `brand/brand.css` tokens, `theme.css`, the `sseo-*`/`skg-*`/`scc-*` components | Existing Scoutro design; new pages reuse the cards, pills, tabs and empty states. |
| Authentication | A Jetty authenticator that first accepts a Scoutro session cookie and otherwise delegates to YaCy's unchanged Digest authenticator | The servlet container stays the single source of `getUserPrincipal()`/`isUserInRole()` (`RequestHeader.java:360-376`), so all existing checks (`requireAdmin`, `verifyAuthentication`, AI Shield, transaction tokens) keep working and cannot be spoofed by headers. |
| Decisions | One container-neutral class (`net.yacy.scoutro.access`) that maps paths and API routes to permissions, plus a request principal with role, permissions and collection scope | Container rules, servlet checks and the old helpers ask the same component. |
| Storage | JSON files in `DATA/SETTINGS` (owner-only, atomic writes), like the agent store | No new database; backup and restore with DATA as before. |

## 2. Identities

| Identity | How it signs in | Notes |
|---|---|---|
| Built-in administrator | HTTP Digest (unchanged) or the Scoutro login page with the same name and password | YaCy's `adminAccountUserName` / `adminAccountBase64MD5`. Form sign-in can be switched off (`scoutro.auth.builtinAdminLogin=false`) once a personal administrator account exists; Digest stays for automation. Changing its password in *Accounts* (`ConfigAccounts_p.html`) ends its Scoutro sessions. |
| Scoutro account | Scoutro login page | New. Role, collection access, export flag, status. Passwords as Argon2id hashes. |
| Agent | Bearer token on `/scoutro/api/agent/v1` | Unchanged service identity with its own grants and scope. Never a person, never a session. |
| Guest | none | Anonymous access, **off by default**, only in protected mode, only search in the released collections. |
| Local bypass | loopback rules of YaCy | Unchanged (`adminAccountForLocalhost`, admin-hash Basic for `YaCyLoopback`). |

A Scoutro session makes only accounts with the role *Administrator* a YaCy
administrator (`adminRight`). Research and Operator sessions never get that
role, so every YaCy page, native API and administrator route keeps refusing
them.

## 3. Roles and permissions

| Permission | Guest¹ | Research | Operator | Administrator |
|---|---|---|---|---|
| `search` – Scoutro search | ✓ | ✓ | ✓ | ✓ |
| `chat` – chat with sources and graph facts | – | ✓ | ✓ | ✓ |
| `read` – overview, websites, host analysis, knowledge | – | ✓ | ✓ | ✓ |
| `export` – downloads and exports | – | per account | per account | ✓ |
| `collect` – crawls, discovery jobs, crawl reports | – | – | ✓ | ✓ |
| `admin` – system-wide functions | – | – | – | ✓ |

¹ Guest only when switched on, only in protected mode.

### Permission matrix by function

"Scope" means the function only sees and changes data of the identity's
allowed collections. "All collections" means all collections this identity is
allowed to use.

| Function | Guest | Research | Operator | Admin | Scope | Server check |
|---|---|---|---|---|---|---|
| Search (Scoutro search page, `GET /v1/search`) | released | ✓ | ✓ | ✓ | yes | `search` + scope; `collection:` in the query refused |
| Suggestions (`/suggest.json`, YaCy dictionary) | – | – | – | ✓ | no (whole-index dictionary) | admin in protected mode |
| Chat (`/v1/chat/completions`) and graph facts | – | ✓ | ✓ | ✓ | exactly one allowed collection² | `chat` + scope |
| Overview / dashboard | – | ✓ | ✓ | ✓ | yes | `read`; cards only for allowed collections |
| Websites (index browser), metrics, evidence, host analysis | – | ✓ | ✓ | ✓ | yes | `read` + scope |
| Knowledge: organisations, services, comparisons, relations, sources, history | – | ✓ | ✓ | ✓ | yes; derived links only if both collections are allowed | `read` + scope (`KgReader.within`) |
| Export: domain CSV/JSON, graph download/export/change feed, network GraphML/JSON | – | flag | flag | ✓ | yes | `export` + scope |
| Crawl reports | – | – | ✓ | ✓ | yes | `collect` + scope |
| Crawls: list, start, status, stop | – | – | ✓ | ✓ | yes; hosts with documents outside the scope refused | `collect` + scope |
| Discovery jobs: list, create, edit, run, schedules, backlog | – | – | ✓ | ✓ | profile collection must be allowed | `collect` + scope |
| Discovery global switches (enable, disable, pause, resume) | – | – | – | ✓ | system | `admin` |
| Create a collection | – | – | – | ✓ | system | `admin` |
| Move a domain between collections | – | – | – | ✓ | system | `admin` |
| Knowledge-graph operations: status details, pause/resume, reconcile, rebuild, recompute, LLM run/schedule, collection settings | – | – | – | ✓ | system | `admin` |
| Backups (list, download, restore, delete) | – | – | – | ✓ | system | `admin` |
| Models, chat tools, AI Shield | – | – | – | ✓ | system | `admin` (YaCy pages) |
| Users, roles, sessions, access mode, guest access, audit log | – | – | – | ✓ | system | `admin` |
| Agents and tokens | – | – | – | ✓ | system | `admin` |
| System: restart, shutdown, configuration, advanced YaCy tools, native YaCy/Solr/MCP routes | – | – | – | ✓ | system | `admin` |
| Own account: password, sessions, sign-out | – | ✓ | ✓ | ✓ | – | signed in |

² The chat searches exactly one collection or, only for identities with all
collections, the whole index (the existing rule "never mixed",
`RAGProxyServlet.java:218-238`).

Operator never unlocks a system-wide function; system-wide means `admin`.

## 4. Access modes

| Mode | Setting | Behaviour |
|---|---|---|
| Compatible (default after an upgrade) | `scoutro.access.protected=false` | Everything that was public stays public exactly as before (`publicSearchpage`, AI Shield, …). Scoutro sign-in works for administrator accounts. Research and Operator accounts and guest access cannot be activated. |
| Protected | `scoutro.access.protected=true` | Allowlist: only the routes in the table below are reachable without the administrator role. Everything else, including the native search (`yacysearch`, `suggest`, `/solr/`, `/gsa/`, MCP `/tools`), the old YaCy pages and APIs, needs an administrator. |

Switching to protected mode is an administrator action on *Settings → Users*
(logged). Switching back is refused while active Research/Operator accounts
exist or guest access is on. If active restricted accounts exist while the
setting says `false` (for example after a manual edit), Scoutro fails closed
and applies the protected rules.

Route table in protected mode:

| Path | Who |
|---|---|
| `/env/**`, `/js/**` static files, `/favicon.ico`, `/jslicense.html`, `/scoutro-about.html` | everyone |
| `/scoutro-login.html`, `POST /scoutro/api/v1/auth/login`, `GET /scoutro/api/v1/auth/session`, `POST /scoutro/api/v1/auth/logout` | everyone (self-protected) |
| `/scoutro/api/v1/health`, `/scoutro/api/v1/ui/*`, `/scoutro/api/openapi.json`, `/scoutro/api/actions.json` | everyone (as before) |
| `/scoutro/api/agent/v1/**` | agent token (as before) |
| `/yacy/**` (peer-to-peer) | YaCy network rules (unchanged)³ |
| `/`, `/index.html`, `/scoutro-search.html`, `GET /v1/search`, `GET /v1/collections` | `search` (signed in) or guest |
| `/scoutro-account.html`, other `/scoutro/api/v1/auth/*` | signed in |
| `/scoutro-dashboard.html`, `/IndexBrowser_p.html`, `/ScoutroSEO_p.html`, `/ScoutroKnowledge_p.html` | `read` |
| `/yacychat.html`, `/v1/chat/completions` | `chat` |
| `/ScoutroCrawls_p.html`, `/ScoutroDiscovery_p.html` | `collect` |
| other `/scoutro/api/v1/*` | signed in at the container; the servlet decides per route (deny by default) |
| everything else | administrator |

³ A peer that takes part in the YaCy network (not private Robinson mode)
answers remote searches of other peers from the whole index. That is a
network decision of the administrator; collection rights do not apply to
peers. *Settings → Users* warns when protected mode is on and the peer is not
in private Robinson mode.

## 5. Sign-in, sessions and protection

| Topic | Implementation |
|---|---|
| Login page | `scoutro-login.html`. A browser navigation (GET/HEAD, `Accept: text/html`, no `Authorization` header) to a page that needs a sign-in is redirected there with `next=`; the return target must be a same-origin path (`/…`, not `//`, no scheme, no backslash) and falls back to the overview. API calls and Digest clients still get the Digest challenge (401), so `curl --digest`, `scoutroctl` and scripts keep working. Rollback switch: `scoutro.auth.loginPage=false` shows the Digest dialog again. |
| Passwords | Argon2id (BouncyCastle, already a dependency): m = 19 MiB, t = 2, p = 1, 16-byte salt, PHC string. Length 10-256 characters, not equal to the user name. Hashes are never sent to the browser. |
| Built-in admin password | Its Digest hash cannot become an Argon2id hash. The login page verifies the typed password against the existing Digest hash (`AdminSecurity.checkAdminPassword`, without the localhost hash shortcut); nothing is converted or stored. |
| Sessions | Server-side, in memory: 256-bit random id, only its SHA-256 is kept. Idle timeout `scoutro.auth.session.idleMinutes` (30), absolute `scoutro.auth.session.maxHours` (12), at most 10 per account. A restart ends all sessions. Each request re-reads the account: status, role, collections and export flag apply immediately. |
| Revocation | Sign-out ends the session. Password reset, role, collection, export or status changes and deletion end all sessions of the account. A user's own password change ends the other sessions and renews the current one. Administrators can end sessions per account. |
| Cookie | `scoutro_session`, `HttpOnly`, `Path=/`, `SameSite=Strict` (`scoutro.auth.cookie.sameSite`, `Lax` allowed), `Secure` on HTTPS or when `X-Forwarded-Proto: https` (`scoutro.auth.cookie.secure=auto|always|never`). No token is ever stored in `localStorage`. |
| CSRF | Session requests that change something must carry the session's CSRF token in `X-Scoutro-CSRF` (Scoutro API) or come from the same site (`Sec-Fetch-Site`/`Origin`, old YaCy forms which also keep their transaction tokens). `scoutro.js` adds the header to same-origin `fetch` calls. JSON bodies stay `application/json` with same-origin `Origin`. |
| Login limits | Per account: after 5 failures within 15 minutes, further attempts wait 15 minutes (no permanent lock). Per client address: 30 failures within 15 minutes → 429. Unknown names are hashed too, so timing does not reveal accounts. |
| Must change password | Accounts created or reset by an administrator get a temporary password and must set their own before anything else works. |
| Errors | Not signed in: login page (pages) or 401 JSON (API). Signed in without permission: a Scoutro "no permission" page (pages) or 403 JSON with `error.code`. |

## 6. Collection scope rules

- An account has an explicit list of collections or *all collections*
  (`*`, resolved at request time from the catalog). Administrators always
  have all collections.
- A requested collection outside the scope is refused with 403
  `collection_not_allowed`, also when it does not exist (no existence
  oracle). Names are validated (`[A-Za-z0-9_-]{1,64}`).
- No collection selected = all allowed collections, never the whole index
  unless the identity has all collections.
- Query syntax (`collection:`), URL parameters and stored UI choices never
  widen the scope; the server computes the effective set.
- Lists, counts, facets and memberships only show allowed collections. A
  document that is also in a foreign collection is shown, but the foreign
  name is not.
- Derived knowledge links between two collections are shown only when both
  are allowed (existing rule in `BusinessView.visibleDerived`).
- Operators cannot crawl hosts that already have documents outside their
  scope (the agents' rule), because a recrawl would change those documents.

## 7. Navigation

Same structure on desktop and phone; entries follow the server-side
permissions of the signed-in identity.

| Group | Entries (page) | Permission |
|---|---|---|
| Overview | Overview (`scoutro-dashboard.html`) | `read` |
| Research | Search (`scoutro-search.html`), Chat (`yacychat.html`), Websites (`IndexBrowser_p.html`), Host analysis (`ScoutroSEO_p.html`) | `search` / `chat` / `read` |
| Knowledge | Overview, Organisations, Services, Comparisons, History (`ScoutroKnowledge_p.html` views) | `read` |
| Data collection | Crawls (`ScoutroCrawls_p.html`), Discovery (`ScoutroDiscovery_p.html`), Reports (`ScoutroSEO_p.html` report view) | `collect` |
| Settings | Collections, Users, Agents, Models (`LLMSelection_p.html`), Knowledge graph operations, System (restart/shutdown), Advanced tools (YaCy menu) | `admin` |

Adjustments to the proposed structure, and why:

- *Relations* and *Sources* are not separate menu entries: in the existing
  knowledge pages they belong to an organisation (network view) or a
  document (source view) and are reached from there.
- *Reports* belong to data collection (crawl reports), not to research.
- *Schedules* are part of the Discovery page (each job has its schedule).
- Re-Start and Shutdown moved from the top bar to *Settings → System*.
- The YaCy groups (First steps, Monitoring, Production, Administration,
  Portal) are kept unchanged under *Settings → Advanced tools* for
  administrators.

Header: brand, search field (Scoutro search), collection context (allowed
collections; the server re-validates it on every request), account menu
(name, role, my account, sign out).

## 8. Audit log

`DATA/SETTINGS/scoutro-user-audit.jsonl` (owner-only, newest 20,000 entries,
like the agent audit): time, acting identity (account, built-in admin,
Digest admin, local), action, target, result and reason. Logged: sign-in
success and failure, sign-out, password change and reset, account created,
changed, locked, unlocked, deleted, sessions ended, access mode and guest
changes, and every changing Scoutro API call by a person (route, target
collection or id, status). Never logged: passwords, tokens, cookies, document
contents, search texts.

## 9. Migration and fallback

1. **Upgrade.** Nothing changes for automation: Digest, admin-hash loopback
   calls and agents work as before; the instance stays in compatible mode.
   Browsers see the Scoutro login page instead of the Digest dialog; the
   built-in administrator signs in with the same name and password.
2. **Personal administrator.** Create an Administrator account on
   *Settings → Users*, sign in with it.
3. **Optional:** switch off form sign-in of the built-in administrator; its
   Digest access for tools stays.
4. **Protected mode.** Switch it on; check tools that used public native
   routes without credentials (on Olares the API entrance forwards only
   `/scoutro/api/`, so it is not affected). Then create Research and Operator
   accounts.
5. **Fallback.**
   - Login page problems: `scoutro.auth.loginPage=false` (Digest dialog).
   - Protected mode: lock the restricted accounts, switch guest off, switch
     protected mode off on *Settings → Users*, or stop Scoutro and set
     `scoutro.access.protected=false` and remove/rename
     `DATA/SETTINGS/scoutro-users.json` (only the built-in administrator
     remains).
   - Older image: ignores the new files and settings; Digest is unchanged.
     The account file stays for a later upgrade.

## 10. Packages

| # | Package | Depends on | Acceptance |
|---|---|---|---|
| 1 | Baseline, architecture, permission matrix (this document) | – | Findings with evidence; matrix covers read, chat, export, crawls, discovery, collection creation, graph control, backups, models, users, system. |
| 2 | Accounts, sign-in, sessions, central decision, protected mode | 1 | Argon2id store; login/logout/session/password API; session authenticator delegates to Digest; restricted sessions never `adminRight`; last-administrator guard; login limits; CSRF; audit; protected-mode route policy fails closed. Restricted accounts reach only their own account until package 4. Unit tests. |
| 3 | Navigation, login page, account and user management pages | 2 | One header with search, collection context and account menu; same groups on phone and desktop; Re-Start/Shutdown on *System*; YaCy menu under *Advanced tools*; German translations and help. |
| 4 | Permission-aware pages and data | 3 | Every Scoutro API route mapped to a permission and scope; Research/Operator use search, chat, overview, websites, host analysis, knowledge, crawls and discovery within their collections; knowledge operations only on the administrator view; dynamic dashboard; exports gated. |
| 5 | End-to-end checks, documentation, report | 4 | Multi-user checks (login, logout, expiry, lock, rights change, manipulated parameters, direct APIs, old routes, agents), desktop and phone screenshots, test results, rollout checklist. |

## 11. Before a release or Olares rollout

- Build and test a release image from the merged head; nothing in this work
  publishes images or changes the Olares package.
- Check on Olares that the app window keeps the `scoutro_session` cookie
  (same site as the Olares desktop). If not, use `SameSite=Lax` and record
  the decision.
- Decide whether the production instance switches to protected mode, and when.
- Back up DATA before the upgrade as in previous rollouts; the account file
  and audit log live in `DATA/SETTINGS`.
