# Scoutro 0.12.0, gate T0: the owner's review of the reference set and its corrections

The reference set of phase 1 (22 October 2026: 200 domains, offers and counterexamples with quotes, written by OpenCode
from the live Solr) was reviewed by the owner in 30 cases on 22 October 2026. This document records every correction, its
type and its reason. Release: [SCOUTRO_RELEASE_0.12.0.md](SCOUTRO_RELEASE_0.12.0.md); T6: [SCOUTRO_GATE_T6_0.12.0.md](SCOUTRO_GATE_T6_0.12.0.md).

- **Source of the corrections:** the pages exported in phase 3a, as Solr held them. The live pages could not be reached
  from the maintainers' environment; where a reading rests on the exported page's context only, the table says so. A live
  page may differ from its export.
- **The former verdicts stay:** the reference of phase 1 and the judgements of phase 2 are kept unchanged (Olares storage,
  `~/scoutro-gates-0.12.0`); the corrected set is a second version (199 domains), derived from them by the recorded
  corrections below. Both versions are measured.
- **Types:** **a** no real offer; **b** a real offer in the wrong category; **c** the right offer with an unsuitable quote;
  **d** a real offer the reference did not hold yet. One entry was a duplicate.

## Corrections

| Domain | Type | What | Before | After | Reason |
|---|---|---|---|---|---|
| lacom-coating.de | a | Flooring | offer `construction/bodenbelaege` ("- Flooring") | counterexample (application area) | an item of the menu "Anwendungen" (Fuel Cell, Battery, Decor, Tape, Medical, Textiles, Flooring, Industry): an application area of the coating machines, no floor coverings offered |
| galysansorge.de | b | "#### Wärmepumpen/Müllboxen." | `construction/waermepumpen` | `construction/metallbau` (enclosures for heat pumps and bins); `waermepumpen` judged a topic | "Metallbau Galys & Ansorge GmbH": metal constructions by welders and fitters; the tile stands among gates, balcony and stair railings, fences, canopies and privacy screens. The page names no installation of heat pumps. Read from the exported page only: the word "Einhausung" is not on it, the reading as an enclosure is the maintainers' conclusion from the context |
| nht-stendal.de | c | Trockenbauarbeiten, Maurerarbeiten | a quote cut off before the item ("Demontage … Entsorgung. Geruchsneutralisierung. …") | the whole list of the renovation section ("Zu unseren Tätigkeiten und Kompetenzen gehören: … Maurerarbeiten. Tapezier- und Malerarbeiten. Trockenbauarbeiten. Fliesenlegerarbeiten. Verlegung von Laminatböden.") | real offers, the quote did not name them |
| nht-stendal.de | c | Sanierung von Wasserschaden | a quote running into the next section | "Unser Unternehmen bietet folgende Leistungen: Legionellenprüfung. Heizungs- und Sanitärinstallationen. Wärmepumpentechnik. Bautrocknung und der Sanierung von Wasserschaden." | real offer, now with its whole sentence |
| nht-stendal.de | d | Heizung, Sanitär, Wärmepumpentechnik, Maler, Fliesen, Laminatböden | not in the reference | six offers with the two quotes above | named in the list of offers or of the renovation works |
| www.steinkretzer-coaching.de | duplicate | the whole entry | a second reference domain | removed; case 15 of the review is replaced by janine-marielle-ruch.de (below) | the same firm as steinkretzer-coaching.de; it counted twice in T1 and T2 |
| amedes-pflegedienste.de | c | Ambulante Pflege | "Um in pflegerischen Notsituationen schnell Hilfe zu bekommen, installieren wir Ihnen gerne …" | "### Häusliche Pflege und Hauswirtschaftliche Versorgung. Grundpflege nach SGB XI, Pflegekasse." | the former quote describes the home emergency call |
| amedes-pflegedienste.de | d | Hausnotruf | not in the reference | offer `care/hausnotruf` ("### 24 Std. Hausnotruf. Um in pflegerischen Notsituationen …") | the former quote is this offer of its own |
| pflegeteam-altmark.de | c | Haushaltshilfe | "Wir bieten Betreuungsentlastung in Form von Spaziergängen und gemeinsamer Zeit im Freien s…" | "### Betreuungs- und Entlastungsleistungen. Wir bieten Betreuungsentlastung in Form von Spaziergängen und gemeinsamer Zeit im Freien sowie Hilfe bei der Hauswirtschaft, Erledigung von Einkäufen und Reinigung des Lebensraumes an." | the offer is right, the quote ended before "Hilfe bei der Hauswirtschaft"; a hint of the independent review recorded in the owner's notes, not in the owner's list |
| astrics.eu | a | Infrastruktur | offer `software/infrastruktur` | counterexample (partner network) | "In Usability-Fragen arbeiten wir erfolgreich mit Partnern in unserem Netzwerk zusammen": a network of partners, no IT infrastructure |
| dreberis.com | a | "Skip to main content" | offer without a category | counterexample (navigation) | a link of the navigation; rule/8 rightly offers nothing |
| dreberis.com | a | Cybersecurity (judged in phase 2) | judged an offer | judged a topic | the title of a news item on a cooperation event ("Israeli-German cooperation – civil security technologies and services"), no offer of DREBERIS |
| visit-gruppe.de | a | QA/Testing | offer `software/qa_testing` ("Qualitätssicherung") | counterexample | "## Qualitätssicherung" of a home care provider over its own care work, no software testing |
| yaveon.com | b | "### Quality Assurance." | `software/qa_testing` | `software/erp` | an app of its ERP (Microsoft Business Central) for quality management, no software testing service |
| yaveon.com | a | Embedded (judged in phase 2) | judged an offer | judged a topic | "seamlessly embedded into Microsoft Business Central": an integration, no embedded systems |
| planqc.eu | a | Embedded (outside the 30 cases) | found by the rules, judged an offer | counterexample | "a 1000-qubit system is being embedded into the HPC infrastructure": the verb; found through the general cause of the Yaveon case |

