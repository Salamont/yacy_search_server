/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.ai.ToolTestSettings;

/**
 * The service search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 13) on invented pages of a construction collection:
 * a house gallery that matches the words of the question, two engineering offices (one in the town of the question, one
 * elsewhere) and an office of another collection.
 */
public class ServiceSearchTest {

    static final String QUESTION = "Wer prüft in Musterstadt, ob eine Wand tragend ist?";
    static final String BAU = "bau-web";
    final List<RagCandidate> pages = new ArrayList<>();
    final List<String> searched = new ArrayList<>();

    @Before
    public void pages() {
        ToolTestSettings.use((key, dflt) -> key.endsWith(".enabled") ? "true" : dflt);
        page("https://www.hausgalerie.example/galerie/musterstadt", BAU, "Hausgalerie: Einfamilienhaus in Musterstadt",
                "Unsere Hausgalerie zeigt Einfamilienhäuser in Musterstadt. Jede Wand aus Ziegel, offene Grundrisse, große Fenster.");
        page("https://www.ostermann-statik.example/", BAU, "Ingenieurbüro Ostermann – Tragwerksplanung in Musterstadt",
                "Ingenieurbüro Ostermann: Tragwerksplanung und Statik für Neubau, Umbau und Sanierung in Musterstadt. Als Statiker prüfen wir "
                        + "die Standsicherheit von Bauteilen.");
        page("https://www.rheinblick-statik.example/", BAU, "Statikbüro Rheinblick", "Statikbüro Rheinblick: Tragwerksplanung in Nebenstadt.");
        page("https://www.fremd-statik.example/", "other-web", "Statik Musterstadt GmbH", "Tragwerksplanung und Statik in Musterstadt. FREMDMARKE");
    }

    @After
    public void reset() {
        ToolTestSettings.use(null);
    }

    private void page(final String url, final String collection, final String title, final String text) {
        this.pages.add(new RagCandidate(url, title, "", text, java.net.URI.create(url).getHost(), List.of(collection), this.pages.size()));
    }

    /** A search like the index: every page with a word of the query, of every collection (the scope check is the code's). */
    ToolContext chat(final String collection) {
        return chat(collection, 16000);
    }

    ToolContext chat(final String collection, final int room) {
        return new ToolContext(collection, true, (concepts, q, c, n) -> {
            this.searched.add(q + " @" + c);
            final List<RagCandidate> out = new ArrayList<>();
            for (final RagCandidate p : this.pages) if (!p.matched(concepts).isEmpty()) out.add(p);
            return out;
        }, null, room, "test").withIndex((words, scope, urls, offset, count) -> {
            this.searched.add("index " + String.join(" ", words) + " @" + scope);
            final List<RagCandidate> all = new ArrayList<>();
            for (final RagCandidate p : this.pages) {
                if (scope != null && !p.collections.contains(scope)) continue;
                final String text = (p.title + " " + p.text).toLowerCase(java.util.Locale.ROOT);
                if (words.stream().allMatch(w -> java.util.regex.Pattern.compile("(?<![\\p{L}])" + java.util.regex.Pattern.quote(
                        w.toLowerCase(java.util.Locale.ROOT))).matcher(text).find())) all.add(p);
            }
            return new ToolContext.IndexPage(new ArrayList<>(all.subList(Math.min(offset, all.size()), Math.min(offset + count, all.size()))),
                    all.size());
        });
    }

    @Test
    public void theServiceAndThePlaceAreDerivedFromTheQuestion() {
        final ServiceSearch.Plan plan = ServiceSearch.plan(QUESTION);
        assertEquals(List.of("Tragwerksplanung", "Statiker", "Statik"), plan.services);
        assertEquals("Musterstadt", plan.place);
        assertTrue(plan.derived);
        assertEquals(List.of("Tragwerksplanung Musterstadt", "Statiker Musterstadt", "Statik Musterstadt", "Tragwerksplanung"), plan.variants);
        assertEquals("Bad Musterbach", ServiceSearch.place("Wer macht in Bad Musterbach einen Wanddurchbruch?"));
        assertEquals("Neustadt bei Nebenstadt", ServiceSearch.place("Wer saniert in Neustadt bei Nebenstadt?"));
        assertEquals("Musterstadt", ServiceSearch.place("Wer hilft in Musterstadt bei Schimmel im Keller?"));
        assertEquals("50354", ServiceSearch.place("Statik für ein Haus in 50354"));
        // a question for a provider without a known problem word: its own nouns
        final ServiceSearch.Plan carports = ServiceSearch.plan("Wer baut Carports in Brühl?");
        assertEquals(List.of("carports"), carports.services);
        assertEquals(List.of("carports Brühl", "carports"), carports.variants);
        assertFalse(carports.derived);
        // no question for a provider
        assertNull(ServiceSearch.plan("Was kostet ein Fertighaus?"));
        assertNull(ServiceSearch.plan("Welche Pflegeheime gibt es in Nebenstadt?"));
    }

