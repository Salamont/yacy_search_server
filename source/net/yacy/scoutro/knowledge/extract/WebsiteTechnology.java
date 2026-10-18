package net.yacy.scoutro.knowledge.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Website technologies (Scoutro R5, docs/SCOUTRO_WEBSITE_TECHNOLOGY.md): the index field {@code website_technology_sxt}
 * holds what the signatures of {@code defaults/parser.tech.properties} found in a page's technical markup; this reads it
 * into {@link Vocabulary#WEBSITE_TECHNOLOGY} statements about the site's operator. A statement says only that the page
 * <b>integrates</b> the technology: never that the company uses it internally, offers it as a service or worked with it
 * for a customer (those are {@code system_signal} contexts and {@code offers}, each with its own evidence).
 * <p>
 * The value is a canonical JSON object: {@code technology} (catalog id), {@code name}, {@code variant} (only from its own
 * signature, e.g. Elementor Pro), {@code category}, {@code host}, and {@code version} with {@code version_source} only
 * when a generator meta tag or the product's own HTML comment states it; a version in an asset's query parameter is the
 * asset's, not the product's, and stays in the evidence excerpt. Ids the catalog does not know are skipped, so a new
 * signature in the index is inert until the catalog names it.
 */
public final class WebsiteTechnology {

    /** The extractor's name in {@code kg_extractor} (tier 1, page markup). */
    public static final String NAME = "technology";
    /** Part of every hash of a page with technologies: a new version reads exactly those pages again. */
    public static final String VERSION = "1";

    /** One technology of the catalog. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String variant;
        public final String category;
        /** Where a product version may be read: a generator meta tag or the product's own comment; null: never. */
        final Pattern version;

        Entry(final String id, final String name, final String variant, final String category, final String version) {
            this.id = id;
            this.name = name;
            this.variant = variant;
            this.category = category;
            this.version = version == null ? null : Pattern.compile(version);
        }
    }

    public static final String CMS = "cms";
    public static final String PAGE_BUILDER = "page_builder";
    public static final String SEO_PLUGIN = "seo_plugin";
    public static final String CACHE_PERFORMANCE = "cache_performance";
    public static final String TAG_MANAGEMENT = "tag_management";
    public static final String ANALYTICS = "analytics";
    public static final String MARKETING_AUTOMATION = "marketing_automation";

    private static final String NUMBER = "([0-9]+(?:\\.[0-9]+){1,3})";

    /** The catalog: the ids of {@code defaults/parser.tech.properties}. */
    public static final Map<String, Entry> CATALOG;
    static {
        final Map<String, Entry> c = new LinkedHashMap<>();
        put(c, new Entry("wordpress", "WordPress", null, CMS, "(?i)^WordPress\\s+" + NUMBER));
        put(c, new Entry("elementor", "Elementor", null, PAGE_BUILDER, "(?i)^Elementor\\s+" + NUMBER));
        put(c, new Entry("elementor-pro", "Elementor", "Pro", PAGE_BUILDER, null));
        put(c, new Entry("yoast-seo", "Yoast SEO", null, SEO_PLUGIN, "(?i)Yoast SEO(?: Premium)? plugin v" + NUMBER));
        put(c, new Entry("yoast-seo-premium", "Yoast SEO", "Premium", SEO_PLUGIN, "(?i)Yoast SEO Premium plugin v" + NUMBER));
        put(c, new Entry("wp-rocket", "WP Rocket", null, CACHE_PERFORMANCE, null));
        put(c, new Entry("google-tag-manager", "Google Tag Manager", null, TAG_MANAGEMENT, null));
        put(c, new Entry("google-analytics", "Google Analytics", null, ANALYTICS, null));
        put(c, new Entry("pardot", "Pardot (Salesforce Marketing Cloud Account Engagement)", null, MARKETING_AUTOMATION, null));
        CATALOG = Collections.unmodifiableMap(c);
    }

    private static void put(final Map<String, Entry> c, final Entry e) {
        c.put(e.id, e);
    }

    /** The elements a version may come from, with its source name. */
    private static final Map<String, String> VERSION_SOURCES = Map.of("metagenerator", "generator", "comment", "html_comment");

    /** The confidence of a marker by the element it was found in. */
    static double confidence(final String element) {
        switch (element) {
            case "metagenerator":
                return 0.9;
            case "scriptpath":
            case "csspath":
            case "iframepath":
                return 0.85;
            case "comment":
                return 0.8;
            case "scriptcode":
                return 0.75;
            default:
                return 0.6;
        }
    }

    /** One parsed index entry {@code id|rule version|element|matches|excerpt}; null if malformed. */
    public static final class Found {
        public final String id;
        public final String ruleVersion;
        public final String element;
        public final int matches;
        public final String excerpt;

        Found(final String id, final String ruleVersion, final String element, final int matches, final String excerpt) {
            this.id = id;
            this.ruleVersion = ruleVersion;
            this.element = element;
            this.matches = matches;
            this.excerpt = excerpt;
        }

        public static Found parse(final String s) {
            if (s == null) return null;
            final String[] p = s.split("\\|", 5);
            if (p.length < 5 || p[0].isEmpty() || p[2].isEmpty()) return null;
            int n;
            try {
                n = Integer.parseInt(p[3].trim());
            } catch (final NumberFormatException e) {
                n = 1;
            }
            return new Found(p[0].trim().toLowerCase(Locale.ROOT), p[1].trim(), p[2].trim(), Math.max(1, n), p[4].trim());
        }
    }

    private WebsiteTechnology() {}

    /** The canonical value of a found technology on this host. */
    static String value(final Entry e, final Found f, final String host) {
        final Map<String, Object> v = new LinkedHashMap<>();
        v.put("technology", e.id);
        v.put("name", e.name);
        v.put("variant", e.variant);
        v.put("category", e.category);
        v.put("host", host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT));
        final String source = VERSION_SOURCES.get(f.element);
        if (e.version != null && source != null) {
            final Matcher m = e.version.matcher(f.excerpt);
            if (m.find()) {
                v.put("version", m.group(1));
                v.put("version_source", source);
            }
        }
        return Values.canonical(v);
    }

    /**
     * Adds a {@link Vocabulary#WEBSITE_TECHNOLOGY} claim per catalog technology the page's index entries name, about the
     * site's operator ({@link BusinessRules#subject}: the imprint operator, the single structured subject, else the
     * operator of the domain), tier 1, kind metadata; the locator names the element and the signature's rule version,
     * the excerpt is what it matched. Returns the number of claims.
     */
    public static int extract(final List<String> entries, final String host, final Extraction out, final int maxExcerpt) {
        if (entries == null || entries.isEmpty()) return 0;
        final List<Found> found = new ArrayList<>();
        for (final String s : entries) {
            final Found f = Found.parse(s);
            if (f != null && CATALOG.containsKey(f.id)) found.add(f);
        }
        if (found.isEmpty()) return 0;
        Mention subject = BusinessRules.subject(out, out.mention(RuleExtractor.OPERATOR_REF));
        if (subject == null) {
            // several structured subjects on the page: the website still is the domain operator's
            subject = out.add(new Mention(BusinessRules.SITE_REF, Vocabulary.ORGANIZATION, 2));
            subject.domainOperator = true;
        }
        int n = 0;
        for (final Found f : found) {
            final Entry e = CATALOG.get(f.id);
            final String excerpt = f.excerpt.length() > maxExcerpt ? f.excerpt.substring(0, maxExcerpt) : f.excerpt;
            final String locator = "html:" + f.element + ":tech/" + (f.ruleVersion.isEmpty() ? "?" : f.ruleVersion);
            if (out.add(new Claim(subject.ref, Vocabulary.WEBSITE_TECHNOLOGY, null, value(e, f, host), 1, Claim.KIND_METADATA, false,
                    locator, excerpt, confidence(f.element)))) {
                n++;
            }
        }
        return n;
    }
}
