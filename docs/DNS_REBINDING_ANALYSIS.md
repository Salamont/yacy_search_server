# DNS rebinding: robots validation bound to connection

The reproduced validation-versus-connect gap is closed for public/global YaCy robots fetches and Scoutro Discovery's robots pre-check. Each request captures all DNS answers, applies the existing public-address policy, and passes that immutable snapshot to the connector. The connector dials only numeric addresses from that snapshot and checks the connected peer IP and port. No later hostname lookup can select a different destination. Empty, failed or mixed public/private answers remain refused.

## Java

`source/net/yacy/crawler/robots/RobotsTxt.java` uses `LoaderDispatcher.loadRobots()` with `NOCACHE`; `HTTPLoader` validates the initial target, every redirect and any alternative-name rewrite. It passes the validated snapshot to `HTTPClient.pinRobotsTarget()` before fetching.

`PinnedRobotsTransport` installs a request-specific Apache connection manager with a fixed DNS resolver. The URL retains its original host, scheme and port, preserving HTTP Host and HTTPS certificate hostname/SNI. TLS uses the default trusted SSL context and hostname verifier, explicit HTTPS endpoint identification and DNS SNI; it does not use the general crawler's permissive TLS factory. IPv4/IPv6 fallback can select only another address in the same validated snapshot. Unexpected route/peer changes fail before HTTP data is sent.

The manager is never shared with the general connection pool. Automatic retries, internal redirects and connection reuse are disabled; the client and its manager close after the single fetch. Manual loader redirects obtain a new snapshot and client, including same-host redirects. Both configured proxy routes and request-config proxies are refused: a remote resolver cannot be bound to the validated destination by this adapter. A proxy is not silently bypassed.

The explicit `local` and `any` network modes retain their existing intentionally local-capable transport and policy. Ordinary crawl/profileless/stream loaders are unchanged. Existing unavailable-robots parser/cache behavior is unchanged; this change prevents an unvalidated robots connection, rather than changing robots permissions.

## Python Discovery

The robots-only urllib opener prepares a canonical URL and immutable family/address snapshot. Its HTTP and HTTPS connections call `socket.connect()` with numeric IPv4/IPv6 destinations instead of `socket.create_connection()` or a hostname. Scoped IPv6/escaped host names are refused to avoid an additional interface/hostname resolution in socket selection. Peer IP/port must equal the selected validated destination. HTTPS wraps that socket with `ssl.create_default_context()` and the original canonical hostname for SNI and certificate verification.

Every redirect creates and validates a new request. Connections close after each fetch; there is no shared pool. Connect fallback uses only addresses from the current snapshot. Environment proxies are refused when they apply; configured `NO_PROXY` bypasses still use the pinned direct connector. Policy errors survive urllib's exception wrapping and return `False` from `robots_allows()`, without the ordinary unavailable-robots fallback. Existing HTTPS/HTTP content fallback and transient 5xx behavior remain, with a separate validated binding for every attempted request.

## Offline proof and regressions

`test/scoutro-discovery/test_dns_rebinding_analysis.py` replaces the former successful gap reproduction with 12 transport regressions. Real urllib/http.client request and connection selection run against fake DNS, sockets and TLS. Public-to-loopback, RFC1918, link-local and public-to-public answer schedules prove that only the first validated snapshot is dialed. Tests also cover mixed/empty/failed DNS, IPv6 literals/nondefault ports and address fallback, Host/SNI, certificate failure, redirects, fresh requests, proxy refusal/bypass and peer mismatch.

`PinnedRobotsTransportTest` runs the real Apache route/resolver/connect/TLS/HTTP logic with fake socket factories. It verifies immutable snapshots, numeric IPv4/IPv6 dialing, address fallback, Host/port, SNI/endpoint identification, certificate-hostname and handshake rejection, changed routes, both proxy paths, no automatic redirects/retries, separate managers, peer mismatch, and the actual HTTPClient binding/close/single-use path. Existing loader/dispatcher/RobotsTxt tests additionally assert that the validated snapshots reach the connector on each hop and retain local/any compatibility. Its test JVM uses a dedicated hosts file; no external DNS or real connection occurs. The PEM fixture is only a public test certificate; no private key is stored.

```sh
ant robots-redirect-security-test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s test/scoutro-discovery -p 'test_*.py' -v
```

## Coverage boundary

This is a robots-specific transport fix, not a global network rewrite. Other crawler/Discovery download transports are outside its coverage. Supported public robots requests require a direct route; configured proxies fail with a clear transport/policy error. Standard CA/hostname verification can reject certificates that the prior permissive Java transport accepted. No proxy service, production crawl, deployment or release is required.

The MCP adapter remains separate: its fixed origin is trusted local configuration, scoped credentials never follow redirects, and system metric/host-analysis actions do not fetch or resolve their requested hosts.
