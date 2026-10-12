/*
 *  JobAdHintsTest
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * Software and system hints of job advertisements (0.10.2): read for the declared employer also without job objects, kept as
 * a requirement where they are one, never attributed to an intermediary or its client, independent of the job display; and
 * the per-collection policy (on unless switched off, never part of the extractor identity).
 */
public class JobAdHintsTest {

    /** Job objects and hints of job advertisements as given; examples only. */
    private static ExtractContext ctx(final boolean jobs, final boolean hints) {
        return new ExtractContext(KgVocabularies.get(), Set.of(), jobs, hints, List.of("example-web"));
    }

    private static Extraction jsonld(final String block, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(200);
        new JsonLdExtractor(200).extract(List.of(block), url, java.net.URI.create(url).getHost(), "de", ex, ctx);
        return ex;
    }

    private static Extraction rules(final String text, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), List.of());
        return ex;
    }

    /** {@code subject name|product|context|section} of every system signal. */
    private static List<String> hints(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.SYSTEM_SIGNAL.equals(c.predicate) && c.value != null) {
                final JSONObject v = Values.json(c.value);
                final Mention m = ex.mention(c.subject);
                out.add((Vocabulary.JOB.equals(m.type) ? "job:" : "") + m.name + "|" + v.optString("product") + "|" + v.optString("context") + "|"
                        + v.optString("section"));
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean jobObject(final Extraction ex) {
        return ex.mentions().stream().anyMatch(m -> Vocabulary.JOB.equals(m.type));
    }

    private static final String POSTING = "{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\",\"description\":\"Wir nutzen SAP S/4HANA"
            + " intern. Kenntnisse in Salesforce sind wünschenswert.\",\"skills\":\"SAP\",\"hiringOrganization\":{\"@type\":\"Organization\","
            + "\"name\":\"Muster Handel GmbH\",\"url\":\"https://www.muster-handel.example/\"}}";

    @Test
    public void theHintsOfAnAdvertisementBelongToItsDeclaredEmployerAlsoWithoutJobObjects() {
        final Extraction ex = jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter", ctx(false, true));
        assertFalse("no job object", jobObject(ex));
        assertEquals(List.of("Muster Handel GmbH|salesforce|desirable_competence|job_ad_description",
                "Muster Handel GmbH|sap-s4hana|internal_use|job_ad_description",
                "Muster Handel GmbH|sap|required_competence|job_ad_skills"), hints(ex));
        // a requirement stays a requirement: no internal use, no need and no role of the company from the advertisement
        for (final Claim c : ex.claims()) {
            assertFalse(c.toString(), Vocabulary.BUSINESS_NEED_SIGNAL.equals(c.predicate) || Vocabulary.BUSINESS_ROLE_EVIDENCE.equals(c.predicate));
        }
    }

    @Test
    public void withJobObjectsTheHintsStayOnTheJobAsBefore() {
        final Extraction ex = jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter", ctx(true, true));
        assertTrue(jobObject(ex));
        assertEquals(List.of("job:Buchhalter (m/w/d)|salesforce|desirable_competence|description",
                "job:Buchhalter (m/w/d)|sap-s4hana|internal_use|description", "job:Buchhalter (m/w/d)|sap|required_competence|skills"), hints(ex));
        // the constructor of before 0.10.2 reads hints exactly where it reads job objects
        assertEquals(hints(ex), hints(jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter",
                new ExtractContext(KgVocabularies.get(), Set.of(), true, List.of("example-web")))));
        assertEquals(List.of(), hints(jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter",
                new ExtractContext(KgVocabularies.get(), Set.of(), false, List.of("example-web")))));
    }

    @Test
    public void switchedOffNothingIsReadFromTheAdvertisement() {
        assertEquals(List.of(), hints(jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter", ctx(false, false))));
        final Extraction objects = jsonld(POSTING, "https://www.muster-handel.example/stellen/buchhalter", ctx(true, false));
        assertTrue("job objects without hints", jobObject(objects));
        assertEquals(List.of(), hints(objects));
    }

    @Test
    public void anIntermediaryOrAnUndeclaredEmployerGetsNothing() {
        final String agency = POSTING.replace("Wir nutzen SAP S/4HANA intern.", "Im Auftrag unseres Kunden: Wir nutzen SAP S/4HANA intern.");
        assertEquals("whose systems these are stays unclear", List.of(),
                hints(jsonld(agency, "https://www.agentur.example/jobs/1", ctx(false, true))));
        final String none = "{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\",\"description\":\"Wir nutzen SAP S/4HANA intern.\"}";
        assertEquals(List.of(), hints(jsonld(none, "https://www.portal.example/jobs/2", ctx(false, true))));
        final String two = POSTING.replace("\"hiringOrganization\":{", "\"hiringOrganization\":[{\"@type\":\"Organization\",\"name\":\"Zweite AG\"},{")
                .replace("example/\"}}", "example/\"}]}");
        assertEquals("two declared employers: ambiguous", List.of(), hints(jsonld(two, "https://www.portal.example/jobs/3", ctx(false, true))));
    }

    @Test
    public void aCareersPageGivesHintsOnlyWhereItDeclaresItsOperatorTheEmployer() {
        final String own = "Karriere. Wir suchen Buchhalter (m/w/d) in Vollzeit. Wir nutzen SAP S/4HANA intern. SAP Kenntnisse erforderlich. "
                + "Muster Handel GmbH, Musterstraße 1, 10115 Berlin.";
        final Extraction ex = rules(own, "https://www.muster-handel.example/karriere", ctx(false, true));
        assertFalse(jobObject(ex));
        final List<String> found = hints(ex);
        assertTrue(found.toString(), found.stream().anyMatch(h -> h.endsWith("|sap|required_competence|job_ad_text")));
        assertTrue(found.toString(), found.stream().anyMatch(h -> h.endsWith("|sap-s4hana|internal_use|job_ad_text")));
        final String agency = own.replace("Wir suchen", "Für unseren Kunden suchen wir");
        assertEquals("an intermediary's page", List.of(), hints(rules(agency, "https://www.agentur.example/karriere", ctx(false, true))));
        final String neutral = own.replace("Wir suchen ", "");
        assertEquals("no declared employer", List.of(), hints(rules(neutral, "https://www.portal.example/karriere", ctx(false, true))));
        assertEquals("switched off", List.of(), hints(rules(own, "https://www.muster-handel.example/karriere", ctx(false, false))));
    }

    @Test
    public void theSharedParserReadsAnAdvertisementAsAPosting() {
        // a company's own competence statement stays no requirement; the same sentence in an advertisement is one
        final String quote = "SAP Kenntnisse erforderlich.";
        assertEquals("stated_competence", context(BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL, quote, "text", Vocabulary.ORGANIZATION, "A GmbH")));
        assertEquals("required_competence", context(BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL, quote, BusinessSignals.JOB_AD + "text",
                Vocabulary.ORGANIZATION, "A GmbH")));
        assertEquals("customer_projects", context(BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL, "Wir arbeiten mit SAP in Kundenprojekten.",
                BusinessSignals.JOB_AD + "text", Vocabulary.ORGANIZATION, "A GmbH")));
    }

    private static String context(final List<String> values) {
        assertEquals(values.toString(), 1, values.size());
        return Values.json(values.get(0)).optString("context");
    }

    @Test
    public void thePolicyIsOnUnlessSwitchedOffAndNoPartOfTheExtractorIdentity() {
        final KgConfig plain = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "a,b"));
        assertTrue(plain.jobSignalsRead("a") && plain.jobSignalsRead("b"));
        assertEquals("signals", plain.jobMode("a"));
        final KgConfig off = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "a,b", KgConfig.JOBS_SIGNALS_PREFIX + "a", "false"));
        assertFalse("an explicit switch-off is kept", off.jobSignalsRead("a"));
        assertTrue(off.jobSignalsRead("b"));
        assertEquals("none", off.jobMode("a"));
        final KgConfig list = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "a,b", KgConfig.JOBS_SIGNALS_COLLECTIONS, "b"));
        assertFalse(list.jobSignalsRead("a"));
        assertTrue(list.jobSignalsRead("b"));
        final Map<String, String> star = KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.JOBS_SIGNALS_PREFIX + "a", "false",
                KgConfig.JOBS_SIGNALS_COLLECTIONS, "*");
        final KgConfig all = KgConfig.read(star::get, star.keySet());
        assertFalse("under * the per-collection setting counts", all.jobSignalsRead("a"));
        assertTrue(all.jobSignalsRead("c"));
        assertFalse("the list key is no collection", all.jobsSignalOverrides.containsKey("collections"));
        assertEquals("the hints never change the extractor identity", plain.extractionKey(), off.extractionKey());
        assertEquals(plain.extractionKey(), list.extractionKey());
        final KgConfig objects = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "a", KgConfig.JOBS_COLLECTIONS, "a"));
        assertEquals("full", objects.jobMode("a"));
        assertTrue(objects.jobSignalsFor(List.of("a", "x")));
        assertFalse(off.jobSignalsFor(List.of("a", "x")));
        // display and matching stay their own policies
        assertFalse(plain.jobsShown("a"));
        assertFalse(plain.jobSignalsMatching("a"));
        assertFalse(plain.jobsExtracted("a"));
    }
}
