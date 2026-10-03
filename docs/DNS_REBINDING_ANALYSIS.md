# DNS-rebinding: open transport boundary

The existing robots redirect defenses validate every target/hop and all DNS answers, refuse private/mixed/empty results and retain YaCy domain/network policy. They protect redirect selection. They do not bind that decision to the socket address used by the HTTP client.

Evidence in the current main and this PR:

- Python `tools/scoutro/scoutro-discovery`: `public_robots_url()` calls `resolve_public()` and returns only a normalized URL. `robots_allowed()` passes it to `urllib` which resolves the hostname again during connection.
- Java `source/net/yacy/crawler/data/RobotsTxt.java`: contextual target validation precedes the loader fetch; `source/net/yacy/cora/protocol/http/HTTPClient.java` owns the separate connection manager/resolution path. The validation result is not an immutable connection address constraint. Loader caching/proxy/pooling also need consideration before claiming a fix.
- `test/scoutro-discovery/test_dns_rebinding_analysis.py` runs the actual urllib connection-selection code with fake DNS and a fake socket: validation sees a public address, connection selection sees loopback. The fake socket records the attempted address and raises before connecting. No packet, live DNS, production URL or private service is contacted. This reproduces the validation/connect gap; it does not claim a working network exploit or a security fix.

A check just before `open()`, an extra DNS lookup, or IP substitution in the URL is insufficient. The first two retain the race; IP substitution can break TLS hostname verification/SNI, virtual-host routing and existing proxy/loader policy. A global DNS cache or broad HTTPClient rewrite would exceed this PR without proving the invariant. No runtime network change is made here.

## Concrete follow-up plan

1. Inventory the robots-only Java and Python transport paths: direct connections, proxies, redirects, pooled reuse, IPv4/IPv6 and retries. Establish how to enforce each existing policy at actual connection selection.
2. Introduce a bounded request context containing the canonical host/port and the full validated public address set. Fail closed on empty, mixed or changed policy results. Ensure the connector can dial only that set for this request, including retries/reuse, while preserving the original HTTP Host and TLS SNI/certificate hostname verification. Do not allow a later resolver to choose an unchecked address.
3. Implement a robots-specific transport adapter first; avoid changing unrelated crawls/loaders. If a proxy performs DNS, require a supported way to enforce the same destination boundary or fail closed for that mode. Connection pools must isolate or revalidate destinations before reuse.
4. Add fake DNS schedules public→loopback, public→RFC1918/link-local, mixed A/AAAA, DNS failure and public→public. Test actual dial selection, redirect hops, pooled reuse/retries, proxy behavior, HTTPS Host/SNI/certificate rejection and normal public targets. Tests must use controlled local listeners or fake transports, never production DNS/URLs.
5. Require an independent security review and invariant proof before enabling the new transport. Retain the existing redirect/robots policy tests and document precisely which loaders are covered.

Residual risk remains: an attacker-controlled hostname may change its DNS answer between validation and connect. Existing redirect defenses reduce other SSRF paths but do not close this race. The MCP adapter is distinct: its fixed server origin comes from trusted local configuration, carries scoped agent credentials, refuses redirects and does not consume crawler-provided target URLs. System metric/host analysis actions never fetch or resolve a target.
