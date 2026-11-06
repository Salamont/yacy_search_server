/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/**
 * The comparison of the facility fixtures before and after a change (docs/SCOUTRO_KG_FACILITIES.md): the corpus of
 * {@link FacilityGraphTest} through the real sync and the controlled model into a temporary graph, written as Markdown tables
 * to the file the system property {@code facilities.report} names (else to the standard output). No assertion: run it with
 * JUnit on two builds and compare the tables. Not part of a test target; it uses only the API of 0.14.1, so it runs on both.
 */
public class FacilityFixtureReport extends LlmFixture {

    @Override
    protected String[] settings() {
        return new FacilityGraphTest().settings();
    }

    @Override
    protected LlmServiceTest.Fake newModel() {
        final LlmServiceTest.Fake f = new LlmServiceTest.Fake();
        f.answer = FacilityGraphTest::answer;
        return f;
    }

    private static String q(final String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** The values of an entity's statements of a predicate, each with the kinds of its evidence, joined. */
    private String values(final String publicId, final String predicate) {
        final List<String> v = strings("SELECT s.obj_val || ' (' || group_concat(DISTINCT CASE ev.kind WHEN 1 THEN 'jsonld' WHEN 2 THEN 'metadata'"
                + " WHEN 3 THEN 'rule' WHEN 4 THEN 'llm' END) || ')' FROM kg_statement s"
                + " JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab p ON p.term_id = s.pred LEFT JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid"
                + " WHERE e.public_id = " + q(publicId) + " AND p.name = " + q(predicate) + " AND s.obj_val IS NOT NULL GROUP BY s.stmt_rowid ORDER BY s.obj_val");
        return v.isEmpty() ? "–" : String.join("<br>", v);
    }

    private List<String> entities(final String type) {
        return strings("SELECT e.public_id || '|' || coalesce((SELECT s.obj_val FROM kg_statement s JOIN kg_vocab p ON p.term_id = s.pred"
                + " WHERE s.subj = e.ent_rowid AND p.name = 'name' ORDER BY s.stmt_rowid LIMIT 1), '') || '|' || coalesce(e.subkind, '')"
                + " FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type WHERE e.status = 1 AND t.name = " + q(type)
                + " AND EXISTS (SELECT 1 FROM kg_statement s WHERE s.subj = e.ent_rowid) ORDER BY 1");
    }

    @Test
    public void report() throws Exception {
        addAll(FacilityGraphTest.docs(this));
        settleSync();
        settleLlm();
        final StringBuilder b = new StringBuilder();
        b.append("### Facilities\n\n| Name | Entity | Kind | Address | Postal code | Town | Phone | Mailbox |\n|---|---|---|---|---|---|---|---|\n");
        for (final String row : entities("facility")) {
            final String[] f = row.split("\\|", -1);
            b.append("| ").append(f[1]).append(" | `").append(f[0]).append("` | ").append(f[2].isEmpty() ? "–" : f[2]).append(" | ")
                    .append(values(f[0], "address")).append(" | ").append(values(f[0], "postal_code")).append(" | ").append(values(f[0], "locality"))
                    .append(" | ").append(values(f[0], "phone")).append(" | ").append(values(f[0], "email")).append(" |\n");
        }
        b.append("\n### Organisations\n\n| Name | Address | Phone | Mailbox | Opening and office hours |\n|---|---|---|---|---|\n");
        for (final String row : entities("organization")) {
            final String[] f = row.split("\\|", -1);
            b.append("| ").append(f[1]).append(" | ").append(values(f[0], "address")).append(" | ").append(values(f[0], "phone")).append(" | ")
                    .append(values(f[0], "email")).append(" | ").append(values(f[0], "opening_hours")).append(" ").append(values(f[0], "office_hours"))
                    .append(" |\n");
        }
        b.append("\n### Sites\n\n| Name | Postal code | Town |\n|---|---|---|\n");
        for (final String row : entities("site")) {
            final String[] f = row.split("\\|", -1);
            b.append("| ").append(f[1]).append(" | ").append(values(f[0], "postal_code")).append(" | ").append(values(f[0], "locality")).append(" |\n");
        }
        b.append("\n### Counts\n\n| What | Count |\n|---|---|\n");
        for (final String[] c : new String[][] {
                {"active facilities with statements", "SELECT count(*) FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type WHERE e.status = 1 AND t.name = 'facility'"
                        + " AND EXISTS (SELECT 1 FROM kg_statement s WHERE s.subj = e.ent_rowid)"},
                {"address statements", "SELECT count(*) FROM kg_statement s JOIN kg_vocab p ON p.term_id = s.pred WHERE p.name = 'address'"},
                {"address evidence by the rules", "SELECT count(*) FROM kg_evidence ev JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid JOIN kg_vocab p ON p.term_id = s.pred"
                        + " WHERE p.name = 'address' AND ev.tier = 2"},
                {"address evidence by the LLM tier (values read from the page)", "SELECT count(*) FROM kg_evidence ev JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid"
                        + " JOIN kg_vocab p ON p.term_id = s.pred WHERE p.name = 'address' AND ev.tier = 3"},
                {"events identity_review", "SELECT count(*) FROM kg_event WHERE code = 'identity_review'"},
                {"events identity_conflict", "SELECT count(*) FROM kg_event WHERE code = 'identity_conflict'"},
                {"events identity_merge", "SELECT count(*) FROM kg_event WHERE code = 'identity_merge'"}}) {
            b.append("| ").append(c[0]).append(" | ").append(count(c[1])).append(" |\n");
        }
        final String out = System.getProperty("facilities.report");
        if (out == null) {
            System.out.println(b);
        } else {
            Files.write(Paths.get(out), b.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
