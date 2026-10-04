/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Conservative deterministic DE/EN routing. Content questions keep the ordinary chat path. */
public final class SystemQuestions {
    private SystemQuestions() { }
    private static final Pattern HOST = Pattern.compile("^(?:analysiere|analyze|analyse) (?:den )?host\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    public static final class Plan {
        final String kind, action, host;
        final List<String> path;
        final boolean german;
        Plan(final String kind, final String action, final String path, final String host, final boolean german) {
            this.kind = kind; this.action = action; this.path = Arrays.asList(path.split("/")); this.host = host; this.german = german;
        }
        Map<String, String> query(final Map<String, String> input) throws ApiException {
            for (final String key : input.keySet()) if (!key.equals("q") && !key.equals("collection"))
                throw ApiException.invalid(key, "System questions accept only q and collection.");
            if (this.action == null) throw new ApiException(400, "unsupported_system_question", "This system question has no supported structured action; no web answer is substituted.");
            final Map<String, String> q = new LinkedHashMap<>();
            if (input.containsKey("collection")) q.put("collection", input.get("collection"));
            if (this.host != null) HostInput.parse(this.host); // validate before dispatch, never resolve DNS
            return q;
        }
    }
    public static Plan detect(final String text) {
        if (text == null) return null;
        final String raw = text.trim();
        final boolean explicit = raw.toLowerCase(Locale.ROOT).startsWith("/system");
        final String clean = (explicit ? raw.substring(7).trim() : raw).replaceAll("[?.!]+$", "").replaceAll("\\s+", " ");
        final String s = clean.toLowerCase(Locale.ROOT);
        final boolean de = s.matches("^(wie|welche|ist|analysiere|zeige).*" );
        if (s.matches("(?:wie viele (?:domains/hosts|domains|hosts|seiten|dokumente)(?: (?:sind|gibt es|haben wir)(?: (?:im system|in scoutro|hier))?)?|how many (?:domains|hosts|pages|documents)(?: (?:are there|are in (?:the system|scoutro)))?)"))
            return new Plan("index", "index.metrics", "index/metrics", null, de);
        if (s.matches("(?:welche (?:collections|sammlungen)(?: (?:gibt es|sind (?:im system|verfügbar)))?|(?:which|what) collections(?: (?:are available|are there|exist))?|(?:zeige|list) collections)"))
            return new Plan("collections", "collections.list", "collections", null, de);
        if (s.matches("(?:welche crawls (?:laufen|sind aktiv)|(?:which|what) crawls (?:are running|are active)|(?:crawlstatus|crawl status|running crawls))"))
            return new Plan("crawls", "crawl.list", "crawls", null, de);
        if (s.matches("(?:ist discovery aktiv|is discovery active|discovery(?:status| status))"))
            return new Plan("discovery", "discovery.status", "discovery/status", null, de);
        final Matcher host = HOST.matcher(clean);
        if (host.matches()) return new Plan("host", "seo.read", "seo/hosts/" + host.group(1), host.group(1), de);
        if (explicit || (s.matches("^(?:wie viele|how many) (?:domains|hosts|seiten|pages|documents|dokumente).*" )
                && !s.matches(".*(?:buch|book|pdf).*")) || s.contains("im system") || s.contains("in scoutro") || s.contains("in the system"))
            return new Plan("unsupported", null, "", null, de);
        return null;
    }
    static Plan require(final String text) throws ApiException {
        if (text == null || text.length() > 1000 || text.codePoints().anyMatch(Character::isISOControl))
            throw ApiException.invalid("q", "Use a system question of at most 1000 characters.");
        final Plan p = detect(text);
        if (p == null) throw new ApiException(400, "not_system_question", "This endpoint accepts system questions, not content search.");
        return p;
    }
    static AgentApi.Request request(final Plan plan, final AgentApi.Request r) throws ApiException {
        final Map<String, String> query = plan.query(r.query);
        final List<String> path = plan.host == null ? plan.path : Arrays.asList("seo", "hosts", HostInput.parse(plan.host).host);
        return new AgentApi.Request("GET", path, query, r.authorization, r.client, r.idempotencyKey, r.body);
    }
    static JSONObject admin(final Plan plan, final Map<String, String> input, final ScoutroActions actions) throws ApiException {
        final Map<String, String> q = plan.query(input);
        final List<String> collections = SeoAnalysis.adminCollections(q);
        final JSONObject facts;
        switch (plan.kind) {
            case "index": facts = IndexMetrics.current().read(q, collections); break;
            case "collections": facts = actions.collections(); break;
            case "crawls": facts = actions.crawlList(); break;
            case "discovery": facts = net.yacy.scoutro.discovery.DiscoveryService.get().status(); break;
            case "host": facts = SeoAnalysis.current().route(Arrays.asList("hosts", HostInput.parse(plan.host).host), q, collections); break;
            default: throw new ApiException(400, "unsupported_system_question", "No structured action for this question.");
        }
        return answer(plan, facts);
    }
    static JSONObject answer(final Plan p, final JSONObject data) throws ApiException {
        try {
            final StringBuilder text = new StringBuilder(p.german ? "Scoutro-Systemdaten\n\n" : "Scoutro system data\n\n");
            switch (p.kind) {
                case "index":
                    text.append("Index: ").append(data.getLong("documents")).append(p.german ? " Dokumente, davon " : " documents, including ")
                        .append(data.getLong("pages")).append(p.german ? " erfolgreiche Seiten auf " : " successful pages on ")
                        .append(data.getLong("hosts")).append(p.german ? " Hosts.\n" : " hosts.\n");
                    text.append(p.german ? "Erfolgreich: HTTP 200 ohne Ladefehler. Hosts sind keine registrierbaren Domains.\n" :
                        "Successful: HTTP 200 without load errors. Hosts are not registrable domains.\n");
                    final org.json.JSONArray scope = data.optJSONArray("collections");
                    text.append(p.german ? "Collections: " : "Collections: ").append(scope == null ?
                        (p.german ? "alle freigegebenen" : "all authorized") : scope.toString());
                    break;
                case "collections": {
                    final org.json.JSONArray rows = data.getJSONArray("collections");
                    text.append(p.german ? "Freigegebene Collections:\n" : "Authorized collections:\n");
                    if (rows.length() == 0) text.append(p.german ? "Keine sichtbaren Collections." : "No visible collections.");
                    for (int i = 0; i < rows.length(); i++) {
                        final JSONObject row = rows.getJSONObject(i);
                        text.append("\n- ").append(plain(row.getString("id"))).append(": ");
                        text.append(row.isNull("documents") ? (p.german ? "Dokumentzahl unbekannt" : "document count unknown") :
                            row.getLong("documents") + (p.german ? " Dokumente" : " documents"));
                    }
                    break;
                }
                case "crawls": {
                    final org.json.JSONArray rows = data.getJSONArray("crawls");
                    text.append(p.german ? "Crawlstatus im sichtbaren Besitzbereich (Agent-Liste: maximal 100 Einträge):\n" :
                        "Crawl status in the visible ownership scope (agent list: up to 100 entries):\n");
                    if (rows.length() == 0) text.append(p.german ? "Keine sichtbaren Crawl-Einträge." : "No visible crawl entries.");
                    for (int i = 0; i < rows.length(); i++) {
                        final JSONObject row = rows.getJSONObject(i);
                        text.append("\n- ").append(plain(row.optString("name", row.optString("host", row.optString("id")))))
                            .append(": ").append(plain(row.getString("state")));
                        if (!row.isNull("collection")) text.append(" (Collection: ").append(plain(row.getString("collection"))).append(')');
                    }
                    break;
                }
                case "discovery":
                    text.append("Discovery: ").append(plain(data.getString("automation_status"))).append(".\n")
                        .append(p.german ? "Batch läuft: " : "Batch running: ").append(data.getBoolean("running") ?
                            (p.german ? "ja" : "yes") : (p.german ? "nein" : "no"));
                    break;
                case "host": {
                    text.append("Host: ").append(plain(data.getString("host"))).append(".\n")
                        .append(p.german ? "Sichtbare indexierte Seiten: " : "Visible indexed pages: ").append(data.getLong("indexed_pages")).append(".\n");
                    final JSONObject content = data.optJSONObject("content"), citation = data.optJSONObject("citation");
                    if (!data.getBoolean("indexed")) text.append(p.german ? "Keine Hostanalyse im freigegebenen Index verfügbar." : "No host analysis available in the authorized index.");
                    else {
                        for (final String key : Arrays.asList("title_pages", "description_pages", "h1_pages"))
                            text.append("\n- ").append(key.replace("_", " ")).append(": ").append(known(content, key, p.german));
                        for (final String key : Arrays.asList("processed_pages", "pending_pages", "unavailable_pages", "unknown_pages", "references_total"))
                            text.append("\n- ").append(key.replace("_", " ")).append(": ").append(known(citation, key, p.german));
                        text.append(p.german ? "\nReferenzen stammen aus finalisierten lokalen Indexfeldern; historische Vollständigkeit ist unbekannt." :
                            "\nReferences come from finalized local index fields; historical completeness is unknown.");
                    }
                    break;
                }
                default: throw new ApiException(400, "unsupported_system_question", "No structured action for this question.");
            }
            return Json.obj("kind", p.kind, "action", p.action, "observedAt", Instant.now().toString(), "facts", data, "answer", text.toString());
        } catch (final org.json.JSONException e) {
            throw new ApiException(503, "system_data_unavailable", "Structured action data is incomplete; no substitute answer is returned.");
        }
    }
    private static String known(final JSONObject data, final String key, final boolean german) {
        return data == null || !data.has(key) || data.isNull(key) ? (german ? "unbekannt" : "unknown") : String.valueOf(data.opt(key));
    }
    private static String plain(final String input) {
        return input.replaceAll("[\\p{Cntrl}]", " ").replace("<", "&lt;").replace(">", "&gt;");
    }
}
