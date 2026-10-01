/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.htroot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import org.apache.lucene.store.Directory;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;

import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.kelondro.util.MemoryControl;
import net.yacy.crawler.CrawlSwitchboard;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.peers.operation.yacyBuildProperties;
import net.yacy.scoutro.dashboard.DashboardMetrics;
import net.yacy.scoutro.dashboard.DashboardMetrics.Counts;
import net.yacy.scoutro.dashboard.DashboardMetrics.Snapshot;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;
import net.yacy.server.servletProperties;

/** Administration overview only. Never delegates to responders with side effects. */
public final class ScoutroDashboard {
    private static final long CACHE_MS = 30000;
    private static EmbeddedSolrConnector cachedConnector;
    private static Snapshot cachedSnapshot;
    private static long cachedAt;

    private ScoutroDashboard() { }

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final servletProperties prop = new servletProperties();
        final ResponseHeader responseHeader = new ResponseHeader(200);
        responseHeader.put("Cache-Control", "no-store");
        prop.setOutgoingHeader(responseHeader);
        // Parameters are deliberately ignored, including any legacy action parameters.
        final EmbeddedSolrConnector connector = sb.index.fulltext().getDefaultEmbeddedConnector();
        final Snapshot snapshot = snapshot(connector);
        renderIndex(prop, snapshot);
        prop.putHTML("network", sb.getConfig(SwitchboardConstants.NETWORK_NAME, ""));
        prop.putHTML("core", "collection1");
        prop.putHTML("indexSize", indexBytes(connector));
        prop.putHTML("heapUsed", bytes(MemoryControl.used()));
        prop.putHTML("heapMax", bytes(MemoryControl.maxMemory()));
        prop.putHTML("dashboardUptime", uptime(System.currentTimeMillis() - sb.startupTime));
        prop.putHTML("yacyVersion", yacyBuildProperties.getVersion());
        prop.putHTML("build", yacyBuildProperties.getReleaseStub());
        prop.putHTML("scoutroVersion", scoutroVersion(sb));
        renderCrawler(prop, sb);
        return prop;
    }

    private static synchronized Snapshot snapshot(final EmbeddedSolrConnector connector) {
        final long now = System.nanoTime() / 1000000;
        if (cachedSnapshot != null && connector == cachedConnector && now - cachedAt < CACHE_MS) return cachedSnapshot;
        cachedSnapshot = DashboardMetrics.read(params -> {
            if (connector == null || connector.isClosed()) throw new IOException("Local index unavailable");
            try (SolrQueryRequest request = connector.request(params)) {
                final SolrQueryResponse response = connector.query(request);
                if (response.getException() != null) throw new IOException("Solr aggregate failed", response.getException());
                return response.getValues();
            }
        });
        cachedConnector = connector;
        cachedAt = System.nanoTime() / 1000000;
        return cachedSnapshot;
    }

    public static void renderIndex(final serverObjects prop, final Snapshot snapshot) {
        prop.putHTML("pages", count(snapshot.index.pages));
        prop.putHTML("hosts", count(snapshot.index.hosts));
        prop.put("indexUnavailable", snapshot.index.pages < 0 || snapshot.index.hosts < 0 ? 1 : 0);
        for (int i = 0; i < DashboardMetrics.COLLECTION_IDS.length; i++) {
            final String id = DashboardMetrics.COLLECTION_IDS[i];
            final Counts c = snapshot.collections.get(id);
            final String prefix = "collections_" + i + "_";
            prop.putHTML(prefix + "name", DashboardMetrics.COLLECTION_NAMES[i]);
            prop.putHTML(prefix + "id", id);
            prop.putHTML(prefix + "pages", count(c.pages));
            prop.putHTML(prefix + "hasHosts_hosts", count(c.hosts));
            prop.put(prefix + "hasHosts", c.hosts >= 0 ? 1 : 0);
            prop.put(prefix + "empty", c.pages == 0 ? 1 : 0);
            final double share = DashboardMetrics.share(c.pages, snapshot.index.pages);
            prop.putHTML(prefix + "share", share < 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", share));
            prop.putHTML(prefix + "bar", share < 0 ? "0" : String.format(Locale.ROOT, "%.1f", Math.min(100, share)));
        }
        prop.put("collections", DashboardMetrics.COLLECTION_IDS.length);
    }

    private static void renderCrawler(final serverObjects prop, final Switchboard sb) {
        prop.put("crawlerState", 0);
        prop.put("running", 0);
        prop.put("active", "—");
        prop.put("workers", "—");
        prop.put("pending", "—");
        prop.put("terminated", "—");
        try {
            int active = 0;
            for (final byte[] handle : sb.crawler.getActive()) {
                final CrawlProfile profile = sb.crawler.getActive(handle);
                if (profile != null && !CrawlSwitchboard.DEFAULT_PROFILES.contains(profile.name())) active++;
            }
            final int workers = sb.crawlQueues.activeWorkerEntries().size();
            final int pending = sb.crawlQueues.noticeURL.size();
            prop.putHTML("active", count(active));
            prop.putHTML("workers", count(workers));
            prop.putHTML("pending", count(pending));
            prop.putHTML("terminated", count(sb.crawler.getPassive().size()));
            final boolean running = workers > 0 || (pending > 0 && !sb.crawlJobIsPaused(SwitchboardConstants.CRAWLJOB_LOCAL_CRAWL));
            prop.put("running", running ? 1 : 0);
            prop.put("crawlerState", running ? 2 : 1);
        } catch (final RuntimeException e) {
            // The index and system overview remain usable if crawler data is unavailable.
        }
    }

    private static String indexBytes(final EmbeddedSolrConnector connector) {
        if (connector == null || connector.isClosed()) return "—";
        RefCounted<SolrIndexSearcher> searcher = null;
        try {
            searcher = connector.getCore().getSearcher();
            final Directory directory = searcher.get().getIndexReader().directory();
            long size = 0;
            // File metadata only: never walk DATA, fetch documents, commit or open an index writer.
            for (final String file : directory.listAll()) size += directory.fileLength(file);
            return bytes(size);
        } catch (final IOException | RuntimeException e) {
            return "—";
        } finally {
            if (searcher != null) searcher.decref();
        }
    }

    private static String scoutroVersion(final Switchboard sb) {
        final Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(new File(sb.getAppPath(), "scoutro.properties"))) {
            properties.load(input);
            final String upstream = properties.getProperty("scoutro.upstream.version", "");
            final String release = properties.getProperty("scoutro.release", "");
            return upstream.isEmpty() || release.isEmpty() ? "—" : upstream + "-scoutro." + release;
        } catch (final IOException e) {
            return "—";
        }
    }

    private static String count(final long value) {
        return value < 0 ? "—" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String bytes(final long value) {
        if (value > 0 && value < 1048576L) return String.format(Locale.ROOT, "%.3f MiB", value / 1048576.0);
        return value >= 1073741824L ? String.format(Locale.ROOT, "%.1f GiB", value / 1073741824.0)
                : String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    private static String uptime(final long millis) {
        final long minutes = Math.max(0, millis / 60000);
        return String.format(Locale.ROOT, "%dd %02dh %02dm", minutes / 1440, minutes / 60 % 24, minutes % 60);
    }
}
