/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.15.0 (docs/SCOUTRO_KG_FACILITIES.md, rule/11): the page of one facility or site of a provider, its subject for the rules. A
 * care provider runs several homes on one domain and gives each its own page ({@code /einrichtungen/haus-lindenhof/}); the
 * operator's name and address stand in the footer of every page. Before, the rules gave the facts of such a page to the operator
 * (or to nobody): the rules named no facility, and the page's one structured organisation was the operator. A page is a facility's
 * page only on all of these, nothing is guessed:
 * <ul>
 * <li>its address is a detail page of a site's places: a segment of places ({@code einrichtungen}, {@code standorte},
 * {@code haeuser}, {@code niederlassungen}, …) followed by the place's own segment, or a last segment that writes the name
 * ({@code /haus-lindenhof/});</li>
 * <li>its title or first heading names a place ({@link PlaceEvidence#check}: a place word with a proper name, "Haus Lindenhof",
 * "Seniorenzentrum Am Markt"), never only a kind ("Tagespflege");</li>
 * <li>the page gives that name's full address right after it, outside a menu, and only one ({@link PlaceEvidence#fullPlace}).</li>
 * </ul>
 * The facility's block is the name's paragraph with its address: its phone and role mailbox are read there only. The page's
 * own part ends before the operator's footer (a unit with an organisation's legal form after the block, a copyright, the links
 * to the imprint): the business rules read that part for the facility, nothing after it.
 */
public final class FacilityPage {

    /** The reference of the page's facility among the rules' mentions. */
    public static final String REF = "rule:facility";

