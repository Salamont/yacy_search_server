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

    private static String verdict(final JSONObject check, final int id) throws Exception {
        final JSONArray support = check.getJSONArray("support");
        for (int i = 0; i < support.length(); i++) {
            if (support.getJSONObject(i).getInt("id") == id) return support.getJSONObject(i).getString("verdict");
        }
        return null;
    }

    @Test
    public void aCitedSourceMustSpeakAboutTheStatementItStandsBehind() throws Exception {
        // each statement with the source that makes it
        JSONObject check = RagCitations.check("Das Pflegeheim Haus Sonnenschein liegt in Köln [1]. "
                + "Das Seniorenzentrum am Rhein ist ein Pflegeheim in Köln-Rodenkirchen [2].", sources());
        assertEquals(RagCitations.SUPPORTED, verdict(check, 1));
        assertEquals(RagCitations.SUPPORTED, verdict(check, 2));
        assertEquals("[]", check.getJSONArray("unsupported").toString());
        assertEquals("[]", check.getJSONArray("weak").toString());

        // a valid number behind a statement its source does not make: the number exists, the support does not
        check = RagCitations.check("Der Tagessatz beträgt 148,50 Euro bei Vollverpflegung [1].", sources());
        assertEquals("[1]", check.getJSONArray("valid").toString());
        assertEquals(RagCitations.UNSUPPORTED, verdict(check, 1));
        assertEquals("[1]", check.getJSONArray("unsupported").toString());
        assertEquals("the statement is reported with the verdict", "Der Tagessatz beträgt 148,50 Euro bei Vollverpflegung",
                check.getJSONArray("support").getJSONObject(0).getString("statement"));

        // one supported and one unsupported statement behind the same number: the worst verdict counts
        check = RagCitations.check("Haus Sonnenschein ist ein Pflegeheim in Köln [1].\n- Es hat einen Streichelzoo mit Alpakas [1].", sources());
        assertEquals(RagCitations.UNSUPPORTED, verdict(check, 1));

        // two statements in one sentence: each number is compared with its own part
        check = RagCitations.check("Haus Sonnenschein ist ein Pflegeheim in Köln [1], das Seniorenzentrum am Rhein liegt in Rodenkirchen [2].",
                sources());
        assertEquals(RagCitations.SUPPORTED, verdict(check, 1));
        assertEquals(RagCitations.SUPPORTED, verdict(check, 2));

        // a number after the full stop belongs to the sentence before it
        check = RagCitations.check("Haus Sonnenschein ist ein Pflegeheim in Köln. [1] Mehr ist nicht bekannt.", sources());
        assertEquals(RagCitations.SUPPORTED, verdict(check, 1));

        // only few of the statement's words are in the source: weak, reported apart
        check = RagCitations.check("Das Pflegeheim bietet Kurzzeitpflege, Verhinderungspflege, Tagespflege und Nachtpflege [2].", sources());
        assertEquals(RagCitations.WEAK, verdict(check, 2));
        assertEquals("[2]", check.getJSONArray("weak").toString());
    }

    @Test
    public void aSourceWithoutKnownTextIsNotJudged() throws Exception {
        final List<RagContext.Source> listed = new ArrayList<>();
        listed.add(new RagContext.Source(1, "Haus Sonnenschein", "https://pflege-sonnenschein.example/", "pflege-sonnenschein.example", null));
        final JSONObject check = RagCitations.check("Der Tagessatz beträgt 148,50 Euro [1].", listed);
        assertEquals(RagCitations.UNCHECKED, verdict(check, 1));
        assertEquals("[]", check.getJSONArray("unsupported").toString());
        // the sources of an earlier answer are read back with their text, so a later answer is checked like the first
        final List<RagContext.Source> kept = RagContext.parse("[1] Pflegeheim Haus Sonnenschein Köln\nURL: https://pflege-sonnenschein.example/\n"
                + "Collection: edelsenior-web\nText: Pflegeheim in Köln.\n\n[2] Seniorenzentrum am Rhein\nURL: https://seniorenzentrum-rhein.example/pflege\n"
                + "Text: Pflegeheim in Köln-Rodenkirchen.");
        assertEquals("Pflegeheim in Köln.", kept.get(0).text());
        assertEquals("Pflegeheim in Köln-Rodenkirchen.", kept.get(1).text());
        assertEquals(RagCitations.UNSUPPORTED, verdict(RagCitations.check("Der Tagessatz beträgt 148,50 Euro [2].", kept), 2));
    }
}
