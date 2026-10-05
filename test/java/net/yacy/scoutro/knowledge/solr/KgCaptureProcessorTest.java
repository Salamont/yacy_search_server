package net.yacy.scoutro.knowledge.solr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.sync.Capture;
import net.yacy.scoutro.knowledge.sync.DirtySet;

/**
 * Open point O9 of the plan, proven with the shipped defaults/solr
 * configuration: the capture processor loads in the embedded core, sees every
 * change with Solr's version, and real-time get sees uncommitted adds and
 * deletes; versions are monotonic, also across a restart.
 */
public class KgCaptureProcessorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File data;
    private EmbeddedInstance instance;
    private DirtySet set;

    @Before
    public void open() throws Exception {
        this.data = this.tmp.newFolder("index");
        this.instance = new EmbeddedInstance(new File("defaults/solr"), this.data, "collection1",
                new String[] {"collection1", "webgraph"});
        this.set = new DirtySet(1000);
        Capture.activate(this.set);
    }

    @After
    public void close() {
        Capture.deactivate(this.set);
        if (this.instance != null) {
            this.instance.close();
        }
    }

    static SolrInputDocument doc(final String id, final String host) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", "https://" + host + "/" + id);
        d.setField("host_s", host);
        d.setField("collection_sxt", List.of("c1"));
        d.setField("httpstatus_i", 200);
        return d;
    }

    private SolrDocumentList rtg(final String... ids) throws Exception {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("fl", "id,_version_,host_s");
        return this.instance.getDefaultServer().getById(Arrays.asList(ids), p);
    }

    private static long version(final SolrDocumentList l, final String id) {
        for (final SolrDocument d : l) {
            if (id.equals(d.getFieldValue("id"))) {
                return ((Number) d.getFieldValue("_version_")).longValue();
            }
        }
        return -1L;
    }

    private Map<String, DirtySet.Event> drainAll() {
        final Map<String, DirtySet.Event> m = new java.util.HashMap<>();
        for (final DirtySet.Event e : this.set.drain(Integer.MAX_VALUE)) {
            m.put(e.id, e);
        }
        return m;
    }

    @Test
    public void processorIsPartOfTheDefaultChainOfCollection1() {
        final List<UpdateRequestProcessorFactory> chain = this.instance.getCore("collection1").getUpdateProcessingChain(null).getProcessors();
        int distributed = -1;
        int capture = -1;
        for (int i = 0; i < chain.size(); i++) {
            final String name = chain.get(i).getClass().getName();
            if (name.endsWith("DistributedUpdateProcessorFactory")) {
                distributed = i;
            }
            if (chain.get(i) instanceof KgCaptureProcessorFactory) {
                capture = i;
            }
        }
        assertTrue("capture processor loaded: " + chain, capture >= 0);
        assertTrue("after the processor that assigns _version_", distributed >= 0 && distributed < capture);
    }

    @Test
    public void realTimeGetSeesUncommittedAddsAndDeletesWithTheCapturedVersions() throws Exception {
        final SolrClient c = this.instance.getDefaultServer();
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        c.add(doc("BBBBBBBBBBBB", "b.example"));
        // no commit: a search sees nothing, real-time get sees both
        final ModifiableSolrParams q = new ModifiableSolrParams();
        q.set("q", "*:*");
        q.set("rows", 0);
        assertEquals(0L, c.query(q).getResults().getNumFound());
        SolrDocumentList got = rtg("AAAAAAAAAAAA", "BBBBBBBBBBBB", "CCCCCCCCCCCC");
        assertEquals(2, got.size());
        Map<String, DirtySet.Event> events = drainAll();
        assertEquals(version(got, "AAAAAAAAAAAA"), events.get("AAAAAAAAAAAA").version);
        assertEquals(version(got, "BBBBBBBBBBBB"), events.get("BBBBBBBBBBBB").version);
        assertFalse(events.get("AAAAAAAAAAAA").delete);
        final long firstA = events.get("AAAAAAAAAAAA").version;

        // uncommitted delete: gone for real-time get, recorded with a higher version
        c.deleteById("AAAAAAAAAAAA");
        got = rtg("AAAAAAAAAAAA", "BBBBBBBBBBBB");
        assertEquals(-1L, version(got, "AAAAAAAAAAAA"));
        events = drainAll();
        assertTrue(events.get("AAAAAAAAAAAA").delete);
        assertTrue(events.get("AAAAAAAAAAAA").version > firstA);
        final long deleteA = events.get("AAAAAAAAAAAA").version;

        // reactivation: re-added with a higher version
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        got = rtg("AAAAAAAAAAAA");
        events = drainAll();
        assertEquals(version(got, "AAAAAAAAAAAA"), events.get("AAAAAAAAAAAA").version);
        assertTrue(events.get("AAAAAAAAAAAA").version > deleteA);

        // a partial (atomic) update as YaCy's postprocessing writes it is an add with a new version
        final SolrInputDocument part = new SolrInputDocument();
        part.setField("id", "BBBBBBBBBBBB");
        part.setField("host_s", Map.of("set", "b2.example"));
        c.add(part);
        got = rtg("BBBBBBBBBBBB");
        assertEquals("b2.example", got.get(0).getFieldValue("host_s"));
        events = drainAll();
        assertEquals(version(got, "BBBBBBBBBBBB"), events.get("BBBBBBBBBBBB").version);
    }

    @Test
    public void coalescingKeepsTheNewestEventAndADeleteWins() throws Exception {
        final SolrClient c = this.instance.getDefaultServer();
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        c.deleteById("AAAAAAAAAAAA");
        assertEquals(1, this.set.size());
        final DirtySet.Event e = this.set.drain(10).get(0);
        assertTrue(e.delete);
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        assertFalse("a later re-add replaces the delete", this.set.drain(10).get(0).delete);
    }

    @Test
    public void deleteByQueryAndFullClearAreSignalsAndWebgraphIsIgnored() throws Exception {
        final SolrClient c = this.instance.getDefaultServer();
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        c.deleteByQuery("host_s:a.example");
        assertEquals(1L, this.set.queryDeletes());
        c.deleteByQuery("*:*");
        assertEquals(1L, this.set.fullResets());
        this.set.drain(10);
        this.instance.getServer("webgraph").add(doc("WWWWWWWWWWWW", "w.example"));
        assertEquals("the webgraph core shares the chain but is not followed", 0, this.set.size());
    }

    @Test
    public void notRecordedWhileTheGraphIsNotRunningAndUpdatesNeverFail() throws Exception {
        final SolrClient c = this.instance.getDefaultServer();
        Capture.deactivate(this.set);
        c.add(doc("AAAAAAAAAAAA", "a.example"));
        assertEquals(0, this.set.size());
        assertEquals(1, rtg("AAAAAAAAAAAA").size());
        // a broken sink must not fail the Solr update
        Capture.activate(new DirtySet(1000) {
            @Override
            public void added(final String id, final long version) {
                throw new IllegalStateException("broken sink");
            }
        });
        final long failures = KgCaptureProcessorFactory.failures();
        c.add(doc("BBBBBBBBBBBB", "b.example"));
        assertEquals(1, rtg("BBBBBBBBBBBB").size());
        assertEquals(failures + 1, KgCaptureProcessorFactory.failures());
    }

    @Test
    public void overflowDropsNewIdsAndIsReported() throws Exception {
        Capture.deactivate(this.set);
        final DirtySet small = new DirtySet(2);
        Capture.activate(small);
        try {
            final SolrClient c = this.instance.getDefaultServer();
            c.add(doc("AAAAAAAAAAAA", "a.example"));
            c.add(doc("BBBBBBBBBBBB", "b.example"));
            c.add(doc("CCCCCCCCCCCC", "c.example"));
            c.add(doc("AAAAAAAAAAAA", "a.example")); // known IDs are still updated
            assertEquals(2, small.size());
            assertTrue(small.overflowed());
            assertEquals(1L, small.status().getLong("dropped"));
            assertEquals(3, rtg("AAAAAAAAAAAA", "BBBBBBBBBBBB", "CCCCCCCCCCCC").size());
        } finally {
            Capture.deactivate(small);
        }
    }

    @Test
    public void versionsStayMonotonicAcrossARestart() throws Exception {
        SolrClient c = this.instance.getDefaultServer();
        c.add(doc("DDDDDDDDDDDD", "d.example"));
        c.commit();
        final long before = this.set.maxVersion();
        this.instance.close();
        this.instance = new EmbeddedInstance(new File("defaults/solr"), this.data, "collection1",
                new String[] {"collection1", "webgraph"});
        c = this.instance.getDefaultServer();
        assertNotNull("committed document survives", rtg("DDDDDDDDDDDD").isEmpty() ? null : "ok");
        c.add(doc("EEEEEEEEEEEE", "e.example"));
        assertTrue(this.set.maxVersion() > before);
        assertTrue(version(rtg("EEEEEEEEEEEE"), "EEEEEEEEEEEE") > before);
        assertNull(this.set.drain(100).stream().filter(e -> e.version <= 0).findAny().orElse(null));
    }
}
