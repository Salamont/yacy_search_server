/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * K2 and K6 of the validation of the facilities' first stage on the production graph (docs/SCOUTRO_KG_FACILITIES.md), on the LLM
 * tier's application of an answer ({@link LlmExtractor#apply}) to invented pages as YaCy's parser gives them. The quotes of the
 * production graph showed 86 facility addresses read on imprint pages, 20 equal to an organisation's address of the domain, and
 * names that are a town or a firm confirmed by the address after them. A facility's full address after its name is its address only
 * <ul>
 * <li>where the page proves a place (an unclear name keeps its mark {@code type_unconfirmed} and gets no address);</li>
 * <li>never on an imprint page, never inside the page's imprint, never the address of a known organisation of the page;</li>
 * <li>never for a name that is a firm's (its legal form) or the address's town.</li>
 * </ul>
 * A named house of a provider's overview keeps its address (M3 as before).
 */
public class PlaceAddressRegressionTest {

    private static final String SUN = "https://www.sonnen-pflege.de";

    private static Mention operator(final String name) {
        final Mention m = new Mention(RuleExtractor.OPERATOR_REF, Vocabulary.ORGANIZATION, 2);
        m.name = name;
        m.siteOperator = true;
        return m;
    }

    private static ExtractContext ctx(final String text, final String title, final String url) {
        return new ExtractContext(KgVocabularies.get(), Set.of("care"), false, List.of("care-web")).withServiceText(text)
                .withServiceTitle(title, operator("Sonnen Pflege gGmbH")).withPage(url);
    }

    /** The answer: facilities {name, quote} at the quote's place in the text. */
    private static JSONObject accepted(final String text, final String... nameAndQuote) {
        final JSONArray es = new JSONArray();
        for (int i = 0; i + 1 < nameAndQuote.length; i += 2) {
            es.put(KgJson.obj("id", "e" + (i / 2 + 1), "type", Vocabulary.FACILITY, "name", nameAndQuote[i], "kind", "nursinghome", "quote",
                    nameAndQuote[i + 1], "at", Math.max(0, text.indexOf(nameAndQuote[i + 1]))));
        }
        return KgJson.obj("entities", es, "claims", new JSONArray(), "values", new JSONArray());
    }

