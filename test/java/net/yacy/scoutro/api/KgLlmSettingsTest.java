package net.yacy.scoutro.api;

import static org.junit.Assert.*;

import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.LlmSchedule;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.util.*;

public class KgLlmSettingsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static class Memory implements KgLlmSettings.Settings {
        String value;
        int writes;

        public String get() {
            return value;
        }

        public void set(String value) {
            this.value = value;
            writes++;
        }
    }

    @Test
    public void completePlanValidatedBeforeSingleWriteAndPersistsAcrossInstance() throws Exception {
        Memory m = new Memory();
        KgLlmSettings settings = new KgLlmSettings(m, () -> null);
        assertEquals("automatic", settings.read().getJSONObject("plan").getString("mode"));
        JSONObject p = LlmSchedule.defaults().json();
        p.put("mode", "scheduled");
        p.put("zone", "Europe/Berlin");
        assertFalse(settings.update(p).getBoolean("reopened"));
        assertEquals(1, m.writes);
        String saved = m.value;
        p.put("zone", "bad-zone");
        try {
            settings.update(p);
            fail();
        } catch (ApiException e) {
            assertEquals(400, e.status());
        }
        assertEquals(saved, m.value);
        assertEquals(1, m.writes);
        assertEquals(
                "Europe/Berlin",
                new KgLlmSettings(m, () -> null).read().getJSONObject("plan").getString("zone"));
    }

    @Test
    public void hotUpdatePreservesRuntimeStoreEpochPauseAndQueue() throws Exception {
        Map<String, String> config = KgTestSupport.enabled();
        Memory m = new Memory();
        KgRuntime r =
                new KgRuntime(
                        new KgRuntime.Env(
                                tmp.getRoot(),
                                key -> LlmSchedule.KEY.equals(key) ? m.value : config.get(key),
                                System::currentTimeMillis,
                                new KgTestSupport.Probe(),
                                KgStore.SQLITE,
                                false));
        r.open();
        try {
            r.pause();
            JSONObject before = r.status();
            String epoch = before.getJSONObject("store").toString();
            KgLlmSettings settings = new KgLlmSettings(m, () -> r);
            JSONObject p = LlmSchedule.defaults().json();
            p.put("mode", "manual");
            assertTrue(settings.update(p).getBoolean("applied"));
            assertEquals(KgRuntime.State.RUNNING, r.state());
            assertFalse(r.status().getJSONObject("storage").getBoolean("growthAllowed"));
            assertEquals(
                    before.getJSONObject("store").optString("epoch"),
                    r.status().getJSONObject("store").optString("epoch"));
            assertEquals(
                    "manual",
                    r.status()
                            .getJSONObject("config")
                            .getJSONObject("llmSchedule")
                            .getString("mode"));
            r.close();
            r.open();
            assertEquals(
                    "manual",
                    r.status()
                            .getJSONObject("config")
                            .getJSONObject("llmSchedule")
                            .getString("mode"));
        } finally {
            r.close();
        }
    }

    @Test
    public void focusedRoutesRejectArbitraryKeysQueriesAndInvalidManualLimits() throws Exception {
        Memory m = new Memory();
        KgLlmSettings settings = new KgLlmSettings(m, () -> null);
        KnowledgeApi api = new KnowledgeApi(() -> null, () -> null, () -> settings);
        String[] plan = "/v1/kg/llm-schedule".split("/"), run = "/v1/kg/llm-run".split("/");
        assertEquals(
                "automatic",
                api.route("GET", plan, () -> new JSONObject())
                        .getJSONObject("plan")
                        .getString("mode"));
        for (String json :
                new String[] {
                    "{\"action\":\"start\",\"maxRequests\":0}",
                    "{\"action\":\"start\",\"maxDocuments\":101}",
                    "{\"action\":\"stop\",\"maxDocuments\":1}",
                    "{\"action\":\"start\",\"force\":true}"
                }) {
            JSONObject request = new JSONObject(json);
            try {
                api.route("POST", run, () -> request);
                fail();
            } catch (ApiException e) {
                assertEquals(400, e.status());
            }
        }
        try {
            api.route("GET", plan, Map.of("collection", "c1"), () -> new JSONObject());
            fail();
        } catch (ApiException e) {
            assertEquals(400, e.status());
        }
        JSONObject body = LlmSchedule.defaults().json();
        body.put("config", "arbitrary");
        try {
            api.route("PUT", plan, () -> body);
            fail();
        } catch (ApiException e) {
            assertEquals(400, e.status());
        }
        assertEquals(0, m.writes);
        try {
            api.route("POST", plan, () -> body);
            fail();
        } catch (ApiException e) {
            assertEquals(405, e.status());
        }
    }
}
