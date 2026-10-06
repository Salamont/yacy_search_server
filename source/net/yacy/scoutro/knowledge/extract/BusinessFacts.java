/*
 *  BusinessFacts
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.TermMatcher;

/**
 * Shared rules of vocabulary 2 for all tiers (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 23): service categories, places, the industry a page's facts imply, the
 * employment types of jobs and the company profiles of social networks.
 * <p>
 * The industry of an organisation is only ever read from something the page
 * states: its schema.org type ({@code Plumber} -> NACE 43.22), or a service it
 * offers whose category implies an industry at a safe NACE level (a category
 * that does not, implies none). The evidence names the basis.
 */
public final class BusinessFacts {

    public static final String SERVICE_REF = "rule:service:";
    public static final String PLACE_REF = "place:";

    private BusinessFacts() {}

    // ------------------------------------------------------------ categories

    /** Category hits of the document's vocabularies in {@code text[from, to)}. */
    public static List<TermMatcher.Hit<Categories.Entry>> categories(final ExtractContext ctx, final String text, final int from,
            final int to) {
        final List<TermMatcher.Hit<Categories.Entry>> out = new ArrayList<>();
        if (ctx == null || text == null) {
            return out;
        }
        for (final Categories.Vocab v : ctx.vocabularies) {
            out.addAll(v.categories(text, from, to));
        }
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    /**
     * The categories a service's name or type states (several are possible):
     * one {@code category} claim each, quoting the name.
     */
    public static void categorize(final Extraction out, final ExtractContext ctx, final Mention service, final String text,
            final int tier, final int kind, final String locator, final String excerptPrefix, final int maxExcerpt) {
        if (service == null || text == null) {
            return;
        }
        final Set<String> done = new LinkedHashSet<>();
        for (final TermMatcher.Hit<Categories.Entry> h : categories(ctx, text, 0, text.length())) {
            if (done.add(h.entry.id)) {
                out.add(new Claim(service.ref, Vocabulary.CATEGORY, null, h.entry.id, tier, kind, false, locator,
                        Normalizers.clip(excerptPrefix + text, maxExcerpt)));
            }
        }
    }

    /**
     * Industries implied by the services the page's organisations and
     * facilities offer: the safe NACE level of each category ({@code industry})
     * and its Scoutro group ({@code industry_category}). Run once per tier
     * after the tier's claims are in.
     */
    public static void industriesFromServices(final Extraction out, final ExtractContext ctx, final int tier, final int maxExcerpt) {
        if (ctx == null || ctx.categories == null) {
            return;
        }
        final Map<String, List<String>> categoriesOf = new LinkedHashMap<>();
        final List<String[]> offers = new ArrayList<>();
        for (final Claim c : out.claims()) {
            // tier 2 reads the services of tiers 1 and 2; the LLM tier its own
            if (tier == LlmExtractor.TIER ? c.tier != tier : c.tier > 2) {
                continue;
            }
            if (Vocabulary.CATEGORY.equals(c.predicate) && c.value != null) {
                categoriesOf.computeIfAbsent(c.subject, k -> new ArrayList<>()).add(c.value);
            } else if (Vocabulary.OFFERS.equals(c.predicate) && c.object != null) {
                offers.add(new String[] {c.subject, c.object});
            }
        }
        final int kind = tier == LlmExtractor.TIER ? Claim.KIND_LLM : Claim.KIND_RULE;
        for (final String[] o : offers) {
            final Mention provider = out.mention(o[0]);
            final Mention service = out.mention(o[1]);
            final List<String> cats = categoriesOf.get(o[1]);
            if (provider == null || service == null || cats == null) {
                continue;
            }
            for (final String id : cats) {
                final Categories.Entry e = ctx.categories.entry(id);
                if (e == null) {
                    continue;
                }
                final String basis = "offers " + Normalizers.clip(service.name, 80) + " (" + e.de + ")";
                if (e.nace != null && ctx.nace.valid(e.nace)) {
                    out.add(new Claim(provider.ref, Vocabulary.INDUSTRY, null, e.nace, tier, kind, false, "derived:category:" + id,
                            Normalizers.clip(basis + " -> NACE " + e.nace, maxExcerpt), 0.6));
                }
                if (e.group != null) {
                    out.add(new Claim(provider.ref, Vocabulary.INDUSTRY_CATEGORY, null, e.group, tier, kind, false,
                            "derived:category:" + id, Normalizers.clip(basis, maxExcerpt), 0.6));
                }
            }
        }
    }

    /** NACE level of a schema.org business type (lower case); null if the type implies none reliably. */
    public static String industryOfType(final String schemaType) {
        if (schemaType == null) {
            return null;
        }
        return TYPE_NACE.get(schemaType.toLowerCase(Locale.ROOT));
    }

    private static final Map<String, String> TYPE_NACE;
    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("nursinghome", "87.10");
        m.put("hospital", "86.10");
        m.put("dentist", "86.23");
        m.put("physician", "86.2");
        m.put("medicalclinic", "86");
        m.put("pharmacy", "47.73");
        m.put("childcare", "88.91");
        m.put("childcarecenter", "88.91");
        m.put("preschool", "85.10");
        m.put("electrician", "43.21");
        m.put("plumber", "43.22");
        m.put("hvacbusiness", "43.22");
        m.put("roofingcontractor", "43.41");
        m.put("housepainter", "43.34");
        m.put("generalcontractor", "41.00");
        m.put("homeandconstructionbusiness", "F");
        m.put("legalservice", "69.10");
        m.put("attorney", "69.10");
        m.put("notary", "69.10");
        m.put("accountingservice", "69.20");
        m.put("realestateagent", "68.31");
        m.put("insuranceagency", "66.22");
        m.put("exercisegym", "93.13");
        m.put("hairsalon", "96.21");
        m.put("beautysalon", "96.22");
        m.put("dayspa", "96.23");
        m.put("travelagency", "79.11");
        m.put("employmentagency", "78.10");
        m.put("veterinarycare", "75.00");
        m.put("restaurant", "56.11");
        m.put("hotel", "55.10");
        m.put("autorepair", "95.31");
        TYPE_NACE = Collections.unmodifiableMap(m);
    }

