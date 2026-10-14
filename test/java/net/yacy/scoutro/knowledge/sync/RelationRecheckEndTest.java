package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * The end of the relation re-check in the running process (0.10.6 production finding C29): the full check (scope {@code all}, a
 * graph whose check of the rules 3 had not ended) published every page it selected, nothing was open or failed, and its end was
 * never written. The LLM tier ran beside it, as in production. Neutral example data; the model is the fake of
 * {@link LlmServiceTest}.
 */
public class RelationRecheckEndTest extends LlmFixture {

    static final String HOST = "https://www.muster-pflege.de";
    static final String JOB_PAGE = "JOBDETpfle01";
    static final String PARTNER_PAGE = "PARTNEpfle01";
    /** An old page without anything of the rules' check; the LLM tier reads a position from it while the check runs. */
    static final String TEAM_PAGE = "TEAMSEpfle01";
    static final String TEAM_TEXT = "Unser Team im Haus Lindenhof wächst. Zur Verstärkung im Wohnbereich kommt bald eine Pflegefachkraft"
            + " für die Tagespflege dazu. Die Muster Pflege gGmbH freut sich auf Sie.";
    /** The position as the LLM tier states it: its quote holds the advertisement ("Zur Verstärkung"), as the validator wants it. */
    static final String LLM_JOB = "{\"entities\":[{\"id\":\"e1\",\"type\":\"job\",\"name\":\"Pflegefachkraft\","
            + "\"quote\":\"Zur Verstärkung im Wohnbereich kommt bald eine Pflegefachkraft für die Tagespflege dazu\"}],\"claims\":[],\"values\":[]}";
    static final String NOTHING = "{\"entities\":[],\"claims\":[],\"values\":[]}";

