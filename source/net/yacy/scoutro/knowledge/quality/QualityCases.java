/*
 *  QualityCases
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

package net.yacy.scoutro.knowledge.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * The reference pages of the model quality check (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 3): example pages of invented
 * organisations, each with the statements a correct reading must make and those it must not make. The expectations are
 * set by hand from the page text, never from the answer of a model. A change of a page, an expectation or the rules of
 * {@link QualityEvaluator} is a new {@link #VERSION}: earlier results are then stale.
 */
public final class QualityCases {

    public static final String ID = "kg-reference";
    public static final String VERSION = "1";
    /**
     * The collection the example pages belong to. It is only a name for the reading (the LLM tier follows it and reads job
     * postings in it); the graph's own collections and their settings are not used.
     */
    public static final String COLLECTION = "scoutro-quality-check";

    /** Any type ({@link Ref#type}). */
    public static final String ANY = "*";

    /** An entity by its type and the names a correct reading may give it (compared normalised). */
    public static final class Ref {
        public final String type;
        public final List<String> names;

        Ref(final String type, final String... names) {
            this.type = type;
            this.names = List.of(names);
        }

        JSONObject json() {
            return KgJson.obj("type", this.type, "names", new JSONArray(this.names));
        }

        @Override
        public String toString() {
            return this.names.isEmpty() ? "(anyone)" : this.type + " “" + this.names.get(0) + "”";
        }
    }

    /** What a value statement must state: a price (amount, currency, unit, VAT), a code, or any value at all. */
    public static final class Value {
        /** A price: the amount as the page states it in a decimal ("28.50"), the currency, the unit and the VAT condition; null fields are not compared. */
        public final String amount, currency, unit, vat;
        /** A code the value must be (e.g. {@code full_time}); null for none. */
        public final String code;
        /** For a forbidden value: any value of the predicate. */
        public final boolean any;
        /** For a forbidden price: every amount except these is forbidden (the page states only these). */
        public final List<String> except;

        private Value(final String amount, final String currency, final String unit, final String vat, final String code, final boolean any,
                final List<String> except) {
            this.amount = amount;
            this.currency = currency;
            this.unit = unit;
            this.vat = vat;
            this.code = code;
            this.any = any;
            this.except = except;
        }

        static Value price(final String amount, final String currency, final String unit, final String vat) {
            return new Value(amount, currency, unit, vat, null, false, null);
        }

        static Value code(final String code) {
            return new Value(null, null, null, null, code, false, null);
        }

        static Value anyValue() {
            return new Value(null, null, null, null, null, true, null);
        }

        static Value amountOtherThan(final String... amounts) {
            return new Value(null, null, null, null, null, false, List.of(amounts));
        }

        JSONObject json() {
            return KgJson.obj("amount", this.amount, "currency", this.currency, "unit", this.unit, "vat", this.vat, "code", this.code,
                    "any", this.any ? Boolean.TRUE : null, "except", this.except == null ? null : new JSONArray(this.except));
        }
    }

    /** One statement: an entity ({@code name}), a relation between two entities or a value of an entity. */
    public static final class Statement {
        public static final String ENTITY = "entity", RELATION = "relation", VALUE = "value";

        public final String kind;
        public final Ref subject;
        public final String predicate;
        public final Ref object;
        public final Value value;
        /** A passage the evidence of the statement must contain (compared normalised); null for none. */
        public final String evidence;
        /** Why the statement is expected or forbidden. */
        public final String reason;

        private Statement(final String kind, final Ref subject, final String predicate, final Ref object, final Value value,
                final String evidence, final String reason) {
            this.kind = kind;
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
            this.value = value;
            this.evidence = evidence;
            this.reason = reason;
        }

        JSONObject json() {
            return KgJson.obj("kind", this.kind, "subject", this.subject.json(), "predicate", this.predicate,
                    "object", this.object == null ? null : this.object.json(), "value", this.value == null ? null : this.value.json(),
                    "evidence", this.evidence, "reason", this.reason);
        }

