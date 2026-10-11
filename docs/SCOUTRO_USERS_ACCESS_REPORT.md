# Abschlussbericht: Anmeldung, Nutzer, Rechte und Oberfläche

Stand: 2026-10-11. Entwicklungsstand zur Prüfung, **nicht gemergt, nicht
veröffentlicht, nicht deployt**. Technische Details: Design und
Berechtigungsmatrix in [SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md),
Ausgangsbefunde mit Datei- und Zeilenbelegen in
[SCOUTRO_ACCESS_BASELINE.md](SCOUTRO_ACCESS_BASELINE.md).

## 1. Ausgangsstand

| | SHA |
|---|---|
| Analysierter `main` (Release 0.9.0) | `8812ad2f901ede7ebe6ea27764d30889c8a7b866` |
| Basis der Pull Requests (`main` nach PR #45, KG-Sync-Fix, Vorbereitung 0.9.1) | `829ab351554be86978fb0cab932033d609dee5f2` |

Die Pakete wurden auf `8812ad2` begonnen und vor dem Öffnen der Pull Requests
auf `829ab35` umgesetzt (Rebase; Konflikte nur additiv in `CHANGELOG.md` und
`build.xml`, generierte API-Beschreibung neu erzeugt). Das abschließende Review
(Abschnitt 10) hat je Paket einen Korrektur-Commit ergänzt (P2, P3, P4) und
diesen Bericht, den RAG-Test und die Reparatur des Wissens-Smokes in P5.

## 2. Befunde (bestätigt, Belege in SCOUTRO_ACCESS_BASELINE.md)

Alle sechs Vorbefunde sind bestätigt: ein einziges eingebautes
Administratorkonto (Digest) für Menschen; alle lesenden Scoutro-Funktionen
verlangten Administratorrechte; Agenten haben bereits differenzierte Aktionen
und Collection-Scopes; die Navigation mischte Scoutro-Funktionen und
YaCy-Administration; die Wissensgraph-Seite mischte Recherche und Betrieb; das
Dashboard enthielt die vier Portal-Collections fest im Code.

Zusätzlich gefunden: `suggest.json` und MCP `/tools` antworten ohne Anmeldung
(auch mit `publicSearchpage=false`); RAG-Gäste ohne Collection lesen den
ganzen Index; der `collection`-Wert von `yacysearch` erreicht ungeprüft den
Solr-Filter; die Admin-Suche ignorierte `?collection`. Olares: privater
UI-Zugang (Port 8090) und interner API-Zugang über einen nginx-Sidecar, der nur
`/scoutro/api/` weiterleitet; **keine belegbare SSO-Schnittstelle** – deshalb
kein Olares-SSO, die Scoutro-Anmeldung arbeitet eigenständig.

## 3. Architektur in Kürze

- **Identitäten:** Scoutro-Konten für Menschen (Argon2id, `DATA/SETTINGS/scoutro-users.json`),
  eingebauter Administrator (Digest, unverändert), Agenten (Token, unverändert getrennt), Gast (optional, aus).
- **Rollen → Aktionen, Collections → Daten:** Recherche, Operator, Administrator, Gast; Export als eigenes Kontorecht.
  Eine Entscheidung (`Caller`) für Container-Regeln, Servlets und Seiten; Research/Operator erhalten nie `adminRight`.
- **Sitzungen:** serverseitig (30 min Leerlauf, 12 h max., Widerruf bei Rechte-/Status-/Passwortänderung),
  Cookie `HttpOnly`/`SameSite=Strict`/`Secure` hinter HTTPS, CSRF-Header, Anmeldelimits, Audit-Log ohne Geheimnisse und Suchtexte.
- **Zugriffsmodi:** kompatibel (Standard nach Upgrade, YaCy-Verhalten unverändert) und **geschützt** (Allowlist; native
  Suche, Solr, MCP, Vorschläge, alte YaCy-Seiten nur für Administratoren; Voraussetzung für Recherche-/Operator-Konten,
  „fail closed“, wenn aktive eingeschränkte Konten existieren).
- **Durchsetzung:** `RoutePolicy` (Container), `PeopleRoutes` (jede `/scoutro/api/v1`-Route mit Recht und Scope),
  `DiscoveryApi` (Jobs nach Profil-Collection), `KnowledgeRead` (eingeschränkter Leser), `RAGProxyServlet` (Chat nach
  Sitzung), Seiten-Servlets; die Navigation zeigt nur an, was der Server ohnehin erlaubt.

## 4. Berechtigungsmatrix (Kurzfassung)

| Funktion | Gast¹ | Recherche | Operator | Admin |
|---|---|---|---|---|
| Suche (Scoutro-Suche) | freigegebene Collections | eigene | eigene | alle |
| Chat mit Quellen und Graph-Fakten | – | eigene (genau eine) | eigene (genau eine) | alle |
| Übersicht, Websites, Host-Analyse, Wissen | – | eigene | eigene | alle |
| Exporte (Domains, Wissensgraph, Änderungsfeed) | – | mit Exportrecht | mit Exportrecht | ✓ |
| Crawls, Crawl-Berichte, Discovery-Jobs | – | – | eigene | alle |
| Discovery global, Collections anlegen, Domains umhängen | – | – | – | ✓ |
| Wissensgraph-Betrieb, Backups, Modelle, KI-Labor | – | – | – | ✓ |
| Nutzer, Zugriffsmodus, Gast, Audit, Agenten | – | – | – | ✓ |
| System (Re-Start, Shutdown), YaCy-Werkzeuge, native Routen | – | – | – | ✓ |
| Eigenes Konto (Passwort, Sitzungen, Abmelden) | – | ✓ | ✓ | ✓ |

¹ nur im geschützten Modus und nur wenn eingeschaltet. Vollständige Matrix mit
Serverprüfung je Funktion: SCOUTRO_USERS_ACCESS.md, Abschnitt 3; je API-Route:
[API.md](API.md#routes-for-accounts) und `x-scoutro-permission` in `openapi.json`.

## 5. Pull Requests (gestapelt, Entwurf)

| # | Branch | Inhalt | Basis |
|---|---|---|---|
| 1 | `ccr-2ad9a69f-3dvaa8-p1-analysis` | Ausgangsbefunde, Architektur, Matrix | `main` |
| 2 | `ccr-2ad9a69f-3dvaa8-p2-auth` | Konten, Anmeldung, Sitzungen, zentrale Rechte, geschützter Modus, Audit; Review: P2P-Routen, Argon2-Slots, CSRF-Pfad, Anmeldelimits | PR 1 |
| 3 | `ccr-2ad9a69f-3dvaa8-p3-ui` | Header, rechtegesteuerte Navigation (alle bisherigen YaCy-Seiten erreichbar), Konto-/Nutzer-/System-/Collections-Seiten, 403-Seite; Review: P2P-Schalter und Warnungen | PR 2 |
| 4 | `ccr-2ad9a69f-3dvaa8-p4-data` | Funktionsseiten und Daten nach Recht und Collection, Scoutro-Suche, Chat-Scope, dynamisches Dashboard, Kontext; Review: Chat-Werkzeuge, exakter Crawl-Host, `/heuristic`; Nachtrag: exakter Starthost auch für Agenten-Crawls | PR 3 |
| 5 | `ccr-2ad9a69f-3dvaa8` | Mehrbenutzer-E2E-Test, RAG-Test mit Modell-Stub, Agenten-Host-Smoke, Reparatur des Wissens-Smokes, dieser Bericht | PR 4 |

Jeder PR ist für sich prüfbar; erst mit PR 4 können Recherche- und
Operator-Konten Funktionsseiten nutzen (bis dahin sehen sie nur ihr Konto).

## 6. Tests (Wegwerf-Instanzen, offline)

| Prüfung | Ergebnis |
|---|---|
| JUnit (Scoutro- und HTTP-Tests, sauberer Build) | 945 Tests nach dem Nachtrag (944 nach dem Review), 3 Fehler – dieselben 3 wie auf `main` `829ab35` (AgentCatalogTest, CollectionsStatusTest, KgRebuildTest; Einordnung in Abschnitt 10.5); KI-Tests (`net.yacy.ai`) 60/60 |
| `ant scoutro-access-test` | 9 Suiten, 74 Tests grün (vor dem Review; im Review die Access-Tests direkt: P2-Stand 71/71) |
| Access-Live-Smoke (HTTP, Mehrbenutzer, Scope-Manipulation, Digest-Automation, P2P-Routen, Operator-Crawl) | 177/177, auch nach dem Nachtrag |
| Agenten-Crawl-Hostgrenze (`agent-crawl-host-live-smoke.py`, erlaubter Starthost und täuschend ähnlicher fremder Host) | 10/10; ohne die Korrektur 7/10 (Abschnitt 10.7) |
| RAG-Chat mit Modell-Stub (`chat-rag-live-smoke.py`) | 27/27 |
| Mehrbenutzer-Browsertest (`accounts-ui-live-smoke.py`, DE/EN, 1280/390 px, Recherche/Operator/Admin/Gast; Administrator öffnet alle 45 internen Navigationsseiten inkl. YaCy-Werkzeuge) | 408/408 (+5 Setup), auch nach dem Review |
| Navigation (`scoutro-ui-test`) / Dashboard (`dashboard-ui-test`, `dashboard-live-smoke`) | 384/384 / 187, Live 237 + 4 + 13 Solr-Prüfungen (nach dem Review erneut grün) |
| SEO-Suite (`seo-live-smoke`: SEO 282, Domains/Export 473, System-Chat 71, KI-Labor mit Werkzeugen 593, Crawl/Discovery-Flow 405, Collection-Anlage 66) | grün, auch nach dem Review |
| Discovery (`discovery-live-smoke`) | 80 UI + 43 API grün (nach dem Review erneut) |
| Sprachwechsel (`locale-refresh-live-smoke`) | 75 + 3×63 grün (nach dem Review erneut) |
| LLM-Auswahl (`llm-selection-live-smoke`) | 719 UI + 50 HTTP grün (nach dem Review erneut) |
| Agenten, Wissensgraph-Export, MCP, Chat-UI (`kg-agents-live-smoke`) | 51 + 46 grün, nach dem Nachtrag auf dem finalen Stand erneut |
| Crawl-Berichte (`report-live-smoke`) | 453 UI-Checks + 9 Live-Lesezugriffe grün, nach dem Nachtrag auf dem finalen Stand erneut |
| API-Testsuite (`test_api.py`) | OK (3 Crawl-Tests ohne Test-Site übersprungen, wie auf `main`). Korrektur im Review: die frühere Angabe „identisch zu `main`“ stammte aus Läufen unter verschiedenen Bedingungen; unter identischen Bedingungen war `main` grün und der Stapel rot (`mcpTool: null` der Konto-Aktionen), behoben in PR #47 |
| Weitere Python-API-Suiten (`test_agent_api`, `test_flow_api`, `test_report_api`, `test_seo_api`, `test_system_questions_api`; `test_mcp_adapter`, `test_flow_contract`) | A/B mit identischem Wegwerf-Peer: dieselben Tests scheitern auf `main` und im Stapel (sie erwarten eigene Testdaten, z. B. 29 indexierte Seiten); `test_mcp_adapter` und `test_flow_contract` grün |
| Wissens-Smoke (`knowledge-live-smoke`, nach gezielter Reparatur veralteter Testverträge) | 45 API + 1060 UI + 7 Konto-Prüfungen grün |

### 6.1 YaCy-Navigation in der neuen Oberfläche

Jede Seite der bisherigen Navigation bleibt erreichbar: die Scoutro-Seiten in
ihren Gruppen, YaCys eigene Gruppen (KI-Labor, Erste Schritte, Monitoring,
Produktion, Administration, Suchportal, Konfiguration) unverändert unter
„Erweiterte Werkzeuge“ für Administratoren (öffnet sich automatisch auf diesen
Seiten), Hilfe-Links inklusive Tutorials und Sponsoring, Re-Start/Shutdown auf
*Einstellungen → System*. `NavigationCoverageTest` prüft die vollständige Liste
der alten Navigation, der Browsertest lädt jede interne Navigationsseite als
Administrator auf Desktop und Mobil. Im geschützten Modus bleiben YaCy-Seiten
Administratoren vorbehalten; im kompatiblen Modus gelten YaCys bisherige Regeln.

Screenshots (Desktop und Mobil, Deutsch und Englisch, je Rolle) werden mit dem
Bericht übergeben und nicht im Repository abgelegt.

## 7. Bekannte Lücken und Grenzen

- **Kein Mandantensystem:** Collections sind logische Bereiche eines Index; geteiltes Wörterbuch, Crawler, Logs.
- **Kompatibler Modus** lässt das bisherige YaCy-Verhalten bewusst unverändert (öffentliche Suche, `suggest.json`, MCP,
  RAG-Gäste). Die Befunde D1–D5 sind nur im geschützten Modus geschlossen; der ungeprüfte `collection`-Wert in
  `yacysearch` (D1) bleibt im kompatiblen Modus bestehen (Upstream-Code, nicht geändert).
- **P2P:** Im geschützten Modus sind YaCys Peer-Routen mit Indexdaten Admin-Routen und die Indexverteilung (DHT)
  pausiert, solange *Peer-to-Peer im geschützten Modus* aus ist (Standard). Eingeschaltet gelten YaCys Netzregeln:
  dann sind alle Collections für andere Peers und jeden Client des Ports lesbar (Abschnitt 10.3). Entscheidung
  bestätigt (2026-10-11): Indexaustausch und DHT bleiben im geschützten Modus standardmäßig aus.
- **Vorschläge** (`suggest.json`) lassen sich nicht nach Collection trennen und bleiben im geschützten Modus Admin-Funktion.
- **Anmeldelimit pro Adresse** gilt hinter einem Proxy für alle Clients gemeinsam (ab 0.10.0 auch hinter einem
  vertrauenswürdigen Proxy, Bucket `proxy:<Adresse>`; Abschnitt 10.6).
- **Chat:** mit einem lokalen, deterministischen Modell-Stub Ende-zu-Ende geprüft (`chat-rag-live-smoke.py`); ein echtes
  Sprachmodell wurde nicht verwendet (keine externen Kosten, keine Zugangsdaten).
- **Gemischte Hinweislinks:** Einzelne Hinweise der Host-Analyse verweisen auf `IndexSchema_p.html` (Admin-Seite);
  Konten sehen dort die 403-Seite.
- **Drei JUnit-Fehler** bestehen auf `main` und im Stapel identisch (veraltete Tests bzw. zu knappes Zeitbudget nach
  Produktänderungen auf `main`, Abschnitt 10.5); sie betreffen keine Abnahme dieses Auftrags und bleiben unverändert.
- Der Navigationseintrag „Suche“ öffnet für Administratoren `index.html` ohne den früheren Zusatz `?auth`; die
  Kopfsuche sendet die Anmeldung wie bisher mit.
- **Testpasswörter:** Der HTTP-Smoke verwendet feste Testpasswörter einer Wegwerf-Instanz, der Browsertest erzeugt sie
  je Lauf; es gibt keine echten Zugangsdaten im Code.

## 8. Migration und Rückweg

1. Upgrade: nichts ändert sich für Automation (Digest, Admin-Hash-Loopback, Agenten), mit einer Ausnahme:
   Agenten-Crawls folgen keinem anderen Host als ihrem Starthost mehr (`www.` eingeschlossen; Abschnitt 10.7).
   Browser sehen die Scoutro-Anmeldeseite; der eingebaute Administrator meldet sich mit demselben Namen und
   Passwort an.
2. Persönliches Administratorkonto anlegen (*Einstellungen → Nutzer*), damit anmelden.
3. Optional: Formular-Anmeldung des eingebauten Administrators abschalten (Digest für Werkzeuge bleibt).
4. Geschützten Modus einschalten; Werkzeuge prüfen, die bisher öffentliche native Routen ohne Anmeldung nutzten
   (der Olares-API-Zugang leitet nur `/scoutro/api/` weiter und ist nicht betroffen); dann Recherche-/Operator-Konten anlegen.
5. Rückweg: `scoutro.auth.loginPage=false` (Digest-Dialog); eingeschränkte Konten sperren, Gast aus, geschützten Modus aus;
   im Notfall Scoutro stoppen, `scoutro.access.protected=false` setzen und `DATA/SETTINGS/scoutro-users.json`
   umbenennen. Ein älteres Image ignoriert die neuen Dateien und Einstellungen.

## 9. Voraussetzungen für Release und Olares

- Release-Image aus dem gemergten Stand bauen und testen (diese Arbeit veröffentlicht kein Image und ändert das
  Olares-Paket nicht).
- **Getrennte, spätere Betriebsabnahme auf Olares** (nicht Teil dieses Entwicklungsstands, nicht auf einem echten
  Olares getestet), siehe Abschnitt 10.6: Cookie im App-Fenster (`SameSite`), Proxy-Vertrauen für Anmeldelimits,
  Peer-/DHT-Einstellungen, Heap, interner API-Zugang.
- Entscheiden, ob und wann die Produktion in den geschützten Modus wechselt; vorher Werkzeuge ohne Anmeldung prüfen.
- DATA vor dem Upgrade sichern (Kontodatei und Audit-Log liegen in `DATA/SETTINGS`).
- Versionsnummer, Changelog-Abschnitt und Release-Notizen im Release-PR festlegen.

## 10. Abschließendes Review (2026-10-11)

Geprüft: der gesamte Stapel gegen `main` `829ab35` (Diffs je Paket und gesamt,
generierte Dateien, Binär-/Cache-Dateien, Konfliktreste), die alte
YaCy-Navigation, Rechte und Datenzugriffe (eigene Prüfung und ein
unabhängiger Code-Review), jeder Befund vor der Korrektur reproduziert.
Ergebnisse: keine Binär- oder Cache-Dateien, keine Konfliktmarker; die
generierte API-Beschreibung ist an **jedem** der zehn Commits reproduzierbar;
die XLIFF-Blöcke geänderter Seiten entsprechen der Generierung.

### 10.1 Befunde und Korrekturen

| | Befund (Beleg) | Korrektur |
|---|---|---|
| Hoch | **P2P-Routen im geschützten Modus offen:** `/yacy/**` behielt YaCys Regeln. `urls.xml?call=urlhashlist` liefert Adresse, Titel, Autor und Datum jedes Dokuments mit bekanntem URL-Hash ohne Robinson-Prüfung (`urls.java:105`); `search.html` durchsucht alle Collections, im Standardnetz freeworld ohne Peer-Authentifizierung (`network.unit.protocol.control = uncontrolled`, `Protocol.authentifyRequest`); `idx.json`, `query.html` lesen, `transferRWI/URL`, `crawlReceipt` schreiben; die DHT verteilt ausgehend. Reproduziert: anonym und als Recherche-Konto wurde ein Dokument einer fremden Collection ausgeliefert, in allen vier Varianten (freeworld/webportal, öffentlich/privater Robinson; im privaten Robinson-Modus **ohne** Warnung). | P2: nur Handshake-Routen (hello, seedlist, profile, list, message) behalten YaCys Regeln, alle anderen `/yacy/**` brauchen den Administrator, DHT pausiert; Schalter `scoutro.access.p2p` (aus, nur im geschützten Modus, auditiert, vor dem Verlassen abzuschalten), Warnungen `p2p_open`/`p2p_closed`. Nachher: 401 anonym, 403 Konto, Handshake 200, in allen vier Varianten. |
| Hoch (bedingt) | **Chat-Werkzeug `search` ignoriert die Collection:** `SearchTool` durchsucht ganzen Index und P2P-Netz; die Ausführung prüft nur die Freigabe `ai.tools.search.enabled` (Standard aus). Reproduziert mit Modell-Stub: das Recherche-Konto von alpha-web erhielt Text und Host aus beta-web. | P4: Chats ohne alle Collections (Konten, KI-Schild-Gäste) bekommen Werkzeuge, die den ganzen Index lesen, weder angeboten noch ausgeführt (`LLMModel.indexTools`, `ToolProvider.readsIndex`); übrige Werkzeuge bleiben. |
| Mittel | **Argon2-Speicher:** 19 MiB je Prüfung, unbegrenzt parallel, auch für unbekannte Namen. 150 gleichzeitige Prüfungen bei 600 MiB Heap (YaCy-Standard): 77 OutOfMemoryErrors; über HTTP (768 MiB, 250 parallel) kein OOM – teilweise bestätigt. | P2: höchstens vier gleichzeitig; 300 parallel bei 600 MiB: 0 OOM, 6 s statt 73 s. |
| Mittel | **Operator-Crawl über den Host hinaus:** YaCys Domain-Filter ist ein Präfix (`(www.)?host.*`, trifft `host.com.au`); Crawler_p verknüpft eigene Filter per ODER. Ein Operator-Crawl könnte Seiten fremder Collections in die eigene neu indexieren. Gilt ebenso für Discovery-Crawls. | P4: Crawls von Konten ohne alle Collections und alle Discovery-Crawls bekommen einen Ausschlussfilter für alles außer dem exakten Host; der Startmarker bleibt lesbar. Live: Profil mit Filter, Crawl startet, ist abrufbar und stoppbar. |
| Mittel | **Agenten-Crawl über den Host hinaus (Nachtrag):** Der Agentenpfad (`ScopedActions.crawlStart` → `ScoutroActions.crawlStart(body, marker)`) startete ohne Hostbegrenzung. Die Domain-Allowlist prüft nur den Starthost, `host_indexed_elsewhere` nur dessen Dokumente. Live reproduziert (Abschnitt 10.7): ein Agent mit einer Collection und Allowlist `agent-host.example` rief `agent-host.example.test` ab und indexierte dessen Seite in seine Collection. | P4: auch Agenten-Crawls bekommen `CrawlRequest.hostOnly` hinter dem Startmarker; Idempotenz, Marker, Wiederaufnahme, Limits und Collection-Rechte unverändert. Live 10/10, ohne Korrektur 7/10. |
| Mittel | **Sperre fremder Konten:** 5 Fehlversuche je Name in 15 min sperren die Formularanmeldung dieses Namens für alle. | Nicht geändert (übliche Abwägung gegen Raten); Digest bleibt, Sperre endet von selbst. Hinter nicht vertrauenswürdigem Proxy teilen sich alle Clients das Limit je Adresse (Betriebsabnahme). Entscheidung bestätigt (2026-10-11): die zeitlich begrenzte Sperre bleibt vorerst. |
| Mittel | **`adminAccountForLocalhost=true`** macht hinter einem Proxy auf demselben Rechner jede Anfrage zum Administrator (vorbestehend; Scoutro-Image setzt `false`). | P2/P3: Warnung `localhost_admin` in *Einstellungen → Nutzer*. |
| Niedrig | **CSRF-Pfad:** `POST /scoutro;x/api/v1/users` legte ohne CSRF-Token ein Konto an (Container leitet den Pfadparameter an die API); Cross-Site blieb durch Origin/JSON-Pflicht geschützt. | P2: Pfad ohne Pfadparameter; live 403. |
| Niedrig | **`/heuristic`** in der Personen-/Gastsuche startet über den Admin-Loopback eine föderierte Suche und indexiert fremde Seiten. | P4: `400 query_modifier_not_allowed` für alle ohne Administratorrolle. |
| Niedrig | Jeder 429 schrieb einen Audit-Eintrag (Audit-Log mit 20.000 Einträgen leerbar); bei 10.000 Schlüsseln leerte die Limit-Tabelle sich komplett (frei wählbare Namen setzten den Zähler eines Zielkontos zurück); Wettlauf „Formularanmeldung aus“ gegen „letzten Admin sperren“. | P2: ein Eintrag je Sperrfenster; Einzelfehlversuche zuerst verdrängt; Zugriffsänderungen unter der Sperre des Kontospeichers. |
| Niedrig | `help/Steering.md` ohne Hinweis auf Audit von Neustart/Shutdown. | P3. |
| Niedrig | **API-Beschreibung:** Die Konto-Aktionen trugen `"mcpTool": null`; `test_api.py` war unter identischen Bedingungen auf `main` grün und im Stapel rot. Die frühere Angabe „identisch zu `main`“ beruhte auf ungleichen Läufen. | P2: Schlüssel entfällt wie bei Discovery, Testvertrag um die neuen schreibenden Aktionen ergänzt; grün auf P2-Stand und final. |
| Info | Option `SameSite=Lax` macht GET-Aktionen alter YaCy-Seiten CSRF-anfällig (Standard `Strict`); Cookie ohne `__Host-`-Präfix; Antwortzeit verrät den Namen des eingebauten Administrators; unlesbare Kontodatei plus Einstellung `false` (nur nach Handänderung) ergibt den kompatiblen Modus; Kontodatei-Änderungen im Betrieb werden überschrieben; laufende Chat-Antworten enden nach Widerruf regulär. | Dokumentiert, nicht geändert. |

Bestätigt ohne Befund: Recherche/Operator erhalten nie `adminRight` (auch nicht mit Digest-Header,
Weiterleitungs-Headern oder Pflicht-Passwortwechsel); Sperre, Passwort- und Rechteänderung beenden
Sitzungen sofort; letzter Administrator geschützt; Rückkehrziel (`next`) nur gleiche Herkunft;
Pfadnormalisierung der Allowlist; Collection-Isolation aller Konto-API-Routen, Exporte, Crawls, Berichte,
Discovery, Wissensgraph; Chat-Retrieval nur in der eigenen Collection.

### 10.2 Chat-RAG mit Modell-Stub

`chat-rag-live-smoke.py`: lokales OpenAI-kompatibles Stub-Modell auf 127.0.0.1 (keine Kosten, keine
Zugangsdaten), das jede Anfrage aufzeichnet; zwei Collections mit eigenen Seiten und
Wissensgraph-Fakten. Geprüft wird, was das Modell tatsächlich erhält (Suchkontext, Graph-Fakten,
Werkzeugergebnisse) und welche Quellen der Client bekommt: eigene Collection ohne Wahl, fremde und
ungültige Collection, `collection:` in der Frage, globale Suche, Client-Werkzeuge und -Systemnachrichten,
Werkzeugaufruf des Modells, Systemfragen, Modell-Spiegel des Administrators, Konto mit zwei Collections,
Administrator, anonym. Vor der Korrektur 24/27 (die drei Werkzeugprüfungen rot), danach 27/27.

### 10.3 P2P: Antwort und Voraussetzungen

Die Warnung reichte nicht: auch im privaten Robinson-Modus gab `urls.xml` fremde Dokumente frei.
Jetzt gilt im geschützten Modus ohne den Schalter:

- `/yacy/search.html`, `urls.xml`, `idx.json`, `query.html`, `transferRWI.html`, `transferURL.html`,
  `crawlReceipt.html` und jede weitere `/yacy/`-Route außer dem Handshake: nur Administrator.
- Die Indexverteilung (DHT) pausiert (`Switchboard.dhtShallTransfer`); eigene Netzsuchen des
  Administrators bleiben möglich.
- Der Peer bleibt über `hello`/`seedlist` im Netz sichtbar; *Steering* erkennt den Neustart weiter.

Wer den Schalter einschaltet, gibt alle Collections an das YaCy-Netz und jeden Client des Ports frei.
Voraussetzung für echte Collection-Trennung ist daher: geschützter Modus und Schalter aus (Standard).
Für den kompatiblen Modus gilt YaCys Verhalten unverändert. Die DHT-Pause ist durch Code und
Unit-Test der Entscheidung belegt, nicht durch einen Netzwerktest (kein Peer-Netz im Test).

### 10.4 Tests im Review

Siehe Abschnitt 6; zusätzlich: Vorher/Nachher-Messung der P2P-Routen (4 Netzvarianten × anonym/Konto),
Argon2-Last (Java, 150/300 parallel), CSRF-Pfadprobe, Operator-Crawl gegen lokalen Server (Intranet-Netz).
Jedes Paket wurde nach der Korrektur einzeln sauber gebaut: P2 71 Unit-Tests, Access-Smoke 115/115 und
`test_api.py` OK, P4 944 Scoutro-Tests (nur die drei Altfehler) und 60 KI-Tests. Alle Python-API-Suiten
liefen im A/B mit identischem Wegwerf-Peer gegen `main` (Abschnitt 6).

### 10.5 Die drei JUnit-Fehler (A/B gegen `main`)

Unter identischen Bedingungen (sauberer Build, gleicher Container) auf `main` `829ab35` und im Stapel
dieselben drei Fehler an denselben Stellen; an `fa40e13` (7.10.) waren alle drei Klassen grün.
Bisektion mit sauberem Build je Schritt:

| Test | Erster roter Commit auf `main` | Einordnung |
|---|---|---|
| `AgentCatalogTest.knowledgeGraphActionsNameTheirGrant` | `08a42db` (bewusst neue Leseaktion `kg.entity.suggestions`; Vorgänger grün) | Test veraltet |
| `CollectionsStatusTest.jobsStayOffWithoutAnExplicitSetting` | `ce3f8ed` (Betreiber nur noch Arbeitgeber bei „wir suchen“, „unser Team“ …) | Test veraltet |
| `KgRebuildTest.aRebuildReadsEveryScanPageBeforeTheSwap` | `cfffabc` (zusätzlicher Beobachtungsspeicher je Dokument) | Zeitbudget/Umgebung: mit zehnfachem Taktbudget grün (70 s), der Rebuild bleibt nicht hängen |

Sie betreffen den Wissensgraphen, nicht diese Arbeit, und bleiben gemäß AGENTS.md unverändert. Der
**Wissens-Smoke** scheiterte an denselben Produktänderungen (`ce3f8ed`, `08a42db`); seine Testdaten
und zwei Erwartungen sind gezielt angepasst (P5), ergänzt um eine Prüfung mit einem Konto, das nur
eine Collection sieht. Er läuft vollständig durch.

### 10.6 Getrennte Betriebsabnahme auf Olares (später)

Nicht auf einem echten Olares getestet. Vor einem produktiven Wechsel in den geschützten Modus:

- App-Fenster: sendet es `scoutro_session` mit `SameSite=Strict`? Sonst `Lax` (Abschnitt 10.1, Info).
- Anmeldelimits (korrigiert 2026-10-11 für 0.10.0): Die frühere Empfehlung hier, den Olares-Eingang als
  vertrauenswürdigen Proxy einzutragen und `X-Real-IP` zu übernehmen, ist überholt. Für die Olares-Kette ist
  nicht belegt, dass sie ein vom Client gesetztes `X-Real-IP` ersetzt. Anmeldungen über einen vertrauenswürdigen
  Proxy zählen deshalb in einem gemeinsamen Bucket (`proxy:<Adresse>`);
  `scoutro.auth.limits.proxyAddress=x-real-ip` nur mit belegtem Vertrag
  ([SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md), Abschnitt 5).
- Interner API-Zugang (`authLevel: internal`): leitet alle `/scoutro/api/` weiter, auch die Anmeldung;
  die Begrenzung auf vier Argon2-Prüfungen gilt dort ebenso.
- Peer-Einstellungen: das Image übernimmt `freeworld`; der geschützte Modus pausiert DHT und schließt die
  Peer-Indexrouten – prüfen, dass das gewollt ist, und `scoutro.access.p2p` aus lassen.
- Heap (`javastart_Xmx`, Standard 600 MiB) und `adminAccountForLocalhost=false` (Image) bestätigen.
- Agenten mit Crawl-Recht: prüfen, ob eine bestehende Automation von einem Start aus Subdomains oder weitere
  erlaubte Domains erreichen soll; sie braucht dafür ab jetzt je Host einen eigenen Crawl-Start (Abschnitt 10.7).

### 10.7 Nachtrag: Hostgrenze für Agenten-Crawls (2026-10-11)

Entscheidungen des Auftraggebers (2026-10-11): Im geschützten Modus bleiben Indexaustausch und DHT
standardmäßig aus; die zeitlich begrenzte Kontosperre bleibt vorerst bestehen.

**Befund (bestätigt).** Der Agentenpfad `ScopedActions.crawlStart` rief
`ScoutroActions.crawlStart(body, marker)` mit `hostOnly=false` auf. YaCys Domain-Filter
`(www.)?host.*` ist ein Präfix, die Allowlist des Agenten prüft nur den Starthost und
`host_indexed_elsewhere` nur dessen Dokumente. Nachgestellt mit `agent-crawl-host-live-smoke.py`:
Wegwerf-Peer im Intranet-Netz, zwei lokale Server (erlaubter Starthost `agent-host.example`,
täuschend ähnlicher fremder Host `agent-host.example.test` mit einem Dokument der Collection
`edelsenior-web`; beide Namen nur im Peer über eine temporäre `jdk.net.hosts.file` auflösbar), ein
Agent nur mit `stackfinder-web` und Allowlist `agent-host.example`. Der direkte Start auf dem fremden
Host wird korrekt abgewiesen (`403 limit_exceeded:domains`). Ein erlaubter Start mit `scope=domain`
folgte jedoch dem Link: `agent-host.example.test` erhielt `/robots.txt` und `/fresh`, und
`/fresh` landete in `stackfinder-web`.

**Korrektur (P4, Commit `91c77cd`).** Agenten-Crawls starten mit `CrawlRequest.hostOnly`, wie
Operator- und Discovery-Crawls: Hinter dem Startmarker schließt ein Must-not-match-Filter jeden anderen
Host aus (`www.` und Port erlaubt). Das gilt immer, auch für Agenten mit allen Collections, denn sonst
würde die Allowlist umgangen. Weitere Subdomains oder erlaubte Domains brauchen eigene Starts. Unverändert:
Idempotenz (`Idempotency-Key`, Wiederholung 200), Startmarker (Status, Stopp, Abgleich nach Absturz),
Tiefe, Seiten, Parallelität, `host_busy`, `host_indexed_elsewhere` und Collection-Rechte. Die Überladung,
die einen markierten Crawl ohne Hostentscheidung startete, entfällt. Kein neues Berechtigungskonzept.

**Prüfung.**

| Prüfung | Ergebnis |
|---|---|
| `agent-crawl-host-live-smoke.py` (finaler Stand) | 10/10: fremder Host ohne einen einzigen Abruf, keine fremde Seite in der Agenten-Collection, eigene Seiten indexiert, Wiederholung mit gleichem Schlüssel 200, Status über den Startmarker, Profil mit Marker und Hostfilter, Stopp 200 |
| derselbe Smoke ohne die Korrektur (A/B, nur die eine Zeile zurückgesetzt) | 7/10: fremder Host abgerufen (`/robots.txt`, `/fresh`), `/fresh` in `stackfinder-web`, Profil ohne Hostfilter |
| Regressionstest `CrawlStartRecoveryTest.crawlStaysOnItsStartHostAndKeepsItsMarker` | grün; gegen den alten Agentenpfad rot (`not crawled: https://example.com.au/`) |
| Betroffene Crawl-/Access-Unit-Tests (14 Klassen: `CrawlStartRecoveryTest`, `CrawlFlowTest`, `PeopleRoutesTest`, `DiscoveryGatewayTest`, `DiscoveryScopeTest`, `ScopedActionsTest`, `AgentApiTest`, `AgentAdminTest`, `AgentStoreTest`, `AgentKnowledgeTest`, `CollectionCatalogTest`, `ScoutroAccessTest`, `CallerTest`, `RoutePolicyTest`) | 153/153 am neuen P4-Commit (sauberer Build) und am finalen Stand |
| JUnit gesamt, finaler Stand (sauberer Build) | 945 Tests, nur die drei Altfehler aus Abschnitt 10.5 |
| Access-Live-Smoke, finaler Stand | 177/177 |
| `kg-agents-live-smoke.py`, finaler Stand (nach dem Review erstmals wieder) | 51 Live- + 46 Chat-UI-Prüfungen grün |
| `report-live-smoke.py`, finaler Stand (nach dem Review erstmals wieder) | Seed, 9 Live-Lesezugriffe ohne Zustandsänderung, 453 UI-Prüfungen grün |
| Generierte API-Beschreibung | am neuen P4-Commit und am finalen Stand unverändert reproduziert |
