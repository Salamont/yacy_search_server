/*
 *  ReportedPostingIdentityTest
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

package net.yacy.scoutro.knowledge.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JobEvidenceTest;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The reported job page (0.10.6) through identity resolution on a real store: the short name of the posting's employer
 * and the full name of the imprint end on one organisation where the short name declares the site's own domain, and the
 * page is one job with both of its employment types; a short name that declares another domain stays an organisation of
 * its own, and so does its posting.
 */
public class ReportedPostingIdentityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private Terms terms;
    private Publisher publisher;
    private final long now = 1_800_000_000_000L;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, this.terms);
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final Extraction ex) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = "AAAAAAjobs01";
        d.url = JobEvidenceTest.REPORTED_URL;
        d.host = java.net.URI.create(d.url).getHost();
        d.hostId = d.docId.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("example-web");
        d.solrVersion = 1001L;
        d.loadedAt = this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, -1L, ex, this.now));
    }

    private List<String> strings(final String sql) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private static String term(final String name) {
        return "(SELECT term_id FROM kg_vocab WHERE name = '" + name + "')";
    }

    private List<String> active(final String type) throws Exception {
        return strings("SELECT ent_rowid FROM kg_entity WHERE status = 1 AND type = " + term(type) + " ORDER BY ent_rowid");
    }

    private List<String> values(final String entity, final String predicate) throws Exception {
        return strings("SELECT obj_val FROM kg_statement WHERE subj = " + entity + " AND pred = " + term(predicate) + " ORDER BY obj_val");
    }

    private List<String> objects(final String entity, final String predicate) throws Exception {
        return strings("SELECT DISTINCT obj_ent FROM kg_statement WHERE subj = " + entity + " AND pred = " + term(predicate)
                + " ORDER BY obj_ent");
    }

    /** The holder of the full name of the imprint. */
    private List<String> operator() throws Exception {
        return strings("SELECT s.subj FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj WHERE e.status = 1 AND s.pred = "
                + term(Vocabulary.NAME) + " AND s.obj_val = 'Muster Consulting AG'");
    }

    @Test
    public void theShortNameOfTheOwnDomainIsTheOperatorOfTheFullName() throws Exception {
        publish(JobEvidenceTest.reportedPage(JobEvidenceTest.REPORTED_EMPLOYER));
        final List<String> jobs = active(Vocabulary.JOB);
        assertEquals("one job", 1, jobs.size());
        final String job = jobs.get(0);
        assertEquals(List.of("freelance", "full_time"), values(job, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("2023-08-30"), values(job, Vocabulary.DATE_POSTED));
        assertEquals(List.of(), values(job, Vocabulary.VALID_THROUGH));
        assertEquals(List.of(), values(job, Vocabulary.JOB_STATUS));
        assertEquals(1, objects(job, Vocabulary.JOB_LOCATION).size());
        // the short name and the full name of the operator are one organisation, the job's one employer
        final List<String> operator = operator();
        assertEquals(1, operator.size());
        assertEquals(operator, objects(job, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of("MUSTER", "Muster Consulting AG"), values(operator.get(0), Vocabulary.NAME));
        assertEquals("one organisation", 1, active(Vocabulary.ORGANIZATION).size());
    }

    @Test
    public void aShortNameOfAnotherDomainStaysApart() throws Exception {
        publish(JobEvidenceTest.reportedPage("{\"@type\":\"Organization\",\"name\":\"MUSTER\",\"sameAs\":\"https://www.muster-holding.example\"}"));
        assertEquals("the posting stays a job of its own", 2, active(Vocabulary.JOB).size());
        final List<String> operator = operator();
        assertEquals(1, operator.size());
        assertFalse(values(operator.get(0), Vocabulary.NAME).contains("MUSTER"));
        assertEquals(2, active(Vocabulary.ORGANIZATION).size());
    }
}