## Case 15 replaced

| # | Offer by the reference | Category | Quote (exported page `/en/services/`) | rule/8 finds |
|---|---|---|---|---|
| 1 | Kommunikations- und Präsentationstraining | coaching/rhetorik_praesentationscoaching | "Janine Marielle Ruch – Kommunikations- und Präsentationstraining." (title and brand line) | yes |
| 2 | Konfliktmanagement | coaching/konfliktcoaching | "### Trainingsschwerpunkte allgemein. - Verhandlungs- & Gesprächsführung. - Konfliktmanagement. - Women Empowerment. …" | yes |
| 3 | Stressmanagement | coaching/stresscoaching | "… - Corporate Identity & Teambuilding. - Stressmanagement." (same list) | yes |

Drawn from the 43 other checkthecoach domains with offers (seed 15). The owner's review of this case is open.

## General causes in the rules

Where a wrong offer came from the rules, its general cause was corrected, without a rule for a domain and measured in all
four collections (no collection worse); details in [the knowledge graph, 36.2](SCOUTRO_KNOWLEDGE_GRAPH.md#362-what-rule8-accepts-servicesevidence-businessrules-ruleextractorversion-8):

| Case | Cause | Correction |
|---|---|---|
| LACOM | a menu item under "Anwendungen" counted as a bulleted offer; the title "Laminating" matched "Laminat" as a compound | items under an application area or a market name no offer (`marketItem`); an English word form ("-ing", "-ed", "-ion", "-or") is no compound (`TermMatcher`) |
| astrics | "Netzwerk" of partners matched IT infrastructure | a network of partners is no offer, a sentence about IT, LAN, servers or cabling keeps it (`businessNetwork`) |
| Yaveon, planqc | "embedded into" matched embedded systems; the bare "quality assurance" matched software testing | a past participle before "into/in" is no offer (`participle`); the software vocabulary drops the bare "quality assurance" |
| DREBERIS | the title of a news item with "Services" made the page a services page | the title of a news, article, event or careers page makes no services page; only its path does |

Not corrected in the reference, a hint of the same independent review left to the owner: Yaveon's "Label Connect" ("print
in the cloud", an app for Business Central) stays `software/cloud`; whether an app of a cloud ERP is a cloud offer is the
owner's call.

Not corrected in the rules, with the reason:

- **visit, "Qualitätssicherung":** the word is software testing in a software firm's list ("- Qualitätssicherung." of
  codemanufaktur, a real offer of the reference) and quality management elsewhere. Dropping it loses that offer
  (stackfinder T1 −1), which the rule of 22 October 2026 does not allow; the vocabulary of a collection is the place for
  industry knowledge, a switch per collection is the owner's decision. Known limit: a care provider in the stackfinder
  collection keeps "QA/Testing".
