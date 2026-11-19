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
        return new ToolContext(collection, true, (concepts, q, c, n) -> {
            this.searched.add(q + " @" + c);
            final List<RagCandidate> out = new ArrayList<>();
            for (final RagCandidate p : this.pages) if (!p.matched(concepts).isEmpty()) out.add(p);
            return out;
        }, null, 16000, "test").withIndex((words, scope, urls, offset, count) -> {
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
}
