/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** The RAG quality corpus (test/scoutro-rag/corpus.json) and a search that behaves like the local YaCy search. */
final class RagCorpus {

    final Map<String, RagCandidate> byId = new LinkedHashMap<>();
    final Map<String, String> idByUrl = new LinkedHashMap<>();

    RagCorpus() throws Exception {
        final JSONArray documents = new JSONObject(new String(Files.readAllBytes(Paths.get("test/scoutro-rag/corpus.json")),
                StandardCharsets.UTF_8)).getJSONArray("documents");
        for (int i = 0; i < documents.length(); i++) {
            final JSONObject d = documents.getJSONObject(i);
            final String text = "heimverzeichnis".equals(d.optString("generate")) ? heimverzeichnis() : d.getString("text");
            final String url = d.getString("url");
            this.byId.put(d.getString("id"), new RagCandidate(url, d.getString("title"), d.optString("description", ""), text,
                    new URI(url).getHost(), List.of(d.getString("collection")), i));
            this.idByUrl.put(url, d.getString("id"));
        }
    }

    /** same generator as RagFixture.heimverzeichnis */
    static String heimverzeichnis() {
        final String[] cities = {"Bielefeld", "Dortmund", "Essen", "Münster", "Wuppertal", "Gelsenkirchen", "Duisburg", "Hagen", "Bochum", "Krefeld"};
        final String[] focus = {"Schwerpunkt Kurzzeitpflege", "Schwerpunkt Palliativpflege", "mit Garten", "nahe Innenstadt", "mit Hausarzt im Haus"};
        final StringBuilder text = new StringBuilder("Dieses Heimverzeichnis listet Pflegeheime in Nordrhein-Westfalen nach Städten mit Platzzahl und Schwerpunkt. ");
        int n = 0;
        while (text.length() < 42000) {
            final String city = cities[n % cities.length];
            text.append("Pflegeheim Haus ").append(n + 1).append(" in ").append(city).append(": ").append(40 + n % 90)
                .append(" Plätze, ").append(focus[n % focus.length]).append(", Träger gemeinnützig, Besuchszeiten täglich. ");
            n++;
        }
        text.append("Aachen: Pflegeheim Waldblick in Aachen, 70 Plätze, Demenzgarten, am Aachener Wald. ")
            .append("Aachen: Haus Lousberg in Aachen, 64 Plätze, Schwerpunkt Palliativpflege. ")
            .append("Ende des Verzeichnisses. Angaben ohne Gewähr.");
        return text.toString();
    }

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * Like the local YaCy search (verified on a fixture peer): the words are combined with OR on exact
     * lowercase tokens, without stemming; a wildcard word finds nothing; more matching words rank first.
     */
    RagRetriever.Searcher searcher(final boolean honorCollection, final List<String> queries) {
        return (query, collection, count, global) -> {
            queries.add(query);
            final List<String> words = new ArrayList<>();
            final List<String> phrases = new ArrayList<>();
            final Matcher parts = Pattern.compile("\"([^\"]+)\"|(\\S+)").matcher(query.toLowerCase(Locale.ROOT));
            while (parts.find()) {
                if (parts.group(1) != null) phrases.add(parts.group(1));
                else if (!parts.group(2).contains(":")) words.add(parts.group(2));
            }
            final List<RagCandidate> hits = new ArrayList<>();
            final Map<RagCandidate, Integer> score = new LinkedHashMap<>();
            for (final RagCandidate c : this.byId.values()) {
                if (honorCollection && collection != null && !c.collections.contains(collection)) continue;
                final String text = (c.title + " " + c.description + " " + c.text).toLowerCase(Locale.ROOT);
                final Set<String> tokens = new HashSet<>();
                final Matcher token = TOKEN.matcher(text);
                while (token.find()) tokens.add(token.group());
                int s = 0;
                for (final String w : words) if (!w.contains("*") && tokens.contains(w)) s++;
                for (final String phrase : phrases) if (text.contains(phrase)) s++;
                if (s > 0) {
                    hits.add(c);
                    score.put(c, s);
                }
            }
            hits.sort(Comparator.comparingInt((RagCandidate c) -> -score.get(c)).thenComparingInt(c -> c.rank));
            final List<RagCandidate> ranked = new ArrayList<>();
            for (int i = 0; i < Math.min(count, hits.size()); i++) {
                final RagCandidate c = hits.get(i);
                ranked.add(new RagCandidate(c.url, c.title, c.description, c.text, c.host, c.collections, i));
            }
            return ranked;
        };
    }

    List<String> ids(final List<RagRetriever.Scored> selected) {
        final List<String> ids = new ArrayList<>();
        for (final RagRetriever.Scored s : selected) ids.add(this.idByUrl.get(s.candidate.url));
        return ids;
    }

    String collectionOf(final String id) {
        return this.byId.get(id).collections.get(0);
    }
}
