/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.13.0, tests 15 to 21 of the plan (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 40): a facility of the LLM tier needs a place in its context,
 * checked when an answer is validated and again whenever an accepted answer is published (a fresh, a cached or a replayed one);
 * offer items without a category are services of their own. Invented pages with the structure of the finding (a project page whose
 * menu the model took for facilities, entities only, the menu repeated).
 */
public class PlaceEvidenceTest {

    private static final String MENU = "Startseite\nHochbau\nFertigteilbau\nLogistikbau\nBaustatische Prüfung\nReferenzen\nÜber uns\nJobs\nKontakt\n";
    private static final String PROJECT = MENU + "\n# Institut Kassel\n\nFür ein Forschungsinstitut in Kassel errichteten wir einen Neubau in"
            + " Fertigteilbauweise. Die Gebäude sind vollständig in Fertigteilen errichtet.\n\n" + MENU + "Muster Hochbau GmbH\nMusterstraße 5\n34117 Kassel\n";
    private static final String PROJECT_URL = "https://www.muster-hochbau.de/projekte/institut-kassel/";

    private static ExtractContext ctx(final String text, final String url) {
        return new ExtractContext(KgVocabularies.get(), Set.of("construction"), false, List.of("bau-web")).withServiceText(text).withPage(url);
    }

    private static LlmExtractor.Known service(final String id, final String name) {
        final Mention m = new Mention("rule:service:" + name, Vocabulary.SERVICE, 2);
        m.name = name;
        return new LlmExtractor.Known(id, m);
    }

    /** The answer of the model as it lists entities: {type, name, quote} each, at the quote's first place in the text. */
    private static String answer(final String text, final String[][] entities) {
        final JSONArray es = new JSONArray();
        int i = 0;
        for (final String[] e : entities) {
            es.put(KgJson.obj("id", "e" + ++i, "type", e[0], "name", e[1], "quote", e.length > 2 ? e[2] : e[1]));
        }
        return KgJson.obj("entities", es).toString();
    }

    /** The accepted form of an answer (as the cache and the stored evidence keep it): with the quote's offset in the text. */
    private static JSONObject accepted(final String text, final String[][] entities) {
        final JSONArray es = new JSONArray();
        int i = 0;
        for (final String[] e : entities) {
            final String quote = e.length > 2 ? e[2] : e[1];
            es.put(KgJson.obj("id", "e" + ++i, "type", e[0], "name", e[1], "kind", null, "quote", quote, "at", Math.max(0, text.indexOf(quote))));
        }
        return KgJson.obj("entities", es, "claims", new JSONArray(), "values", new JSONArray());
    }

    private static Set<String> named(final Extraction ex, final String type) {
        final Set<String> out = new TreeSet<>();
        for (final Mention m : ex.mentions()) {
            if (type.equals(m.type) && m.tier == LlmExtractor.TIER) {
                out.add(m.name);
            }
        }
        return out;
    }

