package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.search.SwitchboardConstants;

/** Routes /v1/kg/status and /v1/kg/control, directly and through the servlet (authentication, cross-site rules). */
public class KnowledgeApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String[] STATUS = {"", "v1", "kg", "status"};
    private static final String[] CONTROL = {"", "v1", "kg", "control"};

    private KgRuntime running() {
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled()::get,
                System::currentTimeMillis, new KgTestSupport.Probe(), KgStore.SQLITE, false));
        r.open();
        assertEquals(KgRuntime.State.RUNNING, r.state());
        return r;
    }

    private static KnowledgeApi.Body body(final String json) {
        return () -> {
            try {
                return new JSONObject(json);
            } catch (final org.json.JSONException e) {
                throw new IllegalArgumentException(e);
            }
        };
    }

    private static int status(final KnowledgeApi api, final String method, final String[] parts, final String json)
            throws IOException {
        try {
            api.route(method, parts, body(json));
            return 200;
        } catch (final ApiException e) {
            return e.status();
        }
    }

    @Test
    public void statusWithoutRuntimeIsDisabled() throws Exception {
        final JSONObject s = new KnowledgeApi(() -> null).route("GET", STATUS, body("{}"));
        assertEquals("disabled", s.optString("state"));
        assertFalse(s.optBoolean("enabled", true));
        assertEquals(KgRuntime.STATUS_SCHEMA, s.optString("schema"));
    }

    @Test
    public void pauseAndResume() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            final JSONObject paused = api.route("POST", CONTROL, body("{\"action\":\"pause\"}"));
            assertFalse(paused.getJSONObject("storage").optBoolean("growthAllowed", true));
            final JSONObject resumed = api.route("POST", CONTROL, body("{\"action\":\"resume\"}"));
            assertTrue(resumed.getJSONObject("storage").optBoolean("growthAllowed"));
            assertEquals("running", api.route("GET", STATUS, body("{}")).optString("state"));
        } finally {
            r.close();
        }
    }

    @Test
    public void reconcileActionsNeedTheSyncAndMapTheirErrors() throws Exception {
        final KgRuntime r = running(); // the package-1 environment: no embedded Solr
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            for (final String action : new String[] {"reconcile", "confirm_reconcile"}) {
                try {
                    api.route("POST", CONTROL, body("{\"action\":\"" + action + "\"}"));
                    fail(action);
                } catch (final ApiException e) {
                    assertEquals(503, e.status());
                    assertEquals(KgException.SYNC_UNAVAILABLE, e.code());
                }
            }
            try {
                api.route("POST", CONTROL, body("{\"action\":\"llm_retry\"}"));
                fail("llm_retry without the LLM tier");
            } catch (final ApiException e) {
                assertEquals(409, e.status());
                assertEquals(KgException.LLM_UNAVAILABLE, e.code());
            }
        } finally {
            r.close();
        }
        final ApiException nothing = KnowledgeApi.toApi(new KgException(KgException.NOTHING_TO_CONFIRM, "none"));
        assertEquals(409, nothing.status());
        assertEquals(KgException.NOTHING_TO_CONFIRM, nothing.code());
        // the published contract lists exactly the actions the route accepts
        final org.json.JSONArray published = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                new java.io.File("htroot/env/scoutro/api/openapi.json").toPath()), StandardCharsets.UTF_8))
                .getJSONObject("components").getJSONObject("schemas").getJSONObject("KgControl").getJSONObject("properties")
                .getJSONObject("action").getJSONArray("enum");
        final java.util.List<String> enumValues = new java.util.ArrayList<>();
        for (int i = 0; i < published.length(); i++) {
            enumValues.add(published.getString(i));
        }
        assertEquals(enumValues, KnowledgeApi.ACTIONS);
    }

    /** Package 6.3: a backup is deleted by its name of GET /kg/backups only; a path is refused before anything is looked up. */
    @Test
    public void deletingABackupTakesItsNameNeverAPath() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            for (final String name : new String[] {"../graph.db", "graph.db", "/etc/passwd", "graph-20300101T000000Z.db/../../graph.db",
                    "backup/graph-20300101T000000Z.db", "graph-20300101T000000Z.json", "%2e%2e%2fgraph.db", ""}) {
                assertEquals(name, 400, status(api, "POST", CONTROL, Json.obj("action", "delete_backup", "backup", name).toString()));
            }
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"delete_backup\"}"));
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"delete_backup\",\"backup\":\"graph-20300101T000000Z.db\",\"path\":\"/tmp\"}"));
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"pause\",\"backup\":\"graph-20300101T000000Z.db\"}"));
            assertEquals("a well-formed name that is no backup", 404,
                    status(api, "POST", CONTROL, "{\"action\":\"delete_backup\",\"backup\":\"graph-20300101T000000Z.db\"}"));
            assertEquals(405, status(api, "GET", CONTROL, "{\"action\":\"delete_backup\",\"backup\":\"graph-20300101T000000Z.db\"}"));
        } finally {
            r.close();
        }
        assertEquals(503, KnowledgeApi.toApi(new KgException(KgException.BACKUP_DELETE_FAILED, "x")).status());
        assertTrue(KnowledgeApi.ACTIONS.containsAll(java.util.List.of("pause", "resume", "reconcile", "confirm_reconcile", "llm_retry",
                "backup", "restore")));
        final ApiException llm = KnowledgeApi.toApi(new KgException(KgException.LLM_UNAVAILABLE, "off"));
        assertEquals(409, llm.status());
        assertEquals(KgException.LLM_UNAVAILABLE, llm.code());
    }

    @Test
    public void validationMethodsAndStates() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"delete\"}"));
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"pause\",\"force\":true}"));
            assertEquals(405, status(api, "GET", CONTROL, "{}"));
            assertEquals(405, status(api, "POST", STATUS, "{}"));
            assertEquals(404, status(api, "GET", new String[] {"", "v1", "kg", "nothing"}, "{}"));
            assertEquals(404, status(api, "GET", new String[] {"", "v1", "kg"}, "{}"));
        } finally {
            r.close();
        }
        final KgRuntime disabled = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), k -> null,
                System::currentTimeMillis, new KgTestSupport.Probe(), KgStore.SQLITE, false));
        disabled.open();
        try {
            new KnowledgeApi(() -> disabled).route("POST", CONTROL, body("{\"action\":\"pause\"}"));
            fail();
        } catch (final ApiException e) {
            assertEquals(409, e.status());
            assertEquals(KgException.DISABLED, e.code());
        }
        final KgRuntime broken = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled()::get,
                System::currentTimeMillis, new KgTestSupport.Probe(), db -> {
                    throw new UnsatisfiedLinkError("missing");
                }, false));
        broken.open();
        try {
            new KnowledgeApi(() -> broken).route("POST", CONTROL, body("{\"action\":\"resume\"}"));
            fail();
        } catch (final ApiException e) {
            assertEquals(503, e.status());
            assertEquals(KgException.UNAVAILABLE, e.code());
            assertEquals(KgException.NATIVE_LIBRARY_UNAVAILABLE, e.toJson().getJSONObject("error").getJSONObject("details").optString("reason"));
        }
    }

    private static int read(final KnowledgeApi api, final String method, final String path, final String... query) throws IOException {
        final Map<String, String> q = new HashMap<>();
        for (int i = 0; i + 1 < query.length; i += 2) {
            q.put(query[i], query[i + 1]);
        }
        try {
            api.route(method, ("/v1/kg/" + path).split("/"), q, body("{}"));
            return 200;
        } catch (final ApiException e) {
            return e.status();
        }
    }

    @Test
    public void readRoutesValidateTheirParameters() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            final Map<String, String> none = new HashMap<>();
            final JSONObject page = api.route("GET", "/v1/kg/entities".split("/"), none, body("{}"));
            assertEquals("scoutro.kg.v1", page.getString("schema"));
            assertEquals(0L, page.getLong("total"));
            assertEquals(200, read(api, "GET", "entities", "q", "Muster", "type", "facility", "quality", "supported", "limit", "100",
                    "offset", "10000", "collection", "c1", "host", "https://www.muster.de/impressum"));
            assertEquals(200, read(api, "GET", "hosts/www.muster.de/entities", "limit", "5"));
            assertEquals(400, read(api, "GET", "entities", "sort", "name"));
            assertEquals(400, read(api, "GET", "entities", "limit", "0"));
            assertEquals(400, read(api, "GET", "entities", "limit", "101"));
            assertEquals(400, read(api, "GET", "entities", "offset", "10001"));
            assertEquals(400, read(api, "GET", "entities", "type", "person"));
            assertEquals(400, read(api, "GET", "entities", "quality", "conflicting"));
            assertEquals(400, read(api, "GET", "entities", "collection", "a b"));
            assertEquals(400, read(api, "GET", "entities", "q", "x".repeat(201)));
            assertEquals(400, read(api, "GET", "entities/not-an-id"));
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20)));
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/statements", "direction", "in"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/statements", "direction", "up"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/statements", "predicate", "estimated_salary"));
            // vocabulary 2 routes validate like the others
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/business"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/business", "include", "everything"));
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "depth", "2", "weak", "true"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "depth", "3"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "limit", "201"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "types", "knows"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "weak", "yes"));
            assertEquals(400, read(api, "GET", "compare"));
            assertEquals(400, read(api, "GET", "compare", "category", "Care Tagespflege"));
            assertEquals(200, read(api, "GET", "compare", "category", "care/tagespflege"));
            assertEquals(200, read(api, "GET", "derived", "kind", "suggested_customer"));
            assertEquals(400, read(api, "GET", "derived", "kind", "customer_of"));
            assertEquals(400, read(api, "GET", "derived", "entity", "not-an-id"));
            assertEquals(200, read(api, "GET", "facets"));
            assertEquals(400, read(api, "GET", "facets", "limit", "5"));
            // package 6.1: services of the same name, read-only groups and their rows; prices as a network layer
            assertEquals(200, read(api, "GET", "services"));
            assertEquals(200, read(api, "GET", "services", "q", "SAP", "category", "software/erp", "limit", "50", "offset", "10"));
            assertEquals(400, read(api, "GET", "services", "limit", "51"));
            assertEquals(400, read(api, "GET", "services", "name", "SAP"));
            assertEquals(400, read(api, "GET", "services", "category", "Software ERP"));
            assertEquals(400, read(api, "GET", "services/providers"));
            assertEquals(400, read(api, "GET", "services/providers", "q", "SAP"));
            assertEquals(404, read(api, "GET", "services/providers", "name", "SAP"));
            assertEquals(404, read(api, "GET", "services/other"));
            // package 6.2: the network of one name
            assertEquals(400, read(api, "GET", "services/network"));
            assertEquals(400, read(api, "GET", "services/network", "q", "SAP"));
            assertEquals(400, read(api, "GET", "services/network", "name", "SAP", "limit", "201"));
            assertEquals(400, read(api, "GET", "services/network", "name", "SAP", "include", "everything"));
            assertEquals(400, read(api, "GET", "services/network", "name", "SAP", "depth", "2"));
            assertEquals(404, read(api, "GET", "services/network", "name", "SAP", "include", "stale", "limit", "200", "offset", "5"));
            assertEquals(404, read(api, "GET", "services/network/more", "name", "SAP"));
            assertEquals(405, read(api, "POST", "services/network", "name", "SAP"));
            assertEquals(405, read(api, "POST", "services"));
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "prices", "true"));
            assertEquals(400, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/neighborhood", "prices", "yes"));
            assertEquals(200, read(api, "GET", "entities", "industry", "87.10", "category", "care/tagespflege", "audience", "b2b"));
            assertEquals(400, read(api, "GET", "entities", "industry", "8710"));
            assertEquals(404, read(api, "GET", "entities/kge_" + "a".repeat(20) + "/nothing"));
            assertEquals(404, read(api, "GET", "statements/kgs_" + "b".repeat(20)));
            assertEquals(404, read(api, "GET", "statements/kgs_" + "b".repeat(20) + "/evidence", "limit", "50"));
            assertEquals(400, read(api, "GET", "statements/kgs_" + "b".repeat(20) + "/evidence", "limit", "51"));
            assertEquals(404, read(api, "GET", "sources/AAAAAAhost01"));
            assertEquals(400, read(api, "GET", "sources/AAAA'host01"));
            assertEquals(404, read(api, "GET", "hosts/www.muster.de"));
            assertEquals(405, read(api, "POST", "entities"));
        } finally {
            r.close();
        }
        try {
            new KnowledgeApi(() -> null).route("GET", "/v1/kg/entities".split("/"), new HashMap<>(), body("{}"));
            fail("reads of a disabled graph");
        } catch (final ApiException e) {
            assertEquals(KgException.DISABLED, e.code());
        }
    }

    private static ApiException error(final KnowledgeApi api, final String path, final String... query) throws IOException {
        final Map<String, String> q = new HashMap<>();
        for (int i = 0; i + 1 < query.length; i += 2) {
            q.put(query[i], query[i + 1]);
        }
        try {
            api.route("GET", ("/v1/kg/" + path).split("/"), q, body("{}"));
            fail(path + " " + q + " accepted");
            return null;
        } catch (final ApiException e) {
            return e;
        }
    }

    @Test
    public void exportAndChangesValidateTheirParametersAndCursors() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            final JSONObject page = api.route("GET", "/v1/kg/export".split("/"), new HashMap<>(), body("{}"));
            assertTrue(page.getBoolean("complete"));
            assertEquals(page.getString("epoch") + ":" + page.getLong("as_of_seq"), page.getString("next_changes"));
            assertEquals(200, read(api, "GET", "export", "limit", "200", "include", "evidence", "collection", "c1"));
            assertEquals(200, read(api, "GET", "changes", "limit", "1000"));
            assertEquals(200, read(api, "GET", "changes", "limit", "100", "expand", "true", "cursor", page.getString("next_changes")));
            assertEquals(400, read(api, "GET", "export", "limit", "201"));
            assertEquals(400, read(api, "GET", "export", "include", "secrets"));
            assertEquals(400, read(api, "GET", "export", "offset", "1"));
            assertEquals(400, read(api, "GET", "changes", "limit", "101", "expand", "true"));
            assertEquals(400, read(api, "GET", "changes", "limit", "1001"));
            assertEquals(400, read(api, "GET", "changes", "expand", "yes"));
            assertEquals(404, read(api, "GET", "export/more"));
            assertEquals(404, read(api, "GET", "changes/more"));
            assertEquals(405, read(api, "POST", "export"));
            assertEquals(KgException.INVALID_CURSOR, error(api, "export", "cursor", "nonsense").code());
            assertEquals(KgException.INVALID_CURSOR, error(api, "changes", "cursor", "x".repeat(81)).code());
            final ApiException epoch = error(api, "changes", "cursor", "0123456789abcdef:0");
            assertEquals(410, epoch.status());
            assertEquals(KgException.EPOCH_CHANGED, epoch.code());
            assertEquals("/scoutro/api/v1/kg/export", epoch.toJson().getJSONObject("error").getJSONObject("details").getString("full_sync"));
            final ApiException agent = assertThrows410(new KnowledgeRead(() -> r, KnowledgeRead.AGENT_BASE), "0123456789abcdef:0:e0");
            assertEquals("/scoutro/api/agent/v1/kg/export", agent.toJson().getJSONObject("error").getJSONObject("details").getString("full_sync"));
        } finally {
            r.close();
        }
    }

    private static ApiException assertThrows410(final KnowledgeRead read, final String cursor) throws Exception {
        final Map<String, String> q = new HashMap<>();
        q.put("cursor", cursor);
        try {
            read.route("GET", java.util.List.of("export"), q, java.util.List.of("c1"));
            fail("cursor of another epoch accepted");
            return null;
        } catch (final ApiException e) {
            assertEquals(410, e.status());
            return e;
        }
    }

    @Test
    public void theDownloadStreamsNdjsonOrJsonAndAnswersErrorsBeforeWriting() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            final String[] opened = new String[2];
            final java.io.StringWriter nd = new java.io.StringWriter();
            api.download(new HashMap<>(), (type, name) -> {
                opened[0] = type;
                opened[1] = name;
                return nd;
            });
            assertTrue(opened[0].startsWith("application/x-ndjson"));
            assertTrue(opened[1], opened[1].matches("scoutro-knowledge-all-\\d{8}T\\d{6}Z\\.ndjson"));
            final String[] lines = nd.toString().split("\n");
            assertEquals("header", new JSONObject(lines[0]).getString("record"));
            assertEquals("trailer", new JSONObject(lines[lines.length - 1]).getString("record"));
            assertTrue(new JSONObject(lines[lines.length - 1]).getBoolean("complete"));
            final java.io.StringWriter js = new java.io.StringWriter();
            final Map<String, String> q = new HashMap<>();
            q.put("format", "json");
            q.put("collection", "c1");
            q.put("include", "evidence");
            api.download(q, (type, name) -> {
                assertTrue(type.startsWith("application/json"));
                assertTrue(name.startsWith("scoutro-knowledge-c1-") && name.endsWith(".json"));
                return js;
            });
            final JSONObject whole = new JSONObject(js.toString());
            assertEquals("c1", whole.getJSONObject("header").getString("collection"));
            assertEquals(0, whole.getJSONArray("items").length());
            assertTrue(whole.getJSONObject("trailer").getBoolean("complete"));
            for (final String[] bad : new String[][] {{"format", "csv"}, {"include", "all"}, {"collection", "a b"}, {"cursor", "x"}}) {
                final Map<String, String> b = new HashMap<>();
                b.put(bad[0], bad[1]);
                try {
                    api.download(b, (type, name) -> {
                        throw new AssertionError("opened before the parameters were checked");
                    });
                    fail(bad[0]);
                } catch (final ApiException e) {
                    assertEquals(400, e.status());
                }
            }
        } finally {
            r.close();
        }
        try {
            new KnowledgeApi(() -> null).download(new HashMap<>(), (type, name) -> {
                throw new AssertionError("opened for a disabled graph");
            });
            fail("download of a disabled graph");
        } catch (final ApiException e) {
            assertEquals(KgException.DISABLED, e.code());
        }
    }

    @Test
    public void backupRoutesValidateAndListTheFiles() throws Exception {
        final KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"restore\"}"));
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"restore\",\"backup\":\"../graph.db\"}"));
            assertEquals(400, status(api, "POST", CONTROL, "{\"action\":\"backup\",\"backup\":\"graph-20300101T000000Z.db\"}"));
            final ApiException missing = errorOf(api, "{\"action\":\"restore\",\"backup\":\"graph-20300101T000000Z.db\"}");
            assertEquals(404, missing.status());
            assertEquals(KgException.BACKUP_NOT_FOUND, missing.code());
            final JSONObject list = api.route("GET", "/v1/kg/backups".split("/"), new HashMap<>(), body("{}"));
            assertEquals("scoutro.kg.backup.v1", list.getString("schema"));
            assertEquals(0, list.getJSONArray("items").length());
            assertEquals("DATA/SCOUTRO/knowledge/backup", list.getString("dir"));
            assertEquals(400, read(api, "GET", "backups", "x", "1"));
            assertEquals(405, read(api, "POST", "backups"));
            try {
                api.backupFile("graph-20300101T000000Z.db");
                fail("download of a missing backup");
            } catch (final ApiException e) {
                assertEquals(404, e.status());
            }
            try {
                api.backupFile("../../yacy.conf");
                fail("download outside the backup directory");
            } catch (final ApiException e) {
                assertEquals(404, e.status());
            }
        } finally {
            r.close();
        }
    }

    private static ApiException errorOf(final KnowledgeApi api, final String json) throws IOException {
        try {
            api.route("POST", CONTROL, body(json));
            fail(json + " accepted");
            return null;
        } catch (final ApiException e) {
            return e;
        }
    }

    // ------------------------------------------------------- through the servlet

    private static final class Exchange {
        int status;
        final Map<String, String> headers = new HashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        JSONObject json() throws Exception {
            return new JSONObject(new String(this.body.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static Exchange call(final String method, final String path, final boolean admin, final String contentType,
            final String origin, final String body) throws Exception {
        final Exchange ex = new Exchange();
        final byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        final ServletInputStream sin = new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(final ReadListener l) {}
            @Override public int read() { return in.read(); }
        };
        final ServletOutputStream sout = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(final WriteListener l) {}
            @Override public void write(final int b) { ex.body.write(b); }
        };
        final HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(KnowledgeApiTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getMethod": return method;
                        case "getPathInfo": return path;
                        case "isUserInRole": return admin && SwitchboardConstants.ADMIN_ACCOUNT_ROLE.equals(args[0]);
                        case "getContentType": return contentType;
                        case "getHeader":
                            if ("Origin".equals(args[0])) return origin;
                            if ("Host".equals(args[0])) return "scoutro.local:8090";
                            return null;
                        case "getInputStream": return sin;
                        case "getParameterMap": return new HashMap<String, String[]>();
                        default: return null;
                    }
                });
        final HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(KnowledgeApiTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "setStatus": ex.status = (Integer) args[0]; return null;
                        case "getStatus": return ex.status;
                        case "setHeader": ex.headers.put((String) args[0], (String) args[1]); return null;
                        case "getOutputStream": return sout;
                        default: return null;
                    }
                });
        new ScoutroApiServlet().service(req, resp);
        return ex;
    }

    @Test
    public void servletRequiresTheAdministrator() throws Exception {
        assertEquals(401, call("GET", "/v1/kg/status", false, null, null, null).status);
        assertEquals(401, call("POST", "/v1/kg/control", false, "application/json", null, "{\"action\":\"pause\"}").status);
        final Exchange ok = call("GET", "/v1/kg/status", true, null, null, null);
        assertEquals(200, ok.status);
        assertEquals(KgRuntime.STATUS_SCHEMA, ok.json().optString("schema"));
        assertEquals("no-store", ok.headers.get("Cache-Control"));
    }

    @Test
    public void servletRequiresTheAdministratorForReads() throws Exception {
        for (final String path : new String[] {"/v1/kg/entities", "/v1/kg/entities/kge_aaaaaaaaaaaaaaaaaaaa",
                "/v1/kg/statements/kgs_aaaaaaaaaaaaaaaaaaaa/evidence", "/v1/kg/hosts/www.muster.de/entities", "/v1/kg/sources/AAAAAAhost01",
                "/v1/kg/export", "/v1/kg/changes", "/v1/kg/export/download", "/v1/kg/backups", "/v1/kg/backups/graph-20300101T000000Z.db"}) {
            assertEquals(path, 401, call("GET", path, false, null, null, null).status);
        }
        assertEquals(409, call("GET", "/v1/kg/entities", true, null, null, null).status);
        assertEquals(409, call("GET", "/v1/kg/export/download", true, null, null, null).status);
        assertEquals(405, call("POST", "/v1/kg/export/download", true, "application/json", null, "{}").status);
        assertEquals(404, call("GET", "/v1/kg/export/download/x", true, null, null, null).status);
    }

    @Test
    public void servletAppliesTheCrossSiteRulesToControl() throws Exception {
        assertEquals(415, call("POST", "/v1/kg/control", true, "text/plain", null, "{\"action\":\"pause\"}").status);
        assertEquals(403, call("POST", "/v1/kg/control", true, "application/json", "https://evil.example",
                "{\"action\":\"pause\"}").status);
        final Exchange sameOrigin = call("POST", "/v1/kg/control", true, "application/json", "http://scoutro.local:8090",
                "{\"action\":\"pause\"}");
        // the runtime is not started in this test, so the graph counts as disabled
        assertEquals(409, sameOrigin.status);
        assertEquals(KgException.DISABLED, sameOrigin.json().getJSONObject("error").optString("code"));
        assertEquals(405, call("GET", "/v1/kg/control", true, null, null, null).status);
    }

    // --------------------------------------------------------- knowledge prompt

    private static final String[] PROMPT = {"", "v1", "kg", "prompt"};
    static final String CUSTOM = net.yacy.scoutro.knowledge.extract.LlmExtractor.SYSTEM_PROMPT
            .replace("for a knowledge graph", "for a knowledge graph of care providers");

    private static ApiException refused(final KnowledgeApi api, final String json) throws IOException {
        try {
            api.route("POST", PROMPT, body(json));
        } catch (final ApiException e) {
            return e;
        }
        throw new AssertionError("accepted: " + json);
    }

    @Test
    public void theKnowledgePromptLifecycle() throws Exception {
        final String def = net.yacy.scoutro.knowledge.extract.LlmExtractor.SYSTEM_PROMPT;
        final String defHash = net.yacy.scoutro.knowledge.extract.LlmExtractor.PROMPT_HASH;
        KgRuntime r = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> r);
            // nothing activated: the compiled-in default
            JSONObject p = api.route("GET", PROMPT, body("{}"));
            assertEquals(0, p.getInt("activeVersion"));
            assertEquals(defHash, p.getString("activeHash"));
            assertEquals("default", p.getString("source"));
            assertTrue(p.isNull("modifiedAt"));
            assertFalse(p.getBoolean("differsFromDefault"));
            assertEquals(def, p.getString("text"));
            assertEquals(def, p.getJSONObject("default").getString("text"));
            assertEquals(defHash, p.getJSONObject("default").getString("hash"));
            assertEquals("manual", p.getString("reexamination"));
            assertEquals(0, p.getJSONArray("history").length());
            // validate stores nothing
            final JSONObject v = api.route("POST", PROMPT, body(new JSONObject().put("action", "validate").put("text", CUSTOM).toString()));
            assertTrue(v.getBoolean("valid"));
            assertTrue(v.getBoolean("differsFromActive") && v.getBoolean("differsFromDefault"));
            assertEquals(net.yacy.scoutro.knowledge.extract.LlmExtractor.promptHash(CUSTOM), v.getString("hash"));
            final JSONObject bad = api.route("POST", PROMPT, body("{\"action\":\"validate\",\"text\":\"too short\"}"));
            assertFalse(bad.getBoolean("valid"));
            assertEquals("prompt_too_short", bad.getString("reason"));
            assertEquals(0, api.route("GET", PROMPT, body("{}")).getInt("activeVersion"));
            // activate: revision 1, custom, a new hash, the history without text
            p = api.route("POST", PROMPT, body(new JSONObject().put("action", "activate").put("text", CUSTOM).put("expectedRevision", 0).toString()));
            assertTrue(p.getBoolean("changed"));
            assertEquals(1, p.getInt("activeVersion"));
            assertEquals("custom", p.getString("source"));
            assertTrue(p.getBoolean("differsFromDefault"));
            assertEquals(net.yacy.scoutro.knowledge.extract.LlmExtractor.promptHash(CUSTOM), p.getString("activeHash"));
            assertTrue(p.getLong("modifiedAt") > 0L);
            assertEquals(CUSTOM, p.getString("text"));
            assertEquals(1, p.getJSONArray("history").length());
            assertFalse(p.getJSONArray("history").toString().contains("You extract"));
            // the same text again changes nothing; a stale revision is a conflict; an invalid text is refused
            p = api.route("POST", PROMPT, body(new JSONObject().put("action", "activate").put("text", CUSTOM).toString()));
            assertFalse(p.getBoolean("changed"));
            assertEquals(1, p.getInt("activeVersion"));
            ApiException e = refused(api, new JSONObject().put("action", "activate").put("text", CUSTOM + " More.").put("expectedRevision", 0).toString());
            assertEquals(409, e.status());
            assertEquals(KgException.PROMPT_REVISION_CONFLICT, e.code());
            e = refused(api, new JSONObject().put("action", "activate").put("text", CUSTOM + " api_key=abcdef123456").toString());
            assertEquals(422, e.status());
            assertEquals(KgException.PROMPT_INVALID, e.code());
            assertEquals("prompt_secret_like", e.toJson().getJSONObject("error").getJSONObject("details").getString("reason"));
            assertEquals("nothing changed", 1, api.route("GET", PROMPT, body("{}")).getInt("activeVersion"));
            // unknown fields and actions
            assertEquals(400, refused(api, "{\"action\":\"activate\",\"text\":\"x\",\"source\":\"custom\"}").status());
            assertEquals(400, refused(api, "{\"action\":\"reset\",\"text\":\"x\"}").status());
            assertEquals(400, refused(api, "{\"action\":\"validate\",\"text\":\"x\",\"expectedRevision\":1}").status());
            assertEquals(400, refused(api, "{\"action\":\"delete\"}").status());
            assertEquals(400, refused(api, "{\"action\":\"activate\"}").status());
            assertEquals(400, refused(api, "{\"action\":\"activate\",\"text\":42}").status());
            assertEquals(400, refused(api, "{\"action\":\"reset\",\"expectedRevision\":\"1\"}").status());
            try {
                api.route("GET", PROMPT, java.util.Map.of("x", "1"), body("{}"));
                fail("a parameter");
            } catch (final ApiException x) {
                assertEquals(400, x.status());
            }
            assertEquals(405, status(api, "DELETE", PROMPT, "{}"));
        } finally {
            r.close();
        }
        // stored in the graph: a restart keeps the active version
        final KgRuntime again = running();
        try {
            final KnowledgeApi api = new KnowledgeApi(() -> again);
            JSONObject p = api.route("GET", PROMPT, body("{}"));
            assertEquals(1, p.getInt("activeVersion"));
            assertEquals(CUSTOM, p.getString("text"));
            // reset: the default as a new revision; the history keeps both
            p = api.route("POST", PROMPT, body("{\"action\":\"reset\",\"expectedRevision\":1}"));
            assertTrue(p.getBoolean("changed"));
            assertEquals(2, p.getInt("activeVersion"));
            assertEquals("default", p.getString("source"));
            assertEquals(defHash, p.getString("activeHash"));
            assertFalse(p.getBoolean("differsFromDefault"));
            assertEquals(2, p.getJSONArray("history").length());
            assertFalse("a second reset changes nothing", api.route("POST", PROMPT, body("{\"action\":\"reset\"}")).getBoolean("changed"));
            // an activation is an event, no re-examination
            final java.util.List<String> events = new java.util.ArrayList<>();
            final org.json.JSONArray recent = again.status().getJSONArray("events");
            for (int i = 0; i < recent.length(); i++) {
                events.add(recent.getJSONObject(i).optString("code"));
            }
            assertEquals(2L, events.stream().filter("llm_prompt_activated"::equals).count());
            assertFalse(events.contains("llm_extractor_changed"));
        } finally {
            again.close();
        }
        // without a running graph
        try {
            new KnowledgeApi(() -> null).route("GET", PROMPT, body("{}"));
            fail("disabled");
        } catch (final ApiException x) {
            assertEquals(409, x.status());
            assertEquals(KgException.DISABLED, x.code());
        }
    }

    @Test
    public void thePromptRouteIsForTheAdministratorOnly() throws Exception {
        assertEquals(401, call("GET", "/v1/kg/prompt", false, null, null, null).status);
        assertEquals(401, call("POST", "/v1/kg/prompt", false, "application/json", null, "{\"action\":\"reset\"}").status);
        assertEquals(415, call("POST", "/v1/kg/prompt", true, "text/plain", null, "{\"action\":\"reset\"}").status);
        assertEquals(403, call("POST", "/v1/kg/prompt", true, "application/json", "https://evil.example", "{\"action\":\"reset\"}").status);
        // a prompt of the maximum length with multi-byte characters fits the body limit of this route
        final String longText = "Ä".repeat(net.yacy.scoutro.knowledge.extract.KnowledgePrompt.MAX_CHARS);
        final Exchange big = call("POST", "/v1/kg/prompt", true, "application/json", null,
                new JSONObject().put("action", "validate").put("text", longText).toString());
        assertEquals("not 413: the graph is not started here", 409, big.status);
        assertEquals("the other routes keep their limit", 413, call("POST", "/v1/kg/control", true, "application/json", null,
                new JSONObject().put("action", "pause").put("x", longText + longText).toString()).status);
    }
}