    @Test
    public void aGalleryMatchingTheWordsIsAWeakAnswer() {
        final ServiceSearch.Plan plan = ServiceSearch.plan(QUESTION);
        final RagRetriever.Result first = new RagRetriever((q, c, n, g) -> List.of(this.pages.get(0))).retrieve(RagQuery.prepare(QUESTION), BAU, false, 8);
        assertEquals("majority", first.stage);
        assertTrue("the gallery names no service", ServiceSearch.weak(first, plan));
        final RagRetriever.Result office = new RagRetriever((q, c, n, g) -> List.of(this.pages.get(1), this.pages.get(0)))
                .retrieve(RagQuery.prepare("Tragwerksplanung Musterstadt"), BAU, false, 8);
        assertFalse(ServiceSearch.weak(office, plan));
    }

    @Test
    public void theSearchFindsTheProviderInTheCollectionThePlaceFirst() {
        final ServiceSearch.Plan plan = ServiceSearch.plan(QUESTION);
        final ServiceSearch.Result r = ServiceSearch.run(chat(BAU), plan, List.of());
        final List<String> urls = new ArrayList<>();
        for (final ServiceSearch.Provider p : r.providers) urls.add(p.url());
        assertEquals(List.of("https://www.ostermann-statik.example/", "https://www.rheinblick-statik.example/"), urls);
        assertEquals(2, r.found);
        assertFalse(r.lowerBound());
        // the office states its service for the town: a service area; the other names another town: no place
        assertEquals(ServiceSearch.SERVES_PLACE, r.providers.get(0).place);
        assertEquals(ServiceSearch.NO_PLACE, r.providers.get(1).place);
        for (final String q : this.searched) assertTrue(q, q.endsWith("@" + BAU));
        // the providers as the first numbered sources, their page's text for the support check
        final ServiceSearch.Built built = ServiceSearch.format(r, 1, 4000, "collection " + BAU, false);
        assertEquals("https://www.ostermann-statik.example/", built.sources.get(0).url);
        assertTrue(built.text, built.text.startsWith("[1] Provider Ingenieurbüro Ostermann – Tragwerksplanung in Musterstadt\nURL: https://www.ostermann-statik.example/"));
        assertEquals(2, built.meta.optInt("found"));
        assertEquals(2, built.meta.optInt("shown"));
        final org.json.JSONObject check;
        try {
            check = RagCitations.check("Das Ingenieurbüro Ostermann bietet Tragwerksplanung und Statik in Musterstadt an [1].", built.sources);
            assertEquals(RagCitations.SUPPORTED, check.getJSONArray("support").getJSONObject(0).getString("verdict"));
        } catch (final org.json.JSONException e) {
            throw new AssertionError(e);
        }
        // the whole index (administrator) sees the office of the other collection too
        final ServiceSearch.Result all = ServiceSearch.run(chat(null), plan, List.of());
        assertTrue(all.providers.stream().anyMatch(p -> p.url().contains("fremd-statik")));
        // nothing named: a question for a service no page offers
        final ServiceSearch.Result none = ServiceSearch.run(chat(BAU), ServiceSearch.plan("Wer prüft in Musterstadt den Schornstein?"), List.of());
        assertEquals(0, none.found);
        // only with the release of the search tool
        assertTrue(ServiceSearch.released(false, chat(BAU)));
        assertFalse(ServiceSearch.released(false, null));
        // provider_search follows search while not set on its own; its own setting wins
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.search.enabled") ? "false" : key.equals("ai.tools.provider_search.enabled") ? dflt
                : key.endsWith(".enabled") ? "true" : dflt);
        assertFalse(ServiceSearch.released(false, chat(BAU)));
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.provider_search.enabled") ? "false" : key.endsWith(".enabled") ? "true" : dflt);
        assertFalse(ServiceSearch.released(false, chat(BAU)));
    }

    @Test
    public void theNamedProviderSearchToolDerivesServiceAndPlace() throws Exception {
        final ToolContext chat = chat(BAU);
        org.json.JSONObject r = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search",
                "{\"query\":\"Wer prüft in Musterstadt, ob eine Wand tragend ist?\",\"collection\":\"other-web\"}", false, chat));
        assertEquals("collection " + BAU, r.getString("scope"));
        assertEquals("Musterstadt", r.getString("place"));
        final org.json.JSONArray providers = r.getJSONArray("providers");
        assertEquals(2, r.getInt("found"));
        assertEquals("https://www.ostermann-statik.example/", providers.getJSONObject(0).getString("url"));
        assertEquals(ServiceSearch.SERVES_PLACE, providers.getJSONObject(0).getString("place"));
        assertEquals(ServiceSearch.NO_PLACE, providers.getJSONObject(1).getString("place"));
        assertFalse(r.toString(), r.toString().contains("FREMDMARKE"));
        assertEquals("[1]", providers.getJSONObject(0).getString("cite"));
        // the model's own words without a question for a provider
        r = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search", "{\"query\":\"Tragwerksplanung Musterstadt\"}", false, chat));
        assertEquals("https://www.ostermann-statik.example/", r.getJSONArray("providers").getJSONObject(0).getString("url"));
        // a scope without the graph is enough for it; without a scope the tool does not exist
        assertTrue(net.yacy.ai.ToolProvider.offered("provider_search", false, chat));
        assertFalse(net.yacy.ai.ToolProvider.offered("provider_search", true, null));
        assertFalse(net.yacy.ai.ToolProvider.offered("job_search", false, chat));
    }

    @Test
    public void thePlaceOfAToolRequestWithAndWithoutIn() {
        for (final String[] c : new String[][] {
                {"Tragwerksplanung Musterstadt", "Musterstadt"}, {"Tragwerksplanung in Musterstadt", "Musterstadt"},
                {"Tragwerksplanung Neustadt an der Musterau", "Neustadt an der Musterau"},
                {"Tragwerksplanung in Neustadt an der Musterau", "Neustadt an der Musterau"},
                {"Statik Bad Musterbrunn", "Bad Musterbrunn"}, {"Statik in Bad Musterbrunn", "Bad Musterbrunn"},
                {"Statiker Musterstadt-Nord", "Musterstadt-Nord"}, {"Wer prüft in Musterstadt, ob eine Wand tragend ist?", "Musterstadt"},
                {"Tragwerksplanung", null}, {"Schimmelsanierung Keller", null}, {"Statik Wanddurchbruch", null}, {"Statik", null}}) {
            final ServiceSearch.Plan plan = ServiceSearch.planAny(c[0]);
            assertTrue(c[0], plan != null);
            assertEquals(c[0], c[1], plan.place);
        }
        // the same plan with and without "in"
        final ServiceSearch.Plan bare = ServiceSearch.planAny("Tragwerksplanung Musterstadt");
        final ServiceSearch.Plan with = ServiceSearch.planAny("Tragwerksplanung in Musterstadt");
        assertEquals(with.services, bare.services);
        assertEquals(with.variants, bare.variants);
        // the optional place field: given alone, or instead of the words
        assertEquals("Musterstadt", ServiceSearch.planAny("Statik", "Musterstadt").place);
        assertEquals("Nebenstadt", ServiceSearch.planAny("Statik Musterstadt", "in Nebenstadt").place);
        assertEquals("Neustadt an der Musterau", ServiceSearch.planAny("Schimmelsanierung im Keller", "Neustadt an der Musterau").place);
        assertNull(ServiceSearch.planAny("Statik", " ").place);
        // a count question names no service "viele"
        assertEquals(List.of("fliesenleger"), ServiceSearch.planAny("Wie viele Fliesenleger gibt es in Musterstadt?").services.stream()
                .map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList());
    }

    @Test
    public void theNamedProviderSearchToolGivesTheSameWithAndWithoutIn() throws Exception {
        final ToolContext chat = chat(BAU);
        final org.json.JSONObject with = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search",
                "{\"query\":\"Tragwerksplanung in Musterstadt\"}", false, chat));
        final org.json.JSONObject bare = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search",
                "{\"query\":\"Tragwerksplanung Musterstadt\"}", false, chat(BAU)));
        final org.json.JSONObject field = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search",
                "{\"query\":\"Tragwerksplanung\",\"place\":\"Musterstadt\"}", false, chat(BAU)));
        final org.json.JSONObject none = new org.json.JSONObject(net.yacy.ai.ToolProvider.executeTool("provider_search",
                "{\"query\":\"Tragwerksplanung\"}", false, chat(BAU)));
        for (final org.json.JSONObject r : new org.json.JSONObject[] {with, bare, field}) {
            assertEquals(r.toString(), "Musterstadt", r.getString("place"));
            assertEquals(2, r.getInt("found"));
            assertEquals(with.getJSONObject("per_place").toString(), r.getJSONObject("per_place").toString());
            assertEquals("https://www.ostermann-statik.example/", r.getJSONArray("providers").getJSONObject(0).getString("url"));
            assertEquals(ServiceSearch.SERVES_PLACE, r.getJSONArray("providers").getJSONObject(0).getString("place"));
            assertEquals(ServiceSearch.NO_PLACE, r.getJSONArray("providers").getJSONObject(1).getString("place"));
        }
        assertTrue(none.toString(), none.isNull("place"));
        assertFalse(none.has("per_place"));
        assertEquals(2, none.getInt("found"));
        // the definition offers the optional place, the query stays the only required argument
        final org.json.JSONObject fn = net.yacy.ai.tools.SearchAssistTools.providerSearch().definition().getJSONObject("function");
        assertTrue(fn.getJSONObject("parameters").getJSONObject("properties").has("place"));
        assertEquals("[\"query\"]", fn.getJSONObject("parameters").getJSONArray("required").toString());
    }

    @Test
    public void theProviderSearchToolStaysWithinTheRoomOfTheAnswer() throws Exception {
        // nine more offices with long pages; the rc.4 case: a model context of 4,096 tokens (a tool room of 4,096 characters)
        final String longText = "Tragwerksplanung, Statik und Bauphysik für Wohnhäuser, Gewerbebauten und Bestandsgebäude. ".repeat(14);
        for (int n = 1; n <= 9; n++) page("https://www.statik-" + n + ".example/", BAU, "Statikbüro Nummer " + n,
                "Statikbüro Nummer " + n + ": " + longText + " Für Bauherren in Musterstadt und im Umland, Büro in Nebenstadt.");
        final int room = 4096 * RagContext.CHARS_PER_TOKEN / 3; // RAGProxyServlet.toolResultChars(4096)
        for (final String query : new String[] {"Tragwerksplanung in Musterstadt", "Tragwerksplanung Musterstadt"}) {
            final ToolContext chat = chat(BAU, room);
            final String raw = net.yacy.ai.ToolProvider.executeTool("provider_search", "{\"query\":\"" + query + "\"}", false, chat);
            final org.json.JSONObject r = new org.json.JSONObject(raw); // valid JSON
            assertFalse(raw, r.has("error"));
            assertTrue(raw.length() + "", raw.length() <= room);
            assertEquals(raw, "Musterstadt", r.getString("place"));
            assertEquals(11, r.getInt("found"));
            assertFalse(r.getBoolean("lower_bound"));
            final org.json.JSONObject perPlace = r.getJSONObject("per_place");
            assertEquals(11, perPlace.getInt(ServiceSearch.AT_PLACE) + perPlace.getInt(ServiceSearch.SERVES_PLACE)
                    + perPlace.getInt(ServiceSearch.MENTIONS_PLACE) + perPlace.getInt(ServiceSearch.NO_PLACE));
            final int shown = r.getInt("shown");
            assertTrue(raw, shown >= 3 && shown <= ServiceSearch.MAX_SHOWN);
            assertEquals(shown, r.getJSONArray("providers").length());
            assertTrue(raw, r.getBoolean("shortened"));
            assertEquals("only the providers given are sources", shown, chat.sources().size());
            for (int i = 0; i < shown; i++) {
                final org.json.JSONObject p = r.getJSONArray("providers").getJSONObject(i);
                assertEquals("[" + (i + 1) + "]", p.getString("cite"));
                assertTrue(p.toString(), p.has("place") && p.has("name") && p.has("url"));
            }
            assertTrue(r.getString("note"), r.getString("note").contains("Found 11 providers, shown " + shown));
        }
        // a room for nothing but the totals: no provider, the counts and categories kept
        final ToolContext tiny = chat(BAU, ToolContext.MIN_RESULT_CHARS);
        final String raw = net.yacy.ai.ToolProvider.executeTool("provider_search", "{\"query\":\"Tragwerksplanung Musterstadt\"}", false, tiny);
        final org.json.JSONObject r = new org.json.JSONObject(raw);
        assertFalse(raw, r.has("error"));
        assertTrue(raw, raw.length() <= ToolContext.MIN_RESULT_CHARS);
        assertEquals(11, r.getInt("found"));
        assertEquals(0, r.getInt("shown"));
        assertTrue(raw, r.has("per_place"));
        assertTrue(tiny.sources().isEmpty());
    }
}
