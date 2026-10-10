# Optionale Zeitsteuerung der LLM-Anreicherung

## Basis und Abhängigkeiten

Dieser separate Folge-PR basiert auf `c4c557287248099da3f46fab67cba66553fb0b05` (Dokumentation des abgenommenen gemeinsamen Codestands `3a52850178363ad6565289eab96b1b4326484d7a`). Bei Beginn waren #42 sowie #39 → #40 → #41 noch offen. Er enthält ausschließlich deren abgenommenen Gesamtstand als Basis, keine weiteren Entwicklungsbranches. Nach Integration der Kette ist auf main umzubasieren und erneut zu prüfen. Der Relocation-Guard bleibt unverändert und benötigt die Korrektur aus #42.

## Bedienung und Vertrag

In **Wissensgraph → Einstellungen → Zeitsteuerung der LLM-Anreicherung**:

- **Wie bisher automatisch**: Standard; zeitlich jederzeit erlaubt, Mindestabstand 0. Vorhandene Collection-Auswahl, Timeout, Retry, Circuit Breaker und Schutzprüfungen bleiben maßgeblich.
- **Nach Zeitplan**: ISO-Wochentage (Montag 1 bis Sonntag 7), Von/bis `HH:mm`, ausdrücklich gespeicherte benannte Zeitzone, z. B. `Europe/Berlin`.
- **Nur manuell**: keine automatischen Modellaufrufe.
- **Mindestabstand**: 0–86400 Sekunden zwischen tatsächlichen HTTP-Aufrufstarts, gemeinsam für beide Worker und Format-Fallbacks. Cachetreffer sind keine Aufrufe. Es gibt weder Zeitguthaben noch einen Aufholsturm nach verpassten Fenstern.
- **Jetzt anreichern**: standardmäßig höchstens 25 verschiedene geprüfte Dokumente und 100 tatsächliche Modellaufrufstarts, konfigurierbar 1–100 beziehungsweise 1–1000; spätestens nach einer Stunde endet die Übersteuerung. Chunks/Fallbacks zählen zum Aufruflimit. Ein Dokument kann unfertig bleiben. Stoppen lässt bereits gestartete Anfragen abschließen und erhält den Fortsetzungsstand. Manuelle Läufe werden nach einem Neustart nicht automatisch fortgesetzt.

Ein manueller Lauf verarbeitet offene Arbeit. Erledigte Dokumente werden nicht erneut ausgewertet. Endgültig fehlgeschlagene Dokumente benötigen weiterhin die vorhandene Retry-Aktion. Ein manueller Lauf übersteuert nur das Zeitfenster; KG-Pause, Collection-Ausschlüsse, Indexlast, Last, Heap, Speicherbudget, Festplattenreserve, Integritätsprüfung und Circuit Breaker bleiben wirksam. Stoppen eines manuellen Laufs pausiert nicht die unabhängig konfigurierte automatische Verarbeitung.

Der Plan betrifft ausschließlich die LLM-Stufe. Crawling, Indexierung, Regeln/JSON-LD und vorhandenes Wissen bleiben verfügbar. `wake()` und `llm_retry` ändern keine zeitliche Freigabe.

## Fenster und Zeitumstellung

Fenster sind am Start eingeschlossen und am Ende ausgeschlossen. `22:00–02:00` mit Montag erlaubt Montagabend und Dienstagfrüh, nicht Montagfrüh. Gleiche Zeiten bedeuten 24 Stunden ab dieser Ortszeit des ausgewählten Starttags. Die Zeitzone stammt aus dem gespeicherten Plan, niemals implizit aus der Server- oder Browserzone.

Die Prüfung verwendet tatsächliche Instants mit lokalen Uhrzeiten: nicht existierende Zeiten beim Frühjahrssprung haben keine Starts; beide Vorkommen wiederholter Zeiten im Herbst sind zulässig. Ein Fenster kann deshalb kürzer/länger ausfallen oder in der wiederholten Stunde erneut öffnen. Fenster regeln erlaubte Starts, keine zugesicherten Endzeiten. Die UI zeigt Zeitpunkte in der Browserzone an und nennt daneben die ausdrücklich gespeicherte Planzone.

Vor Arbeitsaufnahme und jedem Transportstart werden Plan und Freigaben geprüft. Eine bereits gestartete Anfrage darf nach Fensterende abschließen. Weitere Chunks und Format-Fallbacks warten. Zeitbedingte Zurückstellung erhöht weder Queue-Versuche noch Fehler-/Breaker-Zähler. Ein tatsächliches HTTP-400 zur Formatverhandlung ist ein Aufruf und zählt zum Abstand; sein Fallback erhält eine neue Freigabe. Die bekannte Format-Ablehnung wird bereits vor einem zurückgestellten Fallback gespeichert (bis Prozessneustart).

## Persistenz und Bestandsschutz

Der vollständige validierte Plan liegt in **einem** bestehenden Konfigurationsschlüssel `scoutro.kg.llm.schedule` als JSON. `PUT` ersetzt ihn vollständig, nach vollständiger Typ-/Wert-/Feldprüfung, durch einen Konfigurationsschreibvorgang. Keine allgemeine Schreibroute. Reine Zeitänderungen aktualisieren den gemeinsamen Controller ohne `KgRuntime.reopen()`: laufende Anfragen, Zähler und manuelle KG-Pause bleiben erhalten.

Zeitsteuerung gehört nicht zu Auswahl-, Cache-, Prompt-, Extraktor- oder Vokabularidentitäten. Kein Schema-/Extraktorversionswechsel und keine automatische Bestandsextraktion. Ungültige von Hand gespeicherte Pläne sperren nur LLM-Aufrufstarts und erscheinen im Status als ungültig; sie schalten nicht den ganzen Wissensgraphen ab.

