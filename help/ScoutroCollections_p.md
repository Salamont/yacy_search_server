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

The Dashboard offers the same creation as **+** next to *Scoutro Collections*
and **−** to remove an empty collection created in Scoutro, to hide a
collection with pages (its pages stay in the index; **Show again** below the
cards) or, ticked and confirmed with its ID, to delete its pages that belong to
no other collection. Not while a Discovery profile, an account, agent, guest
access or running crawl uses it; see [Scoutro Dashboard](scoutro-dashboard.md).
