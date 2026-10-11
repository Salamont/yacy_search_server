# Scoutro changelog

Scoutro is an independent community project based on YaCy (GPL-2.0-or-later).

## Unreleased — users, roles and navigation (development, not released)

- Scoutro accounts for people with the roles Research, Operator and
  Administrator, collection access per account and a separate export right;
  passwords as Argon2id hashes in `DATA/SETTINGS/scoutro-users.json`.
- Own login page (`scoutro-login.html`) with safe return, sign-out, session
  expiry (30 minutes idle, 12 hours at most), server-side revocation,
  password change, administrator reset without e-mail, sign-in limits, CSRF
  protection and an audit log (`scoutro-user-audit.jsonl`). The built-in YaCy
  administrator signs in there with its existing password; HTTP Digest for
  tools and scripts is unchanged.
- Protected access mode (`scoutro.access.protected`, off after an upgrade): an
  allowlist that closes native search, Solr, MCP, suggestions, chat and the old
  YaCy pages to everyone but administrators, the precondition for Research and
  Operator accounts. Optional guest access, off by default.
- Design and permission matrix: [SCOUTRO_USERS_ACCESS.md](docs/SCOUTRO_USERS_ACCESS.md);
  baseline with evidence: [SCOUTRO_ACCESS_BASELINE.md](docs/SCOUTRO_ACCESS_BASELINE.md).

## 0.9.0 / 1.942-scoutro.22 — release candidate

- Customer and partner suggestions include organizations in other **authorized**
  collections, with origin labels and working object, graph and evidence links.
  Ordinary facts remain within the selected collection; access checks cover the
  complete evidence chain.
- Durable, dated system/competence and business-need observations survive live
  evidence/source cleanup. Job search, source availability and system assertions
  have separate statuses. No expiry date means unknown status; source loss does
  not prove job completion or software shutdown.
- Six explicit evidence-based rule groups supplement industry matching: IT
  support, CAD/BIM, own construction/site expansion, energy renovation,
  leadership/team development and regional care-transition cooperation. Customer
  and partner roles and independent reasons remain separate. Own SAP/IT-consultant
  recruiting does not support the corresponding job-based customer reasons.
- Optional global LLM enrichment schedule: automatic remains the default;
  scheduled and manual modes, zone/weekdays/time window, shared call-start
  spacing, bounded manual runs and stop/resume. All existing guards remain active.
- Jetty/Solr relocation fixes preserve module/manifest/service metadata and ship
  original upstream attribution/provenance unchanged. Dependency-Guard retained.
- Release packaging includes Scoutro version metadata and these release notes;
  optional build-only proxy CA support does not add trust to the runtime image.

**Upgrade:** KG schema 4 → 5 → 6, full verified DATA/configuration backup and
storage reserve required; protection/backfill precedes new extraction. No
lossless downgrade. Older versions ignore scheduling and can repeat unfinished
chunks. See [release and operator notes](docs/SCOUTRO_RELEASE_0.9.0.md).

The existing 0.8.7 / 1.942-scoutro.21 image remains immutable. Its historical
[rollout notes](docs/SCOUTRO_ROLLOUT_0.8.7.md) are not a 0.9.0 upgrade contract.