    private static Extraction apply(final String file, final String url, final List<LlmExtractor.Known> known, final String... nameAndQuote)
            throws Exception {
        final String[] p = FacilityPageTest.parsed(file, url);
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(p[0], nameAndQuote), known, ex, ctx(p[0], p[1], url));
        return ex;
    }

    private static Mention facility(final Extraction ex, final String name) {
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.FACILITY.equals(m.type) && name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    /** Values of a mention's claims (and marks, which the extraction keeps among its claims) of a predicate. */
    private static List<String> values(final Extraction ex, final Mention m, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(m.ref) && c.predicate.equals(predicate)) {
                out.add(String.valueOf(c.value));
            }
        }
        return out;
    }

    @Test
    public void aNamedHouseOfAnOverviewKeepsItsAddress() throws Exception {
        final Extraction ex = apply("care-facilities-overview.html", "https://www.muster-altenhilfe.de/einrichtungen/", List.of(),
                "Haus Lindenhof", "betreibt das Haus Lindenhof");
        final Mention f = facility(ex, "Haus Lindenhof");
        assertNotNull(ex.mentions().toString(), f);
        assertEquals(List.of("Lindenweg 12, 12345 Musterstadt"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("12345"), values(ex, f, Vocabulary.POSTAL_CODE));
        assertNotNull(f.address);
    }

    @Test
    public void onAnImprintPageAHouseGetsNoAddress() throws Exception {
        final Extraction ex = apply("sun-imprint.html", SUN + "/impressum/", List.of(), "Haus Sonnenhof", "Haus Sonnenhof");
        final Mention f = facility(ex, "Haus Sonnenhof");
        assertNotNull(ex.mentions().toString(), f);
        assertEquals(List.of(), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of(), values(ex, f, Vocabulary.POSTAL_CODE));
        assertEquals(null, f.address);
    }

    /** The same imprint's lines as the footer of another page: the imprint's address is the operator's, not the house's. */
    @Test
    public void insideAPagesImprintAHouseGetsNoAddress() throws Exception {
        final Extraction ex = apply("sun-about-footer-imprint.html", SUN + "/ueber-uns/", List.of(), "Haus Lindenhof", "Das Haus Lindenhof",
                "Haus Sonnenhof", "Haus Sonnenhof");
        assertEquals("above the imprint: the house's own", List.of("Lindenweg 12, 12345 Musterstadt"),
                values(ex, facility(ex, "Haus Lindenhof"), Vocabulary.ADDRESS));
        assertEquals("inside the imprint: none", List.of(), values(ex, facility(ex, "Haus Sonnenhof"), Vocabulary.ADDRESS));
    }

    /**
     * The imprint rules gave the page's operator this address (its imprint or its footer block): it is the operator's. A structured
     * organisation that only got the page's one address by the rules' fallback does not count (the address may be the house's).
     */
    @Test
    public void theAddressOfThePagesImprintOperatorIsNoHousesAddress() throws Exception {
        final Mention org = operator("Sonnen Pflege gGmbH");
        org.address = Address.of("Rosenweg 5", "12345", "Musterstadt", null);
        final Extraction ex = apply("sun-facility-sonnenhof.html", SUN + "/einrichtungen/haus-sonnenhof/", List.of(new LlmExtractor.Known("k1", org)),
                "Haus Sonnenhof", "Das Haus Sonnenhof bietet 60 Plätze");
        final Mention f = facility(ex, "Haus Sonnenhof");
        assertNotNull(ex.mentions().toString(), f);
        assertEquals(List.of(), values(ex, f, Vocabulary.ADDRESS));
        // without that organisation the house has its address
        final Extraction plain = apply("sun-facility-sonnenhof.html", SUN + "/einrichtungen/haus-sonnenhof/", List.of(),
                "Haus Sonnenhof", "Das Haus Sonnenhof bietet 60 Plätze");
        assertEquals(List.of("Rosenweg 5, 12345 Musterstadt"), values(plain, facility(plain, "Haus Sonnenhof"), Vocabulary.ADDRESS));
        // a structured organisation with that address (the rules' fallback): the house keeps it
        final Mention structured = new Mention("jsonld:0", Vocabulary.ORGANIZATION, 1);
        structured.name = "Sonnen Pflege gGmbH";
        structured.address = Address.of("Rosenweg 5", "12345", "Musterstadt", null);
        final Extraction fallback = apply("sun-facility-sonnenhof.html", SUN + "/einrichtungen/haus-sonnenhof/",
                List.of(new LlmExtractor.Known("k1", structured)), "Haus Sonnenhof", "Das Haus Sonnenhof bietet 60 Plätze");
        assertEquals(List.of("Rosenweg 5, 12345 Musterstadt"), values(fallback, facility(fallback, "Haus Sonnenhof"), Vocabulary.ADDRESS));
    }

    @Test
    public void aTownAndAFirmTakenForFacilitiesGetNoAddress() throws Exception {
        final Extraction ex = apply("sun-news.html", SUN + "/ueber-uns/sommerfest/", List.of(), "Bachstadt", "Unser Sommerfest in Bachstadt",
                "Sonnen Pflege gGmbH", "Sonnen Pflege gGmbH");
        for (final String name : new String[] {"Bachstadt", "Sonnen Pflege gGmbH"}) {
            final Mention f = facility(ex, name);
            if (f != null) {
                assertEquals(name, List.of(), values(ex, f, Vocabulary.ADDRESS));
                assertEquals(name, null, f.address);
            }
        }
        assertNotNull("the firm is still read, only without an address", facility(ex, "Sonnen Pflege gGmbH"));
    }

    @Test
    public void anUnclearNameKeepsItsMarkAndGetsNoAddress() throws Exception {
        final String url = SUN + "/angebote/wohnen/";
        final String[] p = FacilityPageTest.parsed("sun-living.html", url);
        assertEquals("the name is no proven place", PlaceEvidence.Verdict.UNCLEAR,
                PlaceEvidence.check(p[0], "Sonnenschein", "Sonnenschein", url, Vocabulary.FACILITY, false, true).verdict);
        final Extraction ex = apply("sun-living.html", url, List.of(), "Sonnenschein", "Sonnenschein");
        final Mention f = facility(ex, "Sonnenschein");
        assertNotNull(ex.mentions().toString(), f);
        assertEquals(List.of(), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of(Vocabulary.FACILITY), values(ex, f, Vocabulary.TYPE_UNCONFIRMED));
    }

    /** 0.15.0: the last word of a sentence is no part of the street after it ("Wohnen im Alter. Sonnenweg 3"). */
    @Test
    public void aStreetStartsAfterTheSentenceBeforeIt() throws Exception {
        final String[] p = FacilityPageTest.parsed("sun-living.html", SUN + "/angebote/wohnen/");
        final PlaceEvidence.Place place = PlaceEvidence.fullPlace(p[0], "Sonnenschein");
        assertNotNull(place);
        assertEquals("Sonnenweg 3, 12345 Musterstadt", place.address.display());
        final PlaceEvidence.Place saint = PlaceEvidence.fullPlace("Haus Anna\nSt. Anna-Straße 5, 12345 Musterstadt", "Haus Anna");
        assertNotNull(saint);
        assertTrue(saint.address.display(), saint.address.display().startsWith("St. Anna-Straße 5"));
    }

    @Test
    public void placeAddressRefusesFirmsAndTowns() {
        final ExtractContext c = ctx("x", null, SUN + "/");
        final PlaceEvidence.Place p = new PlaceEvidence.Place(Address.of("Bachweg 7", "45678", "Bachstadt", null), 0, 1, 0, 1);
        assertFalse(LlmExtractor.placeAddress(p, "Sonnen Pflege gGmbH", c, List.of()));
        assertFalse(LlmExtractor.placeAddress(p, "Bachstadt", c, List.of()));
        assertTrue(LlmExtractor.placeAddress(p, "Haus am Bach", c, List.of()));
    }
}
