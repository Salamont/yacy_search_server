/*
 *  JobReadingHashTest
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

package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;

/**
 * The job reading in the input hash (0.10.2): only job pages, and only where the reading differs from what the extractor
 * identity says, so that a changed hint policy extracts the job pages again and nothing else, and the former behaviour keeps
 * every hash as it was.
 */
public class JobReadingHashTest {

    @After
    public void reset() {
        SolrDoc.configure(null);
    }

    private static SolrDoc doc(final String url, final String title, final String ldJson) {
        final Map<String, Object> f = new HashMap<>();
        f.put(SolrDoc.ID, "AAAAAAhost01");
        f.put(SolrDoc.SKU, url);
        f.put(SolrDoc.COLLECTIONS, List.of("c1"));
        f.put(SolrDoc.TITLE, List.of(title));
        f.put(SolrDoc.HTTPSTATUS, 200);
        if (ldJson != null) {
            f.put(SolrDoc.LD_JSON, List.of(ldJson));
        }
        return SolrDoc.of(f);
    }

    private static KgConfig cfg(final String... settings) {
        final String[] all = new String[settings.length + 2];
        all[0] = KgConfig.COLLECTIONS;
        all[1] = "c1";
        System.arraycopy(settings, 0, all, 2, settings.length);
        return KgTestSupport.config(KgTestSupport.enabled(all));
    }

    private static final SolrDoc CAREERS = doc("https://www.example.org/karriere", "Karriere", null);
    private static final SolrDoc POSTING = doc("https://www.example.org/job/42", "Buchhalter",
            "{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\"}");
    private static final SolrDoc COMPANY = doc("https://www.example.org/ueber-uns", "Über uns", "{\"@type\":\"Organization\",\"name\":\"A\"}");

    @Test
    public void onlyJobPagesCarryAReadingAndOnlyWhereItDiffersFromTheIdentity() {
        assertTrue(CAREERS.jobPage());
        assertTrue("a JobPosting in JSON-LD", POSTING.jobPage());
        assertFalse(COMPANY.jobPage());
        final KgConfig hints = cfg();
        assertEquals("jobs:signals", CAREERS.jobComponent(hints));
        assertEquals("jobs:signals", POSTING.jobComponent(hints));
        assertNull(COMPANY.jobComponent(hints));
        assertNull("hints off, objects off: as before", POSTING.jobComponent(cfg(KgConfig.JOBS_SIGNALS_PREFIX + "c1", "false")));
        assertNull("objects with their hints: as before", POSTING.jobComponent(cfg(KgConfig.JOBS_COLLECTIONS, "c1")));
        assertEquals("jobs:objects", POSTING.jobComponent(cfg(KgConfig.JOBS_COLLECTIONS, "c1", KgConfig.JOBS_SIGNALS_PREFIX + "c1", "false")));
        assertNull("a collection that is not followed reads nothing", POSTING.jobComponent(KgTestSupport.config(
                KgTestSupport.enabled(KgConfig.COLLECTIONS, "c2"))));
    }

    @Test
    public void aChangedHintPolicyChangesTheHashOfJobPagesOnly() {
        SolrDoc.configure(cfg(KgConfig.JOBS_SIGNALS_PREFIX + "c1", "false"));
        final byte[] job = POSTING.inputHash(), company = COMPANY.inputHash();
        final String extractors = SolrDoc.extractors();
        SolrDoc.configure(cfg());
        assertEquals("the extractor identity stays", extractors, SolrDoc.extractors());
        assertFalse("the job page is extracted again", Arrays.equals(job, POSTING.inputHash()));
        assertArrayEquals("every other page keeps its input", company, COMPANY.inputHash());
        assertArrayEquals("the content hash of the LLM tier stays", POSTING.contentHash(), POSTING.contentHash());
        // switched back: the former hash again (nothing else enters it)
        SolrDoc.configure(cfg(KgConfig.JOBS_SIGNALS_PREFIX + "c1", "false"));
        assertArrayEquals(job, POSTING.inputHash());
    }
}
