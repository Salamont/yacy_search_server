# Scoutro changelog

Scoutro is an independent community project based on YaCy (GPL-2.0-or-later).

## 0.9.1 / 1.942-scoutro.23 — patch release candidate

- Fix a recursive clause regex that can overflow the stack on long text or
  JSON-LD descriptions and terminate the periodic KG synchronization task.
  The linear scanner preserves existing quotation, date and locator contracts.
- Show the sync task's scheduler state, last execution, error and reconcile
  progress in the administrator API and localized overview. Unexpected task
  failures are logged and remain terminal; fatal JVM errors are not retried.
- Preserve unpersisted dirty events after transaction failures and bind task
  diagnostics/cancellation to their runtime session, including late completion
  after close, restore or a rebuild swap. Existing Solr/storage retries remain.

The code failure is reproduced against the 0.9.0 image with the reported
scan/extraction/publication counters; the actual production trigger remains
unconfirmed. No schema, extractor identity, load threshold or matching change.
See [patch release notes](docs/SCOUTRO_RELEASE_0.9.1.md) and
[sync reproduction and validation](docs/SCOUTRO_KG_SYNC_VALIDATION.md).

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