    private static Set<String> unconfirmed(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.TYPE_UNCONFIRMED.equals(c.predicate)) {
                out.add(ex.mention(c.subject).name);
            }
        }
        return out;
    }

    private static final String[][] MENU_FACILITIES = {{"facility", "Hochbau"}, {"facility", "Fertigteilbau"}, {"facility", "Logistikbau"},
        {"facility", "Baustatische Prüfung"}, {"facility", "Referenzen"}, {"facility", "Über uns"}, {"facility", "Jobs"}, {"facility", "Kontakt"}};

    /** Test 15: the stored answer of the menu is replayed with the page's text: no facility for the eight menu items, nor the building. */
    @Test
    public void theMenuOfAProjectPageNamesNoFacility() {
        final List<LlmExtractor.Known> known = List.of(service("k1", "Hochbau"), service("k2", "Fertigteilbau"));
        // validation of a new answer
        final LlmExtractor.Result r = LlmExtractor.validate(answer(PROJECT, MENU_FACILITIES), new LlmExtractor.Chunk(PROJECT, 0), known, Set.of());
        assertEquals(r.accepted.toString(), 0, r.entities);
        // the publication of the old accepted (cached or stored) answer with the page's text drops them too
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(PROJECT, MENU_FACILITIES), known, ex, ctx(PROJECT, PROJECT_URL));
        assertTrue(named(ex, Vocabulary.FACILITY).toString(), named(ex, Vocabulary.FACILITY).isEmpty());
        // another model's sentence about the project's buildings
        final String[][] buildings = {{"facility", "Gebäude", "Die Gebäude sind vollständig in Fertigteilen errichtet."},
            {"facility", "Institut Kassel"}};
        final Extraction b = new Extraction(100);
        LlmExtractor.apply(accepted(PROJECT, buildings), known, b, ctx(PROJECT, PROJECT_URL));
        assertTrue(named(b, Vocabulary.FACILITY).toString(), named(b, Vocabulary.FACILITY).isEmpty());
    }

    /** Tests 16 and 18: the rules' services of an offer list stay; an item without a category is a service without one. */
    @Test
    public void offerItemsOfAServicesPage() {
        final String text = "Muster Hochbau GmbH\n\nLeistungen\nHochbau\nFertigteilbau\nLogistikbau\nBaustatische Prüfung\nReferenzen\nÜber uns\n";
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, "https://www.muster-hochbau.de/leistungen/", "www.muster-hochbau.de", "de", ex,
                ctx(text, null), List.of(), List.of());
        final List<String> categories = new ArrayList<>(), uncategorized = new ArrayList<>(), services = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.CATEGORY.equals(c.predicate)) {
                categories.add(c.value);
            } else if (Vocabulary.UNCATEGORIZED.equals(c.predicate)) {
                uncategorized.add(ex.mention(c.subject).name);
            } else if (Vocabulary.NAME.equals(c.predicate) && Vocabulary.SERVICE.equals(ex.mention(c.subject).type)) {
                services.add(c.value);
            }
        }
        assertTrue(categories.toString(), categories.containsAll(List.of("construction/hochbau", "construction/fertigteilbau")));
        assertEquals(List.of("Logistikbau", "Baustatische Prüfung"), uncategorized);
        assertFalse("never a category it does not name", categories.contains("construction/baustellenlogistik"));
        assertFalse("a page label ends the list and is no service", services.contains("Referenzen") || services.contains("Über uns"));
        // only in a menu, without an offer heading: no service
        final String menu = "Muster Hochbau GmbH\n\nStartseite\nLogistikbau\nReferenzen\nKontakt\n";
        final Extraction m = new Extraction(300);
        new RuleExtractor(200, 65536).extract(menu, "https://www.muster-hochbau.de/", "www.muster-hochbau.de", "de", m, ctx(menu, null),
                List.of(), List.of());
        for (final Claim c : m.claims()) {
            assertFalse(c.toString(), Vocabulary.UNCATEGORIZED.equals(c.predicate));
        }
    }

    /** Test 17: Kontakt, Jobs, Über uns and Referenzen become neither facilities nor services of the LLM tier. */
    @Test
    public void pageLabelsAreNeitherFacilitiesNorServices() {
        final String text = "Kontakt\nJobs\nÜber uns\nReferenzen\n\nWir bieten Kontakt, Jobs und Referenzen für unsere Kunden an.";
        final String[][] labels = {{"service", "Kontakt"}, {"service", "Jobs"}, {"facility", "Über uns"}, {"service", "Referenzen"}};
        final LlmExtractor.Result r = LlmExtractor.validate(answer(text, labels), new LlmExtractor.Chunk(text, 0), List.of(), Set.of());
        assertEquals(r.accepted.toString(), 0, r.entities);
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, labels), List.of(), ex, ctx(text, "https://www.muster.de/"));
        assertTrue(ex.mentions().toString(), named(ex, Vocabulary.SERVICE).isEmpty() && named(ex, Vocabulary.FACILITY).isEmpty());
    }

    /** Test 19: real facilities stay, judged by their context. */
    @Test
    public void realFacilitiesStay() {
        final String text = "Pflege Muster gGmbH\n\nPflegeheim St. Anna, Hauptstraße 5, 34117 Kassel.\n\n"
                + "Unsere Tagespflege Haus Linde öffnet montags bis freitags.\n\nUnsere Niederlassung Leipzig betreut Familien in ganz Sachsen.\n\n"
                + "Haus Birke | Haus Eiche | Kontakt\n\nDas Haus Birke ist unser Pflegeheim am Stadtpark.";
        final String[][] places = {{"facility", "Pflegeheim St. Anna"}, {"facility", "Tagespflege Haus Linde"}, {"facility", "Niederlassung Leipzig"},
            {"facility", "Haus Birke"}};
        final LlmExtractor.Result r = LlmExtractor.validate(answer(text, places), new LlmExtractor.Chunk(text, 0), List.of(), Set.of());
        assertEquals(r.accepted.toString(), 4, r.entities);
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, places), List.of(), ex, ctx(text, "https://www.pflege-muster.de/einrichtungen/"));
        assertEquals(Set.of("Pflegeheim St. Anna", "Tagespflege Haus Linde", "Niederlassung Leipzig", "Haus Birke"), named(ex, Vocabulary.FACILITY));
        assertTrue(unconfirmed(ex).toString(), unconfirmed(ex).isEmpty());
    }

    /** Test 20: no facilities: generic nouns, a menu item beside the footer's address, a project's building, an operates claim alone. */
    @Test
    public void whatIsNoFacility() throws Exception {
        final String text = "Haus\nWerk\nStandort\n\nSonnenhof | Kontakt | Musterstraße 5, 34117 Kassel\n";
        final String[][] none = {{"facility", "Haus"}, {"facility", "Werk"}, {"facility", "Standort"}, {"facility", "Sonnenhof"}};
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, none), List.of(), ex, ctx(text, "https://www.muster.de/"));
        assertTrue(named(ex, Vocabulary.FACILITY).toString(), named(ex, Vocabulary.FACILITY).isEmpty());
        // the building of a project reference stays the project's; with its address it may be a site
        final String project = "# Neubau Seniorenzentrum Haus Linde\n\nDas Seniorenzentrum Haus Linde, Lindenweg 3, 34117 Kassel, haben wir 2025 fertiggestellt.";
        final Extraction p = new Extraction(100);
        LlmExtractor.apply(accepted(project, new String[][] {{"facility", "Seniorenzentrum Haus Linde"}}), List.of(), p,
                ctx(project, "https://www.muster-hochbau.de/referenzen/haus-linde/"));
        assertTrue(named(p, Vocabulary.FACILITY).toString(), named(p, Vocabulary.FACILITY).isEmpty());
        final Extraction site = new Extraction(100);
        LlmExtractor.apply(accepted(project, new String[][] {{"site", "Seniorenzentrum Haus Linde"}}), List.of(), site,
                ctx(project, "https://www.muster-hochbau.de/referenzen/haus-linde/"));
        assertEquals(Set.of("Seniorenzentrum Haus Linde"), named(site, Vocabulary.SITE));
        // an operates claim of the same answer does not rescue a menu item
        final String menu = "Leistungen | Sonnenhof | Kontakt\n\nMuster Pflege gGmbH betreibt Einrichtungen in der Region.";
        final JSONObject a = accepted(menu, new String[][] {{"facility", "Sonnenhof"}, {"organization", "Muster Pflege gGmbH"}});
        KgJson.put(a, "claims", new JSONArray().put(KgJson.obj("subject", "e2", "predicate", "operates", "object", "e1", "hedged", false,
                "quote", "Muster Pflege gGmbH betreibt Einrichtungen in der Region.", "at", menu.indexOf("Muster Pflege"))));
        final Extraction o = new Extraction(100);
        LlmExtractor.apply(a, List.of(), o, ctx(menu, "https://www.muster-pflege.de/"));
        assertTrue(named(o, Vocabulary.FACILITY).toString(), named(o, Vocabulary.FACILITY).isEmpty());
    }

    /**
     * Gate A4 (0.13.0, the replay on production text): an address the model writes on one line is found where the page writes it on
     * two; a place word with a proper name in a list of a site's places is a place; the quote's address proves a name without one;
     * a sentence of the page is no name.
     */
    @Test
    public void placesAsThePageWritesThem() {
        final String text = "Startseite\nEinrichtungen\nKontakt\n\nUnsere Einrichtungen\nSeniorenhaus Am Park\nPflegeheim St. Anna\nKontakt\n\n"
                + "Muster Pflege gGmbH\nMusterweg 5\n34117 Kassel\n\nHaus Muster. Lindenweg 3. 34119 Kassel.\n\n"
                + "Die Halle ist vollständig aus Holz errichtet.";
        final String[][] places = {{"site", "Musterweg 5, 34117 Kassel", "Musterweg 5. 34117 Kassel."}, {"facility", "Seniorenhaus Am Park"},
            {"facility", "Pflegeheim St. Anna"}, {"facility", "Haus Muster", "Haus Muster. Lindenweg 3. 34119 Kassel."},
            {"facility", "Die Halle ist vollständig aus Holz errichtet."}};
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, places), List.of(), ex, ctx(text, "https://www.pflege-muster.de/einrichtungen/").withReplay());
        assertEquals(Set.of("Seniorenhaus Am Park", "Pflegeheim St. Anna", "Haus Muster"), named(ex, Vocabulary.FACILITY));
        assertEquals(Set.of("Musterweg 5, 34117 Kassel"), named(ex, Vocabulary.SITE));
        assertTrue(unconfirmed(ex).toString(), unconfirmed(ex).isEmpty());
    }

    /**
     * Gate A4, third run (0.13.0): a list of a site's places is no menu. On a page of the site's places its proper names are places,
     * elsewhere they are kept and marked; an imprint's line with bullets holds an address; a name with periods is no sentence; a
     * menu (short labels beside a section's label) still names no facility.
     */
    @Test
    public void aListOfPlacesIsNoMenu() {
        final String list = "Unsere Standorte\n\n- Muster Pflege Altstadt\n- Muster Pflege Nordheide\n- Muster Pflege Am See\n\nMuster Pflege gGmbH\n";
        final String[][] places = {{"site", "Muster Pflege Altstadt", "- Muster Pflege Altstadt"}, {"site", "Muster Pflege Nordheide"}};
        final Extraction onPlaces = new Extraction(100);
        LlmExtractor.apply(accepted(list, places), List.of(), onPlaces, ctx(list, "https://www.muster-pflege.de/standorte/").withReplay());
        assertEquals(Set.of("Muster Pflege Altstadt", "Muster Pflege Nordheide"), named(onPlaces, Vocabulary.SITE));
        assertTrue(unconfirmed(onPlaces).toString(), unconfirmed(onPlaces).isEmpty());
        final Extraction elsewhere = new Extraction(100);
        LlmExtractor.apply(accepted(list, places), List.of(), elsewhere, ctx(list, "https://www.muster-pflege.de/").withReplay());
        assertEquals(Set.of("Muster Pflege Altstadt", "Muster Pflege Nordheide"), named(elsewhere, Vocabulary.SITE));
        assertEquals("kept, and marked", Set.of("Muster Pflege Altstadt", "Muster Pflege Nordheide"), unconfirmed(elsewhere));
        // an imprint's line with bullets, a branch named with periods, the seat of a company
        final String imprint = "Impressum\n\nMalerbetrieb Muster GmbH • Musterkamp 33 • 22607 Hamburg\n\nJ. N. Muster & Söhne. Niederlassung Kassel.\n\n"
                + "Hauptsitz Kassel\n";
        final String[][] sites = {{"site", "Musterkamp 33", "Malerbetrieb Muster GmbH • Musterkamp 33 • 22607 Hamburg"},
            {"site", "J. N. Muster & Söhne. Niederlassung Kassel."}, {"site", "Hauptsitz Kassel"}};
        final Extraction i = new Extraction(100);
        LlmExtractor.apply(accepted(imprint, sites), List.of(), i, ctx(imprint, "https://www.muster-maler.de/impressum/").withReplay());
        assertEquals(Set.of("Musterkamp 33", "J. N. Muster & Söhne. Niederlassung Kassel.", "Hauptsitz Kassel"), named(i, Vocabulary.SITE));
        assertTrue(unconfirmed(i).toString(), unconfirmed(i).isEmpty());
        // a menu with a section's label beside the name names none
        final String menu = "Startseite | Sonnenhof | Kontakt\n\nMuster Pflege gGmbH";
        final Extraction m = new Extraction(100);
        LlmExtractor.apply(accepted(menu, new String[][] {{"facility", "Sonnenhof"}}), List.of(), m, ctx(menu, "https://www.muster-pflege.de/").withReplay());
        assertTrue(named(m, Vocabulary.FACILITY).toString(), named(m, Vocabulary.FACILITY).isEmpty());
    }

    /**
     * Gate A4 (0.13.0): the replay keeps a service and its offer as they were published (the rules of their reading), and applies
     * the checks of the new version only: the name of a section is no service. A fresh answer passes the offer proof as before.
     */
    @Test
    public void theReplayKeepsServicesAsPublished() {
        final String text = "Startseite | Pflasterarbeiten | Kontakt\n\nMuster Garten GmbH\nMusterweg 5\n34117 Kassel";
        final JSONObject a = accepted(text, new String[][] {{"organization", "Muster Garten GmbH"}, {"service", "Pflasterarbeiten"},
            {"service", "Kontakt"}});
        KgJson.put(a, "claims", new JSONArray().put(KgJson.obj("subject", "e1", "predicate", "offers", "object", "e2", "hedged", false,
                "quote", "Pflasterarbeiten", "at", text.indexOf("Pflasterarbeiten"))));
        final Extraction fresh = new Extraction(100);
        LlmExtractor.apply(a, List.of(), fresh, ctx(text, "https://www.muster-garten.de/kontakt/"));
        assertTrue(named(fresh, Vocabulary.SERVICE).toString(), named(fresh, Vocabulary.SERVICE).isEmpty());
        final Extraction replay = new Extraction(100);
        LlmExtractor.apply(a, List.of(), replay, ctx(text, "https://www.muster-garten.de/kontakt/").withReplay());
        assertEquals(Set.of("Pflasterarbeiten"), named(replay, Vocabulary.SERVICE));
        boolean offer = false;
        for (final Claim c : replay.claims()) {
            offer |= Vocabulary.OFFERS.equals(c.predicate);
        }
        assertTrue(replay.claims().toString(), offer);
    }

    /** Test 21: a facility and a service of similar names are two objects: no merge, no conversion; an unclear one is marked. */
    @Test
    public void aFacilityAndAServiceOfOneHostStayTwoAndAnUnclearOneIsMarked() {
        final String text = "Der Sonnenhof befindet sich mitten in Kassel und bietet 80 Plätze.\n\nDas Sonnenhof-Menü bringen wir täglich ins Haus.";
        final List<LlmExtractor.Known> known = List.of(service("k1", "Sonnenhof-Menü"));
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, new String[][] {{"facility", "Sonnenhof"}}), known, ex, ctx(text, "https://www.sonnenhof-muster.de/"));
        assertEquals(Set.of("Sonnenhof"), named(ex, Vocabulary.FACILITY));
        assertEquals("the service stays the rules' service", "Sonnenhof-Menü", known.get(0).mention.name);
        // a proper name outside a navigation without a place's sign: kept, marked
        final String plain = "Die Gärtnerei Muster gehört seit 1990 zu uns und beliefert viele Kunden mit Pflanzen.";
        final Extraction u = new Extraction(100);
        LlmExtractor.apply(accepted(plain, new String[][] {{"facility", "Gärtnerei Muster"}}), List.of(), u, ctx(plain, "https://www.muster.de/"));
        assertEquals(Set.of("Gärtnerei Muster"), named(u, Vocabulary.FACILITY));
        assertEquals(Set.of("Gärtnerei Muster"), unconfirmed(u));
        // the same name as a service of the page needs a place's sign: refused without one
        final Extraction s = new Extraction(100);
        LlmExtractor.apply(accepted(plain, new String[][] {{"facility", "Gärtnerei Muster"}}), List.of(service("k1", "Gärtnerei Muster")), s,
                ctx(plain, "https://www.muster.de/"));
        assertTrue(named(s, Vocabulary.FACILITY).isEmpty());
    }
}
