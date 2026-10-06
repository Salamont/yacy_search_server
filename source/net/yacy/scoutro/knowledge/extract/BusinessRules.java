/*
 *  BusinessRules
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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.TermMatcher;

/**
 * Tier 2, the business rules of vocabulary 2 (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 23): deterministic reading of a page's visible text for the organisation
 * the page belongs to.
 * <ul>
 * <li>services: categories of the collection's vocabulary named on a
 * services, prices or home page (a home page alone is a weak signal);</li>
 * <li>prices: an amount with an explicit currency, attached to the service
 * named right before it ("Tagespflege ab 49 € pro Tag") or to the label of
 * a price list line; unit, kind, conditions, care level, own share, VAT note
 * and the page's "Stand" as written; nothing is estimated;</li>
 * <li>relations to other organisations after explicit markers (partners,
 * cooperations, customers, references, memberships, certifications,
 * funding, sponsoring, carrier, parent and subsidiaries, suppliers, service
 * providers, brands); a name is an organisation only with a legal form, an
 * institution word or as a one-word brand in such a list, never a person;
 * the other organisation stays local to the page (no merge by name);</li>
 * <li>the industry stated in an imprint (chamber, trade, business purpose);</li>
 * <li>organisation contacts: fax, office and opening hours, contact form,
 * directions, company profiles, department contacts without a person;</li>
 * <li>jobs on careers pages, if switched on for the collection: titles with
 * a gender marker, employment types, the published salary with its unit,
 * deadline, start, application route; no recruiter;</li>
 * <li>the declared audience after markers ("Für wen", "Zielgruppe", "Wir
 * unterstützen", "Unsere Kunden sind"): customer types, segments, company
 * sizes, target industries, need, and the service area.</li>
 * </ul>
 * Every claim quotes its span ({@code text:offset+length}); excerpts are
 * redacted by the publisher.
 */
public final class BusinessRules {

    public static final String SITE_REF = "rule:site";
    static final double SERVICE_PAGE_CONFIDENCE = 0.65;
    static final double HOME_PAGE_CONFIDENCE = 0.5;
    static final int MAX_SERVICES = 25;
    static final int MAX_PRICES = 40;
    static final int MAX_RELATIONS = 40;
    static final int MAX_JOBS = 30;

    /** What a page is about, from its path and titles (several at once). */
    public enum PageKind { HOME, SERVICES, PRICES, CAREERS, PARTNERS, REFERENCES, ABOUT, AUDIENCE, CONTACT, IMPRINT }