- **Galys Ansorge, "Wärmepumpen/Müllboxen":** a heading in a row of headings with no sign that the products are housings.
  Known limit.
- The other wrong services judged earlier remain (13 in all, below).

## Measurements

T1: share of the reference's offers with a category that rule/8 finds; T2: share of the judged services that are right
(no service unjudged). Gates: T1 ≥ 80 %, T2 ≥ 85 % per collection. "Olares" is the run of phase 3c on the live Solr
(commit `5c311fa`); "local" replays the pages exported in phase 3a with the same probe. The local replay of `bf5f7f6`
gives exactly the numbers of Olares on the corrected reference, so live and replay agree.

| Measurement | Reference | Code | T1 bauteamcheck / checkthecoach / edelsenior / stackfinder | T2 |
|---|---|---|---|---|
| (1) as reported by phase 3c | phase 1 | `5c311fa` (Olares) | 85.0 / 83.1 / 89.8 / 84.5 % | 99.3 / 99.0 / 100 / 98.6 % (13 unjudged) |
| (2) the 13 judged | phase 1 | `5c311fa` (Olares) | 85.0 / 83.1 / 89.8 / 84.5 % | 99.3 / 99.0 / 100 / 97.2 % |
| (3) **reference corrected** | corrected | `5c311fa` (Olares) = `bf5f7f6` (local) | 84.8 / 82.5 / 89.9 / 84.1 % (140/165, 66/80, 160/178, 122/145) | 98.6 / 98.9 / 100 / 95.0 % |
| (4) **code corrected** | corrected | this commit (local) | 84.8 / 82.5 / 89.9 / 84.1 % | 98.9 / 98.9 / 100 / 96.7 % (275/278, 94/95, 250/250, 266/275) |

- **(2) → (3), the reference's correction:** T1 moves by the corrected offers: bauteamcheck 160 → 165 offers (NHT +6, all
  found; LACOM −1), found 136 → 140 (Galys' new category Metallbau is not found); checkthecoach 83 → 80 and 69 → 66 (the
  duplicate); edelsenior 177 → 178 and 159 → 160 (Hausnotruf, found); stackfinder 148 → 145 and 125 → 122 (astrics, visit,
  planqc; Yaveon's ERP replaces QA/Testing and is found). T2 falls by the services now known to be wrong (bauteamcheck
  2 → 4, stackfinder 8 → 14).
- **(3) → (4), the code's correction:** eight wrong services fewer (LACOM Bodenbeläge, astrics Infrastruktur, DREBERIS
  Cybersecurity, Yaveon and planqc QA/Testing and Embedded), no found offer lost, no service gained.
- **Remaining wrong services (13):** bauteamcheck 3 (borapa's Metallbau and Stahlbau from a list of an engineer's
  specialities; Galys' heat pumps), checkthecoach 1 (a client's word "Teamcoaching" in a testimonial), stackfinder 9 (visit's
  "Qualitätssicherung"; "Wissensdatenbank" twice and "Firmennetzwerk" as databases and infrastructure; an article teaser on
  cloud software; "Endpoint Analytics" as a product feature; malorg's event teaser; two lines of wibu.com.cn's integration
  list). All were judged before; this review starts no new analysis of them.

**The 13 services new in phase 3c**, judged by the owner (all from a row of headings): bauteamcheck 6 of 6 right, edelsenior
2 of 2, stackfinder 1 of 5 (aventer's DevOps right; cwd-solution's "Endpoint Analytics", malorg's teaser and two lines of
wibu.com.cn's integration list wrong): 9 of 13 (69 %). The code of this commit keeps all 13; in software sites a row of
headings names product features as often as offers.
