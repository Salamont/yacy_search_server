# Crawl / host / Discovery flow validation

Base: `fix/robots-redirect-security` at
`f0a1431945a33e7d58dabd1591d7fc0610f8fcf7`. Source version remains
`1.942-scoutro.10`. Tests use temporary DATA/index/state and fake HTTP/DNS.
No production server, runtime config, collections, jobs or crawls are touched.

| Suite | Result |
| --- | --- |
| Scoutro Java (agents, search, SEO, recovery, Discovery, dashboard metrics) | 166 passed |
| Standalone dashboard Java | 17 passed (overlaps metrics coverage above) |
| robots redirect Java | 35 passed |
| HTTP / Jetty | 60 passed |
| Locale refresh / identifiers | 44 passed; 8 pages × 14 locales, zero identifier collisions |
| Citation postprocessing | 22 passed |
| Python Discovery incl. robots | 125 passed, 12 existing opt-in skips (137 total) |
| Generated contracts / CLI (offline) | 10 passed |
| SEO live API | 4 passed |
| Host / crawl live API | 6 passed; only invalid/disallowed crawl requests |
| SEO UI, English/German, 360/390/412/1280 | 226 checks passed |
| New crawl / Discovery UI, same four widths | 123 checks passed; all crawl writes intercepted |
| Existing Discovery UI / live API/state | 64 / 43 checks passed |
| Existing dashboard UI / general Scoutro UI | 155 / 377 checks passed |
| Live read-only hash checks | 9 host/crawl/SEO checks + 4 dashboard checks passed |

`ant compile` passes. The security base's robots loader/helper/Python files
remain byte-for-byte unchanged. Git diff/whitespace checks pass.

Focused new regressions exercise normalized hosts/URLs/IDN; scoped indexed and
unknown states; invalid/missing/empty/null/foreign collections before dispatch;
admin restart and lost-answer replay; key/body conflicts; active-host blocking;
metadata/progress/null semantics; recycled IDs; corrupt/torn journal refusal;
more than 10,000 retained idempotency references; internal Discovery validation;
automation × pause × worker combinations; blocked and already-submitted batches;
allowed buttons; coalesced polls and a delayed response across a mutation.

No POST is made on loading native Crawls or following the SEO link. UI fake
starts exercise the JSON contract and idempotency key, while Java fake-upstream
tests verify the actual YaCy translation and persistence. Live tests check
administrator authentication and agent grants/scopes against a disposable peer.

YaCy records first servlet loads in `server.servlets.called`. All measured
read-only endpoints are warmed before the strict settings/index/queue/Scoutro
file-hash comparison; this preserves the original core monitoring behavior.
Temporary agents/jobs and a paused test heartbeat are explicitly exercised only
by the existing disposable harness and are removed with its DATA directory.

Reproduce with JDK 21, cached/installed Ant dependencies and Playwright/Chromium:

```
ant scoutro-agents-test scoutro-dashboard-test robots-redirect-security-test jetty12-server-test locale-refresh-test citation-postprocessing-test
python3 -m unittest discover -s test/scoutro-discovery -v
python3 test/scoutro-api/test_flow_contract.py -v
python3 test/scoutro-ui/check-locale-identifiers.py
python3 test/scoutro-ui/seo-live-smoke.py
python3 test/scoutro-ui/discovery-live-smoke.py
python3 test/scoutro-ui/dashboard-live-smoke.py
```

Set `JAVA`, `NODE_PATH`, `SCOUTRO_CHROMIUM_PATH` and `SCOUTRO_SCREENSHOTS` for the
installed toolchain and screenshot destination. No production URL/DATA is used.

Limits: legacy profiles have unknown metadata; terminated is not proof of
indexing; no reliable completion time or progress percentage is invented.
The append journal grows with retained references and requires future explicit
compaction/retention. Corruption fails closed and requires administrative repair.
New read grants are opt-in. Clients omitting collection must be updated.