        /** A short form for the result view: {@code service "Tagespflege"}, {@code job "…" hiring_organization organization "…"}. */
        public String label() {
            switch (this.kind) {
                case ENTITY:
                    return this.subject.toString();
                case RELATION:
                    return this.subject + " " + this.predicate + " " + (this.object == null ? "*" : this.object.toString());
                default:
                    return this.subject + " " + this.predicate + " " + label(this.value);
            }
        }

        private static String label(final Value v) {
            if (v == null || v.any) {
                return "(any value)";
            }
            if (v.except != null) {
                return "(an amount other than " + String.join(", ", v.except) + ")";
            }
            if (v.code != null) {
                return v.code;
            }
            return (v.amount == null ? "?" : v.amount) + " " + (v.currency == null ? "" : v.currency) + (v.unit == null ? "" : " / " + v.unit)
                    + (v.vat == null ? "" : ", VAT " + v.vat);
        }
    }

    static Statement entity(final Ref r, final String evidence, final String reason) {
        return new Statement(Statement.ENTITY, r, Vocabulary.NAME, null, null, evidence, reason);
    }

    static Statement relation(final Ref s, final String predicate, final Ref o, final String evidence, final String reason) {
        return new Statement(Statement.RELATION, s, predicate, o, null, evidence, reason);
    }

    static Statement value(final Ref s, final String predicate, final Value v, final String evidence, final String reason) {
        return new Statement(Statement.VALUE, s, predicate, null, v, evidence, reason);
    }

    /** One reference page with its expectations. */
    public static final class Case {
        public final String id;
        /** The kind of mistake the page checks for. */
        public final String checks;
        public final String url;
        public final String title;
        public final String language;
        public final String text;
        /** Statements a correct reading makes (missing ones are deviations). */
        public final List<Statement> expected;
        /** Statements a correct reading never makes. */
        public final List<Statement> forbidden;

        Case(final String id, final String checks, final String url, final String title, final String language, final String text,
                final List<Statement> expected, final List<Statement> forbidden) {
            this.id = id;
            this.checks = checks;
            this.url = url;
            this.title = title;
            this.language = language;
            this.text = text;
            this.expected = Collections.unmodifiableList(expected);
            this.forbidden = Collections.unmodifiableList(forbidden);
        }

        public JSONObject json() {
            final JSONArray e = new JSONArray();
            for (final Statement s : this.expected) {
                e.put(s.json());
            }
            final JSONArray f = new JSONArray();
            for (final Statement s : this.forbidden) {
                f.put(s.json());
            }
            return KgJson.obj("id", this.id, "checks", this.checks, "url", this.url, "title", this.title, "language", this.language,
                    "chars", this.text.length(), "expected", e, "forbidden", f);
        }
    }

    private static Ref org(final String... names) {
        return new Ref(Vocabulary.ORGANIZATION, names);
    }

    private static Ref service(final String... names) {
        return new Ref(Vocabulary.SERVICE, names);
    }

    private static Ref job(final String... names) {
        return new Ref(Vocabulary.JOB, names);
    }

    private static Ref any(final String... names) {
        return new Ref(ANY, names);
    }

    /** Any organisation or facility (the object of a forbidden relation that must not exist at all). */
    private static final Ref ANYONE = new Ref(ANY);

    public static final List<Case> CASES;

