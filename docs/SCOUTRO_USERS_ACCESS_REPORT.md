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
`build.xml`, generierte API-Beschreibung neu erzeugt).

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
| 2 | `ccr-2ad9a69f-3dvaa8-p2-auth` | Konten, Anmeldung, Sitzungen, zentrale Rechte, geschützter Modus, Audit | PR 1 |
| 3 | `ccr-2ad9a69f-3dvaa8-p3-ui` | Header, rechtegesteuerte Navigation (alle bisherigen YaCy-Seiten erreichbar), Konto-/Nutzer-/System-/Collections-Seiten, 403-Seite | PR 2 |
| 4 | `ccr-2ad9a69f-3dvaa8-p4-data` | Funktionsseiten und Daten nach Recht und Collection, Scoutro-Suche, Chat-Scope, dynamisches Dashboard, Kontext | PR 3 |
| 5 | `ccr-2ad9a69f-3dvaa8` | Mehrbenutzer-E2E-Test, dieser Bericht | PR 4 |

Jeder PR ist für sich prüfbar; erst mit PR 4 können Recherche- und
Operator-Konten Funktionsseiten nutzen (bis dahin sehen sie nur ihr Konto).

## 6. Tests (Wegwerf-Instanzen, offline)

| Prüfung | Ergebnis |
|---|---|
| JUnit (Scoutro- und HTTP-Tests) | 936 Tests auf dem rebasten Stand, 3 Fehler – dieselben 3 wie auf `main` `829ab35` (AgentCatalogTest, CollectionsStatusTest, KgRebuildTest), nicht durch diese Arbeit verursacht; dazu `NavigationCoverageTest` (3) |
| `ant scoutro-access-test` | 9 Suiten, 74 Tests grün |
| Access-Live-Smoke (HTTP, Mehrbenutzer, Scope-Manipulation, Digest-Automation) | 146/146 |
| Mehrbenutzer-Browsertest (`accounts-ui-live-smoke.py`, DE/EN, 1280/390 px, Recherche/Operator/Admin/Gast; Administrator öffnet alle 45 internen Navigationsseiten inkl. YaCy-Werkzeuge) | 408/408 |
| Navigation (`scoutro-ui-test`) / Dashboard (`dashboard-ui-test`, `dashboard-live-smoke`) | 384/384 / 187, Live 237 + 13 Solr-Prüfungen |
| SEO-Suite (`seo-live-smoke`: SEO 282, Domains/Export 473, System-Chat 71, KI-Labor 593, Crawl/Discovery-Flow 405, Collection-Anlage 66) | grün |
| Discovery (`discovery-live-smoke`) | 80 UI + 43 API grün |
| Sprachwechsel (`locale-refresh-live-smoke`) | 75 + 3×63 grün |
| LLM-Auswahl (`llm-selection-live-smoke`) | 719 UI + 50 HTTP grün |
| Agenten, Wissensgraph-Export, MCP, Chat-UI (`kg-agents-live-smoke`) | 51 + 46 grün – Agenten unverändert |
| Crawl-Berichte (`report-live-smoke`) | 453 UI-Checks grün |
| API-Testsuite (`test_api.py`) | identische Ergebnisse wie `main` (vorbestehende Abweichungen bei Crawl-Validierung, `put`, `mcpTool`, CLI) |
| Wissens-Smoke (`knowledge-live-smoke`) | scheitert mit `KeyError: 'jobs'` – ebenso auf `main` `829ab35` und `8812ad2` (vorbestehend) |

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
- **P2P:** Ein Peer im YaCy-Netz beantwortet entfernte Suchen aus dem ganzen Index; die Nutzerverwaltung warnt im
  geschützten Modus, wenn der Peer nicht im privaten Robinson-Modus läuft.
- **Vorschläge** (`suggest.json`) lassen sich nicht nach Collection trennen und bleiben im geschützten Modus Admin-Funktion.
- **Anmeldelimit pro Adresse** gilt hinter einem nicht vertrauenswürdigen Proxy für alle Clients gemeinsam.
- **Chat-Collection-Regel** ist per Unit-Test und Live-Smoke geprüft, aber ohne konfiguriertes Sprachmodell nur bis zur
  Zulassung (Antwort 503 „kein Chatmodell“ statt 401/403).
- **Gemischte Hinweislinks:** Einzelne Hinweise der Host-Analyse verweisen auf `IndexSchema_p.html` (Admin-Seite);
  Konten sehen dort die 403-Seite.
- **Wissens-Live-Smoke** (`knowledge-live-smoke.py`) scheitert auch auf `main` (vorbestehend, siehe Abschnitt 6).
- Der Navigationseintrag „Suche“ öffnet für Administratoren `index.html` ohne den früheren Zusatz `?auth`; die
  Kopfsuche sendet die Anmeldung wie bisher mit.
- **Testpasswörter:** Der HTTP-Smoke verwendet feste Testpasswörter einer Wegwerf-Instanz, der Browsertest erzeugt sie
  je Lauf; es gibt keine echten Zugangsdaten im Code.

## 8. Migration und Rückweg

1. Upgrade: nichts ändert sich für Automation (Digest, Admin-Hash-Loopback, Agenten). Browser sehen die
   Scoutro-Anmeldeseite; der eingebaute Administrator meldet sich mit demselben Namen und Passwort an.
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
- Auf Olares prüfen, ob das App-Fenster das Cookie `scoutro_session` mitsendet (`SameSite=Strict`); sonst
  `scoutro.auth.cookie.sameSite=Lax` setzen und die Entscheidung festhalten.
- Entscheiden, ob und wann die Produktion in den geschützten Modus wechselt; vorher Werkzeuge ohne Anmeldung prüfen.
- DATA vor dem Upgrade sichern (Kontodatei und Audit-Log liegen in `DATA/SETTINGS`).
- Versionsnummer, Changelog-Abschnitt und Release-Notizen im Release-PR festlegen.
