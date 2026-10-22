# Scoutro 0.12.0, gate T6: the reference of customer suggestions, judged from the provider's view

Production data read only (phase 1 and phase 3b of the quality gates, 22 October 2026); the judgement is the maintainers',
by the rules the owner set on 22 October 2026. Release: [SCOUTRO_RELEASE_0.12.0.md](SCOUTRO_RELEASE_0.12.0.md); rules:
[SCOUTRO_MATCHING.md](SCOUTRO_MATCHING.md) (0.12.0, rounds 1 and 2).

## The rules of the judgement (owner decisions of 22 October 2026)

- A pair is judged from the **provider's view**: could the candidate buy exactly this provider's services? Own use of the
  product is one possible hook, not a condition; software houses and consultancies buy consulting, implementation and
  project support too.
- **Customer, partner and competitor roles** are judged per service and relationship; being a competitor excludes nothing,
  and neither does a consultant title alone.
- A reader offer or a client's testimonial on the candidate's page is **no own use**, but no reason to exclude the company as
  a possible customer either. Other sound reasons are checked separately.
- **References and articles** may prove a business relationship when the parties and its direction are clear.
- Three separate axes: **A** the assignment and meaning of the signal (whose statement is it, and what does it say?);
  **B** the candidate's suitability as a customer from this provider's view; **C** business relationships the sources prove.
  A suggestion is shown when its signal holds (A); B and C are judgements of the reference, not rules of the read.
- **One sentence is one piece of evidence:** the 17 pairs of checkthecoach rest on the same sentence of SEQUOYA (a reader's
  need, "… setzt die Teamentwicklung von SEQUOYA genau dort an, wo Sie uns brauchen"); they count as one, not as 17.

## Corrected verdicts of phase 1