    static {
        final List<Case> c = new ArrayList<>();

        // 1. the operator's own job advertisement: the job, its employer and the employment type the page states
        final Ref nurse = job("Pflegefachkraft (m/w/d)", "Pflegefachkraft");
        final Ref care = org("Muster Pflege gGmbH", "Muster Pflege");
        c.add(new Case("own_job_ad", "own job advertisement", "https://www.muster-pflege.example/karriere/pflegefachkraft",
                "Pflegefachkraft (m/w/d) – Muster Pflege gGmbH", "de",
                "Karriere bei der Muster Pflege gGmbH. Die Muster Pflege gGmbH sucht ab sofort eine Pflegefachkraft (m/w/d) in Vollzeit"
                        + " für das Haus Lindenhof in Musterstadt. Ihre Aufgaben: Grundpflege und Behandlungspflege. Wir bieten eine"
                        + " Vergütung nach Tarif und 30 Urlaubstage. Bewerben Sie sich über unser Bewerbungsformular.",
                List.of(entity(nurse, "Pflegefachkraft (m/w/d)", "the page advertises the position with its title"),
                        relation(nurse, Vocabulary.HIRING_ORGANIZATION, care, "Muster Pflege gGmbH sucht",
                                "the operator itself is looking for the employee"),
                        value(nurse, Vocabulary.EMPLOYMENT_TYPE, Value.code("full_time"), "Vollzeit", "the page states full time")),
                List.of(entity(service("Bewerbungsformular"), null, "an application form of the page is no service"),
                        entity(job("Grundpflege", "Behandlungspflege"), null, "a task of the position is no job of its own"))));

        // 2. an intermediary: the agency recruits for a client it does not name; the employer stays unknown
        final Ref mechanic = job("Industriemechaniker (m/w/d)", "Industriemechaniker");
        final Ref agency = org("Beispiel Personal GmbH", "Beispiel Personal");
        c.add(new Case("intermediary", "recruitment agency for an unnamed client", "https://www.beispiel-personal.example/jobs/industriemechaniker",
                "Industriemechaniker (m/w/d) – Beispiel Personal GmbH", "de",
                "Beispiel Personal GmbH – Ihre Personalvermittlung. Für unseren Kunden, ein mittelständisches Maschinenbauunternehmen in"
                        + " Musterstadt, suchen wir einen Industriemechaniker (m/w/d) in Vollzeit. Ihr Ansprechpartner bei der Beispiel"
                        + " Personal GmbH freut sich auf Ihre Bewerbung.",
                List.of(entity(mechanic, "Industriemechaniker (m/w/d)", "the page advertises the position with its title")),
                List.of(relation(mechanic, Vocabulary.HIRING_ORGANIZATION, agency, null,
                                "the agency recruits; it is not the employer"),
                        relation(mechanic, Vocabulary.HIRING_ORGANIZATION, ANYONE, null,
                                "the employer is not named: it stays unknown"))));

        // 3. a job portal: the portal lists the advertisement of a named employer
        final Ref site = job("Bauleiter (m/w/d)", "Bauleiter");
        final Ref builder = org("Beispiel Bau GmbH", "Beispiel Bau");
        final Ref portal = any("Musterjobs");
        c.add(new Case("job_portal", "job portal listing another employer's advertisement", "https://www.musterjobs.example/stellen/bauleiter-musterstadt",
                "Bauleiter (m/w/d) in Musterstadt – Musterjobs", "de",
                "Musterjobs – das Stellenportal für die Region. Stellenangebot: Die Beispiel Bau GmbH sucht einen Bauleiter (m/w/d) für"
                        + " Projekte in Musterstadt. Bewerbungen nimmt die Beispiel Bau GmbH direkt entgegen. Weitere Stellen finden Sie"
                        + " in unserer Jobsuche.",
                List.of(entity(site, "Bauleiter (m/w/d)", "the page lists the position with its title"),
                        relation(site, Vocabulary.HIRING_ORGANIZATION, builder, "Beispiel Bau GmbH sucht",
                                "the named company is looking for the employee")),
                List.of(relation(site, Vocabulary.HIRING_ORGANIZATION, portal, null, "the portal only lists the advertisement"),
                        entity(service("Jobsuche"), null, "a search function of the portal is no service of an organisation"))));

        // 4. teams of a contact page are no services; the services the page lists are
        final Ref heating = service("Heizungswartung");
        final Ref bath = service("Badsanierung");
        final Ref plumber = org("Muster Haustechnik GmbH", "Muster Haustechnik");
        c.add(new Case("team_vs_service", "a team named next to the offered services", "https://www.muster-haustechnik.example/kontakt",
                "Kontakt – Muster Haustechnik GmbH", "de",
                "Kontakt zur Muster Haustechnik GmbH. Unsere Leistungen: Heizungswartung und Badsanierung. Unser Serviceteam erreichen"
                        + " Sie werktags von 8 bis 16 Uhr. Das Kundendienst-Team beantwortet Ihre Fragen gern.",
                List.of(entity(heating, "Heizungswartung", "listed under the services"),
                        entity(bath, "Badsanierung", "listed under the services"),
                        relation(plumber, Vocabulary.OFFERS, heating, "Leistungen", "the company offers it")),
                List.of(entity(any("Serviceteam", "Service team"), null, "a team is no service and no organisation of its own"),
                        entity(any("Kundendienst-Team", "Kundendienst Team", "Kundendienst"), null, "a team is no service"))));

        // 5. the tools of the page next to a product the company sells
        final Ref product = service("MusterText Pro");
        final Ref vendor = org("Muster Software GmbH", "Muster Software");
        c.add(new Case("page_tool_vs_product", "the tools of the page next to a product sold", "https://www.muster-software.example/produkte/mustertext",
                "MusterText Pro – Muster Software GmbH", "de",
                "MusterText Pro. Die Muster Software GmbH verkauft MusterText Pro, eine Software für Kanzleien, die Schriftsätze auf"
                        + " Tippfehler prüft. Sie können MusterText Pro als Jahreslizenz kaufen.\nWerkzeuge\nSeite drucken\nLinks auf diese"
                        + " Seite\nRechtschreibprüfung\nSeiteninformationen",
                List.of(entity(product, "MusterText Pro", "the company sells it"),
                        relation(vendor, Vocabulary.OFFERS, product, "verkauft MusterText Pro", "the company sells it")),
                List.of(entity(service("Seite drucken"), null, "a tool of the page is no service"),
                        entity(service("Links auf diese Seite"), null, "a tool of the page is no service"),
                        entity(service("Rechtschreibprüfung"), null, "here a tool of the page, not offered"),
                        entity(service("Seiteninformationen"), null, "a tool of the page is no service"))));

        // 6. a navigation around the page's own content: the content's service, not the menu entries
        final Ref dayCare = service("Tagespflege");
        final Ref carer = org("Muster Pflege gGmbH", "Muster Pflege");
        final String menu = "Startseite | Über uns | Leistungen | Karriere | Kontakt | Impressum";
        c.add(new Case("navigation_next_to_content", "a navigation around the page's own content", "https://www.muster-pflege.example/leistungen/tagespflege",
                "Tagespflege – Muster Pflege gGmbH", "de",
                menu + "\nTagespflege im Haus Lindenhof. Die Muster Pflege gGmbH bietet in Musterstadt Tagespflege für Senioren an,"
                        + " montags bis freitags von 8 bis 16 Uhr.\n" + menu,
                List.of(entity(dayCare, "Tagespflege", "the page's content offers it"),
                        relation(carer, Vocabulary.OFFERS, dayCare, "bietet in Musterstadt Tagespflege", "the operator offers it")),
                List.of(entity(job("Karriere"), null, "a menu entry is no job posting"),
                        entity(service("Kontakt", "Impressum", "Über uns", "Startseite", "Leistungen"), null,
                                "a menu entry is no service"))));

        // 7. prices with their service, unit and conditions; no price on the wrong service
        final Ref cleaning = service("Unterhaltsreinigung");
        final Ref windows = service("Glasreinigung");
        c.add(new Case("price_with_service", "prices with their service, unit and conditions", "https://www.muster-reinigung.example/preise",
                "Preise – Muster Gebäudereinigung GmbH", "de",
                "Preise der Muster Gebäudereinigung GmbH. Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt., die"
                        + " Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt. bei mindestens zwei Stunden je Auftrag.",
                List.of(entity(cleaning, "Unterhaltsreinigung", "a service with its price"),
                        entity(windows, "Glasreinigung", "a service with its price"),
                        value(cleaning, Vocabulary.PRICE, Value.price("28.50", "EUR", Values.UNIT_HOUR, "excl"), "28,50 €",
                                "28.50 EUR per hour plus VAT, the price of this service"),
                        value(windows, Vocabulary.PRICE, Value.price("4.20", "EUR", Values.UNIT_HOUR, "excl"), "4,20 €",
                                "4.20 EUR per hour plus VAT, the price of this service")),
                List.of(value(cleaning, Vocabulary.PRICE, Value.amountOtherThan("28.50"), null, "the other service's price"),
                        value(windows, Vocabulary.PRICE, Value.amountOtherThan("4.20"), null, "the other service's price"))));

        // 8. software the company offers competence in, next to software it only uses internally
        final Ref erp = service("Beispiel-ERP-Beratung", "Beispiel ERP Beratung", "Beratung zu Beispiel ERP");
        final Ref office = any("Muster Office");
        c.add(new Case("software_competence_vs_internal_use", "software offered as competence next to software used internally",
                "https://www.muster-it.example/leistungen", "Leistungen – Muster IT-Beratung GmbH", "de",
                "Leistungen der Muster IT-Beratung GmbH. Wir bieten Beispiel-ERP-Beratung für mittelständische Unternehmen an. Für"
                        + " unsere eigene Büroorganisation nutzen wir intern Muster Office.",
                List.of(entity(erp, "Beispiel-ERP-Beratung", "the company offers it")),
                List.of(entity(office, null, "software used internally is no service of the company"))));

        // 9. two prices of the same service: only stated amounts, no computed or merged one
        final Ref fee = service("Tagespflege");
        c.add(new Case("contradictory_sources", "two different prices of one service on the page", "https://www.muster-tagespflege.example/kosten",
                "Kosten – Muster Tagespflege gGmbH", "de",
                "Kosten der Tagespflege. Bei der Muster Tagespflege gGmbH kostet die Tagespflege 89 € pro Tag. Preisliste 2025:"
                        + " Tagespflege 95 € pro Tag.",
                List.of(entity(fee, "Tagespflege", "the service the prices belong to")),
                List.of(value(fee, Vocabulary.PRICE, Value.amountOtherThan("89", "95"), null,
                        "only the amounts the page states; never an average or another value"))));

        // 10. what the page does not state stays unknown
        final Ref helper = job("Lagerhelfer (m/w/d)", "Lagerhelfer");
        final Ref logistics = org("Muster Logistik GmbH", "Muster Logistik");
        c.add(new Case("missing_stays_unknown", "a job advertisement without employment type and salary",
                "https://www.muster-logistik.example/jobs/lagerhelfer", "Lagerhelfer (m/w/d) – Muster Logistik GmbH", "de",
                "Die Muster Logistik GmbH sucht einen Lagerhelfer (m/w/d) für den Standort Musterstadt. Ihre Aufgaben: Kommissionierung"
                        + " und Verpackung von Waren. Bewerbungen bitte per Post.",
                List.of(entity(helper, "Lagerhelfer (m/w/d)", "the page advertises the position with its title"),
                        relation(helper, Vocabulary.HIRING_ORGANIZATION, logistics, "Muster Logistik GmbH sucht",
                                "the operator itself is looking for the employee")),
                List.of(value(helper, Vocabulary.EMPLOYMENT_TYPE, Value.anyValue(), null, "the page states no employment type"),
                        value(helper, Vocabulary.SALARY, Value.anyValue(), null, "the page states no salary"),
                        entity(service("Kommissionierung", "Kommissionierung und Verpackung von Waren"), null,
                                "a task of the position is no service the page offers"))));

        CASES = Collections.unmodifiableList(c);
    }

    private QualityCases() {
    }

    /** The case with this id, or null. */
    public static Case get(final String id) {
        for (final Case c : CASES) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** The test set as the API shows it: id, version, collection and the cases with their expectations. */
    public static JSONObject json() {
        final JSONArray cases = new JSONArray();
        for (final Case c : CASES) {
            cases.put(c.json());
        }
        return KgJson.obj("id", ID, "version", VERSION, "evaluator", QualityEvaluator.VERSION, "cases", cases);
    }
}
