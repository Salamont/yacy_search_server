/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Checks the citations of an answer against the sources the model was given.
 * <ul>
 * <li>Numbers: only [n] with a source number of this answer is valid; other numbers and URLs that are no source URL
 * are reported, so the chat page can show them as unverified instead of as sources.</li>
 * <li>Support: for every valid [n], the statement it stands behind is compared with the text the model was given
 * under that number (the excerpt of the page, the graph facts, the passages of tool results). The comparison is
 * lexical: the content words of the statement (as the retrieval prepares them, with its word stems) must occur in
 * that text. {@code supported}: at least half of them; {@code weak}: fewer; {@code unsupported}: none, the source
 * does not speak about the statement at all; {@code unchecked}: the text of the source is not known or the statement
 * has no content word. It finds a number put behind a statement its source does not make; it cannot judge whether a
 * source that uses the same words means the same.</li>
 * </ul>
 */
public final class RagCitations {

    private static final Pattern GROUP = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\](?!\\()");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");
    /** sentence ends inside a line; a line break always ends a statement (list items, table rows) */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"„(*-])");
    private static final Pattern MARKUP = Pattern.compile("[*_#>|`\"„“”:()]+");
    static final int MAX_CLAIM_CHARS = 200;
    /** content words a part of a sentence needs to be a statement of its own */
    private static final int MIN_CLAIM_TERMS = 2;

    public static final String SUPPORTED = "supported";
    public static final String WEAK = "weak";
    public static final String UNSUPPORTED = "unsupported";
    public static final String UNCHECKED = "unchecked";

    private RagCitations() {
    }

    public static JSONObject check(final String answer, final List<RagContext.Source> sources) throws JSONException {
        final Map<Integer, RagContext.Source> known = new LinkedHashMap<>();
        final Set<String> urls = new LinkedHashSet<>();
        for (final RagContext.Source source : sources) {
            known.put(source.id, source);
            urls.add(normalize(source.url));
        }
        final Set<Integer> valid = new LinkedHashSet<>();
        final Set<Integer> invalid = new LinkedHashSet<>();
        final String text = answer == null ? "" : answer;
        final Matcher group = GROUP.matcher(text);
        while (group.find()) {
            for (final String number : group.group(1).split("\\s*[,;]\\s*")) {
                final int n = Integer.parseInt(number.trim());
                if (known.containsKey(n)) valid.add(n); else invalid.add(n);
            }
        }
        final Set<String> unknownUrls = new LinkedHashSet<>();
        final Matcher url = URL.matcher(text);
        while (url.find()) {
            final String found = url.group().replaceAll("[.,;:!?]+$", "");
            if (!urls.contains(normalize(found))) unknownUrls.add(found);
        }
        final JSONObject result = new JSONObject(true);
        result.put("valid", new JSONArray(valid));
        result.put("invalid", new JSONArray(invalid));
        result.put("unknownUrls", new JSONArray(unknownUrls));
        result.put("sources", known.size());
        // support: the worst verdict of each cited source, with the statement it was given for
        final Map<Integer, Support> worst = new LinkedHashMap<>();
        for (final Support s : support(text, known)) {
            final Support seen = worst.get(s.id);
            if (seen == null || rank(s.verdict) > rank(seen.verdict)) worst.put(s.id, s);
        }
        final JSONArray support = new JSONArray();
        final JSONArray unsupported = new JSONArray();
        final JSONArray weak = new JSONArray();
        for (final Support s : worst.values()) {
            final JSONObject o = new JSONObject(true);
            o.put("id", s.id);
            o.put("verdict", s.verdict);
            o.put("matched", s.matched);
            o.put("terms", s.terms);
            if (!SUPPORTED.equals(s.verdict)) o.put("statement", s.claim);
            support.put(o);
            if (UNSUPPORTED.equals(s.verdict)) unsupported.put(s.id);
            if (WEAK.equals(s.verdict)) weak.put(s.id);
        }
        result.put("support", support);
        result.put("unsupported", unsupported);
        result.put("weak", weak);
        return result;
    }

    /** One valid citation with the statement in front of it. */
    static final class Support {
        final int id;
        final String claim;
        final String verdict;
        final int matched;
        final int terms;

        Support(final int id, final String claim, final String verdict, final int matched, final int terms) {
            this.id = id;
            this.claim = claim;
            this.verdict = verdict;
            this.matched = matched;
            this.terms = terms;
        }
    }

    private static int rank(final String verdict) {
        return UNSUPPORTED.equals(verdict) ? 3 : WEAK.equals(verdict) ? 2 : UNCHECKED.equals(verdict) ? 1 : 0;
    }

    /**
     * Every valid citation of the answer with its statement: the text between the citation before it and itself when
     * that is a statement of its own (two content words), else the whole sentence or line; a citation after the full
     * stop belongs to the sentence before.
     */
    static List<Support> support(final String answer, final Map<Integer, RagContext.Source> known) {
        final List<Support> out = new ArrayList<>();
        String previous = "";
        for (final String line : answer.split("\\R+")) {
            for (final String sentence : SENTENCE.split(line)) {
                final Matcher group = GROUP.matcher(sentence);
                final String whole = plain(GROUP.matcher(sentence).replaceAll(" "));
                final String unit = RagQuery.prepare(whole).terms.size() >= MIN_CLAIM_TERMS ? whole : plain(previous + " " + whole);
                int from = 0;
                String last = null;
                while (group.find()) {
                    final String part = plain(sentence.substring(from, group.start()));
                    final String claim = RagQuery.prepare(part).terms.size() >= MIN_CLAIM_TERMS ? part : last != null ? last : unit;
                    last = claim;
                    from = group.end();
                    for (final String number : group.group(1).split("\\s*[,;]\\s*")) {
                        final RagContext.Source source = known.get(Integer.parseInt(number.trim()));
                        if (source != null) out.add(judge(source, claim));
                    }
                }
                if (!whole.isEmpty()) previous = whole;
            }
        }
        return out;
    }

    private static String plain(final String text) {
        return MARKUP.matcher(URL.matcher(text).replaceAll(" ")).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** Whether the text given under the source's number holds the content words of the statement. */
    static Support judge(final RagContext.Source source, final String claim) {
        final String shown = claim.length() > MAX_CLAIM_CHARS ? RagContext.cut(claim, MAX_CLAIM_CHARS) : claim;
        final String given = source.text();
        final RagQuery query = RagQuery.prepare(claim);
        final boolean onlyWeak = query.strongTerms() == 0;
        final List<RagQuery.Term> terms = new ArrayList<>();
        for (final RagQuery.Term term : query.terms) if (onlyWeak || !term.weak) terms.add(term);
        if (given == null || given.trim().isEmpty() || terms.isEmpty()) return new Support(source.id, shown, UNCHECKED, 0, terms.size());
        final String folded = RagQuery.fold(source.title + "\n" + given);
        int matched = 0;
        for (final RagQuery.Term term : terms) if (RagCandidate.matches(term, folded)) matched++;
        final String verdict = matched == 0 ? UNSUPPORTED : matched * 2 >= terms.size() ? SUPPORTED : WEAK;
        return new Support(source.id, shown, verdict, matched, terms.size());
    }

    static String normalize(final String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
