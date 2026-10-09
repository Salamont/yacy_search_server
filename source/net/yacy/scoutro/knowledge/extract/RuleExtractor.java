/*
 *  RuleExtractor
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Tier 2, deterministic rules on the visible text of candidate pages
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.1).
 * <ul>
 * <li>On an imprint (German "Impressum", "Angaben gemäß § 5 ...", English
 * "Imprint"/"Legal notice") the first organisation name with a legal form
 * after the marker is the declared operator of the site; the register entry,
 * VAT ID, postal address, phone and e-mail of the imprint belong to it.</li>
 * <li>Version 4: only the operator's sections of the imprint count. A section
 * about another party (its liability insurer, its chamber or supervisory
 * authority, the dispute resolution, the makers of the site) is opened by its
 * phrase, or by its heading as a label with a colon or as a line or sentence
 * of its own, and ends at the next label of the operator's data
 * ({@link #operatorRanges}); neither the operator's name nor its contact
 * values are taken from it. Headings and labels decide, never names or
 * running text: an insurer, a chamber or a "Webdesign Beispiel GmbH" that
 * operates the site is found like any other operator.</li>
 * <li>Version 4: an imprint without a legal form names the operator only as a
 * unit (a line, or a sentence of YaCy's page text, where a {@code <br>} is
 * ". ") of its own among the few units above the operator's postal address
 * (a tagline, a role and a person may stand between them), and only if the
 * site's domain ("livaid.com" for "LIVAID") or the page's single structured
 * organisation confirms it; a unit that may be a person's (two or more
 * capitalised words, right after a role) is never a name. That name goes to
 * the site's unnamed operator ({@code domain_operator}), not to a declared
 * operator: it has no legal name to be keyed by. Otherwise nothing is named.</li>
 * <li>On other candidate pages (contact, about, locations, services, home)
 * contact values are attached only if the page's structured data describes
 * exactly one organisation or facility; otherwise nothing is guessed.</li>
 * </ul>
 * Persons are never extracted (managing directors, responsible persons), nor
 * e-mail addresses that may be a person's ({@link Normalizers#roleEmail}).
 * Every claim keeps its text location ({@code text:offset+length}) and an
 * excerpt of at most {@code maxExcerpt} characters.
 * <p>
 * Version 3 (vocabulary 2) adds the business rules ({@link BusinessRules})
 * for the page's organisation: services, prices, relations, industry,
 * contacts, jobs and audiences, and the places of addresses.
 */
public final class RuleExtractor {

    public static final String NAME = "rule";
    public static final String VERSION = "6";
    public static final String OPERATOR_REF = "rule:operator";

    private static final int WINDOW = 2500;

    private static final Pattern CANDIDATE_PATH = Pattern.compile(
            "(?i)(?:^|/)(?:impressum|imprint|legal-?notice|anbieterkennzeichnung|kontakt|contact|about|about-us|ueber-uns|uber-uns|über-uns"
            + "|wir|team|standort|standorte|location|locations|einrichtungen|leistungen|services|angebot|angebote)(?:[./_\\-]|$)");
    private static final Pattern CANDIDATE_TITLE = Pattern.compile(
            "(?i)\\b(?:impressum|imprint|legal notice|kontakt|contact|über uns|ueber uns|about us|standorte?|leistungen|einrichtungen)\\b");
    private static final Pattern IMPRINT_MARKER = Pattern.compile(
            "(?i)(?:\\bimpressum\\b|angaben\\s+gem(?:ä|ae)(?:ß|ss)\\s*§\\s*5|anbieterkennzeichnung|\\bimprint\\b|\\blegal\\s+notice\\b"
            + "|diensteanbieter\\b|verantwortlich\\s+(?:i\\.\\s?s\\.\\s?d\\.|im\\s+sinne))");

