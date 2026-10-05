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
}
