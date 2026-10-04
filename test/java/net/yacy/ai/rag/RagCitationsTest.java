/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Citations of an answer must refer to the sources the model was given. */
public class RagCitationsTest {

    private static List<RagContext.Source> sources() {
        final RagQuery query = RagQuery.prepare("Pflegeheim Köln");
        final List<RagRetriever.Scored> selected = new ArrayList<>();
        selected.add(new RagRetriever.Scored(new RagCandidate("https://pflege-sonnenschein.example/", "Pflegeheim Haus Sonnenschein Köln", "",
                "Pflegeheim in Köln.", "pflege-sonnenschein.example", List.of("edelsenior-web"), 0), 2, 2, 2));
        selected.add(new RagRetriever.Scored(new RagCandidate("https://seniorenzentrum-rhein.example/pflege", "Seniorenzentrum am Rhein", "",
                "Pflegeheim in Köln-Rodenkirchen.", "seniorenzentrum-rhein.example", List.of("edelsenior-web"), 1), 2, 2, 1));
        return RagContext.build(selected, query, 9000, 1500, 8, "edelsenior-web").sources;
    }

    @Test
    public void citationsMatchTheRetrievedSources() throws Exception {
        final JSONObject check = RagCitations.check("Haus Sonnenschein hat einen Demenzbereich [1]. Am Rhein sind Haustiere erlaubt [2]. "
                + "Beide liegen in Köln [1, 2][2].", sources());
        assertEquals("[1,2]", check.getJSONArray("valid").toString());
        assertEquals("[]", check.getJSONArray("invalid").toString());
        assertEquals("[]", check.getJSONArray("unknownUrls").toString());
        assertEquals(2, check.getInt("sources"));
    }

    @Test
    public void modelCannotSmuggleInASourceThatWasNotRetrieved() throws Exception {
        final JSONObject check = RagCitations.check("Es gibt auch das Haus Phantasia [3] und [7; 1], siehe https://erfunden.example/haus. "
                + "Mehr unter https://pflege-sonnenschein.example/. Ein Link [hier](https://ok.example/x) ist kein Beleg.", sources());
        assertEquals("[1]", check.getJSONArray("valid").toString());
        assertEquals("[3,7]", check.getJSONArray("invalid").toString());
        final JSONArray unknown = check.getJSONArray("unknownUrls");
        assertEquals(2, unknown.length());
        assertEquals("https://erfunden.example/haus", unknown.getString(0));
        assertEquals("https://ok.example/x", unknown.getString(1));
        // no sources at all: every citation is invalid
        assertEquals("[1]", RagCitations.check("Laut [1] ...", new ArrayList<>()).getJSONArray("invalid").toString());
    }
}
