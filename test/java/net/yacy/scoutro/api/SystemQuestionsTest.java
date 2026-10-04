/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
public class SystemQuestionsTest {
    @Test public void supportedDeAndEnSystemQuestionsSelectStructuredActions() throws Exception {
        for (String q : List.of("Wie viele Domains/Hosts sind im System?", "Wie viele Hosts sind im System?", "Wie viele Seiten?", "How many pages are there?", "/system Wie viele Seiten?")) {
            final SystemQuestions.Plan p = SystemQuestions.detect(q);
            assertNotNull(q, p);
            assertEquals(q, "index.metrics", p.action);
        }
        assertEquals("crawl.list", SystemQuestions.require("Welche Crawls laufen?").action);
        assertEquals("discovery.status", SystemQuestions.require("Ist Discovery aktiv?").action);
        assertEquals("collections.list", SystemQuestions.require("Welche Collections gibt es?").action);
        SystemQuestions.Plan host = SystemQuestions.require("Analysiere Host EXAMPLE.com.");
        AgentApi.Request r = new AgentApi.Request("GET", List.of(), Map.of("q", "x", "collection", "visible"), null, "local", null, null);
        assertEquals(List.of("seo","hosts","example.com"), SystemQuestions.request(host, r).path);
        assertEquals("visible", SystemQuestions.request(host, r).query.get("collection"));
    }
    @Test public void contentQuestionsStayContentAndUnsupportedSystemQuestionsFailClosed() throws Exception {
        for (String q : List.of("Wie viele Seiten hat das Buch?", "Was ist Discovery in der Astronomie?", "Explain the Internet", "Analyze this article", "Wie entstehen Domains?"))
            assertNull(q, SystemQuestions.detect(q));
        for (String q : List.of("/system delete everything", "Welche Agenten gibt es im System?", "Wie viele Hosts sind vorhanden?")) {
            try { SystemQuestions.require(q).query(Map.of("q",q)); fail(); }
            catch (ApiException e) { assertEquals("unsupported_system_question", e.code()); }
        }
        try { SystemQuestions.require("Analysiere Host localhost").query(Map.of()); fail(); }
        catch (ApiException e) { assertEquals(400, e.status()); }
    }
    @Test public void latestUserAndTextPartsDriveRoutingWithoutAttachmentsOrHistory() throws Exception {
        JSONArray ms = new JSONArray().put(new JSONObject().put("role","user").put("content","Wie viele Seiten?"))
                .put(new JSONObject().put("role","assistant").put("content","old answer"))
                .put(new JSONObject().put("role","user").put("content",new JSONArray()
                        .put(new JSONObject().put("type","text").put("text","Welche Collections gibt es?"))
                        .put(new JSONObject().put("type","image_url").put("image_url","https://unused.invalid/"))));
        assertEquals("Welche Collections gibt es?", SystemQuestionChat.latestUser(ms));
        assertEquals("", SystemQuestionChat.latestUser(null));
    }
}