    private static final Map<PageKind, Pattern> PATHS = new LinkedHashMap<>();
    static {
        PATHS.put(PageKind.SERVICES, Pattern.compile("(?i)(?:^|/)(?:leistungen|leistung|unsere-leistungen|angebot|angebote|services?|portfolio"
                + "|produkte|products|loesungen|lösungen|solutions|was-wir-tun|what-we-do|pflegeangebot|leistungsspektrum)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.PRICES, Pattern.compile("(?i)(?:^|/)(?:preise|preis|kosten|tarife|pricing|prices|price|gebuehren|gebühren|honorar"
                + "|konditionen|preisliste|entgelte)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.CAREERS, Pattern.compile("(?i)(?:^|/)(?:karriere|jobs?|stellen|stellenangebote|offene-stellen|career|careers"
                + "|jobboerse|jobbörse|wir-suchen|ausbildung|arbeiten-bei)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.PARTNERS, Pattern.compile("(?i)(?:^|/)(?:partner|partners|netzwerk|network|kooperation|kooperationen"
                + "|kooperationspartner)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.REFERENCES, Pattern.compile("(?i)(?:^|/)(?:referenzen|referenz|references|kunden|customers|clients|projekte"
                + "|projects|case-studies|cases)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.ABOUT, Pattern.compile("(?i)(?:^|/)(?:ueber-uns|uber-uns|über-uns|about|about-us|wir|unternehmen|company"
                + "|traeger|träger|organisation|mitgliedschaften|zertifikate|zertifizierungen|qualitaet|qualität)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.AUDIENCE, Pattern.compile("(?i)(?:^|/)(?:fuer-wen|für-wen|zielgruppen?|fuer-unternehmen|für-unternehmen"
                + "|fuer-privatkunden|for-business|for-whom)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.CONTACT, Pattern.compile("(?i)(?:^|/)(?:kontakt|contact|anfahrt|standort|standorte|location)(?:[./_\\-]|$)"));
        PATHS.put(PageKind.IMPRINT, Pattern.compile("(?i)(?:^|/)(?:impressum|imprint|legal-?notice)(?:[./_\\-]|$)"));
    }
    private static final Pattern TITLE_SERVICES = Pattern.compile("(?iu)\\b(?:leistungen|angebot|services|unsere\\s+leistungen)\\b");
    private static final Pattern TITLE_PRICES = Pattern.compile("(?iu)\\b(?:preise|kosten|tarife|pricing|preisliste)\\b");
    private static final Pattern TITLE_CAREERS = Pattern.compile("(?iu)\\b(?:karriere|jobs|stellenangebote?|career|careers)\\b");

    /** The kinds of a page; empty if none applies. */
    public static Set<PageKind> kinds(final String url, final List<String> titles) {
        final Set<PageKind> out = java.util.EnumSet.noneOf(PageKind.class);
        String path = null;
        try {
            path = url == null ? null : new URI(url).getRawPath();
        } catch (final Exception e) {
            path = null;
        }
        if (path == null || path.isEmpty() || "/".equals(path) || path.matches("(?i)^/(?:index\\.(?:html?|php)|home|start|de|en)/?$")) {
            out.add(PageKind.HOME);
        }
        if (path != null) {
            final String decoded = decode(path);
            for (final Map.Entry<PageKind, Pattern> e : PATHS.entrySet()) {
                if (e.getValue().matcher(decoded).find()) {
                    out.add(e.getKey());
                }
            }
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t == null) {
                    continue;
                }
                if (TITLE_SERVICES.matcher(t).find()) {
                    out.add(PageKind.SERVICES);
                }
                if (TITLE_PRICES.matcher(t).find()) {
                    out.add(PageKind.PRICES);
                }
                if (TITLE_CAREERS.matcher(t).find()) {
                    out.add(PageKind.CAREERS);
                }
            }
        }
        return out;
    }

    private static String decode(final String path) {
        try {
            return java.net.URLDecoder.decode(path, "UTF-8");
        } catch (final Exception e) {
            return path;
        }
    }

    private final int maxExcerpt;

    public BusinessRules(final int maxExcerpt) {
        this.maxExcerpt = maxExcerpt;
    }

    /** One run over one page. */
    private final class Run {
        final String text;
        final String url;
        final String host;
        final String country;
        final String callingCode;
        final Extraction out;
        final ExtractContext ctx;
        final Set<PageKind> kinds;
        final Mention subject;
        int relations;

        Run(final String text, final String url, final String host, final String language, final Extraction out, final ExtractContext ctx,
                final Set<PageKind> kinds, final Mention subject) {
            this.text = text;
            this.url = url;
            this.host = host;
            this.country = BusinessFacts.country(host);
            this.callingCode = Normalizers.countryCallingCode(host, language);
            this.out = out;
            this.ctx = ctx;
            this.kinds = kinds;
            this.subject = subject;
        }
    }

    /**
     * The subject of the page's business facts: the imprint's declared
     * operator, the only organisation or facility of the structured data, or
     * the site's unnamed operator ({@code domain_operator}).
     */
    public static Mention subject(final Extraction out, final Mention imprintOperator) {
        if (imprintOperator != null) {
            return imprintOperator;
        }
        final List<Mention> subjects = out.subjects(1);
        if (subjects.size() == 1) {
            return subjects.get(0);
        }
        if (!subjects.isEmpty()) {
            return null; // several organisations or facilities on the page: whose facts are these? nothing is guessed
        }
        final Mention site = out.add(new Mention(SITE_REF, Vocabulary.ORGANIZATION, 2));
        site.domainOperator = true;
        return site;
    }

    /**
     * Runs the rules that apply to the page's kinds. {@code subject} is the
     * organisation the facts belong to ({@link #subject}); null runs nothing.
     */
    public void extract(final String text, final String url, final String host, final String language, final List<String> outbound,
            final Extraction out, final ExtractContext ctx, final Set<PageKind> kinds, final Mention subject) {
        if (text == null || text.isEmpty() || subject == null || ctx == null) {
            return;
        }
        final Run run = new Run(text, url, host, language, out, ctx, kinds, subject);
        final boolean servicePage = kinds.contains(PageKind.SERVICES) || kinds.contains(PageKind.PRICES);
        if (servicePage || kinds.contains(PageKind.HOME)) {
            services(run, servicePage ? SERVICE_PAGE_CONFIDENCE : HOME_PAGE_CONFIDENCE);
        }
        relations(run);
        if (kinds.contains(PageKind.IMPRINT) || kinds.contains(PageKind.ABOUT)) {
            industryStatements(run);
        }
        contacts(run, outbound);
        if (ctx.jobs && kinds.contains(PageKind.CAREERS)) {
            jobs(run);
        }
        audiences(run);
        areas(run, 0, text.length(), subject, kinds.contains(PageKind.AUDIENCE) || kinds.contains(PageKind.ABOUT) || kinds.contains(PageKind.HOME)
                || kinds.contains(PageKind.CONTACT) || servicePage);
    }

    // ------------------------------------------------------------- services

    /** A service of the page, per category; with its first and every later position in the text. */
    private static final class Service {
        final Mention mention;
        final Categories.Entry category;
        final List<Integer> at = new ArrayList<>();

        Service(final Mention mention, final Categories.Entry category) {
            this.mention = mention;
            this.category = category;
        }
    }

    private void services(final Run run, final double confidence) {
        final List<TermMatcher.Hit<Categories.Entry>> hits = BusinessFacts.categories(run.ctx, run.text, 0, run.text.length());
        final Map<String, Service> services = new LinkedHashMap<>();
        final List<int[]> positions = new ArrayList<>(); // {start, end, service index}
        final List<Service> list = new ArrayList<>();
        for (final TermMatcher.Hit<Categories.Entry> h : hits) {
            Service s = services.get(h.entry.id);
            if (s == null) {
                if (services.size() >= MAX_SERVICES) {
                    continue;
                }
                final Mention m = run.out.add(new Mention(BusinessFacts.SERVICE_REF + h.entry.id, Vocabulary.SERVICE, 2));
                m.name = h.entry.de;
                s = new Service(m, h.entry);
                services.put(h.entry.id, s);
                list.add(s);
                claim(run, m, Vocabulary.NAME, h.entry.de, h.start, h.end, confidence);
                claim(run, m, Vocabulary.CATEGORY, h.entry.id, h.start, h.end, confidence);
                relation(run, run.subject, Vocabulary.OFFERS, m, h.start, h.end, confidence);
            }
            s.at.add(h.start);
            positions.add(new int[] {h.start, h.end, list.indexOf(s)});
        }
        prices(run, positions, list);
    }

    // --------------------------------------------------------------- prices

    private static final Pattern LABEL_BEFORE = Pattern.compile("(?u)(\\p{Lu}[\\p{L}\\p{N}&+./\\- ]{2,58}?)\\s*(?::|–|-|\\.{2,})\\s*$");

    private void prices(final Run run, final List<int[]> services, final List<Service> list) {
        final boolean pricePage = run.kinds.contains(PageKind.PRICES);
        if (!pricePage && !run.kinds.contains(PageKind.SERVICES) && !run.kinds.contains(PageKind.HOME)) {
            return;
        }
        final String asOf = Values.asOf(run.text);
        int n = 0;
        for (final Values.Price p : Values.prices(run.text, 0, run.text.length())) {
            if (n >= MAX_PRICES) {
                break;
            }
            // the service named right before the price (at most 150 characters back, at most two sentence ends between)
            Mention service = null;
            for (int i = services.size() - 1; i >= 0; i--) {
                final int[] s = services.get(i);
                if (s[1] <= p.start && p.start - s[1] <= 150 && breaks(run.text, s[1], p.start) <= 2 && !anotherPriceBetween(run.text, s[1], p.start)) {
                    service = list.get(s[2]).mention;
                    break;
                }
                if (s[1] <= p.start && p.start - s[1] > 150) {
                    break;
                }
            }
            double confidence = pricePage ? 0.7 : 0.6;
            if (service == null && pricePage) {
                // a price list line: "Grundpaket: 49 € / Monat"
                final int from = Math.max(0, p.start - 64);
                final Matcher lm = LABEL_BEFORE.matcher(run.text.substring(from, p.start));
                if (lm.find()) {
                    final String label = lm.group(1).trim();
                    if (label.length() >= 3 && !BusinessFacts.personLike(label) && !label.matches("(?iu).*\\b(?:preis|preise|kosten|ab|bis|von)$")
                            && !label.matches("(?iu)^(?:preis|preise|kosten|tel|telefon|fax)$")) {
                        final Mention m = run.out.add(new Mention(BusinessFacts.SERVICE_REF + "label:" + Normalizers.key(label), Vocabulary.SERVICE, 2));
                        m.name = label;
                        claim(run, m, Vocabulary.NAME, label, from + lm.start(1), from + lm.end(1), 0.65);
                        relation(run, run.subject, Vocabulary.OFFERS, m, from + lm.start(1), p.end, 0.65);
                        BusinessFacts.categorize(run.out, run.ctx, m, label, 2, Claim.KIND_RULE, loc(from + lm.start(1), from + lm.end(1)),
                                "", this.maxExcerpt);
                        service = m;
                        confidence = 0.65;
                    }
                }
            }
            if (service == null) {
                continue; // a price that belongs to nothing named is not kept
            }
            final Map<String, Object> f = new TreeMap<>(p.fields);
            if (asOf != null) {
                f.put("as_of", asOf);
            }
            final String through = Values.validThrough(run.text, p.end, Values.clauseEnd(run.text, p.end, Math.min(run.text.length(), p.end + 140)));
            if (through != null) {
                f.put("valid_through", through);
            }
            claimAround(run, service, Vocabulary.PRICE, Values.canonical(f), p.start, p.end, confidence);
            n++;
        }
    }

    private static int breaks(final String text, final int from, final int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '.' && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                n++;
            }
        }
        return n;
    }

    private static boolean anotherPriceBetween(final String text, final int from, final int to) {
        return !Values.prices(text, from, to).isEmpty();
    }

    // ------------------------------------------------------------ relations

    /** Markers of the relations, with the direction: true = page subject -> named organisation. */
    private static final class Marker {
        final Pattern pattern;
        final String predicate;
        final boolean outgoing;
        final boolean list;

        Marker(final String regex, final String predicate, final boolean outgoing, final boolean list) {
            this.pattern = Pattern.compile("(?iu)" + regex);
            this.predicate = predicate;
            this.outgoing = outgoing;
            this.list = list;
        }
    }

    private static final List<Marker> MARKERS = List.of(
            new Marker("\\b(?:in\\s+)?tr(?:ä|ae)gerschaft\\s+(?:der|des|von|vom)\\s+", Vocabulary.CARRIER_OF, false, false),
            new Marker("\\btr(?:ä|ae)ger(?:in)?(?:\\s+(?:der\\s+einrichtung|des\\s+hauses|ist))?\\s*:\\s*", Vocabulary.CARRIER_OF, false, false),
            new Marker("\\b(?:eine\\s+)?tochter(?:gesellschaft|unternehmen|firma)\\s+(?:der|des|von)\\s+", Vocabulary.SUBSIDIARY_OF, true, false),
            new Marker("\\b(?:ein\\s+)?unternehmen\\s+der\\s+", Vocabulary.SUBSIDIARY_OF, true, false),
            new Marker("\\bsubsidiary\\s+of\\s+", Vocabulary.SUBSIDIARY_OF, true, false),
            new Marker("\\b(?:unsere\\s+)?tochter(?:gesellschaften|unternehmen|firmen)\\s*:\\s*", Vocabulary.PARENT_OF, true, true),
            new Marker("\\b(?:eine\\s+)?marke\\s+der\\s+", Vocabulary.BRAND_OF, true, false),
            new Marker("\\bkooperationspartner(?:n)?\\s*:?\\s*|\\bin\\s+kooperation\\s+mit\\s+|\\bkooperieren\\s+(?:eng\\s+)?mit\\s+"
                    + "|\\bin\\s+zusammenarbeit\\s+mit\\s+", Vocabulary.COOPERATION_WITH, true, true),
            new Marker("\\b(?:unsere|our)\\s+(?:partner(?:unternehmen|firmen)?|partners)\\s*:?\\s*|\\bpartner\\s*:\\s*"
                    + "|\\bin\\s+partnerschaft\\s+mit\\s+", Vocabulary.PARTNER_OF, true, true),
            new Marker("\\b(?:unsere|our)\\s+(?:kunden|clients|customers)(?:\\s+(?:sind|wie|u\\.\\s?a\\.|unter\\s+anderem|include|including))?\\s*:?\\s*"
                    + "|\\bkunden\\s+wie\\s+|\\bauswahl\\s+unserer\\s+kunden\\s*:?\\s*|\\btrusted\\s+by\\s+|\\bkundenliste\\s*:?\\s*",
                    Vocabulary.CUSTOMER_OF, false, true),
            new Marker("\\breferenz(?:en|kunden|liste)\\s*:?\\s*|\\breferences\\s*:?\\s*", Vocabulary.REFERENCE_FOR, false, true),
            new Marker("\\bmitglied(?:schaft(?:en)?)?\\s+(?:im|in\\s+der|in\\s+den|des|der|bei)\\s+|\\bmitgliedschaften\\s*:?\\s*"
                    + "|\\bmember\\s+of\\s+(?:the\\s+)?", Vocabulary.MEMBER_OF, true, true),
            new Marker("\\bzertifiziert\\s+(?:durch|von|vom)\\s+(?:den\\s+|die\\s+|das\\s+)?|\\bzertifizierung\\s+durch\\s+(?:den\\s+|die\\s+)?"
                    + "|\\bcertified\\s+by\\s+(?:the\\s+)?", Vocabulary.CERTIFIED_BY, true, false),
            new Marker("\\bgef(?:ö|oe)rdert\\s+(?:durch|von|vom|aus\\s+mitteln\\s+(?:der|des))\\s+(?:den\\s+|die\\s+|das\\s+)?"
                    + "|\\bf(?:ö|oe)rderung\\s+durch\\s+(?:den\\s+|die\\s+|das\\s+)?|\\bfunded\\s+by\\s+(?:the\\s+)?", Vocabulary.FUNDED_BY, true, true),
            new Marker("\\bgesponsert\\s+von\\s+|\\bsponsoren\\s*:\\s*|\\bsponsored\\s+by\\s+", Vocabulary.SPONSORED_BY, true, true),
            new Marker("\\blieferant\\s+(?:von|für|fuer|der|des)\\s+", Vocabulary.SUPPLIER_OF, true, false),
            new Marker("\\bunsere\\s+lieferanten\\s*:?\\s*", Vocabulary.SUPPLIER_OF, false, true),
            new Marker("\\bdienstleister\\s+(?:für|fuer|von|der|des)\\s+", Vocabulary.SERVICE_PROVIDER_FOR, true, false));

    private static final Pattern CERTIFICATION = Pattern.compile("(?iu)\\bzertifiziert\\s+nach\\s+((?:DIN\\s+)?(?:EN\\s+)?ISO\\s?[0-9]{3,5}"
            + "(?:[:-][0-9]{1,4})?|DIN\\s+[0-9]{3,5}|AZAV|MAAS-BGW|KTQ|EFQM)|\\b(?:certified\\s+(?:to|under)|iso-zertifiziert)\\s+"
            + "((?:ISO|DIN)\\s?[0-9]{3,5}(?:[:-][0-9]{1,4})?)");
    private static final String LEGAL = "GmbH\\s*&\\s*Co\\.?\\s*KG|UG\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)|gGmbH|GmbH|gAG|KGaA|AG|e\\.\\s?V\\.|eG|"
            + "PartG(?:\\s*mbB)?|OHG|KG|GbR|SE|e\\.\\s?K\\.|Stiftung|Ltd\\.?|Inc\\.?|LLC|S\\.A\\.|B\\.V\\.|N\\.V\\.";
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + LEGAL + ")(?![\\p{L}\\p{N}])");
    private static final Pattern INSTITUTION = Pattern.compile("(?iu)(?:verband|verbandes|verein|kammer|innung|stiftung|bund\\b|gesellschaft"
            + "|akademie|institut|universit(?:ä|ae)t|hochschule|klinik|klinikum|krankenhaus|stadt\\b|gemeinde|landkreis|ministerium|agentur"
            + "|caritas|diakonie|awo\\b|drk\\b|johanniter|malteser|samariter|volkssolidarit|parit(?:ä|ae)tisch|t(?:ü|ue)v|dekra|ihk\\b|hwk\\b"
            + "|kfw\\b|bafa\\b|jobcenter|sparkasse|volksbank|bundesagentur|landesamt|bezirk|kreis\\b|gruppe\\b|holding|group\\b|association"
            + "|federation|council|chamber|foundation)");
    private static final Pattern ASSOCIATION = Pattern.compile("(?iu)verband|verbandes|innung|kammer|bund\\b|association|federation|chamber"
            + "|council|netzwerk|arbeitsgemeinschaft|vereinigung");
    private static final Pattern ITEM_SPLIT = Pattern.compile("\\s*(?:[,;•|·\\n]|\\.\\s+|\\s+und\\s+|\\s+sowie\\s+|\\s+and\\s+|\\s+&\\s+(?=\\p{Lu}\\p{L}+\\s))\\s*");
    private static final Pattern STOP_WORDS = Pattern.compile("(?iu)^(?:der|die|das|den|dem|des|the|a|an|unser(?:e|em|en|er)?|our|ein(?:e|em|en|er)?)\\s+");
    /** A word that ends a list: the next sentence starts. */
    private static final Pattern NOT_A_NAME = Pattern.compile("(?iu)^(?:wir|sie|ich|es|mehr|weitere|alle|unsere?|hier|jetzt|kontakt|impressum"
            + "|datenschutz|home|startseite|leistungen|über|ueber|news|aktuelles|zurück|weiter|mehr\\s+erfahren|termin|anfrage|copyright)\\b");

    private void relations(final Run run) {
        final Set<String> seen = new LinkedHashSet<>();
        for (final Marker mk : MARKERS) {
            final Matcher m = mk.pattern.matcher(run.text);
            while (m.find() && run.relations < MAX_RELATIONS) {
                final int from = m.end();
                final int to = Math.min(run.text.length(), from + (mk.list ? 600 : 160));
                final List<int[]> names = mk.list ? names(run.text, from, to) : first(run.text, from, to);
                for (final int[] span : names) {
                    String name = run.text.substring(span[0], span[1]).trim();
                    name = STOP_WORDS.matcher(name).replaceFirst("");
                    if (name.length() < 2 || BusinessFacts.personLike(name)) {
                        continue;
                    }
                    String predicate = mk.predicate;
                    if (Vocabulary.MEMBER_OF.equals(predicate) && ASSOCIATION.matcher(name).find()) {
                        predicate = Vocabulary.ASSOCIATION_MEMBER;
                    }
                    if (!seen.add(predicate + "\u0000" + Normalizers.key(name))) {
                        continue;
                    }
                    final Mention other = organisation(run, name, span[0], span[1]);
                    if (other == null || other.ref.equals(run.subject.ref)) {
                        continue;
                    }
                    if (mk.outgoing) {
                        relation(run, run.subject, predicate, other, m.start(), span[1], 0.65);
                    } else {
                        relation(run, other, predicate, run.subject, m.start(), span[1], 0.65);
                    }
                    run.relations++;
                }
            }
        }
        final Matcher c = CERTIFICATION.matcher(run.text);
        while (c.find()) {
            final String standard = (c.group(1) != null ? c.group(1) : c.group(2)).replaceAll("\\s+", " ").trim();
            claim(run, run.subject, Vocabulary.CERTIFICATION, standard, c.start(), c.end(), 0.7);
        }
    }

    /** An organisation named on the page: local to the page (a legal name also gets the operator-name key of the domain). */
    private Mention organisation(final Run run, final String name, final int start, final int end) {
        final String clipped = Normalizers.clip(name, 200);
        final Mention m = run.out.add(new Mention("rule:org:" + Normalizers.key(clipped), Vocabulary.ORGANIZATION, 2));
        if (m.name == null) {
            m.name = clipped;
            final String legal = Normalizers.legalName(clipped);
            if (legal != null && LEGAL_FORM.matcher(clipped).find()) {
                m.legalName = legal;
            }
            claim(run, m, Vocabulary.NAME, clipped, start, end, 0.65);
        }
        return m;
    }

    /** The organisation right after a marker: up to its legal form, or a short name with an institution word. */
    private static List<int[]> first(final String text, final int from, final int to) {
        final List<int[]> out = new ArrayList<>();
        final int end = Values.clauseEnd(text, from, to);
        final String seg = text.substring(from, end);
        final Matcher lf = LEGAL_FORM.matcher(seg);
        if (lf.find() && lf.end() <= 120) {
            out.add(new int[] {from, from + lf.end()});
            return out;
        }
        final String item = seg.split("[,;(]|\\s+(?:und|sowie|mit|für|fuer|in|an|bei|seit|zur|zum)\\s+", 2)[0].trim();
        if (qualifies(item, false)) {
            out.add(new int[] {from, from + item.length()});
        }
        return out;
    }

    /** The organisations of a list after a marker; the list ends after three items that are no names. */
    private static List<int[]> names(final String text, final int from, final int to) {
        final List<int[]> out = new ArrayList<>();
        final Matcher sp = ITEM_SPLIT.matcher(text).region(from, to);
        int start = from;
        int misses = 0;
        while (start < to && misses < 3 && out.size() < 20) {
            final int stop;
            final int next;
            if (sp.find()) {
                stop = sp.start();
                next = sp.end();
            } else {
                stop = to;
                next = to;
            }
            final String item = text.substring(start, stop).trim();
            if (!item.isEmpty()) {
                final int lead = text.indexOf(item, start);
                if (qualifies(item, true)) {
                    final Matcher lf = LEGAL_FORM.matcher(item);
                    final int len = lf.find() ? lf.end() : item.length();
                    out.add(new int[] {lead, lead + len});
                    misses = 0;
                } else {
                    misses++;
                }
            }
            start = next;
        }
        return out;
    }

    /** An organisation name: a legal form, an institution word, or (in a list) a one-word brand; never two plain capitalised words. */
    static boolean qualifies(final String item, final boolean list) {
        if (item.length() < 2 || item.length() > 100 || BusinessFacts.personLike(item) || NOT_A_NAME.matcher(item).find()) {
            return false;
        }
        if (!Character.isUpperCase(item.codePointAt(0)) && !Character.isDigit(item.charAt(0))) {
            return false;
        }
        final String[] words = item.split("\\s+");
        if (words.length > 8) {
            return false;
        }
        if (LEGAL_FORM.matcher(item).find()) {
            return true;
        }
        if (INSTITUTION.matcher(item).find()) {
            return true;
        }
        // a brand in a customer or partner list: one word ("Siemens", "DHL", "SAP"); "Max Mustermann" is no organisation
        return list && words.length == 1 && item.length() >= 2 && item.length() <= 30 && item.matches("[\\p{Lu}\\p{N}][\\p{L}\\p{N}&+.\\-]*");
    }

    // ------------------------------------------------------------- industry

    private static final Pattern INDUSTRY_MARKER = Pattern.compile("(?iu)\\b(?:berufsbezeichnung|handwerkskammer|handwerksrolle|gewerk"
            + "|unternehmensgegenstand|gegenstand\\s+des\\s+unternehmens|tätigkeitsbereich|branche|kammerzugehörigkeit|zuständige\\s+kammer)\\b");

    /** Industry the imprint or about page states (trade, chamber, business purpose): the category's NACE level near the marker. */
    private void industryStatements(final Run run) {
        final Matcher m = INDUSTRY_MARKER.matcher(run.text);
        final Set<String> done = new LinkedHashSet<>();
        while (m.find()) {
            final int to = Math.min(run.text.length(), m.end() + 200);
            for (final TermMatcher.Hit<Categories.Entry> h : BusinessFacts.categories(run.ctx, run.text, m.end(), to)) {
                if (h.entry.nace != null && run.ctx.nace.valid(h.entry.nace) && done.add(h.entry.nace)) {
                    claimAround(run, run.subject, Vocabulary.INDUSTRY, h.entry.nace, m.start(), h.end, 0.75);
                }
                if (h.entry.group != null && done.add(h.entry.group)) {
                    claimAround(run, run.subject, Vocabulary.INDUSTRY_CATEGORY, h.entry.group, m.start(), h.end, 0.75);
                }
            }
        }
    }

    // ------------------------------------------------------------- contacts

    private static final Pattern FAX = Pattern.compile("(?i)(?:\\bTele)?\\bfax(?:nummer)?\\.?\\s*:?\\s*((?:\\+|00)?[0-9][0-9 ()/\\-.]{5,24}[0-9])");
    private static final Pattern HOURS = Pattern.compile("(?iu)\\b(sprechzeiten|bürozeiten|buerozeiten|telefonzeiten|servicezeiten"
            + "|öffnungszeiten|oeffnungszeiten|opening\\s+hours|office\\s+hours)\\s*:?\\s*");
    private static final Pattern DAYS = Pattern.compile("(?iu)\\b(?:mo|di|mi|do|fr|sa|so|montag|dienstag|mittwoch|donnerstag|freitag|samstag"
            + "|sonntag|mon|tue|wed|thu|fri|sat|sun)\\b");
    private static final Pattern DIRECTIONS = Pattern.compile("(?iu)\\b(?:anfahrt|wegbeschreibung|so\\s+finden\\s+sie\\s+uns|directions|getting\\s+here)"
            + "\\s*:?\\s*");
    private static final Pattern FORM = Pattern.compile("(?iu)\\b(?:kontaktformular|contact\\s+form|formular)\\b");
    private static final Pattern DEPARTMENT = Pattern.compile("(?u)\\b(?:Abteilung|Bereich|Team|Department)\\s+(\\p{Lu}[\\p{L}\\-]{2,30}(?:\\s+(?:und|&)\\s+"
            + "\\p{Lu}[\\p{L}\\-]{2,30})?)\\s*:?");
    private static final Pattern PHONE = Pattern.compile("(?i)(?:\\bTel(?:efon)?\\.?|\\bPhone|\\bFon|\\bT\\.)\\s*:?\\s*((?:\\+|00)?[0-9][0-9 ()/\\-.]{5,24}[0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}");

    private void contacts(final Run run, final List<String> outbound) {
        final boolean contactish = run.kinds.contains(PageKind.CONTACT) || run.kinds.contains(PageKind.IMPRINT) || run.kinds.contains(PageKind.HOME)
                || run.kinds.contains(PageKind.ABOUT);
        if (!contactish) {
            return;
        }
        final Matcher fx = FAX.matcher(run.text);
        if (fx.find() && !personBefore(run.text, fx.start())) {
            final String fax = Normalizers.phone(fx.group(1), run.callingCode);
            claim(run, run.subject, Vocabulary.FAX, fax, fx.start(), fx.end(), 0.7);
        }
        final Matcher h = HOURS.matcher(run.text);
        while (h.find()) {
            final int end = Values.clauseEnd(run.text, h.end(), Math.min(run.text.length(), h.end() + 160));
            final String hours = run.text.substring(h.end(), end).trim();
            if (hours.length() >= 5 && DAYS.matcher(hours).find() && hours.matches("(?s).*[0-9].*")) {
                final String word = h.group(1).toLowerCase(Locale.ROOT);
                final String predicate = word.startsWith("ö") || word.startsWith("oe") || word.startsWith("opening") ? Vocabulary.OPENING_HOURS
                        : Vocabulary.OFFICE_HOURS;
                claim(run, run.subject, predicate, Normalizers.clip(hours, 200), h.start(), end, 0.7);
            }
        }
        final Matcher d = DIRECTIONS.matcher(run.text);
        if (d.find() && run.kinds.contains(PageKind.CONTACT)) {
            final int end = Values.clauseEnd(run.text, d.end(), Math.min(run.text.length(), d.end() + 220));
            final String text = run.text.substring(d.end(), end).trim();
            if (text.length() >= 15) {
                claim(run, run.subject, Vocabulary.DIRECTIONS, Normalizers.redactPersons(Normalizers.clip(text, 200)), d.start(), end, 0.6);
            }
        }
        if (run.kinds.contains(PageKind.CONTACT) && run.url != null) {
            final Matcher f = FORM.matcher(run.text);
            if (f.find()) {
                claim(run, run.subject, Vocabulary.CONTACT_FORM, Normalizers.url(run.url), f.start(), f.end(), 0.6);
            }
        }
        final Matcher dep = DEPARTMENT.matcher(run.text);
        int points = 0;
        while (dep.find() && points < 8) {
            final int to = Math.min(run.text.length(), dep.end() + 100);
            if (personBetween(run.text, dep.start(), to)) {
                continue; // a department with a named person: the person's line is not kept
            }
            final Map<String, Object> cp = new TreeMap<>();
            cp.put("function", dep.group(1));
            final Matcher pm = PHONE.matcher(run.text).region(dep.end(), to);
            if (pm.find()) {
                cp.put("phone", Normalizers.phone(pm.group(1), run.callingCode));
            }
            final Matcher em = EMAIL.matcher(run.text).region(dep.end(), to);
            if (em.find()) {
                final String mail = Normalizers.email(em.group());
                if (mail != null && Normalizers.roleEmail(mail)) {
                    cp.put("email", mail);
                }
            }
            cp.values().removeIf(java.util.Objects::isNull);
            if (cp.size() > 1) {
                claim(run, run.subject, Vocabulary.CONTACT_POINT, Values.canonical(cp), dep.start(), to, 0.6);
                points++;
            }
        }
        if (outbound != null) {
            final Set<String> profiles = new LinkedHashSet<>();
            for (final String stub : outbound) {
                final String profile = BusinessFacts.socialProfile(stub);
                if (profile != null && profiles.size() < 10) {
                    profiles.add(profile);
                }
            }
            for (final String p : profiles) {
                run.out.add(new Claim(run.subject.ref, Vocabulary.SOCIAL_PROFILE, null, p, 2, Claim.KIND_RULE, false, "link:" + Normalizers.clip(p, 180),
                        Normalizers.clip("link: " + p, this.maxExcerpt), 0.6));
            }
        }
    }

    /** True if a person's name is right before {@code at} (a personal extension, a recruiter). */
    private static boolean personBefore(final String text, final int at) {
        final int from = Math.max(0, at - 60);
        return Normalizers.excerpt(text, from, at).contains("[…]");
    }

    private static boolean personBetween(final String text, final int from, final int to) {
        return Normalizers.excerpt(text, from, to).contains("[…]");
    }

    // ----------------------------------------------------------------- jobs

    private static final Pattern GENDER = Pattern.compile("(?iu)\\(\\s*(?:m\\s*/\\s*w\\s*/\\s*d|w\\s*/\\s*m\\s*/\\s*d|d\\s*/\\s*m\\s*/\\s*w"
            + "|m\\s*/\\s*f\\s*/\\s*d|f\\s*/\\s*m\\s*/\\s*d|m\\s*/\\s*w\\s*/\\s*x|w\\s*/\\s*m\\s*/\\s*x|all\\s+genders|gn|w\\s*/\\s*m|m\\s*/\\s*w)\\s*\\)");
    private static final Pattern DEADLINE = Pattern.compile("(?iu)\\b(?:bewerbungsfrist|bewerbungsschluss|bewerben\\s+(?:sie\\s+sich\\s+)?bis(?:\\s+zum)?"
            + "|ausschreibung\\s+(?:gültig\\s+)?bis|gültig\\s+bis|apply\\s+by)\\s*:?\\s*((?:[0-3]?[0-9]\\.\\s?[01]?[0-9]\\.\\s?20[0-9]{2})|20[0-9]{2}-[01][0-9]-[0-3][0-9])");
    private static final Pattern START = Pattern.compile("(?iu)\\b(?:eintritt(?:sdatum)?|beginn|start(?:termin)?|ab\\s+dem|zum)\\s*:?\\s*"
            + "((?:[0-3]?[0-9]\\.\\s?[01]?[0-9]\\.\\s?20[0-9]{2})|20[0-9]{2}-[01][0-9]-[0-3][0-9])");
    private static final Pattern POSTED = Pattern.compile("(?iu)\\b(?:veröffentlicht\\s+am|online\\s+seit|ausgeschrieben\\s+am|posted\\s+on)\\s*:?\\s*"
            + "((?:[0-3]?[0-9]\\.\\s?[01]?[0-9]\\.\\s?20[0-9]{2})|20[0-9]{2}-[01][0-9]-[0-3][0-9])");
    private static final Pattern APPLY_ONLINE = Pattern.compile("(?iu)\\b(?:online\\s+bewerben|jetzt\\s+bewerben|bewerbungsformular|apply\\s+now|apply\\s+online)\\b");
    private static final Pattern SALARY_WORD = Pattern.compile("(?iu)\\b(?:gehalt|vergütung|verguetung|lohn|stundenlohn|brutto|salary|pay|entgelt|tvöd|tv-l|avr)\\b");
    private static final Pattern WORK_PLACE = Pattern.compile("(?iu)\\b(?:standort|einsatzort|arbeitsort|location)\\s*:?\\s*(\\p{Lu}[\\p{L}\\-]{2,40})");

    private void jobs(final Run run) {
        final Matcher g = GENDER.matcher(run.text);
        final List<int[]> titles = new ArrayList<>();
        while (g.find() && titles.size() < MAX_JOBS) {
            final int start = titleStart(run.text, g.start());
            if (start >= 0 && g.start() - start >= 3) {
                titles.add(new int[] {start, g.end(), g.start()});
            }
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < titles.size(); i++) {
            final int[] t = titles.get(i);
            final String title = run.text.substring(t[0], t[1]).replaceAll("\\s+", " ").trim();
            final String key = Normalizers.key(title);
            if (key == null || !seen.add(key) || BusinessFacts.personLike(title)) {
                continue;
            }
            final int windowEnd = i + 1 < titles.size() ? titles.get(i + 1)[0] : Math.min(run.text.length(), t[1] + 1200);
            final Mention job = run.out.add(new Mention("rule:job:" + key, Vocabulary.JOB, 2));
            job.name = title;
            final Matcher wp = WORK_PLACE.matcher(run.text).region(t[1], windowEnd);
            final String place = wp.find() ? wp.group(1) : null;
            job.jobKey = (run.subject.name == null ? "" : Normalizers.key(run.subject.name)) + "|" + key + "|"
                    + (place == null ? "" : Normalizers.key(place));
            claim(run, job, Vocabulary.NAME, title, t[0], t[1], 0.7);
            relation(run, job, Vocabulary.HIRING_ORGANIZATION, run.subject, t[0], t[1], 0.7);
            final Set<String> types = new LinkedHashSet<>();
            for (final TermMatcher.Hit<Categories.Entry> h : run.ctx.categories.employmentTypes(run.text, t[1], Math.min(windowEnd, t[1] + 400))) {
                if (types.add(h.entry.id)) {
                    claim(run, job, Vocabulary.EMPLOYMENT_TYPE, h.entry.id, h.start, h.end, 0.7);
                }
            }
            for (final Values.Price p : Values.prices(run.text, t[1], windowEnd)) {
                final String unit = (String) p.fields.get("unit");
                final int from = Math.max(t[1], p.start - 60);
                if (unit != null && !Values.UNIT_OTHER.equals(unit) && !Values.UNIT_ONCE.equals(unit)
                        && SALARY_WORD.matcher(run.text.substring(from, Math.min(run.text.length(), p.end + 30))).find()) {
                    final Map<String, Object> f = new TreeMap<>(p.fields);
                    f.remove("conditions");
                    f.remove("care_level");
                    f.remove("own_share");
                    f.remove("vat");
                    final String clause = run.text.substring(p.start, Values.clauseEnd(run.text, p.end, Math.min(run.text.length(), p.end + 40)));
                    if (clause.toLowerCase(Locale.ROOT).contains("brutto") || clause.toLowerCase(Locale.ROOT).contains("gross")) {
                        f.put("gross", Boolean.TRUE);
                    }
                    claim(run, job, Vocabulary.SALARY, Values.canonical(f), from, p.end, 0.7);
                    break;
                }
            }
            date(run, job, Vocabulary.VALID_THROUGH, DEADLINE, t[1], windowEnd);
            date(run, job, Vocabulary.START_DATE, START, t[1], windowEnd);
            date(run, job, Vocabulary.DATE_POSTED, POSTED, t[1], windowEnd);
            final Matcher em = EMAIL.matcher(run.text).region(t[1], windowEnd);
            boolean route = false;
            while (em.find()) {
                final String mail = Normalizers.email(em.group());
                if (mail != null && Normalizers.roleEmail(mail)) {
                    claim(run, job, Vocabulary.APPLICATION_ROUTE, "mailto:" + mail, em.start(), em.end(), 0.7);
                    route = true;
                    break;
                }
            }
            final Matcher ap = APPLY_ONLINE.matcher(run.text).region(t[1], windowEnd);
            if (!route && ap.find() && run.url != null) {
                claim(run, job, Vocabulary.APPLICATION_ROUTE, Normalizers.url(run.url), ap.start(), ap.end(), 0.6);
            }
        }
    }

    private void date(final Run run, final Mention job, final String predicate, final Pattern p, final int from, final int to) {
        final Matcher m = p.matcher(run.text).region(from, to);
        if (m.find()) {
            final String d = Values.date(m.group(1).replaceAll("\\s", ""));
            if (d != null) {
                claim(run, job, predicate, d, m.start(), m.end(), 0.7);
            }
        }
    }

    /** Start of a job title that ends at {@code markerAt}: back to a line break, a sentence end or a list mark; -1 if none. */
    private static int titleStart(final String text, final int markerAt) {
        int i = markerAt;
        final int floor = Math.max(0, markerAt - 90);
        while (i > floor) {
            final char c = text.charAt(i - 1);
            if (c == '\n' || c == '•' || c == '|' || c == ':' || c == ';' || c == '#' || c == '*'
                    || c == '.' && (i >= markerAt - 1 || Character.isWhitespace(text.charAt(i)))) {
                break;
            }
            i--;
        }
        while (i < markerAt && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '-')) {
            i++;
        }
        if (i >= markerAt || !Character.isUpperCase(text.codePointAt(i)) && !Character.isDigit(text.charAt(i))) {
            return -1;
        }
        return i;
    }

    // ------------------------------------------------------------ audiences

    private static final Pattern AUDIENCE = Pattern.compile("(?iu)\\b(?:für\\s+wen|fuer\\s+wen|zielgruppen?|wir\\s+unterstützen|wir\\s+unterstuetzen"
            + "|wir\\s+beraten|wir\\s+begleiten|wir\\s+helfen|unser\\s+angebot\\s+richtet\\s+sich\\s+an|richtet\\s+sich\\s+an|unsere\\s+kunden\\s+sind"
            + "|wir\\s+arbeiten\\s+für|speziell\\s+für|our\\s+clients\\s+are|we\\s+help|we\\s+support|designed\\s+for|who\\s+we\\s+serve)\\b\\s*:?\\s*");
    private static final Pattern NEED = Pattern.compile("(?iu)\\b(?:wenn\\s+sie|falls\\s+sie|bei\\s+fragen\\s+(?:rund\\s+)?(?:um|zu)|when\\s+you)\\s+"
            + "([^.!?;\\n]{8,120})");

    private void audiences(final Run run) {
        final Matcher m = AUDIENCE.matcher(run.text);
        int windows = 0;
        while (m.find() && windows < 6) {
            windows++;
            final int from = m.end();
            final int to = Math.min(run.text.length(), from + 320);
            final Set<String> done = new LinkedHashSet<>();
            for (final TermMatcher.Hit<Categories.Entry> h : run.ctx.categories.customerTypes(run.text, from, to)) {
                if (done.add("ct:" + h.entry.id)) {
                    claimAround(run, run.subject, Vocabulary.CUSTOMER_TYPE, h.entry.id, m.start(), h.end, 0.6);
                }
            }
            for (final Categories.Vocab v : run.ctx.vocabularies) {
                for (final TermMatcher.Hit<Categories.Entry> h : v.segments(run.text, from, to)) {
                    if (done.add("seg:" + h.entry.id)) {
                        claimAround(run, run.subject, Vocabulary.AUDIENCE_SEGMENT, h.entry.id, m.start(), h.end, 0.6);
                    }
                }
            }
            for (final TermMatcher.Hit<Categories.Entry> h : run.ctx.categories.companySizes(run.text, from, to)) {
                if (done.add("size:" + h.entry.id)) {
                    claimAround(run, run.subject, Vocabulary.COMPANY_SIZE, h.entry.id, m.start(), h.end, 0.6);
                }
            }
            // target industries: the categories of every vocabulary ("für Pflegeeinrichtungen" on a software page)
            for (final Categories.Vocab v : run.ctx.allVocabularies()) {
                if (run.ctx.vocabularies.contains(v)) {
                    continue; // the page's own field names its services, not its customers
                }
                for (final TermMatcher.Hit<Categories.Entry> h : v.categories(run.text, from, to)) {
                    if (done.add("cat:" + h.entry.id)) {
                        claimAround(run, run.subject, Vocabulary.TARGET_CATEGORY, h.entry.id, m.start(), h.end, 0.55);
                        if (h.entry.nace != null && run.ctx.nace.valid(h.entry.nace) && done.add("nace:" + h.entry.nace)) {
                            claimAround(run, run.subject, Vocabulary.TARGET_INDUSTRY, h.entry.nace, m.start(), h.end, 0.55);
                        }
                    }
                }
            }
            final Matcher need = NEED.matcher(run.text).region(from, to);
            if (need.find() && !personBetween(run.text, need.start(), need.end())) {
                claim(run, run.subject, Vocabulary.NEED, Normalizers.clip(need.group(1).trim(), 120), need.start(), need.end(), 0.55);
            }
            areas(run, from, to, run.subject, true);
        }
    }

    // ----------------------------------------------------------------- areas

    private static final Pattern NATIONAL = Pattern.compile("(?iu)\\b(?:bundesweit|deutschlandweit|in\\s+ganz\\s+deutschland|nationwide|österreichweit"
            + "|in\\s+ganz\\s+österreich|schweizweit)\\b");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?iu)\\b(?:international\\s+tätig|weltweit|europaweit|worldwide|im\\s+gesamten\\s+dach-raum"
            + "|dach-region|international\\s+active)\\b");
    private static final Pattern RADIUS = Pattern.compile("(?iu)\\bim\\s+umkreis\\s+von\\s+([0-9]{1,4})\\s*(?:km|kilometern?)\\b(?:\\s+(?:um|rund\\s+um)\\s+(\\p{Lu}[\\p{L}\\-]{2,40}))?"
            + "|\\bwithin\\s+([0-9]{1,4})\\s*(?:km|kilomet(?:er|re)s?)\\b");
    private static final Pattern NOT_PLACE = Pattern.compile("(?u)^(?:Ihre?[nmrs]?|Sie|Uns|Unsere?[nmrs]?|Das|Die|Der|Den|Dem|Ihnen|Alle|Alles|Haus"
            + "|Thema|Themen|Pflege|Familie|Gesundheit|Beratung|Leistungen|Angebote?)\\b");
    private static final Pattern REGION = Pattern.compile("(?u)\\b(?:im\\s+(?:Raum|Großraum|Grossraum|Landkreis|Kreis)|in\\s+und\\s+um|in\\s+der\\s+Region)"
            + "\\s+(\\p{Lu}[\\p{L}\\-]{2,40}(?:\\s+\\p{Lu}[\\p{L}\\-]{2,40})?)|\\b(\\p{Lu}[\\p{L}\\-]{2,40})\\s+und\\s+Umgebung\\b");
    private static final Pattern STATE;
    static {
        final StringBuilder sb = new StringBuilder();
        for (final String n : BusinessFacts.stateNames()) {
            sb.append(sb.length() == 0 ? "" : "|").append(Pattern.quote(n));
        }
        STATE = Pattern.compile("(?u)\\b(?:in\\s+ganz|in|im\\s+gesamten|ganz|für)\\s+(" + sb + ")(?![\\p{L}])|\\b(" + sb + ")weit\\b");
    }

    /** The service area: national, international, a radius, a state or a region/place; only in audience, about and contact contexts. */
    private void areas(final Run run, final int from, final int to, final Mention subject, final boolean allowed) {
        if (!allowed) {
            return;
        }
        final Set<String> done = new LinkedHashSet<>();
        Matcher m = NATIONAL.matcher(run.text).region(from, to);
        if (m.find()) {
            final String country = run.country != null ? run.country : "de";
            final Map<String, Object> f = new TreeMap<>();
            f.put("kind", "national");
            f.put("name", BusinessFacts.countryName(country));
            area(run, subject, f, m.start(), m.end(), country, "country", BusinessFacts.countryName(country), done);
        }
        m = INTERNATIONAL.matcher(run.text).region(from, to);
        if (m.find()) {
            final Map<String, Object> f = new TreeMap<>();
            f.put("kind", "international");
            if (m.group().toLowerCase(Locale.ROOT).startsWith("europa")) {
                f.put("name", "Europa");
            }
            area(run, subject, f, m.start(), m.end(), null, null, null, done);
        }
        m = RADIUS.matcher(run.text).region(from, to);
        if (m.find()) {
            final String km = m.group(1) != null ? m.group(1) : m.group(3);
            final Map<String, Object> f = new TreeMap<>();
            f.put("kind", "radius");
            f.put("radius_km", km);
            f.put("around", m.group(2));
            area(run, subject, f, m.start(), m.end(), null, null, null, done);
        }
        m = STATE.matcher(run.text).region(from, to);
        int states = 0;
        while (m.find() && states < 5) {
            final String[] st = BusinessFacts.state(m.group(1) != null ? m.group(1) : m.group(2));
            if (st != null) {
                final Map<String, Object> f = new TreeMap<>();
                f.put("kind", "state");
                f.put("name", st[1]);
                area(run, subject, f, m.start(), m.end(), st[0], "state", st[1], done);
                states++;
            }
        }
        m = REGION.matcher(run.text).region(from, to);
        int regions = 0;
        while (m.find() && regions < 5) {
            final String name = m.group(1) != null ? m.group(1) : m.group(2);
            if (BusinessFacts.state(name) != null || BusinessFacts.personLike(name) || NOT_PLACE.matcher(name).find()) {
                continue;
            }
            final Map<String, Object> f = new TreeMap<>();
            f.put("kind", "region");
            f.put("name", name);
            area(run, subject, f, m.start(), m.end(), run.country, "locality", name, done);
            regions++;
        }
    }

    private void area(final Run run, final Mention subject, final Map<String, Object> f, final int start, final int end, final String country,
            final String level, final String placeName, final Set<String> done) {
        final String value = Values.canonical(f);
        if (!done.add(value)) {
            return;
        }
        claim(run, subject, Vocabulary.SERVICE_AREA, value, start, end, 0.6);
        if (country != null && level != null && placeName != null) {
            final Mention place = BusinessFacts.place(run.out, country, level, placeName, 2, Claim.KIND_RULE, loc(start, end),
                    Normalizers.clip(Normalizers.excerpt(run.text, Math.max(0, start - 20), Math.min(run.text.length(), end + 20)), this.maxExcerpt));
            if (place != null) {
                relation(run, subject, Vocabulary.SERVES_PLACE, place, start, end, 0.6);
            }
        }
    }

    // --------------------------------------------------------------- claims

    private void claim(final Run run, final Mention m, final String predicate, final String value, final int start, final int end,
            final double confidence) {
        if (value == null || start < 0) {
            return;
        }
        final int a = Math.max(0, start - 30);
        final int b = Math.min(run.text.length(), Math.max(end, start) + 30);
        run.out.add(new Claim(m.ref, predicate, null, value, 2, Claim.KIND_RULE, false, loc(start, end),
                Normalizers.clip(Normalizers.excerpt(run.text, a, b), this.maxExcerpt), confidence));
    }

    /** A claim whose excerpt shows the span itself (a price or a marker and its value). */
    private void claimAround(final Run run, final Mention m, final String predicate, final String value, final int start, final int end,
            final double confidence) {
        if (value == null) {
            return;
        }
        final int a = Math.max(0, start - 40);
        final int b = Math.min(run.text.length(), end + 10);
        run.out.add(new Claim(m.ref, predicate, null, value, 2, Claim.KIND_RULE, false, loc(start, end),
                Normalizers.clip(Normalizers.excerpt(run.text, a, b), Math.max(this.maxExcerpt, 200)), confidence));
    }

    private void relation(final Run run, final Mention subject, final String predicate, final Mention object, final int start, final int end,
            final double confidence) {
        if (subject == null || object == null || subject.ref.equals(object.ref)) {
            return;
        }
        final int a = Math.max(0, start - 30);
        final int b = Math.min(run.text.length(), end + 30);
        run.out.add(new Claim(subject.ref, predicate, object.ref, null, 2, Claim.KIND_RULE, false, loc(start, end),
                Normalizers.clip(Normalizers.excerpt(run.text, a, b), Math.max(this.maxExcerpt, 200)), confidence));
    }

    private static String loc(final int start, final int end) {
        return "text:" + start + "+" + Math.max(0, end - start);
    }
}