    /** A segment of a site's places in an address; the next segment is one place's page. */
    private static final Pattern PLACES = Pattern.compile("(?iu)^(?:unsere-|our-)?(?:einrichtungen|einrichtung|standorte?|h(?:ae|ä)user"
            + "|pflegeheime|pflegeeinrichtungen|seniorenzentren|seniorenheime|seniorenresidenzen|altenheime|wohnangebote|wohnen|tagespflegen"
            + "|filialen|niederlassungen|zweigstellen|praxen|kliniken|kitas|locations?|branches|offices|sites)$");
    /** A last segment that is an index or a list, never one place's page. */
    private static final Pattern NOT_A_PLACE_SEGMENT = Pattern.compile("(?iu)^(?:index(?:\\.\\w+)?|uebersicht|übersicht|alle|liste|karte|map"
            + "|page|seite|\\d+)$");
    /** Separators of a title's parts ("Haus Lindenhof – Muster Altenhilfe", "Seniorenzentrum Am Markt | Muster"). */
    private static final Pattern TITLE_PARTS = Pattern.compile("\\s+[|–—-]\\s+|\\s*[|:]\\s+");
    /** A heading of the page text as YaCy writes it ("# Haus Lindenhof."). */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,2}\\s+(.+?)\\s*$");
    /** Where the operator's part of a page starts after the facility's block: a copyright, the links of the imprint. */
    private static final Pattern FOOTER = Pattern.compile("(?imu)©|\\(c\\)\\s*\\d{4}|^\\s*(?:-\\s*)?(?:impressum|datenschutz\\p{L}*)\\s*(?:\\||\\.|$)");
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:gGmbH|GmbH|gAG|AG|e\\.\\s?V\\.|eG|KGaA|KG|SE|Stiftung"
            + "|GbR|OHG|UG)(?![\\p{L}\\p{N}])");
    /** The characters after the address that its block may hold at most (phone and mailbox follow it). */
    private static final int BLOCK = 300;
    /** A paragraph that continues an address's block: it opens with a phone's or a mailbox's label. */
    private static final Pattern CONTACT_LABEL = Pattern.compile("(?iu)^[\\s*_#>-]*(?:tel(?:efon)?\\b|fon\\b|phone\\b|fax\\b|e-?mail\\b|mail\\b)");

    /** The facility of a page: its name, its full address, where the page writes them, its block and the page's own part. */
    public static final class Page {
        public final String name;
        public final PlaceEvidence.Place place;
        /** The start of the facility's block: the name's occurrence that heads the address, or the address's paragraph. */
        public final int blockStart;
        /** The end of the block with the address: the facility's phone and mailbox stand in it. */
        public final int blockEnd;
        /** The end of the page's own part, before the operator's footer. */
        public final int ownEnd;

        Page(final String name, final PlaceEvidence.Place place, final int blockStart, final int blockEnd, final int ownEnd) {
            this.name = name;
            this.place = place;
            this.blockStart = blockStart;
            this.blockEnd = blockEnd;
            this.ownEnd = ownEnd;
        }

        public Address address() {
            return this.place.address;
        }

        /** True if {@code at} lies in the facility's block. */
        public boolean inBlock(final int at) {
            return at >= this.blockStart && at < this.blockEnd;
        }
    }

    private FacilityPage() {
    }

    /** The page's facility, or null if the page is none's (see the class). */
    public static Page detect(final String text, final String url, final List<String> titles, final boolean operatesPlaces) {
        if (text == null || text.isEmpty() || url == null) {
            return null;
        }
        final List<String> segments = segments(url);
        if (segments.isEmpty()) {
            return null;
        }
        final String last = segments.get(segments.size() - 1);
        boolean detail = false;
        for (int i = 0; i < segments.size() - 1; i++) {
            if (PLACES.matcher(segments.get(i)).matches()) {
                detail = true;
            }
        }
        detail &= !PLACES.matcher(last).matches() && !NOT_A_PLACE_SEGMENT.matcher(last).matches();
        String host = null;
        try {
            host = new URI(url).getHost();
        } catch (final Exception e) {
            host = null;
        }
        final String domain = net.yacy.scoutro.knowledge.resolve.Normalizers.registrableDomain(host);
        final String brand = domain == null || domain.indexOf('.') < 0 ? "" : RuleExtractor.compact(domain.substring(0, domain.indexOf('.')));
        for (final String name : names(text, titles)) {
            final String compact = RuleExtractor.compact(name);
            if (!detail && !compact.equals(RuleExtractor.compact(last))) {
                continue;
            }
            // the site's name in every title ("… – Muster Altenhilfe") and an organisation by its legal form are the operator's
            if (compact.equals(brand) || organisation(text, name) || PlaceEvidence.kindOnly(name)) {
                continue;
            }
            final PlaceEvidence.Result r = PlaceEvidence.check(text, name, null, url, Vocabulary.FACILITY, false, operatesPlaces);
            if (r.verdict != PlaceEvidence.Verdict.PLACE) {
                continue;
            }
            final PlaceEvidence.Place place = PlaceEvidence.fullPlace(text, name);
            if (place != null) {
                final int blockEnd = blockEnd(text, place.end);
                return new Page(name, place, place.nameStart, blockEnd, ownEnd(text, blockEnd));
            }
            // the page's own part (after its heading, before the operator's footer) gives exactly one full address: the facility's
            final int[] heading = heading(text, name);
            if (heading == null) {
                continue;
            }
            final int own = ownEnd(text, heading[2]);
            final PlaceEvidence.Place only = PlaceEvidence.onlyAddress(text, heading[2], own);
            if (only != null) {
                final int blockStart = paragraphStart(text, only.start, heading[2]);
                return new Page(name, new PlaceEvidence.Place(only.address, heading[0], heading[1], only.start, only.end), blockStart,
                        Math.min(own, blockEnd(text, only.end)), own);
            }
        }
        return null;
    }

    /** True if the page writes the name with a legal form right after it ("Muster Altenhilfe gGmbH"): an organisation. */
    private static boolean organisation(final String text, final String name) {
        final Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "\\s+(?:gGmbH|GmbH|gAG|AG|e\\.\\s?V\\.|eG|KGaA|KG|SE"
                + "|Stiftung|GbR|OHG|UG)(?![\\p{L}\\p{N}])").matcher(text);
        return m.find();
    }

    /** The page's heading that writes the name ("# Haus Am See."): its name's start and end and the heading's end; null if none. */
    private static int[] heading(final String text, final String name) {
        final Matcher h = HEADING.matcher(text);
        final String n = RuleExtractor.compact(name);
        while (h.find()) {
            if (RuleExtractor.compact(h.group(1)).equals(n)) {
                return new int[] {h.start(1), h.end(1), h.end()};
            }
        }
        return null;
    }

    /** The start of the paragraph that holds {@code at}, not before {@code floor}. */
    private static int paragraphStart(final String text, final int at, final int floor) {
        final int blank = text.lastIndexOf("\n\n", at);
        return Math.max(floor, blank < 0 ? 0 : blank + 2);
    }

    /** The path's segments, decoded, without empty ones. */
    static List<String> segments(final String url) {
        final List<String> out = new ArrayList<>();
        try {
            final String path = new URI(url).getRawPath();
            if (path == null) {
                return out;
            }
            for (final String s : path.split("/")) {
                if (!s.isEmpty()) {
                    out.add(URLDecoder.decode(s, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).replaceFirst("\\.(?:html?|php|aspx?)$", ""));
                }
            }
        } catch (final Exception e) {
            out.clear();
        }
        return out;
    }

    /** The names the page gives itself: the parts of its title, then its first headings, without Markdown and a final dot. */
    static List<String> names(final String text, final List<String> titles) {
        final Set<String> out = new LinkedHashSet<>();
        if (titles != null) {
            for (final String t : titles) {
                if (t != null) {
                    for (final String part : TITLE_PARTS.split(t)) {
                        add(out, part);
                    }
                }
            }
        }
        final Matcher h = HEADING.matcher(text);
        for (int i = 0; i < 3 && h.find(); i++) {
            add(out, h.group(1));
        }
        return new ArrayList<>(out);
    }

    private static void add(final Set<String> out, final String raw) {
        final String n = raw.replace("*", "").replace("_", " ").replaceAll("\\s+", " ").trim().replaceAll("[.:]+$", "").trim();
        if (n.length() >= 4 && n.length() <= 80) {
            out.add(n);
        }
    }

    /** The end of the paragraph after {@code from}: a blank line, else at most {@link #BLOCK} characters on. */
    private static int paragraphEnd(final String text, final int from) {
        final int blank = text.indexOf("\n\n", from);
        final int max = Math.min(text.length(), from + BLOCK);
        return blank >= 0 && blank < max ? blank : max;
    }

    /**
     * The end of an address's block after {@code from}: its paragraph, and the next one if it opens with a phone's or a mailbox's
     * label ("Telefon: …", "E-Mail: …"), as a contact block is often written in two.
     */
    static int blockEnd(final String text, final int from) {
        final int end = paragraphEnd(text, from);
        int next = end;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        if (next < text.length() && next - end <= 4 && CONTACT_LABEL.matcher(text.substring(next, Math.min(text.length(), next + 20))).find()) {
            return paragraphEnd(text, next);
        }
        return end;
    }

    /** The end of the page's own part: the first unit after {@code from} that names an organisation by its legal form, or a footer mark. */
    private static int ownEnd(final String text, final int from) {
        int end = text.length();
        final Matcher f = FOOTER.matcher(text).region(from, text.length());
        if (f.find()) {
            end = f.start();
        }
        final Matcher lf = LEGAL_FORM.matcher(text).region(from, end);
        if (lf.find()) {
            int u = lf.start();
            while (u > from && text.charAt(u - 1) != '\n') {
                u--;
            }
            end = u;
        }
        return end;
    }
}
