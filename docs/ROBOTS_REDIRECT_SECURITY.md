# Robots redirect security

## Cause and scope

YaCy `RobotsTxt.getEntry()` and `ensureExist()` create requests without a crawl
profile. Previously `HTTPLoader` followed their redirects without re-entering
`CrawlStacker` acceptance. Discovery checked the candidate's original DNS but
`robots_allows()` delegated redirect following to urllib's default handler.
Both paths could therefore fetch a private redirect target.

This change only secures robots requests. It does not change crawl UI/API
contracts, collection assignment, job scheduling, ranking, Citation or SEO.
No version/default setting, DATA migration or production action is required.

## Java

Both robots call sites use `LoaderDispatcher.loadRobots()`, which carries an
explicit robots context through `HTTPLoader`'s existing redirect recursion.
The initial target, every redirect and any rewritten YaCy-name fetch target
must use HTTP/HTTPS. `DigestURL` supplies existing URL normalization, including
relative redirects.

In public/global mode, every target is resolved again and passed to the existing
`CrawlStacker` network/domain-list policy with **all** DNS answers. Its new
resolved-address overload uses `Domains.isPublicAddress()`, reusing YaCy's
local-address classification and supplementing it for non-public special-use
ranges (including shared, documentation, benchmark, multicast and reserved
addresses). Checking resolved answers avoids the existing known-TLD shortcut.
Empty/failed DNS, private/local addresses and URL credentials abort the fetch.

The public check applies only to the explicit robots context and only outside
`Switchboard.isIntranetMode()`. YaCy's explicit `local` and `any` network modes
retain local HTTP/HTTPS robots access. Existing locality/URL hash behavior and
ordinary profiled/profileless loading remain unchanged. Normal crawl redirects
still go through the existing stacker acceptance path.

The existing five-redirect limit also bounds robots loops. A blocked target is
never handed to the HTTP transport. The existing unavailable-robots cache/parser
fallback remains unchanged; this fix does not invent new robots permissions.

## Python Discovery

`PublicRobotsRedirectHandler` checks and normalizes each Location before the
standard handler can follow it. Only HTTP/HTTPS without URL credentials is
accepted; relative URLs and IDNs are normalized. The existing `resolve_public()`
checks every DNS answer and now rejects empty results and shared address space
through the existing `is_public_ip()` helper.

Each request carries a redirect count, with at most five follow requests.
`robots-target-invalid`, `robots-target-blocked` and `robots-redirect-limit`
are policy refusals: `robots_allows()` returns false instead of treating them
as an unavailable robots file or retrying an unsafe target over HTTP. Ordinary
robots content handling, HTTPS/HTTP fallback and transient HTTP 5xx behavior
are retained. No candidate-state schema or processing taxonomy changes.

## Offline verification

```sh
ant robots-redirect-security-test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s test/scoutro-discovery -p test_robots_redirect_security.py -v
```

The Java tests execute the real loader, dispatcher and both robots entry paths
with fake HTTP responses and an injected address resolver. The test JVM uses a
dedicated `jdk.net.hosts.file`, so even YaCy's local-address initialization cannot
query real DNS. Temporary WorkTables are disposed after each integration test;
no crawl worker, scheduler or production DATA is initialized.

Python tests use the actual guarded urllib redirect handler with fake HTTP and
DNS; socket construction is blocked. Coverage includes ordinary robots,
public-to-public/IPv6/relative redirects, loopback/private/link-local/localhost,
mixed DNS, a same-host DNS change, a late private hop, invalid schemes,
credentials, loops/limits, robots disallow and transient 5xx. Java additionally
checks local/any compatibility, unchanged generic loading and crawl acceptance.

Relevant regressions are `scoutro-agents-test` (Discovery, Crawl API, Agent/SEO),
`scoutro-dashboard-test`, `citation-postprocessing-test`, `locale-refresh-test`,
`jetty12-server-test` and the full `test/scoutro-discovery` Python suite. Existing
mock-server regressions bind only loopback and use temporary data; no live
external DNS/HTTP or production smoke is part of this fix.

Validation on main `97f65770a8e7138adb3b44ed513aa58e4bcf549c` (JDK 21):

| Suite | Result |
| --- | --- |
| Java robots security + existing robots/Domain tests | 35 passed |
| Scoutro Java / Agent / Crawl API / Discovery / SEO | 140 passed |
| Dashboard / Admin security | 17 passed |
| Citation postprocessing | 22 passed |
| Locale refresh | 44 passed |
| HTTP / Jetty | 60 passed |
| Python Discovery, including the new robots tests | 137 run: 125 passed, 12 existing skips |
| New Python robots tests alone | 20 passed (included above) |

There were no final failures/errors. The 12 Python skips require an explicitly
enabled disposable-instance evidence suite (11) or an opt-in real-state copy (1).
Those opt-ins were not enabled. The Java build and `git diff --check` passed.
Suite totals overlap where Dashboard/Admin tests are run by several targets;
these numbers are per target, not a count of unique cases.

## Boundary

Target validation is performed before every robots follow fetch. DNS results
are not pinned to the HTTP client's subsequent connection; a hostile DNS change
between validation and connection remains a separate transport-hardening topic.
This is not a blanket SSRF guarantee for unrelated loaders or other Discovery
source downloads.

The bounded fake-DNS reproduction and a concrete transport-hardening plan are now recorded in [DNS_REBINDING_ANALYSIS.md](DNS_REBINDING_ANALYSIS.md). This follow-up does not change network behavior or claim to close the remaining validation/connect race.
