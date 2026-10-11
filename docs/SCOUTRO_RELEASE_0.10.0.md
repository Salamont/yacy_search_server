# Scoutro 0.10.0 release candidate

Prepared versions: **0.10.0** and **1.942-scoutro.24**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `492fef688814822d4adc2e44f8ac052d11ece953` (accounts #46–#50,
knowledge-graph corrections #51, locale test #52). Previous release: 0.9.1 /
1.942-scoutro.23, `ghcr.io/salamont/scoutro@sha256:ea86ba1f950db0e067d5993e9e2d4238be689f3f3ff2575819b36df970923482`.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Design and permission
matrix: [SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md); review report with
known gaps: [SCOUTRO_USERS_ACCESS_REPORT.md](SCOUTRO_USERS_ACCESS_REPORT.md);
context correction of customer suggestions: [SCOUTRO_MATCHING.md](SCOUTRO_MATCHING.md).

## Upgrade from 0.9.1

What happens on the first start of the new image with existing DATA:

- **Compatible access mode.** `scoutro.access.protected` is off and no account
  exists, so YaCy's previous route rules apply unchanged. The account code
  writes no setting at startup; `scoutro-users.json` appears only when an account
  is created, `scoutro-user-audit.jsonl` with the first audited sign-in or change.
- **Sign-in.** A browser navigation to a protected page shows the Scoutro login
  page (`scoutro.auth.loginPage`, default on) instead of the Digest dialog. The
  built-in administrator signs in there with the existing name and password.
  Sessions live in memory: a restart signs everyone out. HTTP Digest (tools,
  `scoutroctl`) and agent Bearer tokens are unchanged.
- **Cookie.** `scoutro_session`, `HttpOnly`, `Path=/`, `SameSite=Strict`.
  `Secure` follows `scoutro.auth.cookie.secure` (default `auto`): set when the
  request is HTTPS or carries `X-Forwarded-Proto: https`. Behind a TLS-terminating
  entrance whose inner hop sends `X-Forwarded-Proto: http` the cookie is **not**
  `Secure` in `auto` mode. **On Olares** (external entrance HTTPS only) set
  `scoutro.auth.cookie.secure=always` in `yacy.conf` during the stopped upgrade
  window, before the first browser sign-in: the set and the clearing cookie then
  carry `Secure` although Scoutro itself is reached over internal HTTP.
  `SameSite` stays `Strict`.
- **Sign-in limits behind a proxy.** The limits and the audit field `client`
  count a direct connection by its socket address and every connection from a
  peer in `server.reverseProxy.trusted` (installed: loopback, i.e. the whole
  Olares chain) in one shared bucket `proxy:<proxy address>`. `X-Real-IP` and
  `X-Forwarded-For` sent by a client therefore cannot choose a bucket. Trade-off:
  30 failed form sign-ins from anyone within 15 minutes make every form sign-in
  through that proxy wait up to 15 minutes (the 5-per-name limit and the limit of
  4 parallel password checks stay; HTTP Digest and agent Bearer tokens are not
  affected). `scoutro.auth.limits.proxyAddress=x-real-ip` counts one valid
  `X-Real-IP` of a trusted proxy instead, only for a proxy shown to replace that
  header; the package does not set it.
- **Settings preserved.** The image reads heap, proxy trust, localhost
  administrator and peer-to-peer settings from `DATA/SETTINGS/yacy.conf`. An
  existing configuration is neither re-seeded nor rewritten, so installed values
  such as `javastart_Xmx=Xmx3072m`, `server.reverseProxy.trusted` (loopback
  patterns), `adminAccountForLocalhost=false`, the disabled index
  distribution/receipt (DHT) and `scoutro.kg.gate.maxLoad=8` stay as they are.
  Do not reset or re-seed `yacy.conf`; the image defaults (`Xmx600m`, loopback
  trust) apply only to a first installation.
- **Knowledge graph.** Schema stays **6**; store and migration code are
  unchanged since 0.9.1. The shared signal parser moves to `BusinessSignals` 3,
  which is not part of an extractor identity: no `extractor_changed` work, no
  re-extraction, rebuild or reset. Wrong customer suggestions from reference
  pages and stated competence (STELLWERK) disappear on read immediately; the
  regular matching cycles delete their cache rows with a delete notice.
- **Agent crawls** stay on exactly their start host (`www.` and port allowed).
  A crawl from `example.com` no longer follows `example.com.au`, other allowed
  domains or subdomains; each needs its own start.
- **Peer-to-peer.** Unchanged in compatible mode. In protected mode the index
  routes need the administrator and the DHT pauses unless
  `scoutro.access.p2p=true` (default off).

Protected mode, Research/Operator accounts and guest access are **not** part of
the upgrade. Enable them only after the acceptance below, as a separate decision.

## Rollback

The primary return point stays a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.9.1 image digest above.

Because the schema stays 6, starting the 0.9.1 image on DATA written by 0.10.0
is expected to work, but it is **not tested here**. Consequences of a rollback:

- The login page, accounts, audit and protected mode are gone; the files
  `scoutro-users.json` and `scoutro-user-audit.jsonl` remain and are ignored.
  Keep production in compatible mode until acceptance, so a rollback never
  re-opens routes that protected mode had closed.
- 0.9.1 matching re-reads the old contexts: the withdrawn reference-page
  contributions (STELLWERK) can return in later matching cycles.
- 0.9.1 recognizes a start marker only when the must-not-match filter is the
  marker alone. Agent and Discovery crawl profiles created by 0.10.0 carry the
  marker plus the host filter, so 0.9.1 cannot assign them to their agent or
  Discovery attempt. Before a rollback, pause Discovery and let its crawls
  started under 0.10.0 finish (or stop them), and finish or stop agent crawls.
  The precheck found no agent store on the production instance.
- Agent, Operator and Discovery crawls follow YaCy's host-prefix filter again.

No lossless downgrade is claimed. Keep the newer DATA separately when restoring
an older backup.

## Olares acceptance gates

Before relying on sign-in limits, cookies or protected mode on Olares, run the
acceptance plan of the package repository (`docs/ACCEPTANCE-0.10.0.md`). The
code facts it rests on:

| Area | Behaviour in this release |
| --- | --- |
| Sign-in limits | 5 failures per account name and 30 per client within 15 minutes (`LoginThrottle`), at most 4 parallel password checks. The client is `SignInClient`: the socket peer, or `proxy:<peer>` for every peer matching `server.reverseProxy.trusted` (one shared bucket; on Olares `proxy:127.0.0.1`). `X-Real-IP` counts only with `scoutro.auth.limits.proxyAddress=x-real-ip` and exactly one valid value; `X-Forwarded-For` never. Per-client limits behind the Olares entrance are therefore **not** available until an `X-Real-IP` contract is proven. YaCy's own effective address (`RequestHeader.client`, Access Tracker) is unchanged and not used for these limits. |
| Chat, LLM shield | `ClientAddress`: from a trusted peer one valid `X-Real-IP` first, otherwise the right-most `X-Forwarded-For` entry that is not a trusted proxy; `source` is `direct`, `untrusted_proxy`, `x_real_ip`, `x_forwarded_for` or `unresolved`. |
| Cookie | `SameSite=Strict`, `Secure` in `auto` only for HTTPS or `X-Forwarded-Proto: https`. Planned for the Olares upgrade: `scoutro.auth.cookie.secure=always` before the first browser sign-in (set, use and clear over internal HTTP tested). `scoutro.auth.cookie.sameSite=lax` only after a measured app-window finding and approval. |
| Rights | Only administrator sessions get `adminRight`; Research/Operator are limited to their collections in protected mode. |

Existing diagnostics (none of them records cookies, `Authorization` headers or tokens):

- **Audit** (`GET /scoutro/api/v1/access/audit`, Settings → Users → Audit; file
  `DATA/SETTINGS/scoutro-user-audit.jsonl`): `auth.login` (`ok`, `failed`
  `invalid_credentials`, `denied` `too_many_attempts`/`account_locked`) and
  `auth.logout`, each with the sign-in client `client` (an address, or
  `proxy:<address>` for the shared bucket of a trusted proxy).
- **YaCy access tracker** (`AccessTracker_p.html`, administrator): YaCy's
  effective address per requested path (one valid `X-Real-IP` from a trusted
  peer). It shows which `X-Real-IP` arrives, but is not the sign-in client.
- **Chat log line** (`DATA/LOG`, logger `RAGProxy`): `event=rag-request
  phase=start … localhost=… source=… peerTrusted=… shield=…`, written for a
  signed-in `GET /v1/chat/completions` before the method is refused, so no model
  is called. The line contains no addresses or headers.

No diagnostic shows the sign-in client together with the headers it ignored;
compare the audit `client` and the Access Tracker with controlled source
addresses and injected test headers.

## Candidate validation and later publication

The release PR records the exact tested commit, the locally built linux/amd64
image configuration ID, checks and limitations. A local configuration ID is not
a registry digest. Skipped fork CI is not a passed build.

**A later authorized merge** of the release PR changes `scoutro.properties` and
the workflow and therefore triggers the main `Publish Scoutro image` workflow.
It first refuses existing tags (only a genuine `404 / MANIFEST_UNKNOWN` counts
as free), then builds and pushes `1.942-scoutro.24` and `0.10.0` with the actual
merge revision. Do not dispatch it in addition. Before the merge, re-fetch
main/head and recheck both tag names. After publishing, verify one immutable
digest for both tags, linux/amd64 and the merge revision label; the Olares
package pins that digest, never a local image ID.
