# Scoutro: integrierte Abnahme der PR-Kette #39 → #40 → #41

Prüfdatum: 10. Oktober 2026. Ausschließlich isolierte Worktrees, temporäre Daten und kontrollierte lokale Quellen. Keine Produktionsänderung, kein Merge, Release oder Deployment.

## Ergebnis und geprüfter Stand

Die Mergeempfehlung gilt für den unten bezeichneten gemeinsamen Stand und setzt die separate Packaging-Korrektur [#42](https://github.com/Salamont/yacy_search_server/pull/42) voraus. Der bisher offene Jetty/Solr-Guard wurde auf unverändertem main reproduziert und an seiner Ursache korrigiert. Seine Assertions und die Abhängigkeitsversionen wurden nicht geändert.

| Bestandteil | Tatsächlich geprüfter Commit / Basis |
|---|---|
| Aktuelles main; unveränderte Vergleichsbasis | `7847fe1ed54eda64348eaa64cd15d7eb8c107639` |
| [#39, Paket A](https://github.com/Salamont/yacy_search_server/pull/39) | Head `08a42db1d6609f6f53e10a51890f8e1bc28b7847`; Basis main wie oben |
| [#40, Paket B](https://github.com/Salamont/yacy_search_server/pull/40) | Head `8f6f3f11f5a510fd55da90664ebf2c7491f4c326`; Basis A wie oben |
| #41 vor der Abnahme | Head `264dc3038559373feddcb40abb7f141b2cb28f63`; Basis B wie oben |
| [#41, Paket C](https://github.com/Salamont/yacy_search_server/pull/41) nach den Abnahmekorrekturen | Code-/Test-Head `8d259a0b543f515237fd3e1d2407ccdb6cff3927`; unverändert auf B aufgebaut |
| #42, unabhängige Packaging-Korrektur | Head `cc15befe281717b0761d4c13186afa03c4336a00`; Basis main wie oben |
| **Exakt getesteter gemeinsamer Code-/Teststand** | **`3a52850178363ad6565289eab96b1b4326484d7a`**, Branch `test/scoutro-integrated-acceptance` |

Der gemeinsame Stand enthält den ursprünglichen C-Head einschließlich A/B, die eigene Packaging-Korrektur, die eigene Feed-Korrektur und die neuen Abnahmetests. Die Packaging-Korrektur wird separat geliefert; sie wurde nicht versteckt in C aufgenommen. Die entsprechenden C-Korrekturcommits heißen `4a62e557a17d682bffd43fc1724011424ca72f80` und `8d259a0b543f515237fd3e1d2407ccdb6cff3927`. Ihre Änderungen stimmen mit den im gemeinsamen Stand geprüften Änderungen überein. Ein nachfolgender Dokumentationscommit enthält nur diesen Bericht, keine andere getestete Implementierung.

PR-Beschreibungen, tatsächliche GitHub-Head-/Basis-SHAs und Repository-Anweisungen wurden gelesen. Bei der erneuten Remote-Prüfung war main unverändert; alle vier PRs waren offen und Draft. Der ursprüngliche Workspace blieb unverändert. Keine weiteren fremden Branches wurden übernommen.

## Umgebung und Reproduzierbarkeit

- Linux `6.18.44`, x86_64; Containergrenzen: 2 CPU-Kerne, 8 GiB RAM.
- Temurin `17.0.20.1+1`, Ant `1.10.15`, Ivy `2.5.3`, ASM-Bridge-Werkzeuge `9.9`; Node `24`, Playwright und Chromium.
- Repository-Abhängigkeiten: Jetty-Server `12.0.37`, private Bridge-Jetty-Eingaben `10.0.26`, Solr `9.10.1`, Lucene `9.12.3`, SQLite-JDBC `3.53.4.0`.
- Die bereits aufgelösten JAR-Eingaben wurden in getrennte Worktrees kopiert und per SHA-256 protokolliert. Ein lokaler Ant-Wrapper überspringt ausschließlich die erneute Ivy-Auflösung und setzt deren Klassenpfade. Bridge-Erzeugung, Kompilierung, Repository-Testtargets und Guard laufen unverändert. Eine Auflösung aller Abhängigkeiten aus einem leeren Maven/Ivy-Cache wurde nicht behauptet.
- End-to-End: neue DATA-Verzeichnisse, Loopback-HTTP-Quellen, feste Fixture-Hosts, deaktivierte externe LLM-Auswertung, lokaler Testadministrator und eigens erzeugter eingeschränkter Agent. Testinstanzen wurden anschließend beendet. Keine produktiven Credentials oder Daten in den Nachweisen.

Die folgenden Befehle wurden aus dem gemeinsamen Worktree ausgeführt; `JAVA_HOME`/`PATH` zeigen auf das genannte JDK:

```sh
java -cp '/workspace/scoutro-acceptance/tools/*' org.apache.tools.ant.Main \
  -f /workspace/scoutro-acceptance/tools/build-integrated.xml all scoutro-kg-observations-test
test/jetty-solr-dependency-guard.sh
test/solr9-jetty-bridge-spike.sh
SCOUTRO_ACCEPTANCE_DIR='<neues Nachweisverzeichnis>' python3 test/scoutro-api/kg-chain-acceptance.py
java -Xmx512m -cp 'build/classes/java/main:build/scoutro-kg-observations-tests:lib/*:libt/*' \
  net.yacy.scoutro.knowledge.derive.MatchingLoadProbe '<neues Lasttestverzeichnis>'
node test/scoutro-ui/knowledge-suggestions-ui-test.mjs
node test/scoutro-ui/knowledge-history-ui-test.mjs
node test/scoutro-ui/knowledge-matching-ui-test.mjs
python3 -m unittest discover -s test/scoutro-api -p 'test_flow_contract.py'
python3 -m unittest discover -s test/scoutro-api -p 'test_mcp_adapter.py'
python3 tools/scoutro/generate_api_description.py htroot/env/scoutro/api
python3 test/scoutro-ui/check-locale-identifiers.py
node --check htroot/env/scoutro/knowledge.js
git diff --check
```

OpenAPI wurde zusätzlich mit `openapi-spec-validator 0.7.2` als OpenAPI 3.1 validiert. Die Generierung der 77 Actions reproduziert die eingecheckten Verträge ohne Differenz.

## Behobene Befunde

### 1. Unvollständige Jetty-Relocation – separate PR #42

Auf unverändertem main und dem ursprünglichen A/B/C-Gesamtstand:

```text
Befehl: test/jetty-solr-dependency-guard.sh
FAIL: unrelocated Jetty reference in lib/solr9-bridge-jetty-alpn-java-client-10.0.26.jar
```

Auch eine erneute Bridge-Erzeugung auf unverändertem main reproduzierte denselben Fehler. Guard, Relocator und Dependency-Koordinaten waren über die Kette identisch. Die Laufzeitklassen waren bereits verschoben; JPMS-Modul-/Paketnamen und gefaltete OSGi-Manifestfelder waren es nicht. Originale Maven-Koordinaten und Attribution lösten zusätzlich die Ressourcenprüfung aus. **Befund: bestehender Build-/Packaging-Fehler; keine Scoutro-Code-, neue Dependency- oder JDK-Ursache.**

`test/jetty/RelocateJettyPackages.java` korrigiert jetzt Modul-/Paketidentitäten, geparste Manifestattribute und Service-/Textressourcen. Originalmanifest, Maven-Provenienz und Attribution bleiben unverändert unter `lib/solr9-bridge-upstream/<artifact>/` erhalten. Laufzeit-Attribution mit ursprünglichen Koordinaten verweist auf diese unverändert mitgelieferte Datei. `copyMain4Dist` nimmt dieses Verzeichnis bereits über `lib/**` mit. Binäre Ressourcen bleiben unverändert. Guard und Assertions sind unverändert; keine Version wurde angepasst.

Ein realer JAR-Regressionstest deckt 18 Packaging-Prüfungen einschließlich JPMS, mehrzeiligen Manifesten, Service-Deskriptoren und unveränderter Attribution ab. Diagnose und Distributionsvertrag: [SOLR9_RELOCATION.md](SOLR9_RELOCATION.md).

### 2. Feed-Lücke bei geänderter Matching-Konfiguration – in #41 korrigiert

`MatchingProjection.record()` konnte gespeicherte Beiträge nach geändertem Job-Matching-Opt-in, deaktivierter Ableitung oder `matches.max=0` sofort ausblenden. Ohne weiteren erfolgreichen Ableitungslauf entstand dafür jedoch kein Feed-Ereignis; externe synchronisierte Ansichten konnten veraltete Beiträge behalten.

`MatchingService.policyEstimate()/invalidatePolicy()` verwenden nun einen deterministischen Policy-Fingerprint und erzeugen Hinweise für vorhandene Beiträge, einschließlich ihrer vollständigen Zugriffsketten. Beim Lesen erscheinen für einen berechtigten Empfänger die aktuell gültigen UPSERTs beziehungsweise gezielten DELETEs. Keine historische Beobachtung wird dafür gelöscht.

`KgRuntime.invalidateMatchingPolicy()/open()/tick()/status()` sichern den Vorgang auch bei Speicherproblemen: Der KG bleibt lesbar; ausstehende Policy-Hinweise werden als `policy_notice_deferred` angezeigt und nach Speicherfreigabe erneut versucht. API-/Agentenbeschreibung, Hilfe, UI und deutsche Lokalisierung wurden gemeinsam ergänzt. Eine unveränderte Policy erzeugt keine künstliche Folge von Änderungen.

Die erste Fassung der Korrektur scheiterte an der bestehenden Prüfung für einen Start bei kritisch vollem Speicher. Die finale Fassung berücksichtigt diesen Schutz und besteht die betreffende Regression. Die Assertion wurde nicht abgeschwächt.

### 3. Reproduzierbare Abnahme statt vorzeitiger Wartebedingungen

Die neue lokale Prüfkette wartet auf tatsächlich veröffentlichte Fixtures und deren Identitätsauflösung, nicht allein auf eine kurz leere KG-Warteschlange. Lifecycle-Prüfungen warten ebenfalls auf beobachtbare Ergebnisse. Der Browser prüft die tatsächlich gezeichnete SVG-Kante; eine horizontale Linie kann trotz sichtbarem Stroke eine Bounding-Box mit Höhe null besitzen. Diese Änderungen betreffen den Testharness, keine zusätzliche fachliche Funktion.

## Integrierter Review und Zuordnung der Nachweise

| Übergang / Risiko | Geprüftes Verhalten und Nachweis |
|---|---|
| A: Ansichtsfilter versus Zugriff | `KnowledgeApi`/`KnowledgeRead`/`ScopedActions` trennen ausgewählte Collection und serverseitigen Grant. `Suggestions`/`BusinessGraph` erweitern nur Kunden-/Partnervorschläge. Normale Fakten bleiben abgegrenzt. `BusinessViewTest`, `KnowledgeApiTest`, `AgentKnowledgeTest`, `ScopedActionsTest`; echte Agenten- und Browserprüfung. |
| Vollständige Belegkette | `MatchingAccess.allowed()` verlangt pro Beleg einen erlaubten Scope; `MatchingProjection.record()` prüft die gespeicherten Referenzen und aktuelle Tauglichkeit erneut. Ein Pflegebeleg in einer dritten Collection ist erforderlich. Verdeckte stärkere Gründe verdrängen sichtbare nicht. `MatchingTest` und echte API-/Exportprüfung ohne regionale beziehungsweise geheime Grants. |
| Bestandsschutz vor Extraktion | Schema-Upgrade und idempotente Sicherung noch vorhandener relevanter Evidence laufen vor den neuen Extraktor-Workern. Publisher-Sicherung und SQLite-Trigger schützen destruktive Evidence-/Statement-/Dokumentoperationen. `KgUpgradeTest`, `ObservationStoreTest`, `PublisherTest`, Runtime-/Sync-Tests. |
| Historie versus Stellen-/Quellenstatus | Ablauf, eindeutiges Ende und Quellenverlust sind getrennt vom System-/Kompetenzhinweis. Fehlendes Ablaufdatum bleibt unbekannt. Originalzitat, Zeit, Revision und ursprünglicher Identitätskontext bleiben erhalten. `ObservationStoreTest`, `BusinessSignalsTest`, echte Revision-/Löschprüfung. |
| Arbeitgeber und Ausschluss | Tatsächlicher Arbeitgeber und dessen belegte eigene Leistungen entscheiden. Unklarer Arbeitgeber wird zurückgestellt; unklare Rolle wird sichtbar herabgestuft. Eigenes IT-/SAP-Beratungs- oder Integrator-Recruiting trägt die betreffenden Job-Gründe nicht. Architektur/CAD und Pflegeberatung werden nicht pauschal ausgeschlossen. Positive/negative `MatchingTest`-Fälle und echte SAP-/CAD-Fixtures. |
| Unabhängige Gründe und Deduplizierung | Begründungen bleiben getrennte Beiträge; Collection-Paare, Tiers und identische Quellen zählen nicht mehrfach als Bestätigung. Kunde und Partner bleiben getrennt. Abschaltung beziehungsweise Korrektur entzieht nur betroffene Gründe. `MatchingTest`, drei Seiten des Lastprobes, echte Abschaltungs-/Korrekturprüfung. |
| Cache, Feed, Pagination | Cache-Beiträge werden vor Ausgabe revalidiert. Policy-Wechsel erzeugen jetzt Feed-Hinweise; Scopeentzug kann nur zuvor vollständig berechtigte Tombstones offenlegen. Berechtigung vor Gruppierung, Sortierung und Pagination. Vollständig bearbeitete Partitionen erlauben Rücknahmen, ein Budgetabbruch nicht. `MatchingTest`, `KgChangeLogTest`, Runtime-/Agententests. |
| Export und Belegnavigation | Vollständiger versionierter Historienexport einschließlich Ereignissen; JSON-Verweise bleiben begrenzt und valide, weitere Gründe sind paginiert erreichbar. Historische Belege funktionieren ohne Live-Statement. `KgExportTest`, `ObservationStoreTest`, `MatchingTest`, eingeschränkter echter Export, Browser-JSON/GraphML und Historienlink. |
| Rebuild / Restore | Rebuild übernimmt aktuelle Korrekturen und wirksame Scopes, auch nach der Shadow-Phase; Restore übernimmt den ausgewählten Backupstand ohne Mischung mit neueren Archivdaten. Danach ist Reconciliation gegen den aktuellen Solr-Bestand ein eigener Schritt. Unit- und echte Lifecycle-Prüfung. |
| API / Agenten / UI | Bestehende Pfade und normale Faktengrenzen bleiben erhalten; EN/DE-Prüfungen, Vertragsgenerierung, OpenAPI-Validierung, MCP und echte Agentenrouten bestanden. Interne Scores werden nicht als Prozent-/Erfolgswahrscheinlichkeit dargestellt. |

## Ende-zu-Ende und Lebenszyklus

Der finale Lauf verwendet zwölf kontrollierte Quellen über sieben Collections. Zwei Seiten wurden tatsächlich lokal gecrawlt; zehn wurden vom lokalen Fixture-Server gelesen und durch YaCys tatsächlichen HTML-Parser-/Push-/Indexierungsweg aufgenommen. Keine KG-Daten oder API-Antworten wurden für die fachlichen Positivfälle gemockt.

| Fall | Ergebnis im echten lokalen Weg |
|---|---|
| Industrieunternehmen: interner SAP-Einsatz | Passender SAP-Anbieter; kein Zielbranchenzwang. Historischer Originalbeleg erreichbar. |
| SAP-Beratungshaus: gleiche eigene Anzeige | Kein daraus getragener Kundenvorschlag; Beobachtungen bleiben gespeichert. |
| Architekturbüro: Revit intern / Archicad erwünscht | Passende CAD/BIM-Unterstützung; Kompetenz und interner Einsatz getrennt. |
| Eigener Neubau und energetische Sanierung | Passender Architektur-/Planungs- und Energieanbieter. |
| Ausdrücklicher Führungs-/Teamentwicklungsbedarf | Passender Coaching-Anbieter. |
| Krankenhausprozess zur ambulanten Versorgung | Möglicher **Partner**, kein Kunde; zusätzlicher Regionalbeleg erforderlich. |
| Berechtigte andere Collection | Kennzeichnung, Zielkontext, Rücknavigation, Liste, Graph und Export funktionieren. |
| Verdeckter Kandidat / verdeckter Zusatzbeleg | Keine Namen, Scopes oder inkonsistenten Trefferzähler; Agent kann mit Ansichtsfilter keine Grants vergrößern. |
| Alte Anzeige ohne Ablaufdatum | Suchstatus unbekannt, weiterhin datierter System-/Kompetenzbeleg. |
| Belegtes Stellenende / unvollständige Revision | Beendet Personalsuche beziehungsweise liefert keine Widerlegung; Originalsystembeleg bleibt erhalten. |
| Indexlöschung + Reconciliation | Quellenstatus ändert sich; historische Grundlage und Navigation bleiben erhalten. |
| Spätere organisationweite SAP-Abschaltung | Veralteter Einsatzbeitrag entfällt schon beim Lesen; unabhängiger geforderter Kompetenzgrund bleibt. |
| Arbeitgeber-/Scopekorrektur | Kontrollierte Korrektur-Injektion **nur in der pausierten Testdatenbank**; Cachegrund entfällt, historische Collection autorisiert den Beleg nicht mehr. Der produktive Korrektur-/Mergeweg ist zusätzlich durch Store-/Matching-Tests gedeckt. |
| Backup / normaler Restore | Portable SQLite-Datei heruntergeladen; späterer Identitäts-/Scopezustand wird nicht automatisch hineingemischt. Neuere Archivrevisionen fehlen vor expliziter Reconciliation. |
| Reconciliation und Rebuild nach Restore | Aktueller kontrollierter Solr-Bestand wird verarbeitet; historische ID und Zitat bleiben erreichbar, unabhängiger Grund bleibt gültig. |

**Finaler sauberer Lauf:** 77 API-/Lifecycle-Prüfungen und **12 echte Browserprüfungen bestanden** auf `3a52850178363ad6565289eab96b1b4326484d7a`. Keine Working-Tree-Differenz beim Start; keine Fehler. Start 10.003 s, Aufnahme/Extraktion 70.510 s, Revision/Bereinigung/Abschaltung 8.261 s, Rebuild 2.613 s. Der gewählte Backupstand enthielt 50 Beobachtungen; nach expliziter Reconciliation/Rebuild enthielt der aktuelle Stand 52. Der Index enthält am Ende zwölf Dokumente: eine Quellenlöschung und eine neue Abschaltungsquelle.

## Speicherfehler und begrenzte Last

`ObservationStoreTest` injiziert einen Archiv-UPDATE-Abbruch mit SQLite `RAISE(ABORT)` während der Quellenbereinigung. Die gesamte Transaktion rollt zurück: Dokument, Live-Evidence und Quellenstatus bleiben erhalten.

Eine weitere Prüfung begrenzt ausschließlich die temporäre SQLite-Datenbank mit `PRAGMA max_page_count` und fordert in einem Archiv-INSERT-Trigger einen begrenzten BLOB an. SQLite liefert **tatsächlich `SQLITE_FULL`**, von der Anwendung als `STORAGE_FULL` behandelt. Alte Beobachtungen, Originalzitat und Live-Evidence bleiben erhalten; die Druck-Tabelle erhält keine persistierte Zeile. Dies ist ein echter SQLite-Kapazitätsfehler auf künstlich begrenzter Datenbank, **kein OS-Disk-full-Nachweis**. Das Host-Dateisystem wurde nicht gefüllt; eine reale ENOSPC-Prüfung auf eigenem begrenzten Testmedium wurde nicht ausgeführt.

Die StorageGuard-/Runtime-Tests simulieren außerdem kritischen freien Speicher und dessen Wiederkehr. Allgemeine Indizierung und lesbares Wissen bleiben verfügbar, KG-Anreicherung beziehungsweise Policy-Feed-Erzeugung werden zurückgestellt. Diese Probe-Werte sind simulierte Speichersignale, keine gemessene physische Disk-full-Situation.

Finaler Lastlauf auf dem sauberen Commit `3a52850178363ad6565289eab96b1b4326484d7a`, ohne gleichzeitig laufende E2E-Instanz:

| Messgröße | Gemessener Wert |
|---|---:|
| Anbieter / Kandidaten / Quelldokumente | 12 / 240 / 252 |
| Beobachtungen / Beiträge | 288 / 2.880 |
| Arbeitsbudget je Lauf / vollständige Läufe | 400 / 8 |
| Verarbeitete Kandidatenprüfungen | 2.880 |
| Extraktion + Publikation | 2,685 s |
| Ableitung über acht Läufe | 1,176 s |
| Drei Ergebnis-Seiten, 240 Kandidaten | 1,002 s |
| Gesamter Java-Prozess | 4,923 s |
| Peak-RSS des Java-Prozesses | 200.740 KiB ≈ 196,0 MiB |
| Verwendeter Java-Heap am Messpunkt, kein Peak | 77.602.456 Byte ≈ 74,0 MiB |
| SQLite-Datei nach Checkpoint | 5.058.560 Byte ≈ 4,82 MiB |
| Verarbeitungslücken / Pagination-Duplikate | 0 / 0 |

Die Fixtures durchlaufen tatsächliche JSON-LD-Extraktion, Publisher, SQLite-Archiv, Matching und berechtigte paginierte Leseprojektion. Die Messung ist ein kurzer lokaler Probe-Lauf mit synthetischen Daten, kein Crawling-Durchsatztest, Langzeit-/Parallelitätsnachweis oder Beleg für Produktionslastfähigkeit.

## Prüfmatrix und Grenzen

| Prüfung auf dem gemeinsamen Stand | Ergebnis |
|---|---|
| `all`: vollständige Relocation-Regression | **18 Prüfungen bestanden** |
| `all`: Jetty-Tests | **60 Tests bestanden** |
| `scoutro-kg-observations-test` | **366 Tests bestanden**, einschließlich Migration, Lifecycle, LLM-Verträgen, Rollen, Berechtigungen, Feed und API/Agenten |
| Unveränderter Jetty/Solr-Guard | **Bestanden** |
| Zuvor blockierter integrierter Solr-Spike | **4 Tests bestanden**, tatsächlicher Start/Write/Query/Close |
| Offline-Browser A / B / C, EN und DE | **44 / 26 / 36 Prüfungen bestanden** |
| API-/Generierungsverträge | **15 Tests bestanden** |
| MCP-Adapter | **10 Tests bestanden** |
| Locale-Identifier-Guard | **18 Seiten × 14 Locale-Dateien, 0 Kollisionen** |
| OpenAPI 3.1 / Generator | **Validiert; 77 Actions bytegleich reproduziert** |
| JavaScript-/Python-Syntax und `git diff --check` | **Bestanden** |

Die finale Ant-Ausführung dauerte 2 Minuten 12 Sekunden. Die neuen Archivfehler- und Policy-/Runtime-Regressionen sind in den 366 Tests enthalten; die gezielten Vorab-Testläufe werden nicht zusätzlich als neue Tests gezählt. Kein offener Guard- oder Solr-Integrationsfehler verbleibt auf diesem Stand **mit #42**.

Frühere fehlgeschlagene Läufe sind getrennt einzuordnen: ursprünglicher Guard auf main und C tatsächlich fehlgeschlagen; erste eigene Feed-Fassung durch bestehende Read-only-Startup-Regression beanstandet und korrigiert; frühe E2E-Versuche durch vorzeitige Warteschlangen-/SVG-/Backup-Pause-Annahmen des Harness abgebrochen. Diese Versuche gelten nicht als erfolgreiche Nachweise. Der finale vollständige Lauf ist der oben genannte.

Nicht ausgeführt: produktive Quellen-/LLM-Auswertung, Produktionsupgrade, Produktionseinstellungen, Olares-/Container-Deployment, große oder lang andauernde Produktionslast, reale OS-ENOSPC-Prüfung, komplett leere Ivy-/Maven-Cache-Auflösung und sämtliche fachfremden historischen Repository-Tests. Der kontrollierte Live-Weg nutzt deterministische JSON-LD-/Regel-Extraktion; LLM-Schema, Validator, Grounding, Custom-Prompts und Cache-/Datierungsverträge wurden in den entsprechenden automatisierten Suiten geprüft, nicht gegen einen externen Modellanbieter.

Verbleibende Grenzen: konservative sprachliche Regeln können geeignete Hinweise übersehen; keine freie LLM-Passung. Regionale Pflegepassung beruht auf expliziten kompatiblen Ortsbelegen, nicht auf Geocoding oder einer realen Versorgungszusage. Auch ein bestandener Systembeleg bestätigt keinen heutigen Einkaufsauftrag. Bestehende historische Verluste lassen sich nicht nachträglich rekonstruieren.

## Upgrade, Rollback und Mergeempfehlung

Vor einem späteren produktiven Upgrade: kompatible Binär-/Konfigurations- und vollständige Datenkopie sichern; Platz für Migration/Archiv und verifiziertes Vorabbackup vorsehen. Schema-4 → 5 und Schema-5 → 6 sowie idempotente Übernahme wurden automatisiert geprüft. Schutzmigration und Bestandsübernahme müssen vor jeder neu ausgelösten Extraktion erfolgen; bei Upgrade-Hold/Speicherstopp erst den verifizierten Sicherungsstand und die Ursache prüfen. Kein manueller Tabellenabwurf oder Herabsetzen von `schema_version`.

Normaler Restore stellt den gewählten Stand in einer neuen Epoche her. Spätere Reconciliation darf heutige Solr-Daten wieder beobachten; sie ist keine implizite Archiv-Zusammenführung. Feed-Cursor aus der alten Epoche sind nicht weiterzuverwenden: erneuten berechtigten Vollabgleich durchführen.

**Kein verlustfreier Downgrade nachgewiesen.** Zur Rückkehr von C zu B: C stoppen, Schema-6-Verzeichnis und vollständigen berechtigten Export separat erhalten; einen verifizierten Schema-5-Backupstand und kompatibles B verwenden. B kann Schema 6 nicht öffnen. Für B → ältere Version analog den passenden Schema-4-Stand verwenden. Beobachtungen nach dem gewählten älteren Backup fehlen dort; sie dürfen nicht automatisch hineingemischt werden. Die begrenzten Restore-/Rebuild-Nachweise ersetzen keinen geplanten Produktions-Rollbacktest.

| PR | Empfehlung auf Basis dieser Abnahme |
|---|---|
| #42 | **Als unabhängige Voraussetzung zuerst integrieren.** Packaging-Ursache behoben; unveränderter Guard und der zuvor blockierte Solr-Spike bestehen. Unveränderte Upstream-Attribution muss mit den Bridge-JARs ausgeliefert werden. |
| #39 | **Merge empfohlen nach #42 und Prüfung der aktualisierten Basis.** Collection-Erweiterung ist gezielt, normale Fakten bleiben begrenzt; Benutzer-/Agentenzugriff, Navigation und Export geprüft. Kein fachlicher A-Codebefund erforderte eine Änderung. |
| #40 | **Merge empfohlen nach #39**, mit verifiziertem Vorabbackup, Archiv-Speicherreserve und den dokumentierten Rollbackgrenzen. Migration, Bestandsschutz, Status-/Datierungstrennung und Historienexport geprüft. Kein fachlicher B-Codebefund erforderte eine zusätzliche Änderung. |
| #41 | **Merge des korrigierten Heads empfohlen nach #40**; der ursprüngliche Head allein enthält die behobene Policy-Feed-Lücke. Explizite Regeln, vollständige Belegketten, gezielte Rücknahmen und der bounded Lauf sind geprüft. |

Die Kette bleibt **#39 → #40 → #41**; #42 ist die vorgelagerte gemeinsame Packaging-Voraussetzung. Nach jedem Merge/Basiswechsel tatsächliche Head-/Basis-SHAs erneut feststellen und PR-Basis korrekt umstellen. Bei Squash/Rebase die gestapelten Nachfolger so neu aufbauen, dass keine bereits gemergten Vorgänger erneut als Änderungen erscheinen. Auf dem neu resultierenden Gesamtstand erneut `all`, Guard, Solr-Spike und KG-Suite ausführen; nach dem B-/C-Basiswechsel zusätzlich lokale Chain-E2E, API-/UI-/Exportverträge und Upgradeprüfungen. Patchgleichheit ersetzt diesen Nachweis nicht. Keine Aussage dieses Berichts ist eine bereits erteilte Produktionsfreigabe.
