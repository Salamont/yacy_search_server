# Agent access on a server (without Olares)

This document defines how agents reach a Scoutro server so that they can use
**only** the agent API `/scoutro/api/agent/v1` (Bearer agent token, fixed
grant, collection scope, limits) and nothing else of YaCy. It uses existing
components only: YaCy's own `host` setting and an nginx reverse proxy.
Nothing in this document depends on Olares.

Files:

| File | Purpose |
|---|---|
| `tools/scoutro/server/yacy-agent-server.conf` | settings for `DATA/SETTINGS/yacy.conf` |
| `tools/scoutro/server/nginx-scoutro.conf` | reverse proxy: agent listener and optional people listener |
| `test/scoutro-server/check_agent_access.py` | proof from the agent's position, with and without token |

## Why the YaCy port must not be reachable

Measured on a test instance with the defaults (`host=0.0.0.0`,
`publicSearchpage=true`): from another address, the YaCy port answers **without
any token** `/solr/select` (complete index, also `collection1` and
`webgraph`), `/yacysearch.json|html|rss`, `/suggest.json`,
`/yacy/search.html` (P2P search), the native MCP server `POST /tools` and
`/tools/call`, `/v1/models`, `/api/tags`, `/scoutro/api/v1/health` and the web
interface (`check_agent_access.py` against the port: 15 violations).
Agent scopes are enforced only on the agent path; they cannot restrict these
endpoints. A path that is blocked only in a proxy while the YaCy port stays
reachable is **not** a block.

## Operating path

```
agent host ──► proxy agent listener (e.g. 192.0.2.2:8095) ──► 127.0.0.1:8090 /scoutro/api/agent/v1/
                        every other path: 404
people     ──► proxy people listener (optional, VPN/LAN) ──► 127.0.0.1:8090 (web interface, admin)
YaCy       listens on 127.0.0.1:8090 only (HTTP and, if enabled, HTTPS 8443)
```

1. Stop YaCy. Apply `tools/scoutro/server/yacy-agent-server.conf` to
   `DATA/SETTINGS/yacy.conf`: `host=127.0.0.1` (binds the HTTP and the HTTPS
   connector), `upnp.enabled=false`, `adminAccountForLocalhost=false`,
   `ai.shield.allow-nonlocalhost=false`, `server.reverseProxy.trusted`
   (default, loopback). Decide `publicSearchpage` (below). Start YaCy and
   check: `ss -ltn` shows `127.0.0.1:8090`, not `0.0.0.0:8090`.
2. Install `tools/scoutro/server/nginx-scoutro.conf` (inside `http { }`),
   replace the listen addresses (agent listener on the interface the agents
   use, ideally with TLS) and reload nginx. The agent listener forwards only
   `/scoutro/api/agent/v1/` (methods GET, POST, PATCH) and the public
   descriptions `/scoutro/api/actions.json` and `/scoutro/api/openapi.json`;
   everything else answers 404. nginx matches and forwards the normalized URI,
   so dot segments, `%2e%2e` and `..%2f` cannot reach other endpoints. It sets
   `X-Real-IP`, which YaCy trusts from 127.0.0.1, so audit log and rate limits
   see the agent's address.
3. Give agents only the agent listener URL, e.g.
   `http://192.0.2.2:8095/scoutro/api/agent/v1`, and their own token from the
   wizard. Never give them the people listener or the YaCy port.
4. Prove it from the agent's position (a machine or network namespace where
   the agent runs), once without and once with an agent token:

   ```sh
   SCOUTRO_AGENT_BASE=http://192.0.2.2:8095 SCOUTRO_DIRECT=192.0.2.2:8090,192.0.2.2:8443 \
     python3 test/scoutro-server/check_agent_access.py
   SCOUTRO_AGENT_BASE=http://192.0.2.2:8095 SCOUTRO_DIRECT=192.0.2.2:8090,192.0.2.2:8443 \
     SCOUTRO_AGENT_TOKEN=sca_... python3 test/scoutro-server/check_agent_access.py
   ```

   The script only reads. It checks that the agent API answers 401 without
   and 200 with the token, that Solr, GSA, native search, suggestions, P2P
   search, native MCP, RAG chat, model lists, the URL proxy, the admin API,
   status, crawler, administration pages and the web interface are refused
   (status >= 400) with and without the token, including path tricks, and
   that the YaCy ports refuse connections. Exit status 0 means no violation.

Measured on the disposable test instance (same host, agent address
192.0.2.2): default settings, 64 passes and 1 violation (YaCy port
reachable); after step 1 and 2, 65 passes and 0 violations with the token,
0 violations without.

**Do not reconfigure a running production server silently.** The settings
change who can reach YaCy; apply them in a maintenance window and run the
check afterwards.

### Limits of this path

- **P2P network.** With `host=127.0.0.1` other YaCy peers cannot connect to
  this peer; it works as a non-reachable (junior) peer or in Robinson mode.
  If the peer must accept P2P connections, its port has to be reachable for
  the peers, and with it `/solr/`, `/yacy/search.html` and the public search.
  Then restrict the agent network with a packet filter instead (agents must
  not reach the YaCy port at all) and run the check from the agent network;
  this variant is not covered by the shipped files.
- **Public search** (`publicSearchpage=true`, the default): everyone who
  reaches the people listener can search the whole index, use `/solr/select`
  and the native MCP `/tools` without login. Agent scopes do not make that
  content confidential. Set `publicSearchpage=false` if the index must not be
  public; search and `/solr/` then need the administrator account.
- **Same host.** Every process on the Scoutro host reaches `127.0.0.1:8090`
  and therefore the public endpoints above (not the administrator functions,
  because `adminAccountForLocalhost=false`). The access separation holds for
  agents on other hosts or in separate network namespaces, not for code
  running on the host itself.

## Trust boundary of the research worker

`tools/scoutro/agent/scoutro-agent-bridge` is a program of the operator, not
an agent: it runs under the operator's control, reads its runtime secret
(`DATA/SETTINGS/agent-runtime/<agent>.secret`) and holds the worker's agent
token and the Clustro agent key.

- If it runs on the Scoutro host with
  `SCOUTRO_AGENT_URL=http://127.0.0.1:8090/scoutro/api/agent/v1`, it can
  technically reach everything on 127.0.0.1:8090, including the public
  `/solr/` and native search (see "Same host"). Its restriction to the
  agent's grant and scope comes from its code, which calls only the agent
  API with its own token; the network does not enforce it there. Treat it as
  trusted code like YaCy itself.
- Clustro run contents (tasks) are untrusted input: the worker validates
  them against fixed task schemas and turns them into agent API calls only;
  Scoutro checks every call against the grant and scope.
- The language model (optional, only if the grant allows it) gets evidence
  texts and returns a classification. It has no tools and cannot trigger
  requests; its output is validated and never executed.
- The worker never follows redirects with credentials and allows only one
  worker per Clustro connection (`tools/scoutro/agent/README.md`).
- For network-enforced separation run the worker on another host or in its
  own network namespace, set `SCOUTRO_AGENT_URL` to the agent listener and
  run `check_agent_access.py` from there.

`SCOUTRO_AGENT_URL` has no default; the worker refuses to start without it
and names both forms above.
