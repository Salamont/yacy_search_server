# Abnahme der optionalen LLM-Zeitsteuerung

Stand: 10. Oktober 2026. Separater Folgebranch `feat/scoutro-llm-schedule`; keine Änderungen an den bisherigen PR-Branches, kein Merge, Release oder Deployment.

## Geprüfte Basis

Basis dieses PRs ist `c4c557287248099da3f46fab67cba66553fb0b05` auf `test/scoutro-integrated-acceptance`. Dieser Dokumentationscommit liegt auf dem abgenommenen gemeinsamen Codestand `3a52850178363ad6565289eab96b1b4326484d7a`. Die erneute Remote-Prüfung am 10. Oktober bestätigt:

| Referenz | Aktueller Head | Basis | Zustand |
|---|---|---|---|
| main | `7847fe1ed54eda64348eaa64cd15d7eb8c107639` | — | Kette noch nicht integriert |
| #39 / A | `08a42db1d6609f6f53e10a51890f8e1bc28b7847` | main | offen, Draft |
| #40 / B | `8f6f3f11f5a510fd55da90664ebf2c7491f4c326` | #39-Branch / obiger A-Head | offen, Draft |
| #41 / C | `8d259a0b543f515237fd3e1d2407ccdb6cff3927` | #40-Branch / obiger B-Head | offen, Draft |
| #42 / Relocation | `cc15befe281717b0761d4c13186afa03c4336a00` | main | offen, Draft |

Der Folge-PR braucht **#42 und #39 → #40 → #41 einschließlich der integrierten Abnahmekorrekturen**. Es wurden keine anderen Entwicklungsbranches übernommen. Nach Integration der Kette auf main umbasieren/umstellen und die unten genannten Prüfungen erneut ausführen. Ein erfolgreicher Test auf dieser Basis ist kein Nachweis für main ohne die Kette.

Abschließender Code-/Teststand: `309b4dc8014ad98e87b0b90073cfa0f12a0e918c`. Der komplette serielle Repository-Lauf besteht auf diesem Stand mit 60 / 130 / 380 Tests in 3 Minuten 12 Sekunden; auch Peer/API/Browser, Guard/Spike und die UI-/Vertragsprüfungen wurden auf diesem Stand erfolgreich wiederholt. Er ergänzt gegenüber `3c8663a45d9e0fd27e8a472964d8aef550fcd5ee` ausschließlich zwei Schutzpausenprüfungen; der Produktcode ist identisch. Ein nachfolgender reiner Dokumentationscommit verändert den getesteten Code nicht.

## Verhalten und Grenzen des manuellen Laufs

Ohne Konfiguration bleibt die zeitliche Freigabe automatisch und der Mindestabstand 0. Der zusätzliche Plan ersetzt weder Collection-Auswahl noch bestehende Schutz-, Timeout-, Retry- oder Breaker-Einstellungen. Einstellungen liegen in einem vollständig validierten Konfigurationswert; Zeitänderungen öffnen den Runtime nicht neu und ändern keine Auswahl-/Extraktor-/Cacheidentität.

Ein manueller Lauf ist begrenzt auf standardmäßig **25 verschiedene geprüfte Dokumente / 100 tatsächliche HTTP-Starts**, maximal 100 / 1000 und eine Stunde. Er übersteuert nur das Zeitfenster. Ein Stop beendet weitere Starts dieses Laufs; bereits laufende Anfragen dürfen abschließen. Automatische Verarbeitung kann nach den unabhängig gespeicherten Einstellungen weiterlaufen. Ein Neustart übernimmt den Plan und den letzten tatsächlichen Start, startet aber keinen manuellen Lauf erneut. Erledigte Dokumente bleiben erledigt; fehlgeschlagene benötigen die bestehende Retry-Aktion.

Der Abstand gilt gemeinsam für beide Worker, Chunks und Format-Fallbacks. Cachetreffer kosten keinen Aufruf. Empfangene, validierte Chunks bleiben unabhängig vom optionalen Cache in begrenzten internen Metadaten erhalten. Details, Mitternachts-/Sommerzeitregeln und Upgrade/Rücknahme: [SCOUTRO_LLM_SCHEDULE.md](SCOUTRO_LLM_SCHEDULE.md).

## Umgebung und Ausführung

