/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;

/**
 * The direct index of the chat's job and provider search on an embedded Solr with the shipped schema: which pages match
 * (addresses only), the hosts with matching pages (a count by host), the pages of chosen hosts; the collection as a filter
 * of every query. Invented pages.
 */
public class SolrIndexTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private EmbeddedInstance instance;
    private SolrIndex index;
    private int n;

    @Before
    public void setup() throws Exception {
        this.instance = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1", new String[] {"collection1"});
        final EmbeddedSolrConnector connector = new EmbeddedSolrConnector(this.instance);
        this.index = new SolrIndex(() -> connector);
        add("https://www.statik-a.example/", "bau-web", "Statikbüro A", "Tragwerksplanung und Statik für Wohnhäuser.");
        add("https://www.statik-a.example/leistungen", "bau-web", "Leistungen", "Tragwerksplanung, Statik und Bauphysik.");
        add("https://statik-a.example/kontakt", "bau-web", "Kontakt", "Hauptstraße 1, 12345 Musterstadt.");
        add("https://www.statik-b.example/", "bau-web", "Statikbüro B", "Statik im Bestand. Ein Projekt in Neustadt an der Musterau.");
        add("https://www.galerie.example/", "bau-web", "Hausgalerie", "Einfamilienhäuser in Musterstadt, jede Wand aus Ziegel.");
        add("https://www.fremd.example/", "other-web", "Fremd Statik", "Tragwerksplanung in Musterstadt.");
        add("https://www.jobs.example/stellen/1", "bau-web", "Monteur (m/w/d)", "Sie arbeiten mit SAP und kalibrieren Messgeräte.");
        add("https://www.jobs.example/stellen/2", "bau-web", "Sachbearbeiter (m/w/d)", "Der Sapphire-Kongress war ein Erfolg. Machine Learning im Team.");
        add("https://www.jobs.example/stellen/3", "bau-web", "Planer (m/w/d)", "Learning by doing an der Machine.");
        this.instance.getDefaultServer().commit();
    }

    @After
    public void close() {
        if (this.instance != null) this.instance.close();
    }

    private void add(final String url, final String collection, final String title, final String text) throws Exception {
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", String.format("TESTDOC%05d", this.n++));
        doc.setField("sku", url);
        doc.setField("host_s", java.net.URI.create(url).getHost());
        doc.setField("title", List.of(title));
        doc.setField("text_t", text);
        doc.setField("collection_sxt", List.of(collection));
        this.instance.getDefaultServer().add(doc);
    }

    @Test
    public void whichOfThePagesMatchIsAskedWithoutLoadingThem() throws Exception {
        final List<String> jobs = List.of("https://www.jobs.example/stellen/1", "https://www.jobs.example/stellen/2",
                "https://www.jobs.example/stellen/3", "https://www.jobs.example/stellen/none");
        // a word of up to three letters is a whole word: "SAP" is not the start of "Sapphire"
        assertEquals(Set.of("https://www.jobs.example/stellen/1"), this.index.matching(List.of("SAP"), false, "bau-web", jobs));
        // a longer word is the start of a word
        assertEquals(Set.of("https://www.jobs.example/stellen/1"), this.index.matching(List.of("kalibrier"), false, "bau-web", jobs));
        // several words: all of them, or side by side
        assertEquals(Set.of("https://www.jobs.example/stellen/2", "https://www.jobs.example/stellen/3"),
                this.index.matching(List.of("Machine", "Learning"), false, "bau-web", jobs));
        assertEquals(Set.of("https://www.jobs.example/stellen/2"), this.index.matching(List.of("Machine", "Learning"), true, "bau-web", jobs));
        // only among the addresses asked, only in the collection
        assertEquals(Set.of(), this.index.matching(List.of("SAP"), false, "bau-web", List.of("https://www.jobs.example/stellen/2")));
        assertEquals(Set.of(), this.index.matching(List.of("SAP"), false, "other-web", jobs));
        assertEquals(Set.of("https://www.jobs.example/stellen/1"), this.index.matching(List.of("SAP"), false, null, jobs));
        // more addresses than one question to the index holds
        final List<String> many = new ArrayList<>(jobs);
        for (int i = 0; i < SolrIndex.URL_BATCH + 50; i++) many.add("https://www.jobs.example/stellen/x" + i);
        assertEquals(Set.of("https://www.jobs.example/stellen/1"), this.index.matching(List.of("SAP"), false, "bau-web", many));
    }

    @Test
    public void theHostsWithMatchingPagesAreCountedByHost() throws Exception {
        final ToolContext.HostCount services = this.index.hosts(List.of(List.of("Tragwerksplanung"), List.of("Statiker"), List.of("Statik")), "bau-web");
        // statik-a with and without "www." is one host; the page of other-web is not counted
        assertEquals(Map.of("statik-a.example", 2, "statik-b.example", 1), services.pagesByHost);
        assertEquals(3L, services.pages);
        final ToolContext.HostCount place = this.index.hosts(List.of(List.of("Musterstadt")), "bau-web");
        assertEquals(Map.of("statik-a.example", 1, "galerie.example", 1), place.pagesByHost);
        final ToolContext.HostCount all = this.index.hosts(List.of(List.of("Tragwerksplanung")), null);
        assertEquals(Set.of("statik-a.example", "fremd.example"), all.pagesByHost.keySet());
        // a place of several words
        assertEquals(Set.of("statik-b.example"), this.index.hosts(List.of(List.of("Neustadt", "an", "der", "Musterau")), "bau-web").pagesByHost.keySet());
    }

    @Test
    public void thePagesOfChosenHostsAreReadWithTheirText() throws Exception {
        final List<RagCandidate> pages = this.index.hostPages(List.of(List.of("Musterstadt")), "bau-web", List.of("statik-a.example", "statik-b.example"), 3);
        assertEquals(1, pages.size());
        assertEquals("https://statik-a.example/kontakt", pages.get(0).url);
        assertTrue(pages.get(0).text.contains("12345 Musterstadt"));
        final List<RagCandidate> one = this.index.hostPages(List.of(List.of("Statik")), "bau-web", List.of("statik-a.example", "statik-b.example"), 1);
        assertEquals(2, one.size());
        assertTrue(one.stream().allMatch(p -> p.inCollection("bau-web")));
        // the pages with their text, for the evidence of a selection
        final ToolContext.IndexPage found = this.index.find(List.of("SAP"), "bau-web", List.of("https://www.jobs.example/stellen/1"), 0, 5);
        assertEquals(1L, found.total);
        assertTrue(found.pages.get(0).text.contains("kalibrieren"));
    }
}
