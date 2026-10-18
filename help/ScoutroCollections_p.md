---
page: htroot/ScoutroCollections_p.html
help: help/ScoutroCollections_p.md
title: Collections
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroCollections_p.java
---

# Collections

*Settings → Collections* lists the collection catalog: every collection of the
index, every explicitly created collection and the collections of Discovery
profiles (YaCy's internal `robot_*` collections are left out). For each:
display name and id, document count, source (index, created, Discovery
profile), knowledge-graph state (on/off) and a link to its websites.

**New collection** opens the existing dialog (id, name, description); a new
collection is empty until a crawl writes into it and is offered at once in
every collection choice.

Collections are logical data areas in one index, not separate tenants. Who may
use a collection is set per account (*Users*) and per agent (*Agents*).
Knowledge-graph settings per collection are under *Knowledge graph operations*.

Read from `GET /scoutro/api/v1/collections`; creation through
`POST /scoutro/api/v1/collections` (administrator).

## Correction proposals

The correction automation (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md) finds
websites that are in the wrong specialist collection. It is **off** unless
`scoutro.collections.autocorrect.mode` says `simulate`, `propose` or `apply`,
and it uses independent signals only: the website's structured data and the
categories its pages offer (text fit, read by the rules, no model), the
Discovery classification of the site, and OpenStreetMap. Signals of the
website count as one source, two OpenStreetMap objects as one, a copied
statement with the source it repeats. An absence or a crawl error is never a
signal.

- The list shows each case: the current collection and the target (or that it
  needs a review), the signals by source, its status and whether it was drawn.
- **Approve** applies a proposal as a manual collection decision (a case for
  review asks for the target), **Reject** keeps the website where it is.
  **Right** and **Wrong** judge a correction without applying it; a wrong
  automatic correction opens the breaker.
- **Draw a random sample** draws open corrections at random; only drawn and
  reviewed cases count for the acceptance, so nobody chooses which cases are
  measured. **Check the acceptance** accepts the apply mode when every drawn
  case is reviewed and the one-sided 95 % lower bound of the sample reaches
  `scoutro.collections.autocorrect.minLowerBound` (default 0.99, the 99 %
  target: 299 error-free reviews; one error needs 473, two need 628) with at
  least `minReviewed` (299) reviews and no error of high severity. A bound of
  95 % is shown as the interim stage, never as the operator release.
- After the acceptance a share of the automatic corrections
  (`scoutro.collections.autocorrect.auditRate`, 0.1) is drawn for the audit;
  20 unreviewed audit cases stop the automatic corrections until they are
  reviewed. **Close the breaker** after looking at a wrong case.

The Dashboard offers the same creation as **+** next to *Scoutro Collections*
and **−** to remove an empty collection created in Scoutro, to hide a
collection with pages (its pages stay in the index; **Show again** below the
cards) or, ticked and confirmed with its ID, to delete its pages that belong to
no other collection. Not while a Discovery profile, an account, agent, guest
access or running crawl uses it; see [Scoutro Dashboard](scoutro-dashboard.md).
