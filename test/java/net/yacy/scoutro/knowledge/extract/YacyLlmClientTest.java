package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM;

/**
 * The LLM tier's use of Scoutro's LLM configuration against a local fake
 * endpoint: read timeout, HTTP 400 for the schema, unreachable endpoint,
 * oversized response, no model selected. Nothing leaves 127.0.0.1.
 */
public class YacyLlmClientTest {

    private HttpServer server;
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> mode = new AtomicReference<>("ok");

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            final JSONObject body;
            try {
                body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (final Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            this.requests.add(body);
            String content = "{\"entities\":[],\"claims\":[]}";
            int status = 200;
            switch (this.mode.get()) {
                case "slow":
                    try {
                        Thread.sleep(3000L);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                case "noschema":
                    if (body.has("response_format")) {
                        status = 400;
                    }
                    break;
                case "huge":
                    content = "x".repeat(YacyLlmClient.MAX_RESPONSE_CHARS + 10);
                    break;
                default:
                    break;
            }
            final byte[] out = status != 200 ? new byte[0]
                    : ("{\"choices\":[{\"message\":{\"content\":" + JSONObject.quote(content) + "},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (final IOException e) {
                // the client gave up (timeout test)
            }
            exchange.close();
        });
        this.server.start();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private LLM.LLMModel model(final String hoststub) {
        return new LLM.LLMModel(new LLM(hoststub, "", 512, LLM.LLMType.OPENAI), "fixture", false, false);
    }

    private String stub() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    @Test
    public void answersWithTheSchemaAndTheConfiguredLimit() throws Exception {
        final YacyLlmClient c = new YacyLlmClient(() -> model(stub()));
        assertEquals("OPENAI/fixture", c.model());
        assertEquals("{\"entities\":[],\"claims\":[]}", c.complete("sys", "user", LlmExtractor.SCHEMA, 5000L));
        final JSONObject req = this.requests.get(0);
        assertEquals(512, req.getInt("max_tokens"));
        assertTrue(req.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"));
        assertFalse("no tools are offered", req.has("tools"));
        assertEquals("system", req.getJSONArray("messages").getJSONObject(0).getString("role"));
    }

    @Test
    public void readTimeoutEndsTheCall() throws Exception {
        this.mode.set("slow");
        final YacyLlmClient c = new YacyLlmClient(() -> model(stub()));
        final long t0 = System.currentTimeMillis();
        try {
            c.complete("sys", "user", LlmExtractor.SCHEMA, 500L);
            fail("no timeout");
        } catch (final SocketTimeoutException e) {
            assertTrue(System.currentTimeMillis() - t0 < 2500L);
        }
    }

    @Test
    public void anEndpointWithoutSchemaSupportIsAskedWithoutIt() throws Exception {
        this.mode.set("noschema");
        final YacyLlmClient c = new YacyLlmClient(() -> model(stub()));
        assertEquals("{\"entities\":[],\"claims\":[]}", c.complete("sys", "user", LlmExtractor.SCHEMA, 5000L));
        assertEquals(2, this.requests.size());
        assertTrue(this.requests.get(0).has("response_format"));
        assertFalse(this.requests.get(1).has("response_format"));
        c.complete("sys", "user", LlmExtractor.SCHEMA, 5000L);
        assertEquals("remembered: one request", 3, this.requests.size());
    }

    @Test
    public void unreachableOversizedAndUnselectedFail() throws Exception {
        final int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        try {
            new YacyLlmClient(() -> model("http://127.0.0.1:" + closed)).complete("sys", "user", null, 2000L);
            fail("unreachable");
        } catch (final IOException e) {
            // a transport failure for the breaker
        }
        this.mode.set("huge");
        try {
            new YacyLlmClient(() -> model(stub())).complete("sys", "user", null, 5000L);
            fail("oversized response accepted");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exceeds"));
        }
        final YacyLlmClient none = new YacyLlmClient(() -> null);
        assertNull(none.model());
        try {
            none.complete("sys", "user", null, 1000L);
            fail("no model");
        } catch (final IOException e) {
            assertTrue(e.getMessage().contains("knowledge"));
        }
        assertNull(LlmClient.NONE.model());
    }
    @Test public void rejectedFormatFallbackRequiresItsOwnAdmissionAndRemembersRejection()throws Exception {
        this.mode.set("noschema");YacyLlmClient c=new YacyLlmClient(()->model(stub()));
        java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();
        try{c.complete("sys","user",new JSONObject("{\"type\":\"object\"}"),5000,"auto",()->{
            if(starts.incrementAndGet()>1)throw new net.yacy.scoutro.knowledge.sync.LlmTiming.Deferred("minimum_interval");
            return ()->{};
        });fail();}catch(net.yacy.scoutro.knowledge.sync.LlmTiming.Deferred e){assertEquals("minimum_interval",e.reason);}
        assertEquals(1,this.requests.size());assertTrue(this.requests.get(0).has("response_format"));
        c.complete("sys","user",new JSONObject("{\"type\":\"object\"}"),5000,"auto",()->{starts.incrementAndGet();return ()->{};});
        assertEquals(2,this.requests.size());assertFalse(this.requests.get(1).has("response_format"));
    }

}