Validierte Ergebnisse unfertiger Chunks werden unabhängig vom optionalen Cache in internen, begrenzten `kg_meta`-Segmenten abgelegt (höchstens 3000 Zeichen je Segment, 2 Mi Zeichen je validierter Chunk). Sie referenzieren den vollständigen bestehenden Cache-Kontextschlüssel einschließlich Modell, Prompt, Chunk und Kontext. Veröffentlichung entfernt den Fortsetzungsstand im selben Schreibvorgang wie die Queue-Arbeit. Fehlgeschlagene Dokumente behalten ihn für die ausdrücklich ausgelöste Retry-Aktion. Vollreset beseitigt ihn in begrenzten Schritten nach dem Leeren der Queue. Verwaiste Einträge werden beim Start und danach in kleinen Wartungsbatches bereinigt. Schreibschätzungen berücksichtigen auch die Entfernung bisheriger Checkpoints; geladen werden nur Chunks des aktuellen vollständigen Kontextschlüssels. Interne Fortsetzungsstände werden nicht durch öffentliche Fakten-/Historienexporte freigegeben; SQLite-Backups enthalten sie.

Bei verweigertem Checkpoint-Schreiben bleiben die bereits empfangenen Ergebnisse im begrenzten Arbeitsspeicher der aktiven Worker; neue Modellaufrufe warten mit `checkpoint_storage`, bis sie gesichert sind. Es wird kein bestehendes Wissen gelöscht. Ein harter Prozessverlust **während tatsächlich nicht beschreibbarer Speicherung** kann diesen noch nicht persistierbaren Fortsetzungsstand verlieren; eine ungesicherte Antwort wird nicht als dauerhaft gespeichert behauptet. Modellverarbeitung zwischen HTTP-Start und Empfang kann bei einem Prozessabbruch ebenfalls nicht gerettet werden.

Der letzte tatsächliche Aufrufstart liegt zusätzlich in `kg_meta`; Neustart setzt den Mindestabstand nicht zurück. Bei rückwärts korrigierter Uhr bleibt ein positiver Mindestabstand vorsichtig; bei Abstand 0 entsteht dadurch keine künstliche Wartezeit.

## Admin-API und Automation

Alle neuen Routen sind ausschließlich für Administratoren mit vorhandener Digest-Authentifizierung; mutierende JSON-Aufrufe durchlaufen dieselbe Cross-site-/Origin-Prüfung wie bestehende Admin-Aktionen. Keine Agenten-Grants, keine ausführbaren Agenten-MCP-Tools. Globale Routen nehmen keine Collection- oder anderen Query-Parameter an.

| Route | Körper / Antwort |
|---|---|
| `GET /scoutro/api/v1/kg/llm-schedule` | `plan`, `valid`, `validationError` |
| `PUT /scoutro/api/v1/kg/llm-schedule` | vollständiger Plan; Antwort zusätzlich `applied`, `reopened:false` |
| `POST /scoutro/api/v1/kg/llm-run` | `{"action":"start","maxDocuments":25,"maxRequests":100}` oder ausschließlich `{"action":"stop"}`; Antwort KG-Status |

Beispielplan:

```json
{"mode":"scheduled","days":[1,2,3,4,5],"from":"22:00","until":"02:00","zone":"Europe/Berlin","minStartSeconds":5}
```

`GET /kg/status` ergänzt `config.llmSchedule` und `llm.timing`: Plan/Gültigkeit, `windowOpen`, `nextAllowedStart` (Epoch-ms oder null), `lastActualStart`, `waitReason`, `runningRequests`, manueller Lauf mit Grenzen und Zählern. `pendingResultCheckpoints` beschreibt noch nicht gesicherte Antworten. Der nächste zeitlich erlaubte Start ist **keine Ressourcen- oder Fertigstellungszusage**. Leerer Rückstand, Zeitfenster, Mindestabstand, Breaker und Ressourcenpausen sind getrennt. Vorhandene `llm.processed.calls` bleiben logische Anfragen; das zusätzliche `llm.processed.requestStarts` zählt tatsächliche HTTP-Aufrufe einschließlich Format-Fallback.

## Upgrade und Rücknahme

Start auf der genannten Basis übernimmt ohne neuen Schlüssel unverändert die automatische zeitliche Freigabe. Keine Datenbankmigration, keine neue Extraktorversion; interne Metadaten sind additiv. Ein Rückwechsel auf die Basis ignoriert den neuen Konfigurationsschlüssel und verarbeitet wieder automatisch nach bisherigen Collection-/Schutzregeln: vor Rückwechsel bewusst LLM deaktivieren oder KG pausieren, wenn automatischer Betrieb nicht gewünscht ist. Die alte Version versteht die unabhängigen Fortsetzungsstände nicht und kann unfertige Chunks erneut aufrufen. Bereits veröffentlichtes Beobachtungswissen bleibt im unveränderten Schema erhalten. Ein verlustfreier Downgrade unfertiger Arbeit wird nicht zugesichert.

## Prüfungen

`ant scoutro-kg-llm-schedule-test` prüft Testuhren, lokale Modelltransporte, Fortsetzung und Admin-Verträge; `ant scoutro-kg-observations-test` sichert die Kette ab. Die isolierte echte Browser-/Peer-Prüfung wird unter `test/scoutro-api/kg-llm-schedule-live.py` gestartet; kontrollierte HTML-Fixtures und Modellclient ausschließlich lokal, temporäres DATA, kein produktiver Crawl. [Ausführungsnachweise und verbleibende Grenzen](SCOUTRO_LLM_SCHEDULE_VALIDATION.md).
