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
        return ctx(text, url, "construction");
    }

    private static ExtractContext ctx(final String text, final String url, final String vocabulary) {
        return new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of("muster-web")).withServiceText(text).withPage(url);
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

    /** The names the tier states (a mention whose name the limit cut has none). */
    private static Set<String> stated(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.NAME.equals(c.predicate) && c.tier == LlmExtractor.TIER) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static final String[][] MENU_FACILITIES ={{"facility", "Hochbau"}, {"facility", "Fertigteilbau"}, {"facility", "Logistikbau"},
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

    /**
     * Test 20: no confirmed facilities: generic nouns, a menu item beside the footer's address (a name only a menu lists is kept
     * unclear and marked, never confirmed: gate A4, run 6), a project's building, an operates claim alone.
     */
    @Test
    public void whatIsNoFacility() throws Exception {
        final String text = "Haus\nWerk\nStandort\n\nSonnenhof | Kontakt | Musterstraße 5, 34117 Kassel\n";
        final String[][] none = {{"facility", "Haus"}, {"facility", "Werk"}, {"facility", "Standort"}, {"facility", "Sonnenhof"}};
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, none), List.of(), ex, ctx(text, "https://www.muster.de/"));
        assertEquals("only the menu's name, and marked", Set.of("Sonnenhof"), named(ex, Vocabulary.FACILITY));
        assertEquals(Set.of("Sonnenhof"), unconfirmed(ex));
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
        assertEquals("not confirmed by the claim", Set.of("Sonnenhof"), unconfirmed(o));
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
        // the fourth run: a day centre's place word, an address whose town ends like an activity, an association's abbreviation,
        // a town whose name ends with a place word
        final String care = "Unsere Häuser\n\nSeniorentagesstätte Musterstadt\n\nBurgstraße 30, 12345 Musterungen\n\n"
                + "Geschäftsstelle des Musterwerkes Musterstadt e.V.\n\nMuster-Seniorenzentrum Musterheim\n";
        final String[][] houses = {{"facility", "Seniorentagesstätte Musterstadt"}, {"site", "Burgstraße 30, 12345 Musterungen"},
            {"facility", "Geschäftsstelle des Musterwerkes Musterstadt e.V."}, {"facility", "Muster-Seniorenzentrum Musterheim"}};
        final Extraction h = new Extraction(100);
        LlmExtractor.apply(accepted(care, houses), List.of(), h, ctx(care, "https://www.muster-pflege.de/").withReplay());
        assertEquals(Set.of("Seniorentagesstätte Musterstadt", "Geschäftsstelle des Musterwerkes Musterstadt e.V.", "Muster-Seniorenzentrum Musterheim"),
                named(h, Vocabulary.FACILITY));
        assertEquals(Set.of("Burgstraße 30, 12345 Musterungen"), named(h, Vocabulary.SITE));
        assertTrue(unconfirmed(h).toString(), unconfirmed(h).isEmpty());
        // a menu with a section's label beside the name confirms none: its name is kept unclear and marked
        final String menu = "Startseite | Sonnenhof | Kontakt\n\nMuster Pflege gGmbH";
        final Extraction m = new Extraction(100);
        LlmExtractor.apply(accepted(menu, new String[][] {{"facility", "Sonnenhof"}}), List.of(), m, ctx(menu, "https://www.muster-pflege.de/").withReplay());
        assertEquals(Set.of("Sonnenhof"), unconfirmed(m));
    }

    /**
     * Gate A4 (0.13.0, run 6: refused places of the samples judged): a site's menu lists its places too, by their kind (a group home,
     * a care service, a counselling office) or by a person's name ("Paul-Gerhardt-Haus"); a quote with the place's full address is a
     * place, an activity's name too; a number keeps a generic noun ("Haus 3"); on a project page an establishment of the operator
     * or a place word with the operator's name is the operator's, a building of a project stays the project's.
     */
    @Test
    public void refusedPlacesOfTheSixthRun() {
        final String menu = "Unsere Häuser | Paul-Gerhardt-Haus | WG Musterstadt | Pflegedienst Musterstadt | Beratungsstelle Musterstadt | Newsletter"
                + " | Kontakt\n\nMuster Pflege gGmbH";
        final String[][] houses = {{"facility", "Paul-Gerhardt-Haus"}, {"facility", "WG Musterstadt"}, {"facility", "Pflegedienst Musterstadt"},
            {"facility", "Beratungsstelle Musterstadt"}, {"facility", "Newsletter"}, {"facility", "Muster Wohnen"}};
        final Extraction m = new Extraction(100);
        LlmExtractor.apply(accepted(menu + " | Muster Wohnen", houses), List.of(), m, ctx(menu + " | Muster Wohnen", "https://www.muster-pflege.de/").withReplay());
        assertEquals(Set.of("Paul-Gerhardt-Haus", "WG Musterstadt", "Pflegedienst Musterstadt", "Beratungsstelle Musterstadt", "Muster Wohnen"),
                named(m, Vocabulary.FACILITY));
        assertEquals("a name only the menu lists is marked", Set.of("Muster Wohnen"), unconfirmed(m));
        final String care = "Haus 3 ist eine Jugendwohngruppe der Muster Jugendhilfe.\n\nSuchtberatung. Musterweg 28. 12345 Musterstadt.\n";
        final Extraction c = new Extraction(100);
        LlmExtractor.apply(accepted(care, new String[][] {{"facility", "Haus 3"}, {"facility", "Suchtberatung", "Suchtberatung. Musterweg 28. 12345 Musterstadt."}}),
                List.of(), c, ctx(care, "https://www.muster-jugendhilfe.de/").withReplay());
        assertEquals(Set.of("Haus 3", "Suchtberatung"), named(c, Vocabulary.FACILITY));
        assertEquals("a number names no place by itself", Set.of("Haus 3"), unconfirmed(c));
        final String project = "# Neubau Rathaus Musterstadt\n\nDas Rathaus Musterstadt haben wir 2025 fertiggestellt.\n\n"
                + "Muster Smart Factory\n\nNiederlassung Beispielstadt\n";
        final String[][] own = {{"facility", "Rathaus Musterstadt"}, {"facility", "Muster Smart Factory"}, {"facility", "Niederlassung Beispielstadt"}};
        final Extraction p = new Extraction(100);
        LlmExtractor.apply(accepted(project, own), List.of(), p, ctx(project, "https://www.muster.de/projekte/rathaus/").withReplay());
        assertEquals(Set.of("Muster Smart Factory", "Niederlassung Beispielstadt"), named(p, Vocabulary.FACILITY));
        assertTrue(unconfirmed(p).toString(), unconfirmed(p).isEmpty());
        // a fair's hall with its number is no confirmed place, a computer's name with a number none
        assertEquals(PlaceEvidence.Verdict.UNCLEAR, PlaceEvidence.check("Besuchen Sie uns in Halle 7, Stand 7-236.", "Halle 7",
                "Besuchen Sie uns in Halle 7, Stand 7-236.", "https://www.muster-it.de/", "site", false).verdict);
    }

    /**
     * Gate A4 (0.13.0, runs 8 and 9): a provider of care lists its own homes on its project page, which is no sign of a project there
     * (a builder's project page names the homes it built); a name the page writes across a heading's marks is found; a street with
     * its number after an activity's name in its segment states a place; a day group is a place by its kind.
     */
    @Test
    public void refusedPlacesOfTheEighthRun() {
        final String projects = "Unsere Projekte.\n## Am See.\nPflegeheim Musterstadt „Am See“ seit 01.01.2025 als neuer Standort.\n\nBeispielstadt\n\n"
                + "Wir haben auch einen Standort in Ihrer Nähe.\n### Pflegedienst Musterstadt\n### Kurzzeitpflege in Beispielstadt\n### Muster Pflege gGmbH\n";
        final String[][] homes = {{"facility", "Pflegeheim Musterstadt „Am See“"}, {"facility", "Kurzzeitpflege in Beispielstadt"},
            {"facility", "Beispielstadt"}};
        final String url = "https://www.muster-pflege.de/projekte";
        final Extraction care = new Extraction(100);
        LlmExtractor.apply(accepted(projects, homes), List.of(), care, ctx(projects, url, "care").withReplay());
        assertEquals(Set.of("Pflegeheim Musterstadt „Am See“", "Kurzzeitpflege in Beispielstadt", "Beispielstadt"), named(care, Vocabulary.FACILITY));
        assertEquals("a name without a sign of a place is unclear there, not the object of a project", Set.of("Beispielstadt"), unconfirmed(care));
        final Extraction built = new Extraction(100);
        LlmExtractor.apply(accepted(projects, homes), List.of(), built, ctx(projects, url, "construction").withReplay());
        assertTrue(named(built, Vocabulary.FACILITY).toString(), named(built, Vocabulary.FACILITY).isEmpty());
        assertTrue(ctx(projects, url, "care").operatesPlaces());
        assertFalse(ctx(projects, url, "construction").operatesPlaces());
        assertFalse("every vocabulary of the page's collections must say so",
                new ExtractContext(KgVocabularies.get(), Set.of("care", "construction"), false, List.of("muster-web")).operatesPlaces());
        assertFalse(new ExtractContext(KgVocabularies.get(), Set.of(), false, List.of("muster-web")).operatesPlaces());

        final String parcs = "Unsere Häuser im Norden.\n\nMuster parc.\n\n#### Beispielstadt.\n\nMuster parc.\n\n#### Musterdorf.\n";
        assertEquals(PlaceEvidence.Verdict.UNCLEAR, PlaceEvidence.check(parcs, "Muster parc Beispielstadt", "Muster parc. #### Beispielstadt.",
                "https://www.muster-care.de/", "facility", false).verdict);
        final String places = "** Muster-Berufsfachschule für Pflege Musterstraße 14. 12345 Musterstadt. **0123/45 67 89. Zur Einrichtung "
                + "** Muster-Erziehungsberatung Beispielweg 15. 12345 Musterstadt. **0123/45 67 80. Zur Einrichtung\n";
        assertEquals(PlaceEvidence.Verdict.PLACE, PlaceEvidence.check(places, "Muster-Erziehungsberatung", "Muster-Erziehungsberatung",
                "https://www.muster-beratung.de/", "facility", false).verdict);
        assertEquals("an activity's name stays refused without its address", PlaceEvidence.Verdict.REFUSED, PlaceEvidence.check(
                "Leistungen | Erziehungsberatung | Kontakt", "Erziehungsberatung", "Erziehungsberatung", "https://www.muster-beratung.de/",
                "facility", false).verdict);
        assertEquals(PlaceEvidence.Verdict.PLACE, PlaceEvidence.check("Die Tagesgruppe Musterstadt betreut zwölf Kinder.", "Tagesgruppe Musterstadt",
                "Die Tagesgruppe Musterstadt betreut zwölf Kinder.", "https://www.muster-jugendhilfe.de/", "facility", false).verdict);
    }

    /**
     * Gate A4 (0.13.0, run 9): a name whose words the page writes with a soft hyphen; an address in the lines after the name (a menu
     * with the footer's address below is none); the operator's branches a reference page names; a name with its number only a menu
     * lists.
     */
    @Test
    public void refusedPlacesOfTheNinthRun() {
        final String parcs = "Unsere Häuser im Norden.\n\nMuster parc.\n\n#### Beispiel\u00adstadt.\n\nMuster parc.\n\n#### Muster\u00addorf.\n";
        assertEquals(PlaceEvidence.Verdict.UNCLEAR, PlaceEvidence.check(parcs, "Muster parc Beispielstadt", "Muster parc. #### Beispielstadt.",
                "https://www.muster-care.de/", "facility", false).verdict);
        final String block = "Kinder im Alter bis drei Jahren.\n## Muster Kurzzeit-Kinderbetreuung\nBeispielweg 19\n12345 Musterstadt\nMehr lesen\n"
                + "##### Muster Zentralverwaltung\nMusterstraße 106\n12345 Musterstadt\nTelefon 0123 456\n";
        for (final String name : List.of("Muster Kurzzeit-Kinderbetreuung", "Muster Zentralverwaltung")) {
            assertEquals(name, PlaceEvidence.Verdict.PLACE, PlaceEvidence.check(block, name, name, "https://www.muster-kinder.de/kontakt/",
                    "facility", false).verdict);
        }
        final String menu = "Startseite\nLeistungen\nHochbau\nKontakt\nMusterstraße 5\n12345 Musterstadt\n";
        assertEquals("a menu's entry with the footer's address below stays an activity", PlaceEvidence.Verdict.REFUSED,
                PlaceEvidence.check(menu, "Hochbau", "Hochbau", "https://www.muster-bau.de/", "facility", false).verdict);
        final String company = "Gute Arbeit seit vielen Jahren.\nMuster Hochbau\nMusterstraße 5\n12345 Musterstadt\n";
        assertEquals("the address of a longer name is none of a word of it", PlaceEvidence.Verdict.REFUSED,
                PlaceEvidence.check(company, "Hochbau", "Hochbau", "https://www.muster-bau.de/", "facility", false).verdict);
        final String sentence = "Hochbau\nWir bauen seit 1990 von der Musterstraße 5 in 12345 Musterstadt aus Häuser für unsere Kunden.\n";
        assertEquals("a sentence with an address after the name is no address of it", PlaceEvidence.Verdict.REFUSED,
                PlaceEvidence.check(sentence, "Hochbau", "Hochbau", "https://www.muster-bau.de/", "facility", false).verdict);
        final String references = "### Beispiel AG. Muster Hamburg. Modern Workplace.\n### Beispiel Klinik. Muster Kassel. Rechenzentrum.\n";
        final String url = "https://www.muster-it.de/referenzen";
        assertEquals("the operator's branch", PlaceEvidence.Verdict.UNCLEAR,
                PlaceEvidence.check(references, "Muster Hamburg", "Muster Hamburg.", url, "facility", false).verdict);
        assertEquals("a client", PlaceEvidence.Verdict.REFUSED,
                PlaceEvidence.check(references, "Beispiel Klinik", "Beispiel Klinik.", url, "facility", false).verdict);
        final String homes = "Unsere Häuser | Mustersonne 1 | Mustersonne 3 | Kontakt";
        assertEquals(PlaceEvidence.Verdict.UNCLEAR, PlaceEvidence.check(homes, "Mustersonne 3", "Mustersonne 3", "https://www.muster-pflege.de/",
                "facility", false).verdict);
        final String careProjects = "Projekte | Unsere Häuser | St. Muster | Haus Beispiel | Kontakt";
        assertEquals("a care provider's home in the menu of its project page", PlaceEvidence.Verdict.UNCLEAR, PlaceEvidence.check(careProjects,
                "St. Muster", "St. Muster", "https://www.muster-pflege.de/projekte/", "facility", false, true).verdict);
        assertEquals(PlaceEvidence.Verdict.REFUSED, PlaceEvidence.check(careProjects, "St. Muster", "St. Muster",
                "https://www.muster-pflege.de/projekte/", "facility", false, false).verdict);
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

    /**
     * Gate A4 (0.13.0): the mark of an unclear place takes none of the page's limit of statements. On a full page the marks never
     * displace a place that a release before published; an item the limit cuts carries no mark.
     */
    @Test
    public void aMarkTakesNoPlaceOfThePagesLimit() {
        final String text = "Die Gärtnerei Muster gehört seit 1990 zu uns und beliefert viele Kunden mit Pflanzen.\n\n"
                + "Die Gärtnerei Beispiel gehört seit 2001 zu uns und pflegt die Gärten vieler Kunden.\n\nHaus Linde\nLindenweg 3\n34117 Kassel\n";
        final String[][] places = {{"facility", "Gärtnerei Muster"}, {"facility", "Gärtnerei Beispiel"}, {"facility", "Haus Linde"}};
        final Extraction full = new Extraction(3);
        LlmExtractor.apply(accepted(text, places), List.of(), full, ctx(text, "https://www.muster-pflege.de/").withReplay());
        assertEquals("three names within a limit of three", Set.of("Gärtnerei Muster", "Gärtnerei Beispiel", "Haus Linde"), stated(full));
        assertEquals(Set.of("Gärtnerei Muster", "Gärtnerei Beispiel"), unconfirmed(full));
        assertEquals(0, full.droppedClaims());
        // a limit of two cuts the third name; the cut one is no unclear place of the page
        final String[][] reversed = {{"facility", "Haus Linde"}, {"facility", "Gärtnerei Muster"}, {"facility", "Gärtnerei Beispiel"}};
        final Extraction cut = new Extraction(2);
        LlmExtractor.apply(accepted(text, reversed), List.of(), cut, ctx(text, "https://www.muster-pflege.de/").withReplay());
        assertEquals(Set.of("Haus Linde", "Gärtnerei Muster"), stated(cut));
        assertEquals(Set.of("Gärtnerei Muster"), unconfirmed(cut));
    }
}
