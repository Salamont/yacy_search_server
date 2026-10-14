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
import java.util.HashMap;
import java.util.HashSet;
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
    /**
     * Title occurrences read from a page at most (0.10.7). A careers page repeats the menu of its positions (desktop, mobile,
     * sidebar, footer), so the heading of an advertisement can stand behind dozens of menu entries: {@link #MAX_JOBS} bounds the
     * positions of a page, not the occurrences read (0.10.6 stopped after 30 and never saw the heading).
     */
    static final int MAX_TITLE_OCCURRENCES = 400;
    /** The text of an advertisement after its title, at most (characters). */
    static final int AD_WINDOW = 6000;

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
        /** The page's titles as Solr has them (its {@code <title>}); empty if unknown. */
        final List<String> titles;
        int relations;

        Run(final String text, final String url, final String host, final String language, final Extraction out, final ExtractContext ctx,
                final Set<PageKind> kinds, final Mention subject, final List<String> titles) {
            this.text = text;
            this.url = url;
            this.titles = titles == null ? List.of() : titles;
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
        extract(text, url, host, language, outbound, out, ctx, kinds, subject, List.of());
    }

    /** {@link #extract} with the page's titles, which name the position of a job's detail page. */
    public void extract(final String text, final String url, final String host, final String language, final List<String> outbound,
            final Extraction out, final ExtractContext ctx, final Set<PageKind> kinds, final Mention subject, final List<String> titles) {
        if (text == null || text.isEmpty() || subject == null || ctx == null) {
            return;
        }
        final Run run = new Run(text, url, host, language, out, ctx, kinds, subject, titles);
        final boolean servicePage = kinds.contains(PageKind.SERVICES) || kinds.contains(PageKind.PRICES);
        if (servicePage || kinds.contains(PageKind.HOME)) {
            services(run, servicePage ? SERVICE_PAGE_CONFIDENCE : HOME_PAGE_CONFIDENCE);
        }
        relations(run);
        if (kinds.contains(PageKind.IMPRINT) || kinds.contains(PageKind.ABOUT)) {
            industryStatements(run);
        }
        contacts(run, outbound);
        if (kinds.contains(PageKind.CAREERS)) {
            if (ctx.jobs) {
                jobs(run);
            } else if (ctx.jobSignals) {
                jobHints(run);
            }
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
                final String provider = Mention.providerKey(run.subject);
                m.serviceKey = provider == null ? null : provider + "|" + h.entry.id;
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
                    // the label begins after the last sentence end: "Preise. Wartung Gasheizung: 149 €"
                    final String found = lm.group(1).trim();
                    final String label = Values.afterLastSentence(found);
                    final int shift = found.length() - label.length();
                    if (label.length() >= 3 && !BusinessFacts.personLike(label) && !label.matches("(?iu).*\\b(?:preis|preise|kosten|ab|bis|von)$")
                            && !label.matches("(?iu)^(?:preis|preise|kosten|tel|telefon|fax)$")) {
                        final Mention m = run.out.add(new Mention(BusinessFacts.SERVICE_REF + "label:" + Normalizers.key(label), Vocabulary.SERVICE, 2));
                        m.name = label;
                        final String provider = Mention.providerKey(run.subject);
                        m.serviceKey = provider == null ? null : provider + "|label:" + Normalizers.key(label);
                        claim(run, m, Vocabulary.NAME, label, from + lm.start(1) + shift, from + lm.end(1), 0.65);
                        relation(run, run.subject, Vocabulary.OFFERS, m, from + lm.start(1), p.end, 0.65);
                        BusinessFacts.categorize(run.out, run.ctx, m, label, 2, Claim.KIND_RULE, loc(from + lm.start(1) + shift, from + lm.end(1)),
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
            // in a sentence, not a list: "Wir sind Partner der PflegeSoft GmbH", "offizieller Partner von", "we are a partner of";
            // only a name with a legal form or an institution word ("Partner der Region" names nobody)
            new Marker("\\b(?:sind|ist|als)\\s+(?:offizielle[rn]?\\s+|zertifizierte[rn]?\\s+|autorisierte[rn]?\\s+)?partner\\s+(?:der|des|von|vom)\\s+"
                    + "|\\b(?:are|is)\\s+(?:an?\\s+)?(?:official\\s+|certified\\s+)?partner\\s+of\\s+(?:the\\s+)?", Vocabulary.PARTNER_OF, true, false),
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
    private static final Pattern ITEM_SPLIT = Pattern.compile("\\s*(?:[,;•|·\\n]|(?<![\\s.]\\p{L})(?<!Co)\\.\\s+|\\s+und\\s+|\\s+sowie\\s+|\\s+and\\s+|\\s+&\\s+(?=\\p{Lu}\\p{L}+\\s))\\s*");
    private static final Pattern STOP_WORDS = Pattern.compile("(?iu)^(?:der|die|das|den|dem|des|the|a|an|unser(?:e|em|en|er)?|our|ein(?:e|em|en|er)?)\\s+");
    /** A word that ends a list: the next sentence starts. */
    private static final Pattern NOT_A_NAME = Pattern.compile("(?iu)^(?:wir|sie|ich|es|mehr|weitere|alle|unsere?|hier|jetzt|kontakt|impressum"
            + "|datenschutz|home|startseite|leistungen|über|ueber|news|aktuelles|zurück|weiter|mehr\\s+erfahren|termin|anfrage|copyright)\\b");

    /**
     * The wording of a consent banner (0.10.3): "an unsere Partner für soziale Medien, Werbung und Analysen weiter", "with our
     * partners for social media, advertising and analytics". Its "partners" are the recipients of tracking data, never partners.
     */
    private static final Pattern CONSENT = Pattern.compile("(?iu)\\bcookies?\\b|einwilligung|zustimm|\\bconsent\\b|datenschutzeinstellung"
            + "|privacy\\s+settings|\\btracking\\b|soziale(?:n)?\\s+medien|social\\s+media|werbung\\s+und\\s+analyse|advertising\\s+and\\s+analytics"
            + "|verwendung\\s+unserer\\s+(?:web)?seite|use\\s+of\\s+our\\s+(?:site|website)");
    /** A marker followed by a preposition is used in a sentence ("an unsere Partner für …"), it opens no list of names. */
    private static final Pattern PREPOSITION_AFTER = Pattern.compile("(?u)^\\s*(?:für|fuer|in|im|aus|bei|von|vom|mit|zu|zur|zum|über|ueber|an|auf"
            + "|weiter|for|with|to|from|on|of|at)\\b");

    /** The sentence around {@code [from, to)}: back to and on to the next sentence end or line break (at most 300 characters). */
    private static String sentence(final String text, final int from, final int to) {
        int a = from;
        while (a > 0 && from - a < 300 && ".!?\n".indexOf(text.charAt(a - 1)) < 0) {
            a--;
        }
        int b = to;
        while (b < text.length() && b - to < 300 && ".!?\n".indexOf(text.charAt(b)) < 0) {
            b++;
        }
        return text.substring(a, b);
    }

    /**
     * A person in a role right before a membership marker is its member, not the page's organisation (0.10.5: "durch den Einsatz eines
     * Muster-Beraters als Mitglied des Einkaufs" of a client): the role nouns of people, in the same sentence. Not a number of people
     * ("mit 200 Mitarbeitern ist die … Mitglied") and not where "wir" is the member ("Als Hersteller von Leitern sind wir Mitglied").
     */
    private static final Pattern PERSON_ACTOR = Pattern.compile("(?iu)(?<![\\p{L}])(?<![0-9][ \\t])(?:\\p{L}+-)*(?:berater(?:in(?:nen)?|s|n)?"
            + "|consultants?|mitarbeiter(?:in(?:nen)?|s|n)?|kolleg(?:e|en|in|innen)|\\p{L}*(?<!halb)leiter(?:in(?:nen)?|s|n)?|expert(?:e|en|in|innen)"
            + "|entwickler(?:in(?:nen)?|s|n)?|manager(?:in(?:nen)?|s)?|gesch(?:ä|ae)ftsf(?:ü|ue)hrer(?:in(?:nen)?|s)?|inhaber(?:in(?:nen)?|s)?"
            + "|gr(?:ü|ue)nder(?:in(?:nen)?|s)?|ansprechpartner(?:in(?:nen)?|s)?|herrn?|frau)(?![\\p{L}])"
            + "(?:(?!(?<![\\p{L}])(?:wir|uns)(?![\\p{L}]))[^.!?\\n]){0,40}$");

    /** The sentence before {@code markerAt} (at most 80 characters) names a person in a role ({@link #PERSON_ACTOR}). */
    private static boolean personActor(final String text, final int markerAt) {
        int a = markerAt;
        while (a > 0 && markerAt - a < 80 && ".!?\n".indexOf(text.charAt(a - 1)) < 0) {
            a--;
        }
        return PERSON_ACTOR.matcher(text.substring(a, markerAt)).find();
    }

    private void relations(final Run run) {
        final Set<String> seen = new LinkedHashSet<>();
        for (final Marker mk : MARKERS) {
            final Matcher m = mk.pattern.matcher(run.text);
            while (m.find() && run.relations < MAX_RELATIONS) {
                if (CONSENT.matcher(sentence(run.text, m.start(), m.end())).find()
                        || mk.list && PREPOSITION_AFTER.matcher(run.text).region(m.end(), run.text.length()).lookingAt()) {
                    continue; // a consent text or a marker in a sentence: no relation
                }
                if (Vocabulary.MEMBER_OF.equals(mk.predicate) && personActor(run.text, m.start())) {
                    continue; // a person's membership ("eines Beraters als Mitglied des …"), not the organisation's
                }
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

    /**
     * The organisations of a list after a marker; the list ends with its sentence (0.10.4: "Unsere Partner: A GmbH und B AG. Muster
     * GmbH, Musterstraße 1" named the operator of the imprint a partner of itself), on a line that names no organisation, or after
     * three items that are no names. A list on lines goes on.
     */
    private static List<int[]> names(final String text, final int from, final int to) {
        final List<int[]> out = new ArrayList<>();
        final Matcher sp = ITEM_SPLIT.matcher(text).region(from, to);
        int start = from;
        int misses = 0;
        boolean afterSentence = false;
        while (start < to && misses < 3 && out.size() < 20) {
            final int stop;
            final int next;
            final boolean sentence;
            final boolean fullStop;
            if (sp.find()) {
                stop = sp.start();
                next = sp.end();
                final String sep = sp.group().trim();
                fullStop = sep.startsWith(".") && !ABBREVIATED_FORM.matcher(text.substring(start, stop)).find();
                sentence = fullStop || sp.group().indexOf('\n') >= 0;
            } else {
                stop = to;
                next = to;
                sentence = false;
                fullStop = false;
            }
            String item = LEADING_JOIN.matcher(text.substring(start, stop).trim()).replaceFirst("");
            if (afterSentence && SENTENCE_START.matcher(item).find()) {
                break; // a new sentence ("Die ... GmbH ist ..."), not a list item
            }
            final Matcher form = LEGAL_FORM.matcher(item);
            if (form.find()) {
                item = item.substring(0, form.end()); // a name ends with its legal form; the next sentence is not part of it
            } else {
                // the last item of a sentence carries its predicate: "Bosch vertrauen uns" names "Bosch"
                final Matcher tail = PREDICATE_TAIL.matcher(item);
                if (tail.find()) {
                    item = item.substring(0, tail.start()).trim();
                }
                item = item.replaceAll("[.!?,;]+$", "").trim(); // the end of the text is no part of the name
            }
            if (item.indexOf(':') >= 0) {
                break; // the next heading or marker: the list has ended
            }
            if (!item.isEmpty()) {
                final int lead = text.indexOf(item, start);
                if (qualifies(item, true)) {
                    final Matcher lf = LEGAL_FORM.matcher(item);
                    final int len = lf.find() ? lf.end() : item.length();
                    out.add(new int[] {lead, lead + len});
                    misses = 0;
                } else {
                    misses++;
                    if (sentence || afterSentence) {
                        break; // a sentence that starts or ends without an organisation ends the list
                    }
                }
            }
            if (fullStop) {
                break; // the sentence of the list has ended; the next one names no item of it
            }
            afterSentence = sentence;
            start = next;
        }
        return out;
    }

    /** "und Gamma GmbH" after "Beta Ltd.": the joining word is no part of the name. */
    private static final Pattern LEADING_JOIN = Pattern.compile("^(?:und|sowie|and|&)\\s+");

    /** A legal form abbreviated with a full stop ("Alpha Inc. und Beta GmbH"): its full stop ends no sentence. */
    private static final Pattern ABBREVIATED_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:Inc|Ltd|Corp|S\\.A|B\\.V|N\\.V|e\\.\\s?K)\\s*$");

    /** Lower-case words to the end of the item, without any capitalised word: a predicate, not part of a name. */
    private static final Pattern PREDICATE_TAIL = Pattern.compile("(?<=\\S)\\s+\\p{Ll}[\\p{Ll}\\-]*(?:\\s+\\p{Ll}[\\p{Ll}\\-]*)*\\s*[.!]?$");

    /** Common words that are no brand when they stand alone in a list (0.10.3). */
    private static final Pattern GENERIC_WORD = Pattern.compile("(?iu)werbung|werbepartner|analysen?|analytics|analytik|marketing|medien|media"
            + "|statistik(?:en)?|tracking|cookies?|personalisierung|funktionen|inhalte|anzeigen|dienste|services|partner|partnern|kunden|daten"
            + "|informationen|social|advertising");

    /** A department or team of a company, also as a compound ("Einkaufsteam", "Einkaufs-Team"), not an organisation (0.10.5). */
    private static final Pattern ORG_UNIT = Pattern.compile("(?iu)(?:\\p{L}+-)?(?:einkaufs?|vertriebs?|vorstands?|betriebsrats?|managements?"
            + "|marketings?|personals?|controllings?|gesch(?:ä|ae)fts(?:f(?:ü|ue)hrung|leitung)|\\p{L}*abteilung(?:en)?|bereichs?|\\p{L}*teams?)");

    /** A sentence, not a list item, starts with an article, a pronoun or a preposition. */
    private static final Pattern SENTENCE_START = Pattern.compile("^(?:Die|Der|Das|Den|Dem|Des|Ein|Eine|Wir|Sie|Es|Er|Ihr|Ihre|Unser|Unsere"
            + "|Seit|Mit|Als|In|Auf|Für|Bei|Durch|Zu|Zur|Zum|Nach|Von|Vom|Im|Am|Hier|Damit|Dabei|Auch|So|Und|The|We|Our|With|In)\\s");

    /** An organisation name: a legal form, an institution word, or (in a list) a one-word brand; never two plain capitalised words. */
    static boolean qualifies(final String item, final boolean list) {
        if (item.length() < 2 || item.length() > 100 || BusinessFacts.personLike(item) || NOT_A_NAME.matcher(item).find()) {
            return false;
        }
        if (item.endsWith("-") || item.startsWith("-")) {
            return false; // half of a shortened compound ("Einkaufs- und Logistikteam"), no name (0.10.5)
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
        if (ORG_UNIT.matcher(item).matches()) {
            return false; // "Mitglied des Einkaufs", "… der Geschäftsführung": a department or team of the page's own company
        }
        if (INSTITUTION.matcher(item).find()) {
            return true;
        }
        // a brand in a customer or partner list: one word ("Siemens", "DHL", "SAP"); "Max Mustermann" is no organisation, nor a
        // common word ("Werbung", "Analysen")
        return list && words.length == 1 && item.length() >= 2 && item.length() <= 30 && item.matches("[\\p{Lu}\\p{N}][\\p{L}\\p{N}&+.\\-]*")
                && !GENERIC_WORD.matcher(item).matches();
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
        // the fax and the contact points of the operator's insurer, chamber or web agency are theirs
        final List<int[]> own = RuleExtractor.operatorRanges(run.text, 0, run.text.length());
        final Matcher fx = FAX.matcher(run.text);
        while (fx.find()) {
            if (!RuleExtractor.inside(own, fx.start())) {
                continue;
            }
            if (!personBefore(run.text, fx.start())) {
                final String fax = Normalizers.phone(fx.group(1), run.callingCode);
                claim(run, run.subject, Vocabulary.FAX, fax, fx.start(), fx.end(), 0.7);
            }
            break;
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
            // the department's own clause only: the next sentence may be a person's line
            final int to = Values.clauseEnd(run.text, dep.end(), Math.min(run.text.length(), dep.end() + 100));
            if (personBetween(run.text, dep.start(), to) || !RuleExtractor.inside(own, dep.start())) {
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
    /**
     * Words before a title that are not part of it ("Wir suchen", "Gesucht:", "Stellenangebote — "). Whole words (0.10.5: the lead
     * "Stellenangebot" cut "Stellenangebote — X" to the title "e — X"), with the colon or dash after them.
     */
    private static final Pattern JOB_LEAD = Pattern.compile("(?iu)^(?:(?:zur\\s+verstärkung\\s+(?:unseres\\s+teams\\s+)?)?(?:suchen\\s+wir|wir\\s+suchen)"
            + "(?:\\s+(?:ab\\s+sofort|zum\\s+nächstmöglichen\\s+zeitpunkt|für\\s+unser(?:e|en)?\\s+\\S+|eine?n?))*|gesucht(?:\\s+wird)?"
            + "|stellenangebote?|stellenanzeigen?|wir\\s+stellen\\s+ein|we\\s+are\\s+hiring)(?![\\p{L}\\p{N}])\\s*[:|\\-–—]?\\s*");
    private static final Pattern WORK_PLACE = Pattern.compile("(?iu)\\b(?:standort|einsatzort|arbeitsort|location)\\s*:?\\s*(\\p{Lu}[\\p{L}\\-]{2,40})");

    /**
     * The specialisation after the gender marker that ends a title (0.10.5: "Freiberufliche SAP Senior Berater (m/w/d) Finance." and
     * "… (m/w/d) SAC." are two positions): one to three capitalised words or acronyms ("S/4HANA", "FI/CO") right before the end of a
     * sentence, a list mark or a line break. Terms of the position, places of work and the words of a menu or a sentence are none.
     */
    private static final Pattern TITLE_SUFFIX = Pattern.compile("(?u)\\G[ \\t]+((?:[\\p{Lu}\\p{N}][\\p{L}\\p{N}]*(?:[/&+.\\-][\\p{L}\\p{N}]+)*)"
            + "(?:[ \\t]+(?:[\\p{Lu}\\p{N}][\\p{L}\\p{N}]*(?:[/&+.\\-][\\p{L}\\p{N}]+)*|&)){0,2})"
            + "(?=[ \\t]*(?:[.!?;|•·](?:\\s|$)|$|\\n)|[ \\t]+[\\-–—][ \\t])");
    private static final Pattern SUFFIX_STOP = Pattern.compile("(?iu)^(?:vollzeit|teilzeit|minijob|werkstudent\\p{L}*|aushilfe|festanstellung"
            + "|befristet|unbefristet|remote|homeoffice|hybrid|jetzt|mehr|details|weiterlesen|bewerb\\p{L}*|standort|einsatzort|arbeitsort|ort"
            + "|start|beginn|ab|zum|zur|im|in|für|fuer|wir|ihre?|deine?|unsere?|sie|du|das|die|der|ein|eine|gesucht|stellenangebote?"
            + "|jobs?|karriere|skip|suche|home|startseite|und|oder|&)$");
    /** A word or mark joining two titles in one sentence ("Buchhalter (m/w/d) oder Controller (m/w/d)"); no part of a title. */
    private static final Pattern TITLE_JOIN = Pattern.compile("(?:[/,&+]|(?:und|oder|bzw\\.|sowie|or|and)(?=[\\s/,&+]))[\\s\\-/,&+]*");

    /**
     * The job titles of a careers page: {start, end, gender marker}, at most {@link #MAX_TITLE_OCCURRENCES}. A title never reaches back over
     * the marker before it, so the titles do not overlap and the text of each advertisement ({@link #windowEnd}) has no
     * negative length.
     */
    private static List<int[]> jobTitles(final String text) {
        final Matcher g = GENDER.matcher(text);
        final List<int[]> titles = new ArrayList<>();
        int floor = 0;
        while (g.find() && titles.size() < MAX_TITLE_OCCURRENCES) {
            final int start = titleStart(text, g.start(), floor);
            final int end = suffixEnd(text, g.end());
            if (start >= 0 && g.start() - start >= 3) {
                // {start, end (with its specialisation), start of the marker, end of the marker}
                titles.add(new int[] {start, end, g.start(), g.end()});
            }
            floor = end;
        }
        return titles;
    }

    /** End of the specialisation after a marker that ends at {@code markerEnd} ({@link #TITLE_SUFFIX}); the marker's end if none. */
    private static int suffixEnd(final String text, final int markerEnd) {
        final Matcher m = TITLE_SUFFIX.matcher(text).region(markerEnd, text.length());
        if (!m.lookingAt() || m.group(1).length() > 40) {
            return markerEnd;
        }
        for (final String word : m.group(1).split("[ \\t]+")) {
            if (SUFFIX_STOP.matcher(word).matches()) {
                return markerEnd;
            }
        }
        return m.end(1);
    }

    /** End of the text of advertisement {@code i}: the next title, at most {@link #AD_WINDOW} characters; never before the end of its own. */
    private static int windowEnd(final String text, final List<int[]> titles, final int i) {
        final int[] t = titles.get(i);
        final int next = i + 1 < titles.size() ? titles.get(i + 1)[0] : text.length();
        return Math.max(t[1], Math.min(Math.min(next, text.length()), t[1] + AD_WINDOW));
    }

    /**
     * {@link #windowEnd} of a posting of a list (0.10.7): before the sentence that announces the next title ("Für unseren Kunden
     * suchen wir: B (m/w/d)" belongs to B, not to the advertisement before it), where that sentence starts after this title.
     */
    private static int listWindowEnd(final String text, final List<int[]> titles, final int i) {
        final int end = windowEnd(text, titles, i);
        if (i + 1 >= titles.size() || end != titles.get(i + 1)[0]) {
            return end;
        }
        final int sentence = sentenceStart(text, end);
        return sentence > titles.get(i)[1] ? sentence : end;
    }

    /**
     * End of the advertisement on the page of a position (0.10.7): the next title of another position or of a list (a menu, the
     * positions next to it), at most {@link #AD_WINDOW} characters after its title. A repetition of its own title inside it
     * ("Bewirb Dich als X (m/w/d) jetzt") ends nothing. Where the next title ends it, the navigation labels right before that
     * title ({@link #navigationStart}) are not part of it.
     */
    private static int positionEnd(final String text, final List<int[]> titles, final String[] key, final boolean[] listed, final int i) {
        final int[] t = titles.get(i);
        int next = text.length();
        for (int j = i + 1; j < titles.size(); j++) {
            if (listed[j] || key[j] == null || !key[j].equals(key[i])) {
                next = titles.get(j)[0];
                break;
            }
        }
        final int end = Math.max(t[1], Math.min(next, t[1] + AD_WINDOW));
        if (end != next || next >= text.length()) {
            return end;
        }
        final int sentence = sentenceStart(text, next);
        return navigationStart(text, t[1], sentence > t[1] ? sentence : next);
    }

    /** The separators of the segments of a page text: the end of a sentence, a list mark or a line. */
    private static final String SEGMENT_END = ".!?|•·:\n";
    /** A word: two letters or more, with digits, hyphens or apostrophes inside (not the "m", "w", "d" of a gender marker). */
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}'’-]+");

    /**
     * The start of the navigation labels right before {@code end} (0.10.7): a run of at least two segments of one to three words
     * ("Jobs. Festanstellung. Freiberuflich." before the positions of a sidebar) after a sentence of the advertisement belongs
     * to the list that follows, not to the advertisement. A run that reaches back to a section heading or to the title (the short
     * items of an offer: "Wir bieten:. - Vollzeit.") is kept: where the advertisement ends there is not clear. {@code end} if none.
     */
    private static int navigationStart(final String text, final int from, final int end) {
        int at = end;
        int labels = 0;
        int run = end;
        while (at > from) {
            int stop = at;
            while (stop > from && (SEGMENT_END.indexOf(text.charAt(stop - 1)) >= 0 || Character.isWhitespace(text.charAt(stop - 1)))) {
                stop--;
            }
            int begin = stop;
            while (begin > from && SEGMENT_END.indexOf(text.charAt(begin - 1)) < 0) {
                begin--;
            }
            if (begin <= from || stop <= begin) {
                return end; // back at the title: no sentence of the advertisement before the run
            }
            final String segment = text.substring(begin, stop);
            if (AD_SECTION.matcher(segment).find() || REQUIREMENTS.matcher(segment).find() || OFFER.matcher(segment).find()) {
                return end;
            }
            final int words = words(text, begin, stop);
            if (words > 3) {
                return labels >= 2 ? run : end;
            }
            if (words > 0) {
                labels++;
                run = begin;
                while (run < stop && (Character.isWhitespace(text.charAt(run)) || "-–—*".indexOf(text.charAt(run)) >= 0)) {
                    run++;
                }
            }
            at = begin;
        }
        return end;
    }

    private static int words(final String text, final int from, final int to) {
        final Matcher w = WORD.matcher(text).region(from, to);
        int n = 0;
        while (w.find()) {
            n++;
        }
        return n;
    }

    /**
     * The characters of the sentences of at least five words between {@code from} and {@code to} (0.10.7): the text of an
     * advertisement has them, a menu or a footer has labels.
     */
    private static int prose(final String text, final int from, final int to) {
        int total = 0;
        int segment = from;
        for (int i = from; i <= to; i++) {
            if (i == to || SEGMENT_END.indexOf(text.charAt(i)) >= 0) {
                if (i > segment && words(text, segment, i) >= 5) {
                    total += i - segment;
                }
                segment = i + 1;
            }
        }
        return total;
    }

    // ------------------------------------------------------------ postings

    /**
     * A position the page advertises (0.10.5): its title (start, end, gender marker) and the index of its title among all
     * titles of the page ({@link #jobTitles}); {@code page}: the page is the position's own, named by its title or path (0.10.6);
     * {@code windowEnd}: the end of the text of its advertisement ({@link #windowEnd}, on the page of a position {@link #positionEnd},
     * 0.10.7).
     */
    static final class Posting {
        final int start;
        final int end;
        final int marker;
        final int index;
        final boolean page;
        final int windowEnd;

        Posting(final int start, final int end, final int marker, final int index, final boolean page, final int windowEnd) {
            this.start = start;
            this.end = end;
            this.marker = marker;
            this.index = index;
            this.page = page;
            this.windowEnd = windowEnd;
        }
    }

    /** The sentence of a title announces it ("Wir suchen: …", "Zur Verstärkung …"); a menu says no such thing. */
    private static final Pattern ANNOUNCE = Pattern.compile("(?iu)\\b(?:wir\\s+suchen|suchen\\s+wir|gesucht|wir\\s+stellen\\s+ein"
            + "|zur\\s+verst(?:ä|ae)rkung|we\\s+are\\s+hiring|we\\s+are\\s+looking\\s+for)\\b");
    /** The terms of a position right after its title ("in Vollzeit", "– Teilzeit", "(Minijob)", "ab sofort", "zum 01.08.2027"). */
    private static final Pattern IMMEDIATE = Pattern.compile("(?iu)[\\s\\-–—|·:,(]*(?:(?:in\\s+|als\\s+|zum\\s+)?(?:vollzeit|teilzeit|minijob"
            + "|werkstudent(?:in)?|aushilfe|festanstellung|befristet|unbefristet|ab\\s+sofort|full[\\s-]?time|part[\\s-]?time)\\b"
            + "|(?:zum|ab(?:\\s+dem)?)\\s+[0-3]?\\d\\.\\s?(?:[01]?\\d\\.|\\p{L}{3,9}\\s)\\s?(?:20)?\\d{2})");
    /** The own text of an advertisement: its tasks or its requirements, as headings. */
    private static final Pattern AD_SECTION = Pattern.compile("(?iu)\\b(?:(?:ihre|deine|your)\\s+(?:aufgaben|t(?:ä|ae)tigkeiten|tasks"
            + "|responsibilities)|aufgaben(?:gebiet|bereich)?\\s*:|(?:ihr|dein|your)\\s+profil\\b|profil\\s*:|anforderungen\\s*:"
            + "|qualifikation(?:en)?\\s*:|was\\s+(?:du|sie)\\s+mitbring|das\\s+bring(?:st\\s+du|en\\s+sie)\\s+mit|responsibilities|requirements)");
    /** Nothing but a joining word or mark between two titles: one sentence names both ("A (m/w/d) oder B (m/w/d)"). */
    private static final Pattern JOIN_GAP = Pattern.compile("(?iu)\\s*(?:[/,&+]|und|oder|bzw\\.|sowie|or|and)\\s*");
    /** Characters between two titles below which they form a list (a menu, teaser links), unless the gap is part of an advertisement. */
    static final int LIST_GAP = 60;

    /**
     * The positions a page advertises (0.10.5). A careers page often repeats the menu of the open positions, and a site's
     * text has no line breaks; a title with a gender marker is no position of the page by itself:
     * <ul>
     * <li>a detail page: the page title or the last segment of its path names a title; then only the titles it names, cut to
     * the name ("Jobs & Karriere Stellenangebote SAP-Berater (m/w/d)" of a page text without line breaks becomes
     * "SAP-Berater (m/w/d)"), once each. Of the occurrences of a title (the page's title line, its menu, the heading of the
     * advertisement, a repetition in it) the advertisement is the one followed by its own text (0.10.7): sentences, not the
     * labels of a navigation; an occurrence without the specialisation the page names is that position, not another one, and
     * its text runs over repetitions of its own title ({@link #positionEnd});</li>
     * <li>any other page: a title its sentence announces ("Wir suchen: …"), one with its terms right after it ("in
     * Vollzeit"), or one with its own advertisement text (tasks, profile) that is no entry of a list of titles (a menu,
     * teaser links: titles next to each other with a few words between them).</li>
     * </ul>
     * A title appears once even where the page repeats it (menu and heading).
     */
    static List<Posting> postings(final String text, final String url, final List<String> pageTitles) {
        final List<int[]> titles = jobTitles(text);
        final List<Posting> out = new ArrayList<>();
        if (titles.isEmpty()) {
            return out;
        }
        final List<PageName> names = pageNames(url, pageTitles);
        final int n = titles.size();
        final boolean[] own = new boolean[n];
        for (int i = 0; i < n; i++) {
            own[i] = AD_SECTION.matcher(text).region(titles.get(i)[1], windowEnd(text, titles, i)).find();
        }
        // a list: titles next to each other; the heading of an advertisement that follows a menu is none of its entries
        final boolean[] listed = new boolean[n];
        for (int i = 0; i + 1 < n; i++) {
            final String gap = text.substring(titles.get(i)[1], titles.get(i + 1)[0]);
            if (gap.length() < LIST_GAP && !JOIN_GAP.matcher(gap).matches() && !IMMEDIATE.matcher(gap).lookingAt() && !AD_SECTION.matcher(gap).find()) {
                listed[i] = true;
                listed[i + 1] |= !own[i + 1];
            }
        }
        final int[] start = new int[n];
        final boolean[] named = new boolean[n];
        boolean detail = false;
        for (int i = 0; i < n; i++) {
            final int[] t = titles.get(i);
            final int s = namedStart(text, t, names);
            // a specialisation is part of the name: the page of "… (m/w/d) Finance" names no "… (m/w/d) SAC" of its menu
            named[i] = s >= 0 && (t[1] == t[3] || namedBy(names, tokens(text.substring(t[3], t[1]))));
            start[i] = leadEnd(text, s >= 0 ? s : t[0], t[2]);
            detail |= named[i];
        }
        final String[] key = new String[n];
        for (int i = 0; i < n; i++) {
            key[i] = jobKey(text.substring(start[i], titles.get(i)[1]));
        }
        final int[] end = new int[n];
        final int[] content = new int[n];
        final boolean[] ownText = new boolean[n];
        if (detail) {
            // the page names a specialised title: its title without the specialisation is that position ("Bewirb Dich als X (m/w/d)")
            final Map<String, String> specialised = new HashMap<>();
            final Set<String> ambiguous = new HashSet<>();
            for (int i = 0; i < n; i++) {
                final int[] t = titles.get(i);
                final String base = t[1] > t[3] ? jobKey(text.substring(start[i], t[3])) : null;
                if (named[i] && base != null && key[i] != null) {
                    final String was = specialised.putIfAbsent(base, key[i]);
                    if (was != null && !was.equals(key[i])) {
                        ambiguous.add(base);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (named[i] && titles.get(i)[1] == titles.get(i)[3] && key[i] != null && specialised.containsKey(key[i])
                        && !ambiguous.contains(key[i])) {
                    key[i] = specialised.get(key[i]);
                }
            }
            for (int i = 0; i < n; i++) {
                end[i] = positionEnd(text, titles, key, listed, i);
                if (named[i]) {
                    final int[] t = titles.get(i);
                    ownText[i] = AD_SECTION.matcher(text).region(t[1], end[i]).find();
                    content[i] = prose(text, t[1], adEnd(text, t[1], end[i]));
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                end[i] = listWindowEnd(text, titles, i);
            }
        }
        final Map<String, Integer> chosen = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            final int[] t = titles.get(i);
            final boolean posting = detail ? named[i] : announced(text, start[i]) || immediate(text, titles, i) || !listed[i] && own[i];
            if (!posting || key[i] == null) {
                continue;
            }
            final Integer before = chosen.get(key[i]);
            if (before == null) {
                chosen.put(key[i], i);
            } else if (detail) {
                // the occurrence followed by the advertisement's own text: sentences first (a title line or a menu entry is followed by
                // the labels of the navigation), then its section headings, then outside a list, then the more text (0.10.7)
                final int b = before;
                final int c = Boolean.compare(content[i] > 0, content[b] > 0) != 0 ? Boolean.compare(content[i] > 0, content[b] > 0)
                        : Boolean.compare(ownText[i], ownText[b]) != 0 ? Boolean.compare(ownText[i], ownText[b])
                        : content[i] != content[b] ? Integer.compare(content[i], content[b])
                        : Boolean.compare(!listed[i], !listed[b]) != 0 ? Boolean.compare(!listed[i], !listed[b])
                        : Integer.compare(end[i] - t[1], end[b] - titles.get(b)[1]);
                if (c > 0) {
                    chosen.put(key[i], i);
                }
            } else if (rank(own, listed, i) > rank(own, listed, before) || rank(own, listed, i) == rank(own, listed, before)
                    && end[i] - t[1] > end[before] - titles.get(before)[1]) {
                // of a repeated title the occurrence with its own text, else the one outside a list, else the one with the longest text
                chosen.put(key[i], i);
            }
        }
        final java.util.TreeSet<Integer> picked = new java.util.TreeSet<>();
        for (final int c : chosen.values()) {
            int i = c;
            // on the page of a position its advertisement starts at the last occurrence of its title before its text: a title line
            // or a menu entry followed only by labels gives way to the heading after them (0.10.7)
            for (int j = i + 1; detail && j < n && titles.get(j)[0] < end[i]; j++) {
                if (!listed[j] && key[i].equals(key[j]) && prose(text, titles.get(i)[1], start[j]) == 0) {
                    i = j;
                }
            }
            picked.add(i);
        }
        for (final int i : picked) {
            if (out.size() >= MAX_JOBS) {
                break;
            }
            out.add(new Posting(start[i], titles.get(i)[1], titles.get(i)[2], i, detail, end[i]));
        }
        return out;
    }

    private static int rank(final boolean[] own, final boolean[] listed, final int i) {
        return (own[i] ? 2 : 0) + (listed[i] ? 0 : 1);
    }

    /**
     * The titles of a page with gender markers as comparison keys without the marker ({@link #jobKey}): {postings, others}.
     * The LLM tier names no job the rules see as no position of the page (0.10.5).
     */
    public static List<Set<String>> jobTitleKeys(final String text, final String url, final List<String> pageTitles) {
        final Set<String> postings = new LinkedHashSet<>();
        final Set<String> others = new LinkedHashSet<>();
        if (text == null) {
            return List.of(postings, others);
        }
        for (final Posting p : postings(text, url, pageTitles)) {
            final String k = jobKey(text.substring(p.start, p.end));
            if (k != null) {
                postings.add(k);
            }
        }
        for (final int[] t : jobTitles(text)) {
            final String k = jobKey(text.substring(leadEnd(text, t[0], t[2]), t[1]));
            if (k != null && !postings.contains(k)) {
                others.add(k);
            }
        }
        return List.of(postings, others);
    }

    /** The comparison key of a job title: without its gender marker, umlauts written out. */
    public static String jobKey(final String title) {
        return title == null ? null : fold(Normalizers.key(GENDER.matcher(title).replaceAll(" ")));
    }

    private static String fold(final String key) {
        return key == null ? null : key.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue");
    }

    /** The words of a text as comparison keys (umlauts written out). */
    private static List<String> tokens(final String text) {
        final String k = fold(Normalizers.key(text));
        return k == null ? List.of() : List.of(k.split(" "));
    }

    /** A name of the page: the words of a page title, or of the last segment of its path. */
    private static final class PageName {
        final List<String> words;
        final boolean path;

        PageName(final List<String> words, final boolean path) {
            this.words = words;
            this.path = path;
        }
    }

    /**
     * True if one of the names holds the words in a row. In a path, a word of two or more may be shortened (at least five letters: the
     * path "freiberufl-sap-senior-berater-finance" names "Freiberufliche SAP Senior Berater", 0.10.5); a page title names exactly.
     */
    private static boolean namedBy(final List<PageName> names, final List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        for (final PageName name : names) {
            final boolean shortened = name.path && words.size() >= 2;
            next: for (int i = 0; i + words.size() <= name.words.size(); i++) {
                for (int k = 0; k < words.size(); k++) {
                    final String a = name.words.get(i + k), w = words.get(k);
                    if (!a.equals(w) && !(shortened && a.length() >= 5 && w.startsWith(a))) {
                        continue next;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** Start of a title after its lead words ({@link #JOB_LEAD}): "Stellenangebote — X" starts at X; {@code start} if none. */
    private static int leadEnd(final String text, final int start, final int markerAt) {
        final Matcher m = JOB_LEAD.matcher(text).region(start, markerAt);
        return m.lookingAt() && m.end() < markerAt ? m.end() : start;
    }

    /** The page title(s) and the last segment of the path, as words: what names the position of a detail page. */
    private static List<PageName> pageNames(final String url, final List<String> pageTitles) {
        final List<PageName> out = new ArrayList<>();
        for (final String t : pageTitles == null ? List.<String>of() : pageTitles) {
            final List<String> k = tokens(t);
            if (!k.isEmpty()) {
                out.add(new PageName(k, false));
            }
        }
        try {
            final String path = url == null ? null : new URI(url).getPath();
            if (path != null) {
                final String[] parts = path.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].isEmpty()) {
                        final List<String> k = tokens(parts[i].replaceAll("\\.[a-z0-9]{2,5}$", "").replace('-', ' ').replace('_', ' '));
                        if (String.join(" ", k).length() >= 3) {
                            out.add(new PageName(k, true));
                        }
                        break;
                    }
                }
            }
        } catch (final Exception e) {
            // no path: the page titles only
        }
        return out;
    }

    /** Start of the longest end of the title (whole words before its marker) that a page name contains ({@link #namedBy}); -1 if none does. */
    private static int namedStart(final String text, final int[] t, final List<PageName> names) {
        if (names.isEmpty()) {
            return -1;
        }
        for (int w = t[0]; w < t[2]; w++) {
            if (!Character.isLetterOrDigit(text.charAt(w)) || w > t[0] && !Character.isWhitespace(text.charAt(w - 1))) {
                continue;
            }
            final List<String> words = tokens(text.substring(w, t[2]));
            if (String.join(" ", words).length() >= 3 && namedBy(names, words)) {
                return w;
            }
        }
        return -1;
    }

    /** The sentence before {@code from} (at most 160 characters) announces positions. */
    private static boolean announced(final String text, final int from) {
        int a = from;
        while (a > 0 && from - a < 160 && ".!?\n".indexOf(text.charAt(a - 1)) < 0) {
            a--;
        }
        return ANNOUNCE.matcher(text.substring(a, from)).find();
    }

    /** The terms of the position follow its title, or the title it is joined to in one sentence ("A (m/w/d) oder B (m/w/d) in Vollzeit"). */
    private static boolean immediate(final String text, final List<int[]> titles, final int i) {
        final int end = i + 1 < titles.size() ? titles.get(i + 1)[0] : text.length();
        if (IMMEDIATE.matcher(text).region(titles.get(i)[1], end).lookingAt()) {
            return true;
        }
        return i + 1 < titles.size() && JOIN_GAP.matcher(text.substring(titles.get(i)[1], end)).matches() && immediate(text, titles, i + 1);
    }

    /**
     * Phrases with which an advertisement declares the site's operator its employer: "wir suchen", "suchen wir", "wir stellen
     * ein", "bei uns", the team in any form ("Zur Verstärkung unseres Teams", "Verstärke unser Team", "Werde Teil unseres Teams",
     * "Komm in unser Team"; 0.10.6), "we are hiring", "we are looking for", "join our team". Read in the advertisement's own text
     * only ({@link #adEnd}): a menu or a footer ("Unser Team", "Karriere bei uns") declares nothing.
     */
    private static final Pattern SELF_HIRING = Pattern.compile("(?iu)\\b(?:wir\\s+suchen|suchen\\s+wir|wir\\s+stellen\\s+ein|bei\\s+uns"
            + "|unser(?:e[mnrs]?)?\\s+teams?|we\\s+are\\s+(?:hiring|looking\\s+for)|we['’]re\\s+(?:hiring|looking\\s+for)|join\\s+our\\s+team)\\b");
    /**
     * The phrases of {@link #SELF_HIRING} that announce positions in a sentence (0.10.6): what the lead of a list must say, where
     * the navigation before it names "Unser Team" or "Karriere bei uns".
     */
    private static final Pattern ANNOUNCED_HIRING = Pattern.compile("(?iu)\\b(?:wir\\s+suchen|suchen\\s+wir|wir\\s+stellen\\s+ein"
            + "|verst(?:ä|ae)rk(?:ung|e|en\\s+sie)\\s+(?:f(?:ü|ue)r\\s+)?unser(?:e[mnrs]?)?\\s+teams?|we\\s+are\\s+(?:hiring|looking\\s+for)"
            + "|we['’]re\\s+(?:hiring|looking\\s+for))\\b");
    /**
     * Where the own text of an advertisement ends (0.10.6): its application ("Bewirb Dich", "Jetzt bewerben", the form), the
     * positions next to it ("Weitere Stellen", "Ähnliche Jobs") or the footer (imprint, privacy, copyright).
     */
    private static final Pattern AD_END = Pattern.compile("(?iu)\\b(?:bewirb\\s+dich|bewerben\\s+sie\\s+sich|jetzt\\s+bewerben"
            + "|online\\s+bewerben|zur\\s+bewerbung|bewerbungsformular|apply\\s+(?:now|online)|(?:weitere|(?:ä|ae)hnliche|andere|alle)"
            + "\\s+(?:stellen(?:angebote)?|jobs|positionen)|(?:related|similar|more|other)\\s+(?:jobs|positions)|impressum|datenschutz"
            + "|imprint|privacy\\s+policy|copyright)\\b|©");
    /** The application call that ends an advertisement ("Bewirb Dich jetzt bei uns!"): its sentence still belongs to it (0.10.6). */
    private static final Pattern APPLY_CALL = Pattern.compile("(?iu)(?:bewirb\\s+dich|bewerben\\s+sie\\s+sich|jetzt\\s+bewerben"
            + "|online\\s+bewerben|zur\\s+bewerbung|apply\\s+(?:now|online))");
    /** The heading of an advertisement's requirements: what the applicant brings, not what the position is (0.10.6). */
    private static final Pattern REQUIREMENTS = Pattern.compile("(?iu)\\b(?:(?:ihr|dein|your)\\s+profil|profil\\s*:|anforderungen"
            + "|qualifikation(?:en)?\\s*:|voraussetzungen|was\\s+(?:du|sie)\\s+mitbring\\p{L}*|das\\s+bring(?:st\\s+du|en\\s+sie)\\s+mit"
            + "|wir\\s+erwarten|requirements|what\\s+you\\s+bring)");
    /** The heading of the section after the requirements: the offer or the tasks (0.10.6). */
    private static final Pattern OFFER = Pattern.compile("(?iu)\\b(?:wir\\s+bieten|das\\s+bieten\\s+wir|was\\s+wir\\s+bieten"
            + "|unser\\s+angebot|(?:deine|ihre|your)\\s+(?:vorteile|benefits|aufgaben|t(?:ä|ae)tigkeiten|tasks|responsibilities)|benefits"
            + "|what\\s+we\\s+offer|we\\s+offer|aufgaben(?:gebiet|bereich)?\\s*:)");
    /** A requirement in a sentence ("abgeschlossene Ausbildung", "Erfahrung als Freelancer"): no type of the position (0.10.6). */
    private static final Pattern REQUIRED_BEFORE = Pattern.compile("(?iu)(?:abgeschlossen\\p{L}*|erfolgreich\\p{L}*"
            + "|erfahrung(?:en)?\\s+(?:als|in|im|mit)|kenntnisse\\s+(?:als|in|im)|experience\\s+(?:as|in|with)|completed)\\s+(?:\\p{L}+\\s+){0,2}$");
    /** The second part of a compound that names the position itself ("Teilzeitstelle", "Ausbildungsplatz"; 0.10.6), folded. */
    private static final Set<String> POSITION_WORDS = Set.of("stelle", "stellen", "job", "jobs", "kraft", "kraefte", "platz", "plaetze",
            "anstellung", "beschaeftigung", "position", "vertrag", "modell", "modelle");

    /**
     * The software and system hints of the job advertisements of a careers page without job objects (0.10.2): for the site
     * operator only where the advertisement declares it the employer ({@link #employer}) and no intermediary advertises, read from
     * the text of each advertisement (section {@code job_ad_text}): a required skill stays a requirement, a system "we use"
     * internal use; nothing is a job object.
     */
    private void jobHints(final Run run) {
        final List<int[]> titles = jobTitles(run.text);
        final List<Posting> postings = postings(run.text, run.url, run.titles);
        if (postings.isEmpty()) {
            return;
        }
        for (final Posting p : postings) {
            final int[] t = {p.start, p.end, p.marker};
            final String title = JOB_LEAD.matcher(run.text.substring(t[0], t[1]).replaceAll("\\s+", " ").trim()).replaceFirst("");
            if (Normalizers.key(title) == null || BusinessFacts.personLike(title)) {
                continue;
            }
            final int windowEnd = p.windowEnd;
            if (!employer(run, null, sentenceStart(run.text, Math.min(t[0], titles.get(p.index)[0])),
                    withCall(run.text, adEnd(run.text, t[1], windowEnd), windowEnd), lead(titles, postings, p))) {
                continue; // the employer is not declared: whose systems these are stays unclear
            }
            BusinessSignals.extract(run.text.substring(t[1], windowEnd), run.subject, run.out, 2, Claim.KIND_RULE, BusinessSignals.JOB_AD + "text",
                    "text:" + t[1]);
        }
    }

    private void jobs(final Run run) {
        final List<int[]> titles = jobTitles(run.text);
        final List<Posting> postings = postings(run.text, run.url, run.titles);
        for (final Posting posted : postings) {
            final int[] t = {posted.start, posted.end, posted.marker};
            final String title = JOB_LEAD.matcher(run.text.substring(t[0], t[1]).replaceAll("\\s+", " ").trim()).replaceFirst("");
            final String key = Normalizers.key(title);
            if (key == null || BusinessFacts.personLike(title)) {
                continue;
            }
            final int windowEnd = posted.windowEnd;
            final Mention job = run.out.add(new Mention("rule:job:" + key+":"+t[0], Vocabulary.JOB, 2));
            job.name = title;
            final Matcher wp = WORK_PLACE.matcher(run.text).region(t[1], windowEnd);
            final String place = wp.find() ? wp.group(1) : null;
            job.jobKey = (run.subject.name == null ? "" : Normalizers.key(run.subject.name)) + "|" + key + "|"
                    + (place == null ? "" : Normalizers.key(place));
            claim(run, job, Vocabulary.NAME, title, t[0], t[1], 0.7);
            // the advertisement's own text, from the sentence that announces its title ("Wir suchen") to its end (0.10.6); the
            // lead of a list announces its positions, the page of a position has its menu there. The site operator is not
            // automatically the employer: unidentified jobs stay so.
            final int adEnd = adEnd(run.text, t[1], windowEnd);
            final boolean employerAssigned = employer(run, job, sentenceStart(run.text, Math.min(t[0], titles.get(posted.index)[0])),
                    withCall(run.text, adEnd, windowEnd), lead(titles, postings, posted));
            // the posting is the page and its title, not the title's position: a menu that grows keeps it (0.10.5)
            final String posting=java.util.HexFormat.of().formatHex(net.yacy.scoutro.knowledge.KgIds.objectKey(run.url+"|"+key));
            if(!employerAssigned)job.jobKey="unresolved:"+posting+"|"+key;
            else job.jobKey+="|posting:"+posting;
            if (run.ctx.jobSignals) {
                BusinessSignals.extract(run.text.substring(t[1],windowEnd),job,run.out,2,Claim.KIND_RULE,"text","text:"+t[1]);
            }
            final Set<String> types = new LinkedHashSet<>();
            // the title states it ("Freiberufliche SAP Berater", "Werkstudent …"): a form of the term or a compound of a position
            // ("Teilzeitkraft"), no other compound ("Ausbildungsleiter")
            for (final TermMatcher.Hit<Categories.Entry> h : run.ctx.categories.employmentTypes(run.text, t[0], t[2])) {
                if (stated(run.text, h) && types.add(h.entry.id)) {
                    claim(run, job, Vocabulary.EMPLOYMENT_TYPE, h.entry.id, h.start, h.end, 0.7);
                }
            }
            // or the advertisement's own text, wherever (0.10.6): never its requirements, the positions next to it or a default
            for (final TermMatcher.Hit<Categories.Entry> h : statedTypes(run, t[1], adEnd)) {
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
            if (posted.page && postings.size() == 1) {
                structuredPosting(run, job, title, place, employerAssigned);
            }
        }
    }

    /**
     * The employer of a posting (0.10.6): the operator of the page ({@link #operator}) where the advertisement's own text between
     * {@code from} and {@code to} declares it ({@link #SELF_HIRING}, in a sentence: a label of a menu declares nothing) or the
     * {@code lead} of a list ({@link #lead}) announces it ({@link #ANNOUNCED_HIRING}); never by the domain, the navigation or another
     * organisation of the page. Where an intermediary advertises for its client ({@link BusinessSignals#INTERMEDIARY}) the operator
     * recruits and the employer stays unknown; where the advertisement names another company as its hirer ({@link #NAMED_HIRER}: a
     * job portal) the operator is neither. With {@code job} null nothing is written. True if the operator is the employer.
     */
    private boolean employer(final Run run, final Mention job, final int from, final int to, final int[] lead) {
        final boolean operator = operator(run);
        final Matcher own = BusinessSignals.INTERMEDIARY.matcher(run.text).region(from, Math.max(from, to));
        final Matcher listed = BusinessSignals.INTERMEDIARY.matcher(run.text).region(lead[0], lead[1]);
        final Matcher client = own.find() ? own : listed.find() ? listed : null;
        if (client != null) {
            if (job != null && operator) {
                relation(run, job, Vocabulary.RECRUITING_ORGANIZATION, run.subject, client.start(), client.end(), 0.7);
            }
            return false;
        }
        if (!operator) {
            return false;
        }
        // a company the advertisement names as its hirer ("Die Muster AG sucht …", "Arbeitgeber: Muster AG"; 0.10.7): another
        // one than the operator (a job portal publishes it), and the operator is not the employer; the operator itself, and it is
        final Matcher hirer = NAMED_HIRER.matcher(run.text).region(from, Math.max(from, to));
        if (hirer.find()) {
            final String named = Normalizers.legalNameKey(hirer.group(1) != null ? hirer.group(1) : hirer.group(2));
            final String self = Normalizers.legalNameKey(run.subject.legalName != null ? run.subject.legalName : run.subject.name);
            if (named == null || !named.equals(self)) {
                return false;
            }
            if (job != null) {
                relation(run, job, Vocabulary.HIRING_ORGANIZATION, run.subject, hirer.start(), hirer.end(), 0.7);
            }
            return true;
        }
        Matcher at = null;
        final Matcher declared = SELF_HIRING.matcher(run.text).region(from, Math.max(from, to));
        while (declared.find()) {
            // "Unser Team", "Karriere bei uns", "Jobs bei uns" are labels of a menu or a footer: the team and "bei uns" declare only in
            // a sentence ("Bei uns wirst Du.", "Komm in unser Team!"; 0.10.7)
            if (!LABEL_FORM.matcher(declared.group()).find() || segmentWords(run.text, declared.start()) >= 4) {
                at = declared;
                break;
            }
        }
        final Matcher announced = ANNOUNCED_HIRING.matcher(run.text).region(lead[0], lead[1]);
        if (at == null && announced.find()) {
            at = announced;
        }
        if (at == null) {
            return false;
        }
        if (job != null) {
            relation(run, job, Vocabulary.HIRING_ORGANIZATION, run.subject, at.start(), at.end(), 0.7);
        }
        return true;
    }

    /**
     * The lead of a list of positions: the text between the last title before its first posting (a menu) and that posting
     * (0.10.7; 0.10.6 read from the start of the page to its first title, which is the menu's on a page with a menu); none on
     * the page of a position.
     */
    private static int[] lead(final List<int[]> titles, final List<Posting> postings, final Posting p) {
        if (p.page || postings.isEmpty()) {
            return new int[] {0, 0};
        }
        final Posting first = postings.get(0);
        final int from = first.index > 0 ? titles.get(first.index - 1)[1] : 0;
        return new int[] {Math.min(from, first.start), first.start};
    }

    /** The forms of {@link #SELF_HIRING} that are also the labels of a menu or a footer ("Unser Team", "Karriere bei uns"). */
    private static final Pattern LABEL_FORM = Pattern.compile("(?iu)unser|bei\\s+uns|join\\s+our\\s+team");
    /**
     * A company named as the hirer of an advertisement (0.10.7): "Die Muster Consulting AG sucht …", "… stellt ein", or the label
     * "Arbeitgeber: Muster Consulting AG". Group 1 or 2: its name with its legal form.
     */
    private static final Pattern NAMED_HIRER = Pattern.compile("(?u)(?:\\b(?:Die|Der|Das|The)\\s+)?((?:\\p{Lu}[\\p{L}\\p{N}&'-]*\\s+){1,4}(?:" + LEGAL
            + "))\\s+(?:sucht|stellt\\s+ein|is\\s+hiring|is\\s+looking\\s+for)\\b|(?iu:\\b(?:arbeitgeber|unternehmen|firma|employer|company)\\s*:\\s*)"
            + "((?:\\p{Lu}[\\p{L}\\p{N}&'-]*\\s+){1,4}(?:" + LEGAL + "))(?![\\p{L}\\p{N}])");

    /** The words of the segment (between {@link #SEGMENT_END} marks) that holds {@code at}. */
    private static int segmentWords(final String text, final int at) {
        int a = at;
        while (a > 0 && SEGMENT_END.indexOf(text.charAt(a - 1)) < 0) {
            a--;
        }
        int b = at;
        while (b < text.length() && SEGMENT_END.indexOf(text.charAt(b)) < 0) {
            b++;
        }
        return words(text, a, b);
    }

    /**
     * The page's subject is its operator (0.10.6): the operator its imprint names, the declared operator of its structured data,
     * an organisation of its structured data on the page's own domain, or the domain's unnamed operator, which the graph joins
     * only to the domain's one declared operator. Another organisation the page names (a client, a portal) is not who "wir" is.
     */
    private static boolean operator(final Run run) {
        final Mention s = run.subject;
        if (s.siteOperator || s.domainOperator) {
            return true;
        }
        final String own = Normalizers.registrableDomain(run.host);
        for (final Claim c : run.out.claims()) {
            if (own != null && c.subject.equals(s.ref) && Vocabulary.WEBSITE.equals(c.predicate) && c.value != null) {
                try {
                    if (own.equals(Normalizers.registrableDomain(new URI(c.value).getHost()))) {
                        return true;
                    }
                } catch (final Exception e) {
                    // no website
                }
            }
        }
        return false;
    }

    /** Start of the sentence (at most 160 characters back) that contains {@code at}. */
    private static int sentenceStart(final String text, final int at) {
        int a = at;
        while (a > 0 && at - a < 160 && ".!?\n".indexOf(text.charAt(a - 1)) < 0) {
            a--;
        }
        return a;
    }

    /**
     * The end of the own text of the advertisement whose title ends at {@code from} (0.10.6): the first {@link #AD_END} after its
     * first section, so that an application button above its tasks ends nothing; {@code windowEnd} if there is none.
     */
    private static int adEnd(final String text, final int from, final int windowEnd) {
        int start = from;
        for (final Pattern section : List.of(AD_SECTION, REQUIREMENTS, OFFER)) {
            final Matcher s = section.matcher(text).region(from, windowEnd);
            if (s.find() && (start == from || s.start() < start)) {
                start = s.start();
            }
        }
        final Matcher e = AD_END.matcher(text).region(start, windowEnd);
        return e.find() ? e.start() : windowEnd;
    }

    /** {@code adEnd}, or the end of its sentence (at most 160 characters) where the advertisement ends with its application call. */
    private static int withCall(final String text, final int adEnd, final int windowEnd) {
        if (!APPLY_CALL.matcher(text).region(adEnd, windowEnd).lookingAt()) {
            return adEnd;
        }
        int b = adEnd;
        while (b < windowEnd && b - adEnd < 160 && ".!?\n".indexOf(text.charAt(b)) < 0) {
            b++;
        }
        return b;
    }

    /**
     * The employment types the advertisement states between {@code from} and {@code to} (0.10.6): not in its requirements (from
     * their heading to the next offer or tasks), not as a requirement in a sentence ("abgeschlossene Ausbildung", "Erfahrung als
     * Freelancer"), only as a form of the term or a compound of a position ({@link #stated}).
     */
    private List<TermMatcher.Hit<Categories.Entry>> statedTypes(final Run run, final int from, final int to) {
        final List<int[]> requirements = new ArrayList<>();
        int at = from;
        while (at < to) {
            final Matcher r = REQUIREMENTS.matcher(run.text).region(at, to);
            if (!r.find()) {
                break;
            }
            final Matcher o = OFFER.matcher(run.text).region(r.end(), to);
            final int end = o.find() ? o.start() : to;
            requirements.add(new int[] {r.start(), end});
            at = end;
        }
        final List<TermMatcher.Hit<Categories.Entry>> out = new ArrayList<>();
        hits: for (final TermMatcher.Hit<Categories.Entry> h : run.ctx.categories.employmentTypes(run.text, from, to)) {
            for (final int[] r : requirements) {
                if (h.start >= r[0] && h.start < r[1]) {
                    continue hits;
                }
            }
            if (stated(run.text, h) && !REQUIRED_BEFORE.matcher(run.text.substring(Math.max(from, h.start - 60), h.start)).find()) {
                out.add(h);
            }
        }
        return out;
    }

    /** {@link #inflected}, or a compound whose second part names a position ("Teilzeitstelle", "Ausbildungsplatz"; 0.10.6). */
    private static boolean stated(final String text, final TermMatcher.Hit<Categories.Entry> h) {
        if (inflected(text, h)) {
            return true;
        }
        final String word = TermMatcher.fold(text.substring(h.start, h.end));
        for (final String term : h.entry.terms) {
            final String t = TermMatcher.fold(term);
            if (word.startsWith(t)) {
                final String rest = word.substring(t.length());
                // with a linking element: "Praktikumsplatz", "Werkstudentenstelle"
                if (POSITION_WORDS.contains(rest) || rest.startsWith("s") && POSITION_WORDS.contains(rest.substring(1))
                        || rest.startsWith("en") && POSITION_WORDS.contains(rest.substring(2))
                        || rest.startsWith("n") && POSITION_WORDS.contains(rest.substring(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The structured posting of the page of one position is that position's advertisement (0.10.6): the one {@code JobPosting}
     * of its JSON-LD joins the job of its rules where it names no title of its own ({@link JsonLdExtractor}: none, or the
     * organisation's name) or the same title, and nothing of its identity contradicts the advertisement ({@link #contradicts}).
     * Each statement keeps its tier and evidence; neither source decides for the other.
     */
    private static void structuredPosting(final Run run, final Mention job, final String title, final String place,
            final boolean operatorEmploys) {
        Mention structured = null;
        for (final Mention m : run.out.mentions()) {
            if (Vocabulary.JOB.equals(m.type) && m.tier == 1) {
                if (structured != null) {
                    return; // several postings: which is the page's is not guessed
                }
                structured = m;
            }
        }
        if (structured == null || structured.name != null && !java.util.Objects.equals(jobKey(structured.name), jobKey(title))
                || contradicts(run, job, structured, place, operatorEmploys)) {
            return;
        }
        structured.jobKey = job.jobKey;
        structured.ldId = null; // the page's advertisement is the posting, not its id
    }

    /**
     * A structured posting contradicts the advertisement of its page (0.10.6) if it declares another page (its {@code url}, or an
     * {@code @id} that is the address of another page), another place of work than the advertisement's ("Standort: …"), or
     * another employer: an organisation that is not the operator where the advertisement declares the operator, the operator
     * where the advertisement recruits for a client, or an intermediary's posting where the advertisement is the operator's own.
     * What either source leaves open contradicts nothing.
     */
    private static boolean contradicts(final Run run, final Mention job, final Mention structured, final String place,
            final boolean operatorEmploys) {
        if (structured.postingUrl != null && !samePage(structured.postingUrl, run.url)) {
            return true;
        }
        if (structured.ldId != null && structured.ldId.matches("(?i)https?://.*") && !samePage(structured.ldId, run.url)) {
            return true;
        }
        final String own = Normalizers.key(place);
        if (own != null && structured.jobPlace != null && !own.equals(structured.jobPlace)) {
            return true;
        }
        boolean recruits = false;
        for (final Claim c : run.out.claims()) {
            if (c.subject.equals(job.ref) && Vocabulary.RECRUITING_ORGANIZATION.equals(c.predicate)) {
                recruits = true;
            }
        }
        for (final Claim c : run.out.claims()) {
            if (!c.subject.equals(structured.ref)) {
                continue;
            }
            if (Vocabulary.RECRUITING_ORGANIZATION.equals(c.predicate) && operatorEmploys) {
                return true; // the posting is an intermediary's, the advertisement the operator's own
            }
            if (Vocabulary.HIRING_ORGANIZATION.equals(c.predicate)) {
                final boolean operator = isOperator(run, run.out.mention(c.object));
                if (operatorEmploys && !operator || recruits && operator) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The organisation is the page's operator: declared so, the subject of the page by its name, or on the page's own domain. */
    private static boolean isOperator(final Run run, final Mention o) {
        if (o == null) {
            return false;
        }
        if (o.siteOperator || o.domainOperator || o.ref.equals(run.subject.ref)) {
            return true;
        }
        final String legal = Normalizers.legalNameKey(o.legalName);
        if (legal != null && legal.equals(Normalizers.legalNameKey(run.subject.legalName))) {
            return true;
        }
        final String name = Normalizers.key(o.name);
        if (name != null && (name.equals(Normalizers.key(run.subject.name)) || name.equals(Normalizers.key(run.subject.legalName)))) {
            return true;
        }
        final String domain = Normalizers.registrableDomain(run.host);
        for (final Claim c : run.out.claims()) {
            if (domain != null && c.subject.equals(o.ref) && Vocabulary.WEBSITE.equals(c.predicate) && c.value != null) {
                try {
                    if (domain.equals(Normalizers.registrableDomain(new URI(c.value).getHost()))) {
                        return true;
                    }
                } catch (final Exception e) {
                    // no website
                }
            }
        }
        return false;
    }

    /** Two addresses name the same page: host (without "www."), path (without a final "/") and query; scheme and fragment aside. */
    private static boolean samePage(final String a, final String b) {
        final String pa = page(a);
        return pa != null && pa.equals(page(b));
    }

    private static String page(final String url) {
        try {
            final URI u = new URI(url.trim());
            if (u.getHost() == null) {
                return null;
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") + path + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } catch (final Exception e) {
            return null;
        }
    }

    /** The hit is a term itself or a form of it (at most two more letters: "freiberufliche", "Praktikantin"), no compound. */
    private static boolean inflected(final String text, final TermMatcher.Hit<Categories.Entry> h) {
        final String word = TermMatcher.fold(text.substring(h.start, h.end));
        for (final String term : h.entry.terms) {
            final String t = TermMatcher.fold(term);
            if (word.equals(t) || word.startsWith(t) && word.length() - t.length() <= 2) {
                return true;
            }
        }
        return false;
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

    /**
     * Start of a job title that ends at {@code markerAt}: back to a line break, a sentence end, a list mark or the end of the
     * marker before ({@code floor}), there without the word that joins both titles; -1 if none.
     */
    private static int titleStart(final String text, final int markerAt, final int floor) {
        int i = markerAt;
        final int stop = Math.max(Math.max(0, floor), markerAt - 90);
        while (i > stop) {
            final char c = text.charAt(i - 1);
            if (c == '\n' || c == '•' || c == '|' || c == ':' || c == ';' || c == '#' || c == '*'
                    || c == '.' && (i >= markerAt - 1 || Character.isWhitespace(text.charAt(i)))) {
                break;
            }
            i--;
        }
        final boolean afterMarker = i == floor && floor > 0;
        while (i < markerAt && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '-')) {
            i++;
        }
        if (afterMarker && i < markerAt) {
            final Matcher join = TITLE_JOIN.matcher(text).region(i, markerAt);
            if (join.lookingAt()) {
                i = join.end();
            }
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