    // ---------------------------------------------------------------- places

    private static final Map<String, String> STATES_DE = states("Baden-Württemberg", "Bayern", "Berlin", "Brandenburg", "Bremen",
            "Hamburg", "Hessen", "Mecklenburg-Vorpommern", "Niedersachsen", "Nordrhein-Westfalen", "Rheinland-Pfalz", "Saarland",
            "Sachsen", "Sachsen-Anhalt", "Schleswig-Holstein", "Thüringen");
    private static final Map<String, String> STATES_AT = states("Burgenland", "Kärnten", "Niederösterreich", "Oberösterreich",
            "Salzburg", "Steiermark", "Tirol", "Vorarlberg", "Wien");

    private static Map<String, String> states(final String... names) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (final String n : names) {
            m.put(Normalizers.key(n), n);
        }
        m.remove(null);
        return Collections.unmodifiableMap(m);
    }

    /** The German or Austrian state a name stands for ("NRW" and "Nordrhein-Westfalen" alike), as {country, name}; or null. */
    public static String[] state(final String name) {
        final String k = Normalizers.key(name);
        if (k == null) {
            return null;
        }
        if ("nrw".equals(k)) {
            return new String[] {"de", "Nordrhein-Westfalen"};
        }
        final String de = STATES_DE.get(k);
        if (de != null) {
            return new String[] {"de", de};
        }
        final String at = STATES_AT.get(k);
        return at == null ? null : new String[] {"at", at};
    }

    /** All state names (for the rules). */
    static List<String> stateNames() {
        final List<String> out = new ArrayList<>(STATES_DE.values());
        out.addAll(STATES_AT.values());
        out.add("NRW");
        return out;
    }

    /** The country of a page from its host's top-level domain (de, at, ch); null if it does not say. */
    public static String country(final String host) {
        if (host == null) {
            return null;
        }
        final String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".de")) {
            return "de";
        }
        if (h.endsWith(".at")) {
            return "at";
        }
        if (h.endsWith(".ch")) {
            return "ch";
        }
        return null;
    }

    /**
     * A place mention: a country, a state or a locality, by country and name;
     * its {@code place_name} key is global, so a service area and an address
     * in the same place meet in one entity.
     */
    public static Mention place(final Extraction out, final String country, final String level, final String name, final int tier,
            final int kind, final String locator, final String excerpt) {
        final String k = Normalizers.key(name);
        if (country == null || k == null || name.length() > 80) {
            return null;
        }
        final String key = country + "|" + level + "|" + k;
        final Mention m = out.add(new Mention(PLACE_REF + key, Vocabulary.PLACE, tier));
        m.name = name;
        m.placeKey = key;
        out.add(new Claim(m.ref, Vocabulary.NAME, null, name, tier, kind, false, locator, excerpt));
        return m;
    }

    /** The country names the graph knows as places. */
    public static String countryName(final String country) {
        switch (country) {
            case "de":
                return "Deutschland";
            case "at":
                return "Österreich";
            case "ch":
                return "Schweiz";
            default:
                return null;
        }
    }

    /**
     * {@code in_place} for every mention with a locality claim of this tier:
     * the organisation's or facility's own place, so a service area can meet it.
     */
    public static void ownPlaces(final Extraction out, final String host, final int tier) {
        final String country = country(host);
        if (country == null) {
            return;
        }
        final List<Claim> localities = new ArrayList<>();
        for (final Claim c : out.claims()) {
            if (c.tier == tier && Vocabulary.LOCALITY.equals(c.predicate) && c.value != null) {
                localities.add(c);
            }
        }
        for (final Claim c : localities) {
            final Mention subject = out.mention(c.subject);
            if (subject == null || Vocabulary.PLACE.equals(subject.type)) {
                continue;
            }
            final Mention p = place(out, country, "locality", c.value, tier, c.kind, c.locator, c.excerpt);
            if (p != null) {
                out.add(new Claim(subject.ref, Vocabulary.IN_PLACE, p.ref, null, tier, c.kind, false, c.locator, c.excerpt, c.confidence));
            }
        }
    }

    // ----------------------------------------------------------------- jobs

    /** The employment type of a schema.org value or German word ({@code FULL_TIME}, "Teilzeit"); null if none. */
    public static String employmentType(final String v) {
        if (v == null) {
            return null;
        }
        final String t = v.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (t) {
            case "FULL_TIME":
            case "FULLTIME":
            case "VOLLZEIT":
                return "full_time";
            case "PART_TIME":
            case "PARTTIME":
            case "TEILZEIT":
                return "part_time";
            case "INTERN":
            case "INTERNSHIP":
            case "PRAKTIKUM":
                return "internship";
            case "CONTRACTOR":
            case "FREELANCE":
            case "FREELANCER":
            case "FREIBERUFLICH":
                return "freelance";
            case "MINIJOB":
            case "MINI_JOB":
                return "mini_job";
            case "APPRENTICESHIP":
            case "AUSBILDUNG":
                return "apprenticeship";
            case "WERKSTUDENT":
            case "WORKING_STUDENT":
                return "working_student";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------- profiles

    private static final Pattern PERSONAL_PROFILE = Pattern.compile("(?i)^(?:www\\.)?(?:linkedin\\.com/in/|xing\\.com/profile/"
            + "|de\\.linkedin\\.com/in/)");
    private static final Pattern COMPANY_PROFILE = Pattern.compile("(?i)^(?:www\\.|de\\.|m\\.)?(?:linkedin\\.com/company/[^/?#]+"
            + "|xing\\.com/(?:pages|companies)/[^/?#]+|facebook\\.com/(?!sharer|share|dialog|plugins|login|tr\\b)[A-Za-z0-9.\\-]{2,}"
            + "|instagram\\.com/(?!p/|explore|accounts)[A-Za-z0-9._]{2,}|youtube\\.com/(?:@|channel/|c/|user/)[^/?#]+"
            + "|(?:twitter|x)\\.com/(?!share|intent|home)[A-Za-z0-9_]{2,}|tiktok\\.com/@[^/?#]+|kununu\\.com/[a-z]{2}/[^/?#]+)");

    /**
     * The company profile a link points to (LinkedIn company page, XING page,
     * Facebook, Instagram, YouTube, X, TikTok, kununu), as an https URL; null
     * for a personal profile, a share button or anything else.
     */
    public static String socialProfile(final String urlOrStub) {
        if (urlOrStub == null) {
            return null;
        }
        String s = urlOrStub.trim();
        s = s.replaceFirst("(?i)^https?://", "");
        final int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        final int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        if (PERSONAL_PROFILE.matcher(s).find()) {
            return null;
        }
        final java.util.regex.Matcher m = COMPANY_PROFILE.matcher(s);
        if (!m.find()) {
            return null;
        }
        String profile = m.group().replaceFirst("(?i)^(?:www\\.|de\\.|m\\.)", "");
        try {
            final URI u = new URI("https://" + profile);
            profile = u.toString();
        } catch (final Exception e) {
            return null;
        }
        return profile.length() <= 300 ? profile : null;
    }

    /** A name that is a person or contact data rather than an organisation (O7). */
    private static final Pattern PERSONISH = Pattern.compile("(?iu)^(?:herr|frau|hr\\.|fr\\.|dr\\.|prof\\.|mr\\.?|mrs\\.?|ms\\.?)\\s"
            + "|@|https?://|(?:\\d[\\s/().-]*){6,}");

    public static boolean personLike(final String name) {
        return name == null || PERSONISH.matcher(name).find();
    }
}
