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
import static org.junit.Assert.assertNotEquals;
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
        return rules(text, url, List.of(), ctx);
    }

    /** With the page title as Solr has it. */
    private static Extraction rules(final String text, final String url, final List<String> titles, final ExtractContext ctx) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, titles, List.of());
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

    /** The names of the job objects of an extraction, sorted. */
    private static List<String> jobNames(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.JOB.equals(m.type)) {
                out.add(m.name);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 0.10.2 regression: two gender markers without a break between them ("Buchhalter (m/w/d) oder Controller (m/w/d)", a
     * page text without line breaks) gave the second title a start before the end of the first, so the window of the first
     * ended before it began and the extraction of the page failed with a range error, again on every retry.
     */
    @Test
    public void titlesInOneSentenceDoNotOverlapAndThePageIsRead() {
        final String list = "Karriere bei Muster Handel. Wir suchen: Buchhalter (m/w/d) oder Controller (m/w/d) in Vollzeit."
                + " Wir nutzen SAP S/4HANA intern. SAP Kenntnisse erforderlich. Muster Handel GmbH, Musterstraße 1, 10115 Berlin.";
        final String url = "https://www.muster-handel.example/karriere";
        final List<String> found = hints(rules(list, url, ctx(false, true)));
        assertTrue(found.toString(), found.stream().anyMatch(h -> h.endsWith("|sap|required_competence|job_ad_text")));
        assertTrue(found.toString(), found.stream().anyMatch(h -> h.endsWith("|sap-s4hana|internal_use|job_ad_text")));
        final Extraction objects = rules(list, url, ctx(true, true));
        assertEquals("each title once, the joining word not part of it", List.of("Buchhalter (m/w/d)", "Controller (m/w/d)"), jobNames(objects));
        // the declaration inside the first title stays its own: the second one keeps no employer from it, nothing fails
        final String inline = list.replace("Wir suchen: ", "Wir suchen ");
        rules(inline, url, ctx(false, true));
        assertEquals(List.of("Buchhalter (m/w/d)", "Controller (m/w/d)"), jobNames(rules(inline, url, ctx(true, true))));
    }

    /** The menu of the careers pages of a site (neutral example): the open positions, then the other pages of the section. */
    private static final String MENU = "Jobs & Karriere Stellenangebote Freiberuflicher SAP-Berater (m/w/d) SAP Basis Administrator (m/w/d)"
            + " Projektleiter IT (m/w/d) Benefits Team Kultur ";
    private static final String IMPRINT = " Muster Software GmbH, Musterstraße 1, 10115 Berlin.";
    private static final String DETAIL = "Freiberuflicher SAP-Berater (m/w/d) Ihre Aufgaben: Beratung unserer Kunden zu SAP S/4HANA."
            + " Ihr Profil: SAP Kenntnisse erforderlich. Wir bieten ein starkes Team, bei uns arbeitest du remote."
            + " Bewerbung an jobs@muster-software.example.";

    private static List<String> jobKeys(final Extraction ex) {
        final List<String> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.JOB.equals(m.type)) {
                out.add(m.jobKey);
            }
        }
        return out;
    }

    /**
     * Production 0.10.4 (owner finding): every page of a careers section (its URL makes it a careers page) repeats the menu of
     * the open positions, and each title with a gender marker became a job of that page; a benefits page gave the open
     * positions of the menu, with the benefits text as their evidence. A menu entry, a teaser link and a general careers text
     * are no advertisement.
     */
    @Test
    public void menuEntriesOnACareersPageAreNoJobs() {
        final String benefits = MENU + "Benefits bei uns Wir bieten flexible Arbeitszeiten, Teilzeit und Vollzeit, Weiterbildung und ein"
                + " starkes Team. Jetzt bewerben!" + IMPRINT;
        final String url = "https://www.muster-software.example/jobs-karriere/team-werden/benefits/";
        assertEquals(List.of(), jobNames(rules(benefits, url, List.of("Benefits | Muster Software"), ctx(true, true))));
        // one open position in the menu, and the general text after it
        final String one = benefits.replace("SAP Basis Administrator (m/w/d) Projektleiter IT (m/w/d) ", "");
        assertEquals(List.of(), jobNames(rules(one, url, List.of("Benefits | Muster Software"), ctx(true, true))));
        // teaser links of an overview page: title, a few words, the next title
        final String overview = "Offene Stellen Freiberuflicher SAP-Berater (m/w/d) Remote mehr erfahren SAP Basis Administrator (m/w/d)"
                + " Berlin mehr erfahren Projektleiter IT (m/w/d) Hamburg mehr erfahren" + IMPRINT;
        assertEquals(List.of(), jobNames(rules(overview, "https://www.muster-software.example/jobs-karriere/stellenangebote/",
                List.of("Stellenangebote | Muster Software"), ctx(true, true))));
    }

    /** A position is the job of its own page: named by the page title, or with its own advertisement text; never its menu entries. */
    @Test
    public void aPositionBelongsToItsDetailPage() {
        final String url = "https://www.muster-software.example/jobs-karriere/stellenangebote/freiberuflicher-sap-berater/";
        final Extraction ex = rules(MENU + DETAIL + IMPRINT, url, List.of("Freiberuflicher SAP-Berater (m/w/d) | Muster Software"), ctx(true, true));
        assertEquals(List.of("Freiberuflicher SAP-Berater (m/w/d)"), jobNames(ex));
        final Mention job = ex.mentions().stream().filter(m -> Vocabulary.JOB.equals(m.type)).findFirst().get();
        assertTrue("its application route is the one of its text", ex.claims().stream().anyMatch(c -> c.subject.equals(job.ref)
                && Vocabulary.APPLICATION_ROUTE.equals(c.predicate) && "mailto:jobs@muster-software.example".equals(c.value)));
        // a page title that does not name it: its own advertisement text (tasks, profile) still makes it the page's job
        assertEquals(List.of("Freiberuflicher SAP-Berater (m/w/d)"), jobNames(rules(MENU + DETAIL + IMPRINT, url,
                List.of("Stellenangebot | Muster Software"), ctx(true, true))));
        // without job objects, the hints of the advertisement are read from its own text
        final List<String> found = hints(rules(MENU + DETAIL + IMPRINT, url, List.of("Freiberuflicher SAP-Berater (m/w/d) | Muster Software"),
                ctx(false, true)));
        assertTrue(found.toString(), found.stream().anyMatch(h -> h.endsWith("|sap|required_competence|job_ad_text")));
    }

    /** The identity of a position does not depend on where its title stands in the page (a menu that grows moves it). */
    @Test
    public void aPositionKeepsItsIdentityWhenTheMenuChanges() {
        final String url = "https://www.muster-software.example/jobs-karriere/stellenangebote/freiberuflicher-sap-berater/";
        final List<String> titles = List.of("Freiberuflicher SAP-Berater (m/w/d) | Muster Software");
        final List<String> before = jobKeys(rules(MENU + DETAIL + IMPRINT, url, titles, ctx(true, true)));
        final List<String> after = jobKeys(rules(MENU.replace("Benefits", "Werkstudent Data Engineering (m/w/d) Benefits") + DETAIL + IMPRINT, url,
                titles, ctx(true, true)));
        assertEquals(1, before.size());
        assertEquals(before, after);
        // a different position on its own page stays a different job
        final List<String> other = jobKeys(rules(MENU + DETAIL.replace("Freiberuflicher SAP-Berater", "SAP Basis Administrator") + IMPRINT,
                "https://www.muster-software.example/jobs-karriere/stellenangebote/sap-basis-administrator/",
                List.of("SAP Basis Administrator (m/w/d) | Muster Software"), ctx(true, true)));
        assertEquals(1, other.size());
        assertNotEquals(before.get(0), other.get(0));
    }

    /** A page with several complete advertisements keeps each of them. */
    @Test
    public void severalAdvertisementsOnOnePageStaySeparateJobs() {
        final String text = "Karriere. Wir suchen: Pflegefachkraft (m/w/d) in Vollzeit. Vergütung: 3.400 – 3.900 € brutto monatlich."
                + " Bewerbungsfrist: 31.03.2027. Pflegehelfer (m/w/d) in Teilzeit. Bewerbungsfrist: 31.12.2026. Hauswirtschaftskraft (m/w/d)"
                + " in Minijob. Bewerbung an jobs@beispiel-pflege.example. Beispiel Pflege gGmbH, Pflegeweg 2, 20095 Hamburg.";
        assertEquals(List.of("Hauswirtschaftskraft (m/w/d)", "Pflegefachkraft (m/w/d)", "Pflegehelfer (m/w/d)"),
                jobNames(rules(text, "https://www.beispiel-pflege.example/karriere", List.of("Karriere | Beispiel Pflege"), ctx(true, true))));
    }

    // ---- a consultancy's careers section, reconstructed (neutral) from the excerpts of the production measurement of 0.10.4; the
    // ---- sentences of its advertisements are the tests' own, not the wording of those pages

    private static final String CONSULTING = "https://www.muster-consulting.example";
    /** The menu of every page: the open positions, which differ only by their specialisation after the marker. */
    private static final String CONSULTING_MENU = "Startseite. - Leistungen. - Jobs & Karriere. - Stellenangebote. - Freiberufliche SAP Senior"
            + " Berater (m/w/d) SAC. - Freiberufliche SAP Senior Berater (m/w/d) Finance. Suche nach:. ** ";
    private static final String CONSULTING_IMPRINT = " Muster Consulting AG, Musterweg 1, 20095 Hamburg.";

    /** The page of a position: its title line, the menu, then the advertisement under its heading. */
    private static String advertisement(final String specialisation, final String body) {
        return "Stellenangebote — Freiberufliche SAP Senior Berater (m/w/d) " + specialisation + ". Skip to content - * " + CONSULTING_MENU
                + "Freiberufliche SAP Senior Berater (m/w/d) " + specialisation + ". " + body + " Bewirb Dich ganz unkompliziert über unser."
                + " Bewerbungsformular. Ansprechpartnerin Erika Musterfrau." + CONSULTING_IMPRINT;
    }

    private static final String FINANCE = advertisement("Finance", "Zur Verstärkung unseres Teams suchen wir Dich für Projekte im"
            + " Rechnungswesen. Deine Aufgaben:. - Beratung zu SAP S/4HANA Finance. - Konzeption und Customizing. Dein Profil:. - Erfahrung"
            + " mit Advanced Financial Closing. - Internationale Rechnungslegung. - SAP New GL.");
    private static final String SAC = advertisement("SAC", "Zur Verstärkung unseres Teams suchen wir Dich für Projekte im Reporting."
            + " Deine Aufgaben:. - Dashboards mit SAP Analytics Cloud. - Planung und Datenmodelle. Dein Profil:. - Erfahrung mit SAP BW.");

    private static List<Mention> jobMentions(final Extraction ex) {
        final List<Mention> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.JOB.equals(m.type)) {
                out.add(m);
            }
        }
        return out;
    }

    private static List<Claim> claimsOf(final Extraction ex, final Mention m) {
        final List<Claim> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(m.ref)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> values(final Extraction ex, final Mention m, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : claimsOf(ex, m)) {
            if (predicate.equals(c.predicate)) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static int at(final Claim c) {
        return Integer.parseInt(c.locator.replaceAll("^text:(\\d+)\\D.*$", "$1"));
    }

    /**
     * Production 0.10.4 (owner measurement): the page of one advertisement gave four jobs ("e — Freiberufliche SAP Senior Berater
     * (m/w/d)" from its title line "Stellenangebote — …", the two entries of the menu, the heading), all without the specialisation
     * "Finance", and the employment type "internship" from the word "Internationale". The page of a position has one job: its full
     * title, freelance by its title, every piece of evidence in the advertisement, the name at its heading.
     */
    @Test
    public void theAdvertisementOfAConsultancyIsOneJobWithItsFullTitleAndItsOwnEvidence() {
        final String url = CONSULTING + "/freiberufl-sap-senior-berater-finance/";
        final Extraction ex = rules(FINANCE, url, List.of("Stellenangebote — Freiberufliche SAP Senior Berater (m/w/d) Finance"), ctx(true, true));
        final List<Mention> jobs = jobMentions(ex);
        assertEquals(jobs.toString(), 1, jobs.size());
        final Mention job = jobs.get(0);
        assertEquals("Freiberufliche SAP Senior Berater (m/w/d) Finance", job.name);
        assertEquals("freelance by its title; \"Internationale Rechnungslegung\" names no internship", List.of("freelance"),
                values(ex, job, Vocabulary.EMPLOYMENT_TYPE));
        final int heading = FINANCE.indexOf("** Freiberufliche") + 3;
        final int end = FINANCE.indexOf(CONSULTING_IMPRINT);
        for (final Claim c : claimsOf(ex, job)) {
            assertTrue(c + " lies in the advertisement", at(c) >= heading && at(c) < end);
        }
        assertEquals("the name at the heading of the advertisement", List.of("text:" + heading + "+" + job.name.length()),
                claimsOf(ex, job).stream().filter(c -> Vocabulary.NAME.equals(c.predicate)).map(c -> c.locator).toList());
        assertEquals("the application route of its text", List.of(url), values(ex, job, Vocabulary.APPLICATION_ROUTE));
        // a page title that does not name the position: the shortened path ("freiberufl-…") names it, the title stays complete
        assertEquals(List.of("Freiberufliche SAP Senior Berater (m/w/d) Finance"), jobNames(rules(FINANCE, url,
                List.of("Stellenangebot | Muster Consulting"), ctx(true, true))));
    }

    /** Positions that differ only by their specialisation stay separate jobs: on their own pages and on one page with both ads. */
    @Test
    public void positionsThatDifferByTheirSpecialisationStaySeparate() {
        final Extraction finance = rules(FINANCE, CONSULTING + "/freiberufl-sap-senior-berater-finance/",
                List.of("Stellenangebote — Freiberufliche SAP Senior Berater (m/w/d) Finance"), ctx(true, true));
        final Extraction sac = rules(SAC, CONSULTING + "/freiberufl-sap-senior-berater-sac/",
                List.of("Stellenangebote — Freiberufliche SAP Senior Berater (m/w/d) SAC"), ctx(true, true));
        assertEquals(List.of("Freiberufliche SAP Senior Berater (m/w/d) SAC"), jobNames(sac));
        assertEquals(1, jobKeys(sac).size());
        assertNotEquals(jobKeys(finance), jobKeys(sac));
        final String both = "Karriere. Freiberufliche SAP Senior Berater (m/w/d) Finance. Ihre Aufgaben: Abschlüsse nach HGB und IFRS."
                + " Freiberufliche SAP Senior Berater (m/w/d) SAC. Ihre Aufgaben: Dashboards und Planung. Bei uns arbeitest du remote."
                + CONSULTING_IMPRINT;
        assertEquals(List.of("Freiberufliche SAP Senior Berater (m/w/d) Finance", "Freiberufliche SAP Senior Berater (m/w/d) SAC"),
                jobNames(rules(both, CONSULTING + "/jobs-karriere/stellenangebote/", List.of("Stellenangebote | Muster Consulting"), ctx(true, true))));
    }

    /**
     * Production 0.10.4: the benefits and the culture page of the careers section had the positions of the menu as jobs, with "bei
     * uns … gut bezahlt" as the evidence of their employer and the application form of the page as their route. No job, no employer.
     */
    @Test
    public void theBenefitsAndTheCultureOfACareersSectionGiveNoJob() {
        final String benefits = "Benefits für maximale Flexibilität. Skip to content - * " + CONSULTING_MENU + "Benefits für maximale"
                + " Flexibilität. Wir sorgen dafür, dass Du optimale Arbeitsbedingungen vorfindest. Darüber hinaus wirst Du bei uns natürlich"
                + " gut bezahlt." + CONSULTING_IMPRINT;
        final String culture = "Unternehmenskultur. Skip to content - * " + CONSULTING_MENU + "Unternehmenskultur. Bei uns hilft jede"
                + " Kollegin und jeder Kollege. Bei unseren Meetings und Events fühlen sich alle willkommen. Bewirb Dich ganz unkompliziert über"
                + " unser. Bewerbungsformular." + CONSULTING_IMPRINT;
        for (final String[] page : List.of(new String[] {benefits, "/jobs-karriere/team-werden/benefits/", "Benefits für maximale Flexibilität"},
                new String[] {culture, "/jobs-karriere/team-werden/unternehmenskultur/", "Unternehmenskultur"})) {
            final Extraction ex = rules(page[0], CONSULTING + page[1], List.of(page[2]), ctx(true, true));
            assertEquals(page[1], List.of(), jobMentions(ex));
            assertTrue(page[1], ex.claims().stream().noneMatch(c -> Vocabulary.HIRING_ORGANIZATION.equals(c.predicate)
                    || Vocabulary.APPLICATION_ROUTE.equals(c.predicate) || Vocabulary.EMPLOYMENT_TYPE.equals(c.predicate)));
        }
    }

    /** The term "intern" starts "Internationale", "Internet", "interne": no internship; a title states its kind of employment. */
    @Test
    public void internationalOrInternalIsNoInternship() {
        final String url = "https://www.muster-handel.example/karriere";
        final Extraction ex = rules("Karriere. Wir suchen Buchhalter (m/w/d) in Vollzeit. Internationale Rechnungslegung, Recherche im Internet"
                + " und interne Schulungen." + CONSULTING_IMPRINT, url, List.of("Karriere"), ctx(true, true));
        assertEquals(List.of("full_time"), values(ex, jobMentions(ex).get(0), Vocabulary.EMPLOYMENT_TYPE));
        final Extraction english = rules("Careers. We are hiring a Marketing Intern (m/f/d) for six months." + CONSULTING_IMPRINT,
                "https://www.muster-handel.example/careers", List.of("Careers"), ctx(true, true));
        assertEquals(List.of("internship"), values(english, jobMentions(english).get(0), Vocabulary.EMPLOYMENT_TYPE));
        final Extraction intern = rules("Karriere. Wir suchen eine Praktikantin Marketing (m/w/d) für sechs Monate." + CONSULTING_IMPRINT, url,
                List.of("Karriere"), ctx(true, true));
        assertEquals(List.of("internship"), values(intern, jobMentions(intern).get(0), Vocabulary.EMPLOYMENT_TYPE));
        // a compound in a title states nothing: the head of a training centre is no apprenticeship
        final Extraction lead = rules("Karriere. Wir suchen Ausbildungsleiter (m/w/d) in Vollzeit." + CONSULTING_IMPRINT, url, List.of("Karriere"),
                ctx(true, true));
        assertEquals(List.of("full_time"), values(lead, jobMentions(lead).get(0), Vocabulary.EMPLOYMENT_TYPE));
    }

    @Test
    public void noArrangementOfGenderMarkersBreaksTheExtraction() {
        final String[] joins = {" ", " oder ", " und ", ", ", " / ", " & ", "", " - ", ". ", ": ", "\n", " bzw. ", " sowie "};
        final String[] titles = {"Buchhalter", "Pflegefachkraft", "Wir suchen Controller", "IT-Administrator", "Koch", "AB"};
        final String[] markers = {"(m/w/d)", "(w/m/d)", "(gn)", "( m / w / d )"};
        int pages = 0;
        for (final String join : joins) {
            for (int a = 0; a < titles.length; a++) {
                final StringBuilder text = new StringBuilder("Karriere. Wir suchen: ");
                for (int k = 0; k < 4; k++) {
                    text.append(titles[(a + k) % titles.length]).append(' ').append(markers[(a + k) % markers.length]).append(join);
                }
                text.append("Wir nutzen SAP S/4HANA intern. SAP Kenntnisse erforderlich. Gehalt 3.500 EUR brutto monatlich. Bewerbung bis 31.12.2026"
                        + " an jobs@muster-handel.example. Standort: Berlin");
                for (final boolean jobs : new boolean[] {true, false}) {
                    final Extraction ex = rules(text.toString(), "https://www.muster-handel.example/karriere", ctx(jobs, true));
                    for (final String name : jobNames(ex)) {
                        final java.util.regex.Matcher g = java.util.regex.Pattern.compile("\\([^)]*\\)").matcher(name);
                        int markersInName = 0;
                        while (g.find()) {
                            markersInName++;
                        }
                        assertEquals("one title, one marker: " + name + " in " + text, 1, markersInName);
                    }
                    pages++;
                }
            }
        }
        assertEquals(joins.length * titles.length * 2, pages);
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