    private static final String JOBS_OF = "SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
            + " JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
            + " WHERE t.name = 'job' AND d.doc_id = ";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.llm.kinds.c1", "nursinghome", KgConfig.JOBS_COLLECTIONS, "c1"};
    }

    /** The source of the sync, with a hook after each text it reads (the LLM tier publishing between two pages of the check). */
    private SyncServiceTest.Hooked hooked;

    @Override
    protected void start() throws Exception {
        super.start();
        this.hooked = new SyncServiceTest.Hooked(this.source);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, this.hooked, new Gates(this.cfg, Gates.IDLE), this.clock::get, false);
    }

    /**
     * Three pages read by the rules of 0.10.5 and not yet by the LLM tier (its backlog): the advertisement of a position (a job of
     * the rule tier), a page with partners (relations of the rule tier) and a team page (nothing of the check). The graph as
     * production left it: the rules 3, its check not ended, so the rules 4 check every page once (scope all).
     */
    private void asProductionLeftIt() throws Exception {
        add(JOB_PAGE, HOST + "/karriere/stellenangebote/pflegefachkraft/", "c1", LD, "Karriere Stellenangebote Pflegefachkraft (m/w/d)"
                + " in Vollzeit. Ihre Aufgaben: Pflege und Betreuung unserer Bewohner. Ihr Profil: abgeschlossene Ausbildung."
                + " Vergütung: 3.500 € brutto monatlich. Bewerbung an karriere@muster-pflege.de.", "Pflegefachkraft (m/w/d) | Muster Pflege");
        add(PARTNER_PAGE, HOST + "/partner", "c1", LD, "Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG.", "Partner");
        add(TEAM_PAGE, HOST + "/ueber-uns/team", "c1", LD, TEAM_TEXT, "Team");
        settleSync();
        assertTrue("the rules read the position", count(JOBS_OF + "'" + JOB_PAGE + "'") > 0L);
        assertEquals("nothing of the rules on the team page", 0L, count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d"
                + " ON d.doc_rowid = e.doc_rowid WHERE d.doc_id = '" + TEAM_PAGE + "' AND e.tier IN (1, 2) AND e.stmt_rowid IN"
                + " (SELECT s.stmt_rowid FROM kg_statement s JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                + " WHERE t.name = 'job')"));
        assertEquals("not read by the LLM tier yet", 3L, count("SELECT count(*) FROM kg_doc WHERE llm_status IS NULL"));
        exec("UPDATE kg_meta SET value = '3' WHERE key = 'relation_recheck_rules'");
        exec("DELETE FROM kg_meta WHERE key IN ('relation_recheck_since', 'relation_recheck_done', 'relation_recheck_pages',"
                + " 'relation_recheck_scope', 'relation_recheck_failed')");
        this.clock.addAndGet(1000L);
        restart();
    }

    private JSONObject recheck() throws Exception {
        return this.sync.status().getJSONObject("relationRecheckInfo");
    }

    private long meta(final String key) {
        return count("SELECT count(*) FROM kg_meta WHERE key = '" + key + "'");
    }

    private long since() {
        return Long.parseLong(strings("SELECT value FROM kg_meta WHERE key = 'relation_recheck_since'").get(0));
    }

    private long processedAt(final String id) {
        return Long.parseLong(strings("SELECT processed_at FROM kg_doc WHERE doc_id = '" + id + "'").get(0));
    }

    /** The LLM tier reads its backlog: a position on the team page, nothing elsewhere. */
    private void llmReadsTheTeamPage() {
        this.model.answer = u -> u.contains("kommt bald eine Pflegefachkraft") ? LLM_JOB : NOTHING;
        settleLlm();
    }

    /**
     * C29: the LLM tier publishes a job on the old team page while the check runs. Its publication does not touch the page's
     * processing time, so the page looked like a page of the check that was never extracted again; the check had published every
     * page it named, and its end was never written. The check is the pages it selected at its start: it ends with the last of them,
     * in the running process, and a later publication of the team page changes nothing of it.
     */
    @Test
    public void aJobTheLlmTierReadsDuringTheCheckDoesNotHoldItsEndOpen() throws Exception {
        asProductionLeftIt();
        // the first page of the check is read; the LLM tier publishes before the second one is
        this.hooked.afterText = n -> {
            if (n == 1) {
                llmReadsTheTeamPage();
            }
        };
        settleSync();
        this.hooked.afterText = null;
        assertEquals("the LLM tier published its job on the team page", 1L, count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d"
                + " ON d.doc_rowid = e.doc_rowid WHERE d.doc_id = '" + TEAM_PAGE + "' AND e.tier = 3"));
        assertTrue("not extracted again: processed before the check", processedAt(TEAM_PAGE) < since());
        assertEquals("the two pages of the check, not the team page", 2L, this.sync.counters.extractions.get());
        JSONObject info = recheck();
        assertEquals(info.toString(), "all", info.getString("scope"));
        assertEquals(info.toString(), 2L, info.getLong("pages"));
        assertEquals(info.toString(), 0L, info.getLong("open"));
        assertEquals(info.toString(), 0L, info.getLong("failed"));
        assertFalse("ended in the running process: " + info, info.isNull("done"));
        assertEquals(1L, meta("relation_recheck_done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
        final long done = info.getLong("done");
        final long since = info.getLong("since");
        // the team page changes and is extracted (not as a page of the check): nothing of the check changes
        add(TEAM_PAGE, HOST + "/ueber-uns/team", "c1", LD, TEAM_TEXT + " Wir freuen uns auf Ihre Bewerbung.", "Team");
        settleSync();
        info = recheck();
        assertEquals(info.toString(), done, info.getLong("done"));
        assertEquals(info.toString(), since, info.getLong("since"));
        assertEquals(info.toString(), 2L, info.getLong("pages"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
        // a restart checks nothing again
        restart();
        settleSync();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(done, recheck().getLong("done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
    }

    /**
     * C29, a check left as 0.10.6 left its check (same rules): every page of the check published, nothing open or failed, the end not
     * written, and the team page with the job the LLM tier read during the check. The next start ends the check without extracting
     * any page again; its start, scope and page count stay, and its end is written once.
     */
    @Test
    public void aCheckWhosePagesAreAllPublishedEndsAtTheNextStartWithoutExtractingAgain() throws Exception {
        asProductionLeftIt();
        this.hooked.afterText = n -> {
            if (n == 1) {
                llmReadsTheTeamPage();
            }
        };
        settleSync();
        this.hooked.afterText = null;
        // as the release 0.10.6 left it: no end
        exec("DELETE FROM kg_meta WHERE key = 'relation_recheck_done'");
        exec("DELETE FROM kg_event WHERE code = 'relation_rules_recheck_done'");
        final long since = since();
        final List<String> llm = strings("SELECT value FROM kg_meta WHERE key = 'relation_recheck_llm_jobs'");
        final long tier3 = count("SELECT count(*) FROM kg_evidence WHERE tier = 3");
        this.clock.addAndGet(60_000L);
        restart();
        settleSync();
        assertEquals("no page extracted again", 0L, this.sync.counters.extractions.get());
        final JSONObject info = recheck();
        assertFalse(info.toString(), info.isNull("done"));
        assertEquals(info.toString(), since, info.getLong("since"));
        assertEquals(info.toString(), 2L, info.getLong("pages"));
        assertEquals(info.toString(), "all", info.getString("scope"));
        assertEquals(info.toString(), 0L, info.getLong("open"));
        assertEquals(1L, meta("relation_recheck_done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
        assertEquals("the count of the LLM tier's jobs stays", llm, strings("SELECT value FROM kg_meta WHERE key = 'relation_recheck_llm_jobs'"));
        assertEquals("the LLM tier's evidence stays", tier3, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        // and the start after it neither ends it again nor checks anything
        final long done = info.getLong("done");
        this.clock.addAndGet(60_000L);
        restart();
        settleSync();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(done, recheck().getLong("done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
    }

    /**
     * C29, the transition to an empty selection that no page of the check makes: the last page of the check leaves while the
     * selection still names a page outside this process's list (no path of this version leads there; the state is written
     * directly, as job evidence published before the check). That page is then extracted for a change of its own, not as a page
     * of the check. The running process ends the check once the selection is empty: once, without a restart, without extracting
     * anything for it, with its start, scope and page count.
     */
    @Test
    public void theCheckEndsInTheRunningProcessOnceItsSelectionIsEmptyWithoutAPageOfIt() throws Exception {
        asProductionLeftIt();
        this.hooked.afterText = n -> {
            if (n == 1) {
                // job evidence on the team page dated before the check, so that the selection names it
                exec("INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt,"
                        + " observed_at) SELECT e.stmt_rowid, (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + TEAM_PAGE + "'), 3, e.ext_id,"
                        + " e.kind, e.certainty, e.confidence, e.locator, e.excerpt, e.observed_at FROM kg_evidence e JOIN kg_statement s"
                        + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                        + " WHERE t.name = 'job' AND e.tier = 2 AND e.doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + JOB_PAGE + "')");
            }
        };
        settleSync();
        this.hooked.afterText = null;
        JSONObject info = recheck();
        assertEquals(info.toString(), 0L, info.getLong("open"));
        assertTrue("the selection still names the team page: " + info, info.isNull("done"));
        assertEquals(0L, meta("relation_recheck_done"));
        final long since = info.getLong("since");
        // the team page changes; extracted for its change, it leaves the selection, no page of the check is published
        add(TEAM_PAGE, HOST + "/ueber-uns/team", "c1", LD, TEAM_TEXT + " Wir freuen uns auf Ihre Bewerbung.", "Team");
        settleSync();
        final long extractions = this.sync.counters.extractions.get();
        this.clock.addAndGet(SyncService.RECHECK_END_EVERY_MILLIS);
        settleSync();
        info = recheck();
        assertFalse("ended in the running process: " + info, info.isNull("done"));
        assertEquals(info.toString(), since, info.getLong("since"));
        assertEquals(info.toString(), 2L, info.getLong("pages"));
        assertEquals(info.toString(), "all", info.getString("scope"));
        assertEquals(1L, meta("relation_recheck_done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
        assertEquals("nothing extracted for the end", extractions, this.sync.counters.extractions.get());
        // looked at again later: nothing changes
        final long done = info.getLong("done");
        this.clock.addAndGet(2 * SyncService.RECHECK_END_EVERY_MILLIS);
        settleSync();
        assertEquals(done, recheck().getLong("done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done'"));
        restart();
        settleSync();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(done, recheck().getLong("done"));
    }

    /**
     * The graph 0.10.6 leaves (relation rules 4): its full check published every page, its end was never written (C29). With the
     * rules 5 its end is written once at the start (its pages are not extracted again for it) and kept as the check before
     * ({@code relationRecheckInfoBefore}); the new check takes only the job pages, since the rules 5 changed only the reading of
     * jobs: the page of the position and the team page with the job of the LLM tier, not the page with partners. The evidence of
     * the LLM tier stays.
     */
    @Test
    public void theCheckOfTheRules4WithoutItsEndIsClosedAndOnlyTheJobPagesAreChecked() throws Exception {
        asProductionLeftIt();
        this.hooked.afterText = n -> {
            if (n == 1) {
                llmReadsTheTeamPage();
            }
        };
        settleSync();
        this.hooked.afterText = null;
        // as 0.10.6 left it: the rules 4, every page of its full check published, no end
        exec("UPDATE kg_meta SET value = '4' WHERE key = 'relation_recheck_rules'");
        exec("DELETE FROM kg_meta WHERE key = 'relation_recheck_done'");
        exec("DELETE FROM kg_event WHERE code = 'relation_rules_recheck_done'");
        final long since4 = since();
        final long partner = processedAt(PARTNER_PAGE);
        final long tier3 = count("SELECT count(*) FROM kg_evidence WHERE tier = 3");
        this.clock.addAndGet(60_000L);
        restart();
        settleSync();
        final JSONObject before = this.sync.status().getJSONObject("relationRecheckInfoBefore");
        assertEquals(before.toString(), "4", before.getString("rules"));
        assertEquals(before.toString(), "all", before.getString("scope"));
        assertEquals(before.toString(), 2L, before.getLong("pages"));
        assertEquals(before.toString(), since4, before.getLong("since"));
        assertFalse(before.toString(), before.isNull("done"));
        assertTrue(before.toString(), before.getBoolean("doneAtStart"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done' AND detail LIKE 'rules 4:%'"));
        final JSONObject info = recheck();
        assertEquals(info.toString(), SyncService.RELATION_RULES, info.getString("rules"));
        assertEquals(info.toString(), "jobs", info.getString("scope"));
        assertEquals("the page of the position and the team page with its job of the LLM tier", 2L, info.getLong("pages"));
        assertEquals(2L, this.sync.counters.extractions.get());
        assertEquals("the page with partners is not extracted again", partner, processedAt(PARTNER_PAGE));
        assertFalse("the new check ends in the running process: " + info, info.isNull("done"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck_done' AND detail LIKE 'rules "
                + SyncService.RELATION_RULES + ":%'"));
        assertEquals("the evidence of the LLM tier stays", tier3, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        // a restart: nothing again, the check before stays as it was
        final long done = info.getLong("done");
        restart();
        settleSync();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(done, recheck().getLong("done"));
        assertEquals(before.toString(), this.sync.status().getJSONObject("relationRecheckInfoBefore").toString());
    }
}
