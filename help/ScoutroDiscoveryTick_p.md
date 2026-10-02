# Scoutro Discovery heartbeat

Internal administrator responder: `/ScoutroDiscoveryTick_p.json`.

GET exposes the existing YaCy transaction token in the `X-YaCy-Transaction-Token` response header and JSON (`transactionToken`) and no action. POST with `tick=1` and YaCy's valid transaction token wakes the short-lived Discovery coordinator. WorkTables supplies its owned `apicall_pk`; only the unique Scoutro heartbeat row may be re-recorded to advance next execution. The responder rejects other row IDs. It returns `accepted` and `reason` (e.g. disabled, paused, busy).

No PBF download, extraction, seed delay or crawl completion is awaited by the HTTP request. The persistent background run follows the global/job admission flags, existing run lock and YaCy backpressure. Global automation starts disabled, and this endpoint does not install or enable a scheduler. A manually disabled/deleted heartbeat is not repaired by this call.

See [Discovery Automation](ScoutroDiscovery_p.md) for configuration, recovery, admin API and side effects. Do not schedule one WorkTables row per job or expose this endpoint to agent tokens. Tests use disposable DATA only.