    private static final String LEGAL_FORM_TOKENS = "GmbH\\s*&\\s*Co\\.?\\s*KG|UG\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)(?:\\s*&\\s*Co\\.?\\s*KG)?"
            + "|gGmbH|GmbH|G\\.\\s?m\\.\\s?b\\.\\s?H\\.?|gAG|KGaA|AG|e\\.\\s?V\\.|eG|PartG(?:\\s*mbB)?|OHG|KG|GbR|SE|e\\.\\s?K\\.|Stiftung|Ltd\\.?|Inc\\.?|LLC";
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + LEGAL_FORM_TOKENS + ")(?![\\p{L}\\p{N}])");
    private static final Set<String> CONNECTORS = new TreeSet<>(java.util.Arrays.asList(
            "und", "&", "für", "fuer", "der", "die", "das", "des", "von", "am", "an", "im", "in", "zu", "zur", "zum", "-", "+", "of", "and", "the"));
    private static final Set<String> STOP_BEFORE_NAME = new TreeSet<>(java.util.Arrays.asList(
            "die", "der", "durch", "vertreten", "betreiber", "anbieter", "inhaber", "herausgeber", "betrieben", "von", "ist", "sind",
            "wird", "werden", "the", "by", "operated", "dieser", "diese", "website", "webseite", "seite", "internetseite"));

    /**
     * Phrases that only speak of another party than the operator (its chamber, its insurer): they open such a section
     * wherever they stand ("Zuständige Kammer", "Mitglied der Architektenkammer", "Name und Sitz des Versicherers").
     */
    private static final Pattern THIRD_PARTY_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:"
            + "zust(?:ä|ae)ndige[rns]?\\s+(?:kammer|berufskammer|aufsichtsbeh(?:ö|oe)rde|beh(?:ö|oe)rde)"
            + "|mitglied(?:schaft)?\\s+(?:der|des|in\\s+der|im)\\s+[\\p{L}-]*(?:kammer|verband|verbandes)(?![\\p{L}])"
            + "|(?:name|sitz|anschrift)(?:\\s+und\\s+(?:sitz|anschrift))?\\s+des\\s+versicherers|geltungsraum\\s+der\\s+versicherung)");
    /**
     * Headings of such a section: they count only as a label with a colon or as a heading of their own (a line or a
     * sentence of its own, as YaCy's text has a {@code <br>} or a heading: "### Berufshaftpflichtversicherung."), never
     * inside a name or running text ("Webdesign Beispiel GmbH", "Schlichtungsstelle Bau e.V.", "wir bieten Webdesign").
     */
    private static final Pattern THIRD_PARTY_HEADING = Pattern.compile("(?iu)(?<![\\p{L}])(?:(?:angaben|informationen|hinweise?)\\s+zu[mr]?\\s+)?(?:"
            + "(?:berufs|betriebs|verm(?:ö|oe)gensschaden)[\\s-]?haftpflicht\\p{L}*|haftpflichtversicherung\\p{L}*|versicherer"
            + "|aufsichtsbeh(?:ö|oe)rde\\p{L}*|berufskammer|kammer(?:zugeh(?:ö|oe)rigkeit)?"
            + "|(?:eu-?|online-?)?streitschlichtung|(?:verbraucher)?streitbeilegung(?:\\s*/\\s*(?:universal)?schlichtungsstelle)?"
            + "|(?:universal|verbraucher)?schlichtungsstelle|web-?design|bildnachweis\\p{L}*|bildquellen?|bildrechte\\p{L}*|fotonachweis\\p{L}*)"
            + "(?![\\p{L}])");
    /** Words that open such a section only as a label with a colon ("Design: Pixel Agentur GmbH"). */
    private static final Pattern THIRD_PARTY_LABEL = Pattern.compile("(?iu)(?<![\\p{L}])(?:versicherung|bildmaterial|fotos?|fotografie|hosting"
            + "|realisierung|(?:technische\\s+)?umsetzung|gestaltung|design|konzept(?:ion)?)(?=[ \\t]*:)");
    /** Phrases that open the operator's own data wherever they stand. */
    private static final Pattern OPERATOR_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:impressum|imprint|anbieterkennzeichnung"
            + "|angaben\\s+gem(?:ä|ae)(?:ß|ss)\\s*§\\s*5)(?![\\p{L}])");
    /**
     * Labels of the operator's own data: they end a section about another party when they start a line or a sentence
     * or carry a colon ("Registergericht: …", "Geschäftsführerin."), not inside running text of that section.
     */
    private static final Pattern OPERATOR_LABEL = Pattern.compile("(?iu)(?<![\\p{L}])(?:diensteanbieter(?:in)?|(?:website-?|seiten)?betreiber(?:in)?"
            + "|anbieter(?:in)?|herausgeber(?:in)?|inhaber(?:in)?|vertreten\\s+durch|vertretungsberechtigt\\p{L}*|gesch(?:ä|ae)ftsf(?:ü|ue)hr\\p{L}*"
            + "|registergericht|registereintrag|handelsregister|registernummer|umsatzsteuer\\p{L}*|ust\\.?\\s?-?\\s?id\\p{L}*|kontakt"
            + "|verantwortlich\\p{L}*)(?![\\p{L}])");
    /** What makes a label of the operator's data one of the other party's ("USt-IdNr. des Versicherers"). */
    private static final Pattern OTHER_PARTY_QUALIFIER = Pattern.compile("(?iu)[^\\n:]{0,12}?\\b(?:des|der|dieser|dieses)\\s+"
            + "(?:versicher|kammer|berufskammer|aufsichtsbeh|beh(?:ö|oe)rde|schlichtungsstelle|agentur)");
    /** A unit that introduces a person ("Geschäftsführerin", "Inhaber:", "Vertreten durch"): the next unit is a person's. */
    private static final Pattern PERSON_ROLE_UNIT = Pattern.compile("(?iu)(?:gesch(?:ä|ae)ftsf(?:ü|ue)hr\\p{L}*|inhaber(?:in)?|vorstand\\p{L}*"
            + "|prokurist\\p{L}*|ansprechpartner\\p{L}*|vertreten\\s+durch.*|vertretungsberechtigt.*|verantwortlich.*|owner|ceo"
            + "|managing\\s+directors?)\\s*:?");
    /** Units between the operator's name and its postal address that are looked at (name, tagline, role, person, …). */
    private static final int NAME_UNITS = 6;
    /** A label at the start of an operator's name line ("Betreiber: LIVAID"); a holder ("Inhaber:") is a person's. */
    private static final Pattern NAME_LABEL = Pattern.compile("(?iu)^(?:(?:website-?|seiten)?betreiber(?:in)?|anbieter(?:in)?|diensteanbieter(?:in)?"
            + "|herausgeber(?:in)?)\\s*:\\s*");
    /** A capitalised word as names of persons have them ("Erika", "Musterfrau"), or a particle of such a name. */
    private static final Pattern PERSON_WORD = Pattern.compile("\\p{Lu}\\p{Ll}[\\p{L}'\\-]*|von|van|de|der|den|zu|vom|zur");

    private static final Pattern REGISTER_A = Pattern.compile(
            "(?:Amtsgericht|Registergericht|AG)\\s+([A-ZÄÖÜ][\\p{L}\\-]+(?:\\s+(?:am|an\\s+der|i\\.\\s?Br\\.|\\(Oder\\)|\\(Main\\)|Main|Oder)){0,2})"
            + "[\\s,:;.]{0,6}(?:(?:Registernummer|Reg\\.?\\s?-?\\s?Nr\\.?|Handelsregister(?:nummer)?|HR-?Nr\\.?|Vereinsregister(?:nummer)?)[\\s:.]{0,4})?"
            + "(HRA|HRB|VR|GnR|PR|GsR)\\s*:?\\s*([0-9]{1,7}(?:\\s?[A-Z]{1,2}(?![\\p{L}]))?)");
    private static final Pattern REGISTER_B = Pattern.compile(
            "(HRA|HRB|VR|GnR|PR|GsR)\\s*:?\\s*([0-9]{1,7}(?:\\s?[A-Z]{1,2}(?![\\p{L}]))?)[^\\n]{0,40}?(?:Amtsgericht|Registergericht|AG)\\s+"
            + "([A-ZÄÖÜ][\\p{L}\\-]+(?:\\s+(?:am|an\\s+der|i\\.\\s?Br\\.|\\(Oder\\)|\\(Main\\)|Main|Oder)){0,2})");
    private static final Pattern VAT = Pattern.compile(
            "(?i:USt\\.?\\s?-?\\s?Id(?:ent)?(?:\\.|-)?\\s?(?:Nr\\.?|Nummer)?|Umsatzsteuer-?\\s?Identifikationsnummer|Umsatzsteuer-?\\s?ID"
            + "|VAT(?:\\s?(?:ID|No\\.?|number|Reg\\.?\\s?No\\.?))?|UID(?:-?Nr\\.?)?)[\\s\\S]{0,70}?"
            // country codes are upper case: "es" in "Umsatzsteuergesetz" is no VAT ID
            // the number stays on its line: "DE 123 456 789\nBerufsbezeichnung" is not "DE123456789B"
            + "(?<![A-Za-z])((?:DE|ATU|CHE|BE|NL|FR|LU|DK|PL|CZ|IT|ES|SE|FI|IE|PT|SK|SI|HU|GB)[ \\t.\\-]?[0-9](?:[ \\t.\\-]?[0-9A-Z]){6,13})");
    private static final Pattern PHONE = Pattern.compile(
            "(?i)(?:\\bTel(?:efon)?\\.?|\\bPhone|\\bFon|\\bTelefonnummer|\\bT\\.)\\s*:?\\s*((?:\\+|00)?[0-9][0-9 ()/\\-.]{5,24}[0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}");
    private static final Pattern IK = Pattern.compile("(?i)\\bIK(?:-?(?:Nr\\.?|Nummer))?\\s*:?\\s*([0-9]{9})\\b");
    private static final String CITY_STOP = "(?!(?:Tel|Telefon|Fax|E-?Mail|Mail|Web|Internet|Deutschland|Germany|Österreich|Austria|Schweiz"
            + "|Registergericht|Amtsgericht|Handelsregister|Geschäftsführ|Geschaeftsfuehr|Vertreten|USt|Steuer|Phone|Mobil|Kontakt|Vorstand"
            + "|Inhaber|Verantwortlich|Sitz)\\b)";
    private static final Pattern ADDRESS = Pattern.compile(
            "([A-ZÄÖÜ][\\p{L}.\\-]*(?:[ \\t]+[\\p{L}.\\-]+){0,3}?(?i:straße|strasse|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|steig|pfad"
            + "|markt|hof|berg|feld|garten|park|graben|kamp|stieg|tor|brücke|bruecke|zeile|wall|anger|aue|chaussee))\\s+"
            + "([0-9]{1,4}\\s?[a-zA-Z]?(?:\\s?[-–/]\\s?[0-9]{1,4}\\s?[a-zA-Z]?)?)(?![0-9])\\s*[,;]?\\s*(?:D\\s?-\\s?)?([0-9]{5})\\s+"
            + CITY_STOP + "([A-ZÄÖÜ][\\p{L}\\-]+(?:[ \\-]" + CITY_STOP + "(?:am|an\\s+der|a\\.\\s?d\\.|im|in|bei|ob\\s+der|[A-ZÄÖÜ(][\\p{L}.)\\-]+)){0,3})");

    private final int maxExcerpt;
    private final int maxInput;
    private final BusinessRules business;

    public RuleExtractor(final int maxExcerpt, final int maxInput) {
        this.maxExcerpt = maxExcerpt;
        this.maxInput = maxInput;
        this.business = new BusinessRules(maxExcerpt);
    }

    /** True if tier 2 reads the text of this page (imprint, contact and similar paths or titles, home page, pages with structured data). */
    public static boolean candidate(final String url, final List<String> titles, final Extraction tier1) {
        if (tier1 != null && !tier1.subjects(1).isEmpty()) {
            return true;
        }
        String path = null;
        try {
            path = url == null ? null : new URI(url).getRawPath();
        } catch (final Exception e) {
            path = null;
        }
        if (path == null || path.isEmpty() || "/".equals(path) || path.matches("(?i)^/(?:index\\.(?:html?|php)|home|start|de|en)/?$")) {
            return true;
        }
        if (CANDIDATE_PATH.matcher(path).find() || !BusinessRules.kinds(url, null).isEmpty()) {
            return true;
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t != null && CANDIDATE_TITLE.matcher(t).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tier 2 without the business rules (the imprint and contact rules of version 2). */
    public void extract(final String fullText, final String url, final String host, final String language, final Extraction out) {
        extract(fullText, url, host, language, out, null, null, null);
    }

    /** Tier 2 with the business rules of vocabulary 2 (titles and outbound links as Solr has them). */
    public void extract(final String fullText, final String url, final String host, final String language, final Extraction out,
            final ExtractContext ctx, final List<String> titles, final List<String> outbound) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        final String text = fullText.length() > this.maxInput ? fullText.substring(0, this.maxInput) : fullText;
        final String cc = Normalizers.countryCallingCode(host, language);
        final Matcher marker = IMPRINT_MARKER.matcher(text);
        final List<Mention> subjects = out.subjects(1);
        final boolean imprint = marker.find();
        if (imprint) {
            imprint(text, marker.end(), host, cc, out, subjects);
        } else if (subjects.size() == 1) {
            contact(text, operatorRanges(text, 0, text.length()), cc, out, subjects.get(0));
        }
        if (ctx != null) {
            final java.util.Set<BusinessRules.PageKind> kinds = BusinessRules.kinds(url, titles);
            if (imprint) {
                kinds.add(BusinessRules.PageKind.IMPRINT);
            }
            final Mention subject = BusinessRules.subject(out, out.mention(OPERATOR_REF));
            this.business.extract(text, url, host, language, outbound, out, ctx, kinds, subject);
            if(subject!=null && !kinds.contains(BusinessRules.PageKind.CAREERS))
                BusinessSignals.extract(text,subject,out,2,Claim.KIND_RULE,"text","text");
            BusinessFacts.ownPlaces(out, host, 2);
            BusinessFacts.industriesFromServices(out, ctx, 2, this.maxExcerpt);
        }
        out.ranTier(2);
    }

    private void imprint(final String text, final int markerAt, final String host, final String cc, final Extraction out,
            final List<Mention> structured) {
        final int end = Math.min(text.length(), markerAt + WINDOW);
        // the operator's own sections only: the insurer's, the chamber's or the web agency's name and contacts are theirs
        final List<int[]> ranges = operatorRanges(text, markerAt, end);
        String legal = null;
        int nameStart = -1;
        int nameEnd = -1;
        search: for (final int[] r : ranges) {
            final Matcher lf = LEGAL_FORM.matcher(text).region(r[0], r[1]);
            while (lf.find()) {
                final int start = nameStart(text, lf.start(), r[0]);
                if (start >= 0) {
                    final String candidate = Normalizers.legalName(text.substring(start, lf.end()));
                    if (candidate != null && candidate.length() >= 4) {
                        legal = candidate;
                        nameStart = start;
                        nameEnd = lf.end();
                        break search;
                    }
                }
            }
        }
        final Mention m;
        if (legal != null) {
            m = out.add(new Mention(OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
            m.name = legal;
            m.legalName = legal;
            m.siteOperator = true;
            claim(out, m, Vocabulary.NAME, legal, text, nameStart, nameEnd);
            claim(out, m, Vocabulary.LEGAL_FORM, Normalizers.legalForm(legal), text, nameStart, nameEnd);
        } else {
            final int[] plain = plainOperatorName(text, ranges, host, structured);
            if (plain == null) {
                return; // no operator the imprint names clearly: the site's operator stays unnamed
            }
            nameStart = plain[0];
            nameEnd = plain[1];
            m = out.add(new Mention(OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
            m.name = text.substring(nameStart, nameEnd);
            // no legal name to key a declared operator by: the name belongs to the site's operator of the domain
            m.domainOperator = true;
            claim(out, m, Vocabulary.NAME, m.name, text, nameStart, nameEnd);
        }

        final List<int[]> after = from(ranges, nameStart);
        int[] ra = null;
        Normalizers.Register reg = null;
        for (final int[] r : after) {
            final Matcher a = REGISTER_A.matcher(text).region(r[0], r[1]);
            if (a.find()) {
                reg = Normalizers.register(a.group(1), a.group(2), a.group(3));
                ra = new int[] {a.start(), a.end()};
                break;
            }
        }
        if (reg == null) {
            for (final int[] r : after) {
                final Matcher b = REGISTER_B.matcher(text).region(r[0], r[1]);
                if (b.find()) {
                    reg = Normalizers.register(b.group(3), b.group(1), b.group(2));
                    ra = new int[] {b.start(), b.end()};
                    break;
                }
            }
        }
        if (reg != null) {
            m.strongKeys.put(Vocabulary.REGISTER, reg.key);
            claim(out, m, Vocabulary.ID_REGISTER, reg.display, text, ra[0], ra[1]);
        }
        vat: for (final int[] r : after) {
            final Matcher vm = VAT.matcher(text).region(r[0], r[1]);
            while (vm.find()) {
                final String vat = Normalizers.vat(vm.group(1));
                if (vat != null) {
                    m.strongKeys.put(Vocabulary.VAT, vat);
                    claim(out, m, Vocabulary.ID_VAT, vat, text, vm.start(1), vm.end(1));
                    break vat;
                }
            }
        }
        for (final int[] r : after) {
            final Matcher ik = IK.matcher(text).region(r[0], r[1]);
            if (ik.find()) {
                final String v = Normalizers.ik(ik.group(1));
                if (v != null) {
                    m.strongKeys.put(Vocabulary.IK, v);
                    claim(out, m, Vocabulary.ID_IK, v, text, ik.start(), ik.end());
                }
                break;
            }
        }
        // contact values follow the name; searching from its end keeps the name out of the street
        contact(text, from(ranges, nameEnd), cc, out, m);
    }

    /**
     * The parts of [from, to) outside the sections about other parties: such a section runs from its phrase, heading or
     * label ({@link #THIRD_PARTY_PHRASE}, {@link #THIRD_PARTY_HEADING}, {@link #THIRD_PARTY_LABEL}) to the next label of
     * the operator's data ({@link #OPERATOR_PHRASE}, {@link #OPERATOR_LABEL}) or to {@code to}.
     */
    static List<int[]> operatorRanges(final String text, final int from, final int to) {
        final List<int[]> out = new ArrayList<>();
        int at = from;
        while (at < to) {
            final int[] third = thirdPartyLabel(text, at, to);
            if (third == null) {
                out.add(new int[] {at, to});
                break;
            }
            if (third[0] > at) {
                out.add(new int[] {at, third[0]});
            }
            final int[] own = operatorLabel(text, third[1], to);
            if (own == null) {
                break;
            }
            at = own[0];
        }
        return out;
    }

    /** The first label of another party's section in [from, to), as [start, end), or null. */
    private static int[] thirdPartyLabel(final String text, final int from, final int to) {
        int[] best = null;
        for (final Pattern p : new Pattern[] {THIRD_PARTY_PHRASE, THIRD_PARTY_HEADING, THIRD_PARTY_LABEL}) {
            final Matcher m = p.matcher(text).region(from, to).useTransparentBounds(true);
            while (m.find() && (best == null || m.start() < best[0])) {
                if (p != THIRD_PARTY_HEADING || colonAfter(text, m.end(), to) || unitStart(text, m.start()) && unitEnd(text, m.end(), to)) {
                    best = new int[] {m.start(), m.end()};
                    break;
                }
            }
        }
        return best;
    }

    /** The first label of the operator's own data in [from, to), as [start, end), or null. */
    private static int[] operatorLabel(final String text, final int from, final int to) {
        int[] best = null;
        final Matcher phrase = OPERATOR_PHRASE.matcher(text).region(from, to).useTransparentBounds(true);
        if (phrase.find()) {
            best = new int[] {phrase.start(), phrase.end()};
        }
        final Matcher label = OPERATOR_LABEL.matcher(text).region(from, to).useTransparentBounds(true);
        while (label.find() && (best == null || label.start() < best[0])) {
            if ((colonAfter(text, label.end(), to) || unitStart(text, label.start()))
                    // "USt-IdNr. des Versicherers": a label of the other party's data, its section goes on
                    && !OTHER_PARTY_QUALIFIER.matcher(text).region(label.end(), Math.min(to, label.end() + 40)).lookingAt()) {
                best = new int[] {label.start(), label.end()};
                break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ units: lines and sentences of the page text

    // YaCy's page text ends a block (p, div, li, heading) with a line break and a <br> with ". " and marks bold and
    // headings in Markdown ("**LIVAID. **Live | Architecture. Geschäftsführerin. Tânia Ferreira. Bonnstraße 164"):
    // a unit is a line or a sentence, without its Markdown.

    private static final Set<String> UNIT_ABBREVIATIONS = new TreeSet<>(java.util.Arrays.asList("st", "dr", "prof", "co", "gebr", "str",
            "nr", "hr", "fr", "abs", "ca", "bzw", "inkl", "dipl", "ing", "med", "hl", "tel", "z.b", "u.a", "e.v", "e.k", "i.s.d"));

    private static boolean markup(final char c) {
        return c == ' ' || c == '\t' || c == '*' || c == '_' || c == '#' || c == '•' || c == '>';
    }

    /** True if the period at {@code dot} ends a sentence (not "Str." or "Dr."), followed by white space or the end. */
    private static boolean sentenceEnd(final String text, final int dot) {
        if (dot + 1 < text.length() && !Character.isWhitespace(text.charAt(dot + 1)) && text.charAt(dot + 1) != '*') {
            return false;
        }
        int k = dot;
        while (k > 0 && !Character.isWhitespace(text.charAt(k - 1)) && text.charAt(k - 1) != '*') {
            k--;
        }
        return !UNIT_ABBREVIATIONS.contains(text.substring(k, dot).toLowerCase(Locale.ROOT));
    }

    /** True if a unit starts at {@code at}: only Markdown or white space after a line break, a sentence end or the start. */
    static boolean unitStart(final String text, final int at) {
        int i = at;
        while (i > 0 && (markup(text.charAt(i - 1)) || text.charAt(i - 1) == '-' && (i < 2 || Character.isWhitespace(text.charAt(i - 2))))) {
            i--;
        }
        if (i == 0) {
            return true;
        }
        final char c = text.charAt(i - 1);
        return c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, i - 1);
    }

    /** True if a unit ends at {@code at}: only Markdown or white space before a line break, a sentence end or {@code to}. */
    private static boolean unitEnd(final String text, final int at, final int to) {
        int i = at;
        while (i < to && markup(text.charAt(i))) {
            i++;
        }
        if (i >= to) {
            return true;
        }
        final char c = text.charAt(i);
        return c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, i);
    }

    /** True if a colon follows {@code at} ("Berufshaftpflichtversicherung:", "USt-IdNr.:"). */
    private static boolean colonAfter(final String text, final int at, final int to) {
        int i = at;
        while (i < to && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '.' || text.charAt(i) == '*')) {
            i++;
        }
        return i < to && text.charAt(i) == ':';
    }

    /** The unit that ends before the unit starting at {@code at}, as [start, end) without Markdown, or null at {@code floor}. */
    static int[] previousUnit(final String text, final int at, final int floor) {
        int e = at;
        while (e > floor && (Character.isWhitespace(text.charAt(e - 1)) || markup(text.charAt(e - 1)))) {
            e--;
        }
        if (e > floor && (text.charAt(e - 1) == '.' || text.charAt(e - 1) == '!' || text.charAt(e - 1) == '?') && sentenceEnd(text, e - 1)) {
            e--;
        }
        while (e > floor && (Character.isWhitespace(text.charAt(e - 1)) || markup(text.charAt(e - 1)))) {
            e--;
        }
        if (e <= floor) {
            return null;
        }
        int st = e - 1;
        while (st > floor) {
            final char c = text.charAt(st - 1);
            if (c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, st - 1)) {
                break;
            }
            st--;
        }
        while (st < e && (Character.isWhitespace(text.charAt(st)) || markup(text.charAt(st)) || text.charAt(st) == '-')) {
            st++;
        }
        return st < e ? new int[] {st, e} : null;
    }

    /** The first postal address in [from, to) whose street does not reach back over a sentence end, as its matcher; or null. */
    private static Matcher findAddress(final String text, final int from, final int to) {
        int at = from;
        while (at < to) {
            final Matcher am = ADDRESS.matcher(text).region(at, to);
            if (!am.find()) {
                return null;
            }
            // "Geschäftsführerin. Tânia Ferreira. Bonnstraße 164": the street is "Bonnstraße", not the sentences before it
            int cut = -1;
            for (int i = am.start(1); i < am.end(1) - 1; i++) {
                if (text.charAt(i) == '.' && Character.isWhitespace(text.charAt(i + 1)) && sentenceEnd(text, i)) {
                    cut = i + 1;
                }
            }
            if (cut < 0) {
                return am;
            }
            at = cut;
        }
        return null;
    }

    /** True if {@code at} lies in a section of {@code text} about another party than the operator. */
    public static boolean inThirdPartySection(final String text, final int at) {
        return !inside(operatorRanges(text, 0, text.length()), at);
    }

    /** True if {@code at} lies in one of the ranges. */
    static boolean inside(final List<int[]> ranges, final int at) {
        for (final int[] r : ranges) {
            if (at >= r[0] && at < r[1]) {
                return true;
            }
        }
        return false;
    }

    /** The ranges cut to start at {@code at} or later. */
    private static List<int[]> from(final List<int[]> ranges, final int at) {
        final List<int[]> out = new ArrayList<>();
        for (final int[] r : ranges) {
            if (r[1] > at) {
                out.add(new int[] {Math.max(r[0], at), r[1]});
            }
        }
        return out;
    }

    /**
     * An operator's name without a legal form, as [start, end), or null: a unit (a line or a sentence) above the first
     * postal address of the operator's sections, among the {@link #NAME_UNITS} units before it (a tagline, a role and
     * a person may stand between them), after an optional "Betreiber:", "Anbieter:", "Herausgeber:", and confirmed by
     * the site's domain or by the page's single structured organisation. A unit that may be a person's name (a person
     * marker, two or more capitalised words, a unit right after a role such as "Geschäftsführerin" or "Inhaber:")
     * names nobody; without such a confirmation the operator stays unnamed.
     */
    static int[] plainOperatorName(final String text, final List<int[]> ranges, final String host, final List<Mention> structured) {
        for (final int[] r : ranges) {
            final Matcher am = findAddress(text, r[0], r[1]);
            if (am == null) {
                continue;
            }
            int at = am.start();
            for (int n = 0; n < NAME_UNITS; n++) {
                final int[] u = previousUnit(text, at, r[0]);
                if (u == null) {
                    break;
                }
                int start = u[0];
                final Matcher label = NAME_LABEL.matcher(text).region(u[0], u[1]);
                if (label.lookingAt()) {
                    start = label.end();
                }
                while (start < u[1] && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                final int[] above = previousUnit(text, u[0], r[0]);
                final boolean afterRole = above != null && PERSON_ROLE_UNIT.matcher(text.substring(above[0], above[1])).matches();
                if (start < u[1] && !afterRole && plausibleName(text, start, text.substring(start, u[1]), host, structured)) {
                    return new int[] {start, u[1]};
                }
                at = u[0];
            }
            return null;
        }
        return null;
    }

    private static boolean plausibleName(final String text, final int at, final String name, final String host,
            final List<Mention> structured) {
        if (name.length() < 2 || name.length() > 80 || name.indexOf(':') >= 0 || name.indexOf('§') >= 0 || name.indexOf('@') >= 0
                || !(Character.isUpperCase(name.codePointAt(0)) || Character.isDigit(name.charAt(0)))
                || IMPRINT_MARKER.matcher(name).find() || OPERATOR_PHRASE.matcher(name).find() || OPERATOR_LABEL.matcher(name).find()
                || LEGAL_FORM.matcher(name).find()
                || BusinessFacts.personLike(name)) {
            return false;
        }
        // a person named by a role or a salutation right before the line ("Inhaber:", "Herr")
        if (Normalizers.excerpt(text, Math.max(0, at - 40), at + name.length()).contains("[…]")) {
            return false;
        }
        final String[] words = name.trim().split("\\s+");
        if (words.length > 4) {
            return false;
        }
        boolean personShaped = words.length >= 2;
        for (final String w : words) {
            final String bare = w.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}&+-]", "");
            if (NOT_IN_NAME.contains(bare) || STOP_BEFORE_NAME.contains(bare)) {
                return false;
            }
            if (!PERSON_WORD.matcher(w).matches()) {
                personShaped = false;
            }
        }
        final String compact = compact(name);
        if (compact.length() < 3) {
            return false;
        }
        for (final Mention s : structured) {
            if (Vocabulary.ORGANIZATION.equals(s.type) && structured.size() == 1 && compact.equals(compact(s.name))) {
                return true; // the page's structured data declares this organisation
            }
        }
        if (personShaped) {
            return false; // "Erika Musterfrau" on erika-musterfrau.de is still a person
        }
        final String domain = Normalizers.registrableDomain(host);
        if (domain == null || domain.indexOf('.') < 0) {
            return false;
        }
        return compact.equals(compact(domain.substring(0, domain.indexOf('.'))));
    }

    /** Letters and digits of a name or a domain label, lower case, umlauts as in domains ("Müller-Bau" -> "muellerbau"). */
    static String compact(final String s) {
        if (s == null) {
            return "";
        }
        final String t = s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return t.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Address, phone and e-mail found in the ranges are attached to {@code m}; the first of each. */
    private void contact(final String text, final List<int[]> ranges, final String cc, final Extraction out, final Mention m) {
        for (final int[] r : ranges) {
            final Matcher am = findAddress(text, r[0], r[1]);
            if (am != null) {
                final Address a = new Address(am.group(1), am.group(2), am.group(3), am.group(4).trim(), null);
                if (m.address == null && a.complete()) {
                    m.address = a;
                }
                claim(out, m, Vocabulary.ADDRESS, a.display(), text, am.start(), am.end());
                claim(out, m, Vocabulary.POSTAL_CODE, a.postalCode, text, am.start(3), am.end(3));
                claim(out, m, Vocabulary.LOCALITY, a.locality, text, am.start(4), am.end(4));
                break;
            }
        }
        phone: for (final int[] r : ranges) {
            final Matcher pm = PHONE.matcher(text).region(r[0], r[1]);
            while (pm.find()) {
                final String phone = Normalizers.phone(pm.group(1), cc);
                if (phone != null) {
                    claim(out, m, Vocabulary.PHONE, phone, text, pm.start(), pm.end());
                    break phone;
                }
            }
        }
        mail: for (final int[] r : ranges) {
            final Matcher em = EMAIL.matcher(text).region(r[0], r[1]);
            while (em.find()) {
                final String mail = Normalizers.email(em.group());
                if (mail != null && Normalizers.roleEmail(mail)) { // no addresses of persons (O7)
                    claim(out, m, Vocabulary.EMAIL, mail, text, em.start(), em.end());
                    break mail;
                }
            }
        }
    }

    private static final Set<String> NOT_IN_NAME = new TreeSet<>(java.util.Arrays.asList(
            "tmg", "ddg", "rstv", "mstv", "ttdsg", "uwg", "bgb", "hgb", "impressum", "imprint", "kontakt", "contact"));
    private static final Set<String> ABBREVIATIONS = new TreeSet<>(java.util.Arrays.asList("st.", "dr.", "prof.", "co.", "gebr."));

    /**
     * Start of the organisation name that ends right before {@code formStart}:
     * up to eight words back on the same line (capitalised words, digits and
     * connectors such as "und" or "für"), never before {@code floor}. Stops at
     * a line break, colon, comma, sentence end, law reference ("§ 5 TMG") and
     * words that introduce a name ("vertreten durch", "Betreiber"). Returns -1
     * if no capitalised word precedes the legal form.
     */
    static int nameStart(final String text, final int formStart, final int floor) {
        int i = formStart;
        int start = -1;
        for (int words = 0; words < 8; words++) {
            int j = i;
            while (j > floor && (text.charAt(j - 1) == ' ' || text.charAt(j - 1) == '\t')) {
                j--;
            }
            if (j <= floor || isBreak(text.charAt(j - 1)) || text.charAt(j - 1) == ',') {
                break;
            }
            int k = j;
            while (k > floor && !Character.isWhitespace(text.charAt(k - 1)) && !isBreak(text.charAt(k - 1))) {
                k--;
            }
            // Markdown of bold text before the name ("**Muster Pflege GmbH. **"): the name starts after it
            int lead = k;
            while (lead < j && (text.charAt(lead) == '*' || text.charAt(lead) == '_')) {
                lead++;
            }
            if (lead == j) {
                break;
            }
            final String word = text.substring(lead, j);
            final String lw = word.toLowerCase(Locale.ROOT);
            if (word.endsWith(".") && !ABBREVIATIONS.contains(lw)) {
                break; // end of the previous sentence
            }
            final String bare = lw.replaceAll("[^\\p{L}\\p{N}&+-]", "");
            if (word.contains("§") || bare.matches("[0-9]+") || NOT_IN_NAME.contains(bare) || STOP_BEFORE_NAME.contains(bare)) {
                break;
            }
            final char first = word.charAt(0);
            if (Character.isUpperCase(first) || Character.isDigit(first) || first == '"' || first == '„') {
                start = lead;
                if (lead > k) {
                    break;
                }
            } else if (!CONNECTORS.contains(bare) && !CONNECTORS.contains(lw)) {
                break;
            }
            i = k;
        }
        return start;
    }

    private static boolean isBreak(final char c) {
        return c == '\n' || c == '\r' || c == ':' || c == '|' || c == '•' || c == '·' || c == ';' || c == '(' || c == ')';
    }

    private void claim(final Extraction out, final Mention m, final String predicate, final String value, final String text,
            final int start, final int end) {
        if (value == null || start < 0) {
            return;
        }
        final int a = Math.max(0, start - 30);
        final int b = Math.min(text.length(), Math.max(end, start) + 30);
        out.add(new Claim(m.ref, predicate, null, value, 2, Claim.KIND_RULE, false, "text:" + start + "+" + (end - start),
                Normalizers.clip(Normalizers.excerpt(text, a, b), this.maxExcerpt)));
    }

    /** Register entry from a string such as "HRB 12345 B, Amtsgericht Charlottenburg", or null. */
    public static Normalizers.Register register(final String s) {
        if (s == null) {
            return null;
        }
        final Matcher a = REGISTER_A.matcher(s);
        if (a.find()) {
            return Normalizers.register(a.group(1), a.group(2), a.group(3));
        }
        final Matcher b = REGISTER_B.matcher(s);
        if (b.find()) {
            return Normalizers.register(b.group(3), b.group(1), b.group(2));
        }
        return null;
    }
}