| Pairs | Phase 1 | Corrected | Reason |
|---|---|---|---|
| 20, 23 (regio iT) | competitor only | **customer** | regio iT offers SAP solutions itself, and bought SAP consulting: STELLWERK's success story "S/4HANA Release-Wechsel bei E.V.A. & regio iT" (project profile: client E.V.A. and regio iT, release change S/4HANA 2021 to 2025, completed) proves the purchase from STELLWERK. For basecom and infocient this shows a possible fit; a relationship is proven only with STELLWERK. Its own words "Wir betreiben eine SAP First-Strategie" are its own use. |
| 15, 46 (transact) | customer | **"kein geeigneter Bedarfsbeleg"** | the examined sentence ("Ob KIS, SAP, Dienstplan-Anbieter oder simple CSV-Dateien – mithilfe unserer Connectoren …") offers its connectors to the reader. No general exclusion of transact as a customer. |
| 28, 50 (dab) | customer | **"falscher Akteur"** | the sentences ("Wir nutzen dab Nexus, um … auf unsere SAP-Daten zuzugreifen", "The solution is easy to use …") are testimonials of dab's clients on dab's pages: they belong to Verbund AG, Hydac, Leoni, Oetiker and Huntsman, which the rules now read as dab's clients. No general exclusion of dab. |
| 12 (seitenweise) | customer | **"kein geeigneter Bedarfsbeleg"** | "We have experience integrating with ERP systems (SAP, …)" is a competence of its offer. |
| 22 (ITM Consulting, itm.net) | customer | **"kein geeigneter Bedarfsbeleg"** | "We deploy and operate SAP LeanIX integrations …" is its offer; in phase 3b also "SAP LeanIX-Workspaces weltweit nutzen unsere Reports" (others' use). |
| 24, 29 (SEQUOYA) | competitor only | **"falscher Akteur"** | the reader's need on SEQUOYA's page, not SEQUOYA's. No general exclusion of SEQUOYA. |
| every other "competitor only" | competitor only | replaced by A and B | a competitor can be a customer; the examined sentences were its offer, its address to the reader, examples, partners or other companies (A), so B stays open. |
| 8, 17, 41, 35, 48 (QUANTO, Rewion, STELLWERK) | own capacity | confirmed | postings of consultancies for their own offer. |
| 13, 27, 39, 40 | not assignable | confirmed | an article, a quoted client in a case study, a list of industry partners. |
| 47 (toowoxx) | customer | confirmed | "Wenn wir Softwarelösung in der Azure Cloud entwickeln verwenden wir …": its own use. |

## Results (stored contributions read again; a pair is shown if one of its contributions is)

| Collection | Pairs | Signal holds (A) | Shown 0.11.7: right / wrong | Round 2, local, without the site's employer | Round 2 on Olares (phase 3c, `5c311fa`): **measured** | With the posting's delivery role: **local expectation** |
|---|---:|---:|---|---|---|---|
| stackfinder | 63 | 6 | 6 / 50 | 6 / 8 | 6 / 4 | 6 / 1 |
| checkthecoach | 17 (one piece of evidence) | 0 | 0 / 17 | 0 / 0 | 0 / 0 | 0 / 0 |
| bauteamcheck, edelsenior | no customer suggestion in production | – | – | – | – | – |

- On Olares the employer named as its site hid the four pairs of QUANTO Solutions. Rewion kept the requirement of its
  qualifications ("Mindestens 5 Jahre Erfahrung in der Umsetzung oder Leitung von SAP Projekten") while its description,
  which builds the business with clients, was hidden; the delivery role is now the posting's (`bf5f7f6`, tested locally).
- **Measured on production data: 6 right of 10 shown.** The 6 of 7 after the correction of Rewion's posting is a **local
  expectation** (stored contributions read again with the classes of this branch) until the read-only run of phase 3d
  confirms it on Olares with the final commit.
- One remains: placeworkers, "entfernen wir überflüssige Anpassungen und nutzen, was SAP bereits bietet" (its method for
  clients, read as own use).

**Real positive fits per collection (point 6):** the sample holds six (regio iT twice, toowoxx, INIT, neumeier twice; all
stackfinder). The documented relations add more after the re-extraction: on the 658 exported pages of the gates, STELLWERK
names six clients with a purchased S/4HANA project (Emitec, E.V.A., regio iT, Schloss Wachenheim, MEGA eG, STAWAG), dab five
clients by their testimonials (Hydac, Leoni, Oetiker, Verbund, Huntsman; SAP) and white duck five (ESYON, Novicon, Palfinger,
Rolls-Royce Power Systems with Azure; Schattdecor). Each is a reason of the kind `reference` for the other providers of the
product. Of 400 production pages naming clients (phase 3c, 100 per collection) the rules read 33 clients, each as the page
states it: bauteamcheck 25 (SBB, DB InfraGO, ÖBB Infrastruktur, BLS Netz as clients of a railway builder; Strabag, Züblin,
Max-Planck-Institut Rostock of a cleaning company; a parish of a restorer), stackfinder 8 (B. Braun, ALTANA, CLAAS with SAP
products, ProPotsdam with S/4HANA, Wuppertaler Stadtwerke, …). In checkthecoach, bauteamcheck and edelsenior neither the sample nor the exported pages hold a customer: the
providers' pages name private clients (coaching), no client at all, or a provider's own offer read as a need (a roofer:
"planen und realisieren wir … von der energetischen Sanierung bis zum Neubau", not matchable without own responsibility).
Phase 3c counted the recorded needs (`NeedFunnel`): 486 new builds, 198 rebuilds, 7 energy renovations, 4 site expansions
and 20 care transitions, none with the own responsibility, the hospital's own process or the location their rules ask for.
Of the sampled building needs all but a few own plans of companies were providers' offers ("Wir planen gemeinsam mit Ihnen
den Umbau …", "planen und realisieren wir …"); they are no needs any more (`BusinessSignals.NEED_OFFER`, with the re-read).
The care transitions are mostly care services' offers. **Known gap of recognition:** two hospitals describe their own
discharge management, a real need of the kind edelsenior's providers serve, and the rule does not recognise it: Krankenhaus
Mechernich ("Unser Entlassmanagement zielt auf einen nahtlosen Übergang der entlassenen Patienten in die Anschlussbehandlung
…") and the Kemperhof Koblenz of the Gemeinschaftsklinikum Mittelrhein ("Wir kontaktieren für Sie unsere Pflegefachkräfte
der Pflegeüberleitung, falls Sie zu Hause einen ambulanten Pflegedienst benötigen"). Both are recorded as care transitions,
but without the hospital's own process (`hospital_process` false), because the rule asks for "unser Krankenhaus" or "unsere
Klinik" in the sentence and the matching rule for a location; the hospital and its place stand only in the page's address
(`…/krankenhaus-mechernich/…`, `…/kemperhof-koblenz/…`). In bauteamcheck the sample's few plans of companies are a third
party's quoted on a provider's page (the head office of TRILUX on an architect's page) or completed (an own building
finished in 2022). So the missing customers of bauteamcheck and edelsenior are not only a matter of the crawl's scope: needs
that are on the pages are not recognised in these words. 0.12.0 does not change this (no larger rework now); it is an item
for a later release.

## All pairs

Set P1: the 50 pairs of phase 1 (seed 120); N: the 30 pairs of phase 3b (seed 220, per collection, phase 1 left out). Shown:
contributions shown of the pair's stored contributions with 0.11.7 and with round 2 (read again locally, without
`siteOffersItself`).

| # | Set | Provider | Candidate | Phase 1 | Correction | A: signal | B: suitability | C: documented relation | 0.11.7 | Round 2 |
|---|---|---|---|---|---|---|---|---|---:|---:|
| 1 | P1 | (quasar-edv.de) | cbs Corporate Business Solutions Un… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: examples of systems in its offer | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 2 | P1 | UNIORG AG (uniorg.de) | (d-velop.de) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: other companies | open: not derivable from this evidence (no exclusion) | none found | 28 | 0 |
| 3 | P1 | BLOHM (blohm.de) | BTC AG (btc-ag.com) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: cooperation or partnership | open: not derivable from this evidence (no exclusion) | none found | 1 | 0 |
| 4 | P1 | (bis-itk.de) | cbs Corporate Business Solutions Un… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: examples of systems in its offer | open: not derivable from this evidence (no exclusion) | none found | 12 | 0 |
| 5 | P1 | Fink IT-Solutions GmbH & Co. KG (fi… | OctaVIA (octavia.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 28 | 0 |
| 6 | P1 | (netz98.de) | INIT Individuelle Softwareentwicklu… | competitor only | replaced: a competitor can be a customer (A, B) | own use ("Seit mehr als 25 Jahren nutzen wir … SAP ERP im Verwaltungsbereich"); its offer sentences none | fit: uses SAP ERP itself | none found | 48 | 16 |
| 7 | P1 | Geschäftsführer S&N Invent GmbH (sn… | ITARICON GmbH (itaricon.de) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: a client's words (a client's system in a reference) | open: not derivable from this evidence (no exclusion) | none found | 12 | 0 |
| 8 | P1 | (neumeier-edv.de) | QUANTO Solutions (quanto-solutions.… | own capacity | confirmed | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 6 | 6 |
| 9 | P1 | Axians Deutschland (axians.de) | (neumeier-edv.de) | competitor only | replaced: a competitor can be a customer (A, B) | own use of a tool ("nutzen wir das SAP B1 Integration Framework"); its hosting offer none | possible: uses SAP B1 tooling itself | none found | 320 | 80 |
| 10 | P1 | (lnit.de) | (infocient.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 11 | P1 | IMIDA GmbH (imida.com) | Unternehmensgruppe Atruvia AG (pera… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 12 | P1 | (lupusconsulting.com) | seitenweise (?) | customer | "kein geeigneter Bedarfsbeleg" (was: customer) | no need evidence: a competence | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 13 | P1 | BTC IT Services GmbH (btc-it-servic… | D‑LABS GmbH (d-labs.com) | not assignable | confirmed | no need evidence: article, list or question | open: not derivable from this evidence (no exclusion) | none found | 2 | 0 |
| 14 | P1 | (rku-it.de) | (logiplus.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 400 | 0 |
| 15 | P1 | (scdsoft.de) | AMC Advanced Medical Communication … | customer | "kein geeigneter Bedarfsbeleg" (was: customer) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 20 | 0 |
| 16 | P1 | (ecomify.de) | (betasystems.com) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 17 | P1 | (lupusconsulting.com) | QUANTO Solutions (quanto-solutions.… | own capacity | confirmed | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 429 | 429 |
| 18 | P1 | Objektkultur Software GmbH (objektk… | (logiplus.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 80 | 0 |
| 19 | P1 | OctaVIA (octavia.de) | cbs Corporate Business Solutions Un… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: examples of systems in its offer | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 20 | P1 | (basecom.de) | (regioit.de) | competitor only | customer (was: competitor only) | own use ("Wir betreiben eine SAP First-Strategie") | fit: bought SAP consulting (STELLWERK reference) | STELLWERK → regio iT: S/4HANA release change (with E.V.A., completed); none with basecom | 12 | 12 |
| 21 | P1 | (s4p.de) | (d-velop.de) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: other companies | open: not derivable from this evidence (no exclusion) | none found | 2 | 0 |
| 22 | P1 | Artschwager + Kohl Software GmbH (a… | ITM Consulting (?) | customer | "kein geeigneter Bedarfsbeleg" (was: customer) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 23 | P1 | (infocient.de) | (regioit.de) | competitor only | customer (was: competitor only) | own use ("Wir betreiben eine SAP First-Strategie") | fit: bought SAP consulting (STELLWERK reference) | STELLWERK → regio iT: S/4HANA release change (with E.V.A., completed); none with infocient | 60 | 60 |
| 24 | P1 | Betreuungsverein e.V. (diakonie-os.… | SEQUOYA Karriereberatung und Organi… | competitor only | "falscher Akteur" (was: competitor only) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 212 | 0 |
| 25 | P1 | Brandeis Consulting GmbH (brandeis.… | prismat (prismat.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 32 | 0 |
| 26 | P1 | WIBU-SYSTEMS AG (wibu.com) | (d-velop.de) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: other companies | open: not derivable from this evidence (no exclusion) | none found | 80 | 0 |
| 27 | P1 | (bissconsulting.com) | SUSE (suse.com) | not assignable | confirmed | wrong actor: a client's words (a quoted client in a case study) | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 28 | P1 | DATIS IT-Services GmbH (datis.de) | dab (dab-europe.com) | customer | "falscher Akteur" (was: customer) | wrong actor: a client's words (testimonial on its page) | open: not derivable from this evidence (no exclusion) | dab → Hydac, Leoni, Oetiker, Verbund, Huntsman (testimonials); none with DATIS | 1 | 0 |
| 29 | P1 | körber seminare (koerber-seminare.d… | SEQUOYA Karriereberatung und Organi… | competitor only | "falscher Akteur" (was: competitor only) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 30 | P1 | (infocient.de) | Hand GmbH (pd-g.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: a competence | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 31 | P1 | Vorstand S&N Group AG (sn-group.de) | sovanta AG (sovanta.com) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 24 | 0 |
| 32 | P1 | OS Consulting GmbH (optimized-solut… | cbs Corporate Business Solutions Un… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: examples of systems in its offer | open: not derivable from this evidence (no exclusion) | none found | 2 | 0 |
| 33 | P1 | (betasystems.com) | prismat (prismat.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 3 | 0 |
| 34 | P1 | Faktor Zehn (faktorzehn.com) | Gobas Nord GmbH (gobas.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 35 | P1 | clavis berater sozietät (clavis.biz) | Rewion GmbH (rewion.com) | own capacity | confirmed | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 8 | 8 |
| 36 | P1 | (pmsf.eu) | SERPENTEQ GmbH (serpenteq.com) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: a competence (news of a new employee) | open: not derivable from this evidence (no exclusion) | none found | 6 | 0 |
| 37 | P1 | (ecomify.de) | CIDEON Software & Services GmbH & C… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: cooperation or partnership | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 38 | P1 | RED Global (redglobal.de) | CIDEON Software & Services GmbH & C… | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: cooperation or partnership | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 39 | P1 | KaTe GmbH (kate-group.de) | (kit-gruenderschmiede.de) | not assignable | confirmed | no need evidence: article, list or question (industry partners) | open: not derivable from this evidence (no exclusion) | none found | 1 | 0 |
| 40 | P1 | nubibase (?) | D‑LABS GmbH (d-labs.com) | not assignable | confirmed | no need evidence: article, list or question | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 41 | P1 | Denkwerkstatt für Manager GbR (denk… | QUANTO Solutions (quanto-solutions.… | own capacity | confirmed | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 12 | 12 |
| 42 | P1 | (bpc.ag) | (d-velop.de) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: other companies | open: not derivable from this evidence (no exclusion) | none found | 21 | 0 |
| 43 | P1 | KPS (kps.com) | prismat (prismat.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 1 | 0 |
| 44 | P1 | Axians Deutschland (axians.de) | OctaVIA (octavia.de) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 80 | 0 |
| 45 | P1 | SLA Software Logistik Artland GmbH … | KPS (kps.com) | competitor only | replaced: a competitor can be a customer (A, B) | wrong actor: other companies (a headquarters in its offer) | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 46 | P1 | (profiforms.de) | AMC Advanced Medical Communication … | customer | "kein geeigneter Bedarfsbeleg" (was: customer) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 47 | P1 | (computerra.de) | Toowoxx IT GmbH (toowoxx.de) | customer | confirmed | own use ("… in der Azure Cloud entwickeln verwenden wir") | fit: uses Azure | none found | 1 | 1 |
| 48 | P1 | (rku-it.de) | STELLWERK Consulting AG (stellwerk.… | own capacity | confirmed | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 120 | 0 |
| 49 | P1 | (ratiosoft.de) | (placeworkers.com) | competitor only | replaced: a competitor can be a customer (A, B) | no need evidence: the candidate's offer or address to the reader (its method: "nutzen, was SAP bereits bietet") | open: not derivable from this evidence (no exclusion) | none found | 32 | 32 |
| 50 | P1 | (dochouse.de) | dab (dab-europe.com) | customer | "falscher Akteur" (was: customer) | wrong actor: a client's words (testimonial on its page) | open: not derivable from this evidence (no exclusion) | dab → Hydac, Leoni, Oetiker, Verbund, Huntsman (testimonials); none with dochouse | 16 | 0 |
| 51 | N | prismat (prismat.de) | (neumeier-edv.de) | - | new (phase 3b) | own use of a tool (B1iF); its hosting offer none | possible: uses SAP B1 tooling itself | none found | 96 | 24 |
| 52 | N | Blohm Consulting GmbH (blohm.de) | QUANTO Solutions (quanto-solutions.… | - | new (phase 3b) | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 72 | 72 |
| 53 | N | (neumeier-edv.de) | (quanto-solutions.de) | - | new (phase 3b) | wrong actor: other companies (its clients use SAP) | open: not derivable from this evidence (no exclusion) | none found | 6 | 0 |
| 54 | N | RED Global (redglobal.de) | ITM Consulting (itm.net) | - | new (phase 3b) | wrong actor: other companies (others use its reports) | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 55 | N | (profiforms.de) | Rewion GmbH (rewion.com) | - | new (phase 3b) | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 8 | 8 |
| 56 | N | DATIS IT-Services GmbH (datis.de) | (infocient.de) | - | new (phase 3b) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 1 | 0 |
| 57 | N | (digitalwerkstatt-suedeifel.de) | amotIQ solutions (amotiq-solutions.… | - | new (phase 3b) | no need evidence: a competence (technologies it works with) | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 58 | N | (lnit.de) | (octavia.de) | - | new (phase 3b) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 59 | N | all4cloud Group (?) | sovanta AG (sovanta.com) | - | new (phase 3b) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 60 | N | Geschäftsführer S&N Invent GmbH (sn… | BTC AG (btc-ag.com) | - | new (phase 3b) | no need evidence: cooperation or partnership | open: not derivable from this evidence (no exclusion) | none found | 6 | 0 |
| 61 | N | (digitalwerkstatt-suedeifel.de) | Rewion GmbH (rewion.com) | - | new (phase 3b) | own delivery capacity (posting for its own offer) | open: not derivable from this evidence (no exclusion) | none found | 8 | 8 |
| 62 | N | Gobas Nord GmbH (gobas.de) | (d-velop.de) | - | new (phase 3b) | wrong actor: other companies | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 63 | N | Objektkultur Software GmbH (objektk… | ITM Consulting (itm.net) | - | new (phase 3b) | wrong actor: other companies (others use its reports) | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 64 | N | prismat (prismat.de) | (infocient.de) | - | new (phase 3b) | no need evidence: the candidate's offer or address to the reader | open: not derivable from this evidence (no exclusion) | none found | 24 | 0 |
| 65 | N | WIBU-SYSTEMS AG (wibu.com) | SAP Fioneer (?) | - | new (phase 3b) | no need evidence: article, list or question (a question in an FAQ) | open: not derivable from this evidence (no exclusion) | none found | 0 | 0 |
| 66 | N | (changetogrow.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 67 | N | Lösungsentdecker (loesungsentdecker… | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 68 | N | Ruhrcoach (ruhrcoach.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 69 | N | (horsemanship-coaching.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 70 | N | Angela Ziller – Coaching (angelazil… | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
| 71 | N | Menschen gewinnen, Zukunft gestalte… | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 72 | N | ICF Beratungszentrum Karlsruhe (icf… | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 73 | N | (coachandchange.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 74 | N | (claudia-schmid-coaching.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 75 | N | CiSmart Solutions GmbH (cofoony.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 76 | N | (kraemercoaching.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 77 | N | CoachMaster (coach-master.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 16 | 0 |
| 78 | N | (coaching-puender.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 79 | N | Ortner Coaching GmbH (ortner-coachi… | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 4 | 0 |
| 80 | N | (eskapade-coaching.de) | SEQUOYA Karriereberatung und Organi… | - | new (phase 3b) | wrong actor: the reader's need | open: not derivable from this evidence (no exclusion) | none found | 8 | 0 |
