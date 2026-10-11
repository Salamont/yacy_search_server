# Scoutro: stockender KG-Sync — Befund und Abnahme

Stand: 2026-10-10. Repository: `Salamont/yacy_search_server`.

## Ergänzung: Patchrelease-Vorbereitung 2026-10-11

Draft PR #45 bereitet jetzt **0.9.1 / 1.942-scoutro.23** vor. Die folgenden
ursprünglichen Nachweise beziehen sich weiterhin ausdrücklich auf den unten
genannten Code-Stand und den damaligen Image-Overlay-Lauf; sie werden nicht
als Prüfung eines neu gebauten Images ausgegeben. Die Releaseangaben und
Publish-Versionsliterale sind nun separat aktualisiert. Der Überschreibschutz
bleibt erhalten; keine Schema-/Extraktoridentitäts-/Lastgrenzenänderung.

Die abschließende Patchrelease-Abnahme gegen einen vollständigen lokalen
linux/amd64-Kandidaten wird mit **exaktem Commit, Image-Konfigurations-ID,
Prüfergebnissen und Grenzen** im Abnahmebericht bei
[Draft PR #45](https://github.com/Salamont/yacy_search_server/pull/45)
aufgezeichnet. Dort steht auch der erwartete automatische Publish-Lauf eines
späteren ausdrücklich autorisierten Merges. So erfordert die Aufzeichnung
des Ergebnisses keine nachträgliche Änderung des geprüften Image-Kandidaten.
Siehe auch [Patchrelease-Hinweise](SCOUTRO_RELEASE_0.9.1.md).

## Geprüfter Stand und Aussagegrenze

- Aktuelles `origin/main`, ausgelieferte Quellrevision und isolierte Basis:
  `8812ad2f901ede7ebe6ea27764d30889c8a7b866`.
- Eigener Branch: `fix/scoutro-kg-sync-scheduler`; keine fremden
  Entwicklungsbranches oder uncommittierten Änderungen übernommen.
- Vollständig geprüfter **Code-Stand**:
  `93530be52aae3a522d27525de7e1bd6614a7973a`.
  Der anschließende Bericht-Commit ändert ausschließlich dieses Dokument.
- Unverändertes veröffentlichtes Vergleichsimage:
  `ghcr.io/salamont/scoutro@sha256:13d6888d458b18f3c83e94cf3188c9b8e222d1c91f3ad0be3111288abc49f48d`.
  Lokal erneut geprüft: `linux/amd64`, Benutzer `yacy`, Revisionslabel
  `8812ad2f901ede7ebe6ea27764d30889c8a7b866`, Temurin 24.0.2.

**Nachgewiesen ist ein konkreter Codefehler mit demselben Stillstandsmuster.**
Der tatsächliche Produktionsauslöser ist weiterhin unbestätigt: Es wurden
weder Produktionsquellen noch Produktionsdaten, Einstellungen oder Logs
abgerufen. Die vom Betreiber gemeldeten Werte sind Eingangsbefunde, keine
hier erneut gemessenen Produktionswerte.

## Ursache und Reproduktion

`BusinessSignals.extract()` verwendete auf der Basis den Ausdruck
`(?:[^.!?;\n]|(?<=[0-9])\.(?=[0-9]))+(?:[.!?;]|$)`.
Die wiederholte Alternation benötigt bei langen Absätzen rekursive
Regex-Aufrufe. Bereits ein kontrollierter Absatz aus 20.000 Zeichen löst
einen **echten `StackOverflowError`** aus, bevor die vorhandene
900-Zeichen-Grenze für Belegzitate geprüft wird. Das gilt auch für
JSON-LD-`description`; dafür sind keine LLM-Anfrage und kein Solr-Defekt nötig.

Die Fehlerkette auf der unveränderten Basis:

1. `SyncService.processBatch()` / `process()` führt die Extraktion aus.
2. `SyncService.step()` behandelt `KgException` und `RuntimeException`,
   aber keine `Error`-Instanzen.
3. `KgRuntime.syncTick()` hatte keine äußere Fehlergrenze.
4. `open()` behielt den von `scheduleWithFixedDelay()` gelieferten Future
   nicht. Ein entweichender Fehler beendet die periodische Aufgabe
   außergewöhnlich; der Executor bleibt geöffnet. Der Fehler steckt im
   Future und erreicht keinen gewöhnlichen Thread-Uncaught-Handler.

Ein isolierter Scheduler-Harness gegen das tatsächliche veröffentlichte
Image lieferte:

```text
cause=java.lang.StackOverflowError
calls=1 done=true cancelled=false executorShutdown=false
```

`SyncBoundaryTest.longDescriptionDoesNotHaltAtTenThousandScannedAndExtraction487()`
verwendet 10.050 kontrollierte Solr-Dokumente, echte temporäre SQLite-Daten
und eine lange JSON-LD-Beschreibung im 487. Dokument. Gegen die alte
Image-Implementierung scheitert der Test mit:

```text
java.lang.AssertionError: controlled sync failure:
scanned=10000, published=486, extractions=487, queued=9514
Caused by: java.lang.StackOverflowError
```

Damit sind die gemeldeten Scan-/Extraktions-/Publikationszähler und der
unauffällig geparkte Executor deterministisch erklärbar. Die Fixture wurde
gezielt so angeordnet; sie bildet weder den Produktionsbestand noch dessen
Queuegröße von 115.735 ab. Die Zählergleichheit beweist nicht, welcher
Produktionsabsatz oder welcher sonstige `Error` tatsächlich beteiligt war.

## Reconcile, Solr und bestehende Fehlerpfade

| Datei / Funktion, Fundstelle im geprüften Code | Befund |
| --- | --- |
| `extract/BusinessSignals.java`, `extract()`, Zeile 38 | Rekursive Klausel-Regex war der reproduzierte Auslöser; Ersatzscanner beginnt bei Zeile 47. |
| `KgRuntime.java`, `open()`, Zeile 494; `syncTick()`, Zeile 755 | Future und äußere Task-Grenze fehlten auf der Basis. |
| `sync/SyncService.java`, `step()`, Zeile 248 | Erwartete Fehler werden lokal behandelt; `Error` beendet die Aufgabe. |
| `sync/Reconciler.java`, `PAGE`, Zeile 103; `scan()`, Zeile 464 | Seiten bestehen aus 1.000 Einträgen. Es gibt keine dauerhafte Scan-Grenze bei 10.000. Cursor und Seitenzählung werden mit Queueänderungen persistiert. |
| `sync/SyncService.java`, `drain()`, Zeile 321 | Höchstens 20 × 500 Dirty-Ereignisse pro Schritt; die Zahl 10.000 begrenzt einen Drain-Schritt, nicht den gesamten Abgleich. |
| `sync/EmbeddedSolrSource.java`, `get()`, Zeile 67; `scan()`, Zeile 108 | Gewöhnliche Solr-/Runtime-Fehler werden zu `IOException`; JVM-Fehler können entweichen. |
| `sync/Reconciler.java`, `scan()`; Retry-Konstanten, Zeilen 107–108 | Scan-I/O-Fehler brechen den Lauf ohne Löschbestätigung ab; Wiederaufnahme mit bestehendem Backoff von 60 Sekunden bis 30 Minuten. |
| `sync/SyncService.java`, `processBatch()`, Zeile 380 | Real-time-get-I/O-Fehler geben Claims frei und warten bestehende 10 Sekunden. |
| `store/KgStore.java`, `write()`, Zeile 484 | SQL-Transaktionen werden auch bei `RuntimeException`/`Error` zurückgerollt. |

Ein unveränderter Cursor über 15 Minuten ist somit keine beabsichtigte
10.000er-Grenze. Auch die getrennte LLM-Warteschlangengrenze von 10.000
beendet diesen Scheduler nicht. Der Queue-Gesamtzähler wird direkt aus dem
Store gelesen; der kurze Cache für Typ-Unterzähler erklärt den Stillstand
nicht. Aus diesen Befunden allein folgt noch kein Ausschluss aller anderen
Produktionsursachen.

## Fix und Daten-/Lifecycle-Schutz

- Der Klauselscanner durchläuft den Text einmal, ohne rekursive Alternation
  oder quadratische `find()`-Versuche auf langen unvollständigen Zeilen.
  Die vorhandene Zitatgrenze, ASCII-Ziffernpunkte in Versionen/Datumsangaben,
  Quote-Trimming, UTF-16-Locators und bisherige Zeilenabschlusssemantik
  bleiben erhalten. Keine neuen Extraktions- oder Matching-Regeln.
- `SyncService.drain()` legt entnommene Dirty-Ereignisse in `finally` zurück,
  wenn ihre Persistierung nicht erfolgreich zurückkehrt. Ein Fehler wird
  weitergereicht. Der echte SQLite-Rollback und spätere Final-Drain sind
  geprüft; Ereignisse gehen bei diesem getesteten Fehlerpfad nicht verloren.
- `KgRuntime` hält Future und Diagnostik in einer eigenen Session. Ein
  unerwarteter `RuntimeException`/`Error` wird mit Stacktrace protokolliert
  und erneut geworfen. Der Task bleibt terminal fehlgeschlagen.
- **Keine neue automatische Recovery.** Insbesondere `VirtualMachineError`,
  `ThreadDeath` und Linkage-Fehler werden nicht in einer Wiederholungsschleife
  verarbeitet. Die bekannte Ursache wird entfernt; sonstige terminale
  Fehler werden sichtbar und erfordern nach Ursachenprüfung einen bewussten
  Neustart. Bestehende erwartete I/O-/Storage-Retries bleiben unverändert.
- Close/Stop canceln den Future ohne Interrupt einer laufenden
  Embedded-Solr-Operation. Watchdog und Maintenance beleben den Task nicht
  wieder. Restore und der automatisierte Test-Rebuild-Swap schließen die
  alte Session vor dem Öffnen einer neuen. Es gibt keine zusätzlichen
  Recovery-Tasks.
- Die bestehende Close-Wartezeit von fünf Sekunden wurde nicht verändert.
  Eine länger laufende alte Operation kann danach noch abschließen. Ihr
  Fehler und ihre Zeitstempel bleiben an ihrer geschlossenen Session;
  sie überschreiben nicht die Diagnostik einer Ersatzsession. Ein solches
  zeitweises Überlappen einer alten In-flight-Operation mit der neuen
  Session wird ausdrücklich nicht als ausgeschlossen behauptet.
- Persistierte Queue, Claims und Scan-Cursor bleiben erhalten. Der Test
  einer entweichenden Ausnahme nach Claim zeigt die noch vorhandene Arbeit;
  erst die bestehende Initialisierung beim ausdrücklichen Neustart setzt
  alte Claims zurück und verarbeitet den Rest. Keine pauschale Freigabe
  von Claims oder Zurücksetzung des Cursors im Fehlerhandler.

## Status, Oberfläche und Verträge

Administrator-`GET /scoutro/api/v1/kg/status` ergänzt `sync.scheduler`:
`state`, `executing`, Future-`done`/`cancelled`, `lastStartedAt`,
`lastFinishedAt`, `completedTicks`, `failedAt`, `lastError` und
`automaticRecovery: false`. Zeiten sind Epoch-Millisekunden oder `null`;
Zähler gelten für diese Runtime-Session. Endet der Task unerwartet, erscheint
`sync.state=failed`, `reason=task_terminated`.

Die Übersicht zeigt den lokalisierten Task-Zustand, letzte Ausführung,
Fehlerklasse und den aktuellen Reconcile-Scanstand einschließlich Cursor.
Abgeschlossene Ticks schließen Idle-/Gate-Prüfungen ein und sind **keine**
verarbeiteten Dokumente. Tatsächlicher Fortschritt ergibt sich weiterhin
aus Queue, `processed` und Reconcile-Phase/Zählern/Cursor.

Status bleibt administratorgeschützt; Agenten erhalten keine neuen
Berechtigungen. API-Dokumentation, Generator, Actions/OpenAPI, Hilfe und
DE-Lokalisierung wurden zusammen angepasst. Lesen von Status löst weder
Recovery noch Neuextraktion aus. Schema, Konfiguration, Gates,
Extraktor-/Cache-Identitäten und Releaseversionen bleiben unverändert.

## Ausgeführte Abnahme

Alle folgenden Codeprüfungen liefen auf `93530be52aae3a522d27525de7e1bd6614a7973a`.
Umgebung: Linux 6.18.44 `amd64`, Containerbudget 2 CPUs / 8 GiB,
Temurin 17.0.20.1, Ant 1.10.15, Ivy 2.5.3, Node 24.19.0,
Chromium 151.0.7922.173, lokaler Docker-Daemon 28.4.0. Abhängigkeiten wurden
normal über Ivy aufgelöst. Session-Proxy/CA wurden nur für lokale Werkzeuge
verwendet; TLS-Prüfung und Produkt-Trust wurden nicht abgeschwächt.

| Prüfung | Ergebnis |
| --- | --- |
| `ant all` | Bestanden, einschließlich 18 Packaging-/Relocation-Prüfungen und 60 Jetty-Tests. |
| `ant scoutro-kg-sync-test` | 121 Tests bestanden: echter Parserausfall, Fortsetzung über 10.000, Scan-I/O-Backoff, Queue/Claims, Dirty-Drain, Scheduler und Lifecycle einschließlich Backup/Restore und isoliertem Fixture-Rebuild. |
| `ant scoutro-kg-matching-test` | 88 Tests bestanden. |
| `ant scoutro-kg-llm-schedule-test` | 132 Tests bestanden. |
| `ant scoutro-kg-observations-test` | 386 Tests bestanden. |
| Unveränderter `test/jetty-solr-dependency-guard.sh` | Bestanden, nach beendetem Ant-Build. |
| Unveränderter `test/solr9-jetty-bridge-spike.sh` | Vier integrierte Embedded-Solr-Tests bestanden, 3,436 Sekunden; seriell nach dem Build. |
| Veröffentlichte Runtime mit read-only eingeblendeten Fix-/Testklassen | 21 gezielte Tests bestanden, 11,716 Sekunden; echtes Java 24, kein Netzwerk, read-only Root, ausschließlich temporäres `/tmp`. Kein neu gebautes oder veröffentlichtes Fix-Image. |
| `test/scoutro-ui/knowledge-sync-ui-test.mjs` | 22 Browserprüfungen EN/DE, 390px; echte HTML/JS mit kontrollierten Statusantworten, Fehler-/Idle-Anzeige, Cursor, Zeitstempel, kein JS-Fehler/Überlauf. |
| `python3 test/scoutro-api/test_mcp_adapter.py` | Zehn Tests bestanden; bestehender Agentenvertrag bleibt geschützt. |
| OpenAPI-Validator und erneute Generierung | OpenAPI gültig; Actions und OpenAPI bytegleich reproduzierbar. |
| Lokalisierung, JS-Syntax, `git diff --check` | Eine Seite gegen 14 Locale-Dateien: keine Kollision; Syntax/Whitespace bestanden. |

Die Ant-Ziele liefen gemeinsam in **5 Minuten 12 Sekunden**. Die Suiten
überlappen: Ihre Testzahlen werden nicht zu einer Zahl unterschiedlicher
Tests addiert. Der Image-Overlay-Lauf wiederholt ausgewählte Tests und ist
ebenfalls kein zusätzlicher Satz unabhängiger Szenarien.

Der Scheduler-Test injiziert `AssertionError`, `StackOverflowError`,
`OutOfMemoryError`, `ThreadDeath` und `LinkageError`, um deren terminale
Behandlung zu prüfen. Nur die Parser-Reproduktion erzeugt einen echten
Stackoverflow; OOM und die übrigen Scheduler-Fehler sind simuliert.
Ein zusätzlicher Embedded-Solr-Runtime-Test verarbeitet eine lange
Beschreibung und ihr Folgedokument, erhält die Beobachtung und leert die
Queue. SQLite-`quick_check` nach injiziertem Drain-Fehler ist `ok`.

Reproduzierbare normale Prüfungen, jeweils nach erfolgreichem Build:

```sh
ant all scoutro-kg-sync-test scoutro-kg-matching-test scoutro-kg-llm-schedule-test scoutro-kg-observations-test
./test/jetty-solr-dependency-guard.sh
./test/solr9-jetty-bridge-spike.sh
node test/scoutro-ui/knowledge-sync-ui-test.mjs
python3 test/scoutro-api/test_mcp_adapter.py
```

Für den lokalen Imagevergleich werden ausschließlich Testklassen, beim
Fixvergleich zusätzlich `build/classes/java/main`, read-only eingeblendet.
Arbeitsverzeichnis ist `/opt/yacy_search_server`, Classpath beginnt mit den
Fixtures und beim Fixvergleich den neuen Klassen, danach den Image-JARs
`lib/*` und `libt/*`. Optionen: `--network none --read-only`, begrenztes
`--tmpfs /tmp:rw,exec,size=256m` für temporäre Daten und SQLite-JNI. Es werden
keine produktiven Volumes eingebunden. Gegen das Originalimage scheitert
`SyncBoundaryTest` am oben belegten Stackoverflow; mit Fix bestehen
`SyncBoundaryTest`, `BusinessSignalsTest` und `KgSyncSchedulerTest`.

## Fehlversuche, Grenzen und nächste Integration

- Ein Solr-Spike wurde während eines Ant-Schritts gestartet, der die
  Bridge-JARs neu erzeugte. Der Guard meldete dabei ein fehlendes
  Scripting-Artefakt. Die **serielle** Wiederholung nach Buildende besteht;
  weder Guard-Assertions noch Wartezeiten wurden geändert.
- Erste Image-Harness-Versuche scheiterten am Image-Default-Workdir `/opt`
  beziehungsweise nicht ausführbarem temporärem SQLite-JNI-Mount. Die
  oben angegebenen isolierten Testoptionen korrigieren diese Testumgebung.
  Diese Versuche sind kein bestandener Produktnachweis.
- Keine Produktionsabnahme, keine reale OOM-/Disk-full-Prüfung, keine
  Lastmessung mit 115.735 Queueeinträgen, keine live Modellanfrage oder
  produktiver Crawl. Keine vollständige HTTP-Peer-E2E-Abnahme; Browser-
  Statusfixtures und Java-API-/Berechtigungstests wurden getrennt geprüft.
- Das unveränderte Registry-Image wurde als Fehlerbasis und Runtime benutzt;
  ein späteres Fix-Release/Image muss separat gebaut und abgenommen werden.
- Fork-PR-CI ist laut Workflow auf `yacy/yacy_search_server` beschränkt.
  Ein übersprungener Job im Salamont-Fork ist kein Buildnachweis.
- Vor einem späteren Merge erneut den tatsächlichen Head/Basisstand prüfen.
  Änderungen unter `tools/scoutro/**` können beim Merge auf `main` den
  bestehenden Publish-Workflow auslösen. Die Versionen und der Schutz gegen
  Überschreiben der bereits belegten Release-Tags bleiben unverändert;
  Releasekoordination ist ein eigener Auftrag. Dieser Draft-PR wird hier
  weder gemergt noch veröffentlicht oder deployed.

Die unveränderten Schutzdateien wurden zusätzlich bytegleich gegen die
Basis geprüft: Dependency-Guard, Solr-Spike, `ivy.xml`, `KgSchema.java`,
`KgConfig.java`, `Gates.java`, `SolrDoc.java`, `Vocabulary.java`,
`scoutro.properties` und `.github/workflows/publish-scoutro.yml`.

Rücknahme des Fixes ändert kein Schema und erfordert keine Datenkonvertierung;
sie stellt jedoch den nachgewiesenen Parser-/Diagnostikfehler wieder her.
Eine allgemeine verlustfreie Rücknahme von Datenänderungen oder ein
verlustfreier Downgrade des bestehenden Scoutro-Schemas wird nicht behauptet.
