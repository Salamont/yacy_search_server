/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Checks the citations of an answer against the sources the model was given. Only [n] with a
 * source number of this answer is valid; other numbers and URLs that are no source URL are
 * reported, so the chat page can show them as unverified instead of as sources.
 */
public final class RagCitations {

    private static final Pattern GROUP = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\](?!\\()");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    private RagCitations() {
    }

    public static JSONObject check(final String answer, final List<RagContext.Source> sources) throws JSONException {
        final Set<Integer> known = new LinkedHashSet<>();
        final Set<String> urls = new LinkedHashSet<>();
        for (final RagContext.Source source : sources) {
            known.add(source.id);
            urls.add(normalize(source.url));
        }
        final Set<Integer> valid = new LinkedHashSet<>();
        final Set<Integer> invalid = new LinkedHashSet<>();
        final String text = answer == null ? "" : answer;
        final Matcher group = GROUP.matcher(text);
        while (group.find()) {
            for (final String number : group.group(1).split("\\s*[,;]\\s*")) {
                final int n = Integer.parseInt(number.trim());
                if (known.contains(n)) valid.add(n); else invalid.add(n);
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
        return result;
    }

    static String normalize(final String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