- Isolierter Worktree, temporäre SQLite-/Solr-Daten; Linux 6.18.44, 2 CPUs, 8 GiB RAM; JDK 17.0.20.1+1, Ant 1.10.15, Ivy 2.5.3; Node 24 und Chromium mit Playwright.
- Aufgelöste Abhängigkeiten der akzeptierten Basis wurden als separate Dateien übernommen. Ein temporärer Ant-Wrapper importiert das unveränderte Repository-Build und überspringt ausschließlich das erneute Ivy-Resolve. Compile-, Bridge-, Assertion- und Testtargets bleiben wirksam. Keine Guard-Assertions oder Testwartezeiten abgeschwächt.
- Lokale HTTP-Tests erhielten Netzwerkfreigabe für ihre Sockets. Ein erster Versuch ohne diese Freigabe scheiterte an der Sandbox; die freigegebenen Wiederholungen bestehen. Alle Modelltransporte des Tests laufen gegen kontrollierte Loopback-Fixtures.
- Rohlogs und temporäre Peer-Daten liegen außerhalb Git unter `/workspace/scoutro-schedule-evidence`. Die folgenden Befehle sind die normalen Repository-Äquivalente; lokal wurde für Ant `java -cp '/workspace/scoutro-acceptance/tools/*' org.apache.tools.ant.Main -f /workspace/scoutro-schedule-evidence/build.xml ...` verwendet.

```sh
ant all scoutro-kg-llm-schedule-test scoutro-kg-observations-test
test/jetty-solr-dependency-guard.sh
test/solr9-jetty-bridge-spike.sh
JAVA=/path/to/jdk/bin/java SCOUTRO_CHROMIUM_PATH=/usr/bin/chromium \
  SCOUTRO_SCHEDULE_EVIDENCE=/tmp/new-timing-acceptance \
  python3 test/scoutro-api/kg-llm-schedule-live.py
node test/scoutro-ui/knowledge-schedule-ui-test.mjs
node test/scoutro-ui/knowledge-suggestions-ui-test.mjs
node test/scoutro-ui/knowledge-history-ui-test.mjs
node test/scoutro-ui/knowledge-matching-ui-test.mjs
python3 tools/scoutro/generate_api_description.py htroot/env/scoutro/api
python3 -m unittest discover -s test/scoutro-api -p 'test_mcp_adapter.py'
python3 -m unittest discover -s test/scoutro-api -p 'test_flow_contract.py'
python3 test/scoutro-ui/check-locale-identifiers.py
node --check htroot/env/scoutro/knowledge.js
git diff --check
```

Zusätzlich wurde das generierte OpenAPI-3.1-Dokument mit `openapi_spec_validator.validate` geprüft; erneutes Generieren der 80 Aktionen verursacht keine Änderung.

## Ergebnisse und Abdeckung

| Prüfung | Ergebnis auf `309b4dc…` |
|---|---|
| `all` einschließlich Relocation-Assertions und Jetty-Tests | bestanden; 60 Jetty-Tests |
| Zeitsteuerungs-Target | bestanden; 130 Tests |
| Durable-KG-Regressionen | bestanden; 380 Tests |
| Dependency-Guard | bestanden, unveränderte Assertions |
| Integrierter Solr-Bridge-Spike | bestanden; 4 Tests mit Start, Update, Query und Close |
| Echte lokale Peer-/API-/Browserprüfung | bestanden; 17 API-, 15 Browserprüfungen, 6 tatsächliche Modell-HTTP-Starts |
| Offline-Browser | bestanden; Zeitsteuerung 18, Vorschläge 44, Historie 26, Matching 36 Prüfungen |
| MCP-Adapter / API-Tool-Verträge | bestanden; 10 / 15 Tests |
| OpenAPI, Generator, JS-Syntax, Diff | bestanden |
| Locale-Identifikatoren | 18 Seiten gegen 14 Sprachdateien, 0 Kollisionen |

Der separate Draft-PR ist [#43](https://github.com/Salamont/yacy_search_server/pull/43). Nach Erstellung werden die vorhandenen GitHub-Jobs `build` und `docker` aufgrund ihrer Fork-/Repository-Bedingungen übersprungen (`skipping`). Das ist kein zusätzlicher erfolgreicher CI-Nachweis; die oben genannten Prüfungen wurden lokal tatsächlich ausgeführt. Keine Workflow-Bedingung wurde geändert und kein Release-/Deployment-Workflow ausgelöst.

Die gezielte Java-Suite verwendet steuerbare Uhren, kontrollierte Clients und lokale echte HTTP-Formatverhandlung. Sie prüft:

- Standardmodus; halb offene Grenzen, gleiche Zeiten, Mitternacht mit Startwochentag, explizite Zone, Sommerzeitlücke und doppelte Stunde; keine Nachholstarts.
- Gemeinsame Freigabe zweier Worker, mehrere Chunks, Abstand und Format-Fallbacks; logische Zähler bleiben erhalten, tatsächliche Starts werden zusätzlich gezählt.
- Fensterende während einer Anfrage; Cache ohne Modellstart; erhaltene Ergebnisse ohne Cache über Stop, Neustart und ausdrücklich ausgelösten Retry; Zeitdeferral ohne Fehlversuche/Breaker-Fehler.
- Dokument-/Aufruflimit, maximale Laufdauer, Stop und fehlende automatische Wiederaufnahme; der nächste zulässige Start berücksichtigt auch das Ende der manuellen Übersteuerung.
- Laufende Konfigurationsänderungen ohne Runtime-/Epochwechsel, Zähler-/Pausenverlust oder Neuextraktion; fehlerhafte Pläne ohne teilweise Übernahme.
- Manuelle Übersteuerung unter Indexlast, Last, Heap- und KG-Pause; zusätzliche explizite Prüfungen für Budget, Reserve, kritischen freien Speicher, Integrität, nicht gespeicherten Start und offenen Circuit Breaker. Speicherzustände sind simulierte Messwerte, keine physische Disk-full-Prüfung.
- Begrenzte Checkpoint-Segmente, Kontextfingerprints, idempotente Sicherung, defekte Segmente, aktuelle Kontextauswahl, begrenzte Bereinigung und Vollreset. Bei simulierter Schreibverweigerung hält der Worker empfangene Ergebnisse, startet kein weiteres Modell und setzt nach Freigabe fort.

Die echte Peer-Prüfung verwendet drei kontrollierte HTML-Dokumente in zwei Collections, davon zwei mehrteilige LLM-Kandidaten und ein per Collection ausgeschlossenes Dokument; zwei Worker, Cacheanteil 0. Sie führt den tatsächlichen Parser-/Basis-KG-/Modell-/API-/Browserweg aus. Das erste Modellrequest wird bis zum Browser-Stop angehalten. Die Prüfung umfasst Digest-Admin, anonymen Zugriff, Cross-origin-Abweisung und Agent-Bearer-Abweisung, globale URLs trotz ausgewählter Collection, Validierungsfehler, Hot-Change während laufender Anfrage, Stop/Fortsetzung, keine Wiederholung erledigter Arbeit, Neustart und Desktop/Mobil. Empfangsintervalle lagen nach Speicherung von 2 Sekunden Mindestabstand bei 2,085 / 2,130 / 2,113 / 2,157 / 2,095 Sekunden; die exakte Startfreigabe wird zusätzlich mit Testuhren geprüft. Am Ende: 2 erledigte Dokumente, 1 ausgeschlossenes, 0 fehlgeschlagene, 0 Aufruffehler; Plan und letzter tatsächlicher Start überstehen den Neustart.

## Frühere Fehler und Einordnung

Ein früherer breiter Lauf auf `914d419cbed4f7d3b17fb2e9c08840c6a142b127` unter gleichzeitiger Browser-/Peerlast scheiterte in `KgRebuildTest.aRebuildReadsEveryScanPageBeforeTheSwap`: `AssertionError: not reached`, während der Rebuild noch arbeitete (1031 von 1100 Dokumenten veröffentlicht, 69 offen; kein gemeldeter Ressourcen-/Integritätsfehler). Die vorhandene Testwarteschleife war abgelaufen. Auf diesem Stand bestanden die gezielten 126 Tests und die Transport-/Browserchecks.

Anschließend besteht die unveränderte Klasse isoliert sowohl auf dem eigenen Stand als auch auf der unveränderten akzeptierten Basis (je 9 Tests, etwa 47 bzw. 50 Sekunden). Der komplette serielle Lauf auf `3c8663a…` besteht mit 60 / 128 / 378 Tests in 3 Minuten 7 Sekunden. Das spricht für eine lastabhängige Testzeitgrenze; ein identischer Parallelfehler auf der Basis wurde nicht nachgewiesen. Keine Assertion abgeschwächt und kein allgemeiner Testbacklog umgeschrieben. Den ersten Fehler nicht als erfolgreiche Prüfung zählen.

Während der gezielten Entwicklung wurden ein Null-Unboxing im nächsten Startzeitpunkt, das Vergessen einer Format-Ablehnung vor zurückgestelltem Fallback und eine zu weit reichende Änderung der bestehenden Formatfehler-Verhandlung korrigiert. Die vorhandene `StructuredOutputNegotiationTest` ist deshalb Teil des neuen Targets. Ebenso sind Zeitpunkte nach Ablauf einer manuellen Übersteuerung explizit abgesichert. Diese Befunde sind behoben, nicht offene Produktfehler.

## Nicht geprüfte Betriebsfälle und Rücknahme

- Kein produktiver Modellanbieter, Crawl, Produktionsdatenbestand oder produktiver Lasttest. Der kontrollierte Client prüft Transport/Steuerung, keine Modellqualität. Sommerzeit wurde durch Testuhren geprüft, nicht durch Warten auf einen realen Kalenderwechsel.
- Keine physische Disk-full-Prüfung, kein Host-Dateisystem gefüllt. Checkpoint-Schreibverweigerung, Budget und freier Speicher wurden kontrolliert simuliert. Ein harter Prozessverlust bei tatsächlich unbeschreibbarem Speicher kann noch nicht sicherbare Ergebnisse verlieren; in-flight-Modellantworten vor Empfang ebenfalls.
- UI-Bedienung wurde auf Desktop und mobil, neue Labels in EN/DE geprüft; andere Sprachdateien durch den Identifikator-Guard, nicht durch vollständige Browserläufe jeder Sprache. Keine allgemeine Browser-/Langzeitlastfähigkeit behauptet.
- Kein Schema- oder Extraktorupgrade. Ein Rückwechsel auf die akzeptierte Basis ignoriert den Plan und arbeitet wieder automatisch; vorher bei Bedarf LLM abschalten oder KG pausieren. Die alte Version versteht unabhängige Fortsetzungsmetadaten nicht und kann unfertige Chunks erneut aufrufen. Kein verlustfreier Downgrade unfertiger Arbeit zugesichert.
- Nach Basiswechsel auf integriertes main mindestens `all`, beide KG-Targets, Dependency-Guard, Solr-Spike, die lokale Timing-Peer-Prüfung und die UI-/Vertragschecks wiederholen. Bis dahin bleibt der PR Draft und abhängig von der genannten Kette.

## Umstellung auf die integrierte main-Basis

Für die vom Nutzer freigegebene Integration wurden #42 → #39 → #40 → #41 einzeln auf aktualisierten Basen erneut geprüft und gemergt. Neue Basis von #43 ist `5c45a5ab41b3a19d8ed8bcf8a8ec5629e61c0842`. Seine sieben eigenen Zeitsteuerungs-/Test-/Dokumentationscommits wurden ausschließlich auf dieses main umgesetzt; die bereits integrierten Packaging-/Feed-Cherry-Picks aus dem früheren Testbranch werden nicht erneut übernommen.

Vergleich dieser main-Basis mit `c4c5572…`: ausschließlich `SCOUTRO_PR_CHAIN_ACCEPTANCE.md` und `.json` fehlen. Diese historischen Nachweise werden hier gezielt bytegleich erhalten. Sämtliche Abnahmeharness, Packaging-Regressionen und Feed-/Archivfehlerkorrekturen liegen bereits identisch auf main. Produktcode und Tests des umgestellten #43 entsprechen dem früheren Head `e2e1cd3…`; diese Gleichheit ersetzt die erneuten Prüfungen auf der neuen Basis nicht. Deren Ergebnisse, exakt geprüfte Head-/Basis-SHAs sowie der spätere Merge-/main-SHA werden im Integrationsabschnitt von [PR #43](https://github.com/Salamont/yacy_search_server/pull/43) dokumentiert. Die früheren SHA-/Draft-/Workflowangaben oben sind historische Abnahmesnapshots.

Der notwendige Push der umgesetzten #43-Historie erfolgt ausschließlich mit explizitem Lease gegen den erneut kontrollierten bisherigen Remote-Head. Keine Guard-, Assertion- oder Wartezeitänderung und keine manuelle Release-/Tag-/Deploymentaktion.
