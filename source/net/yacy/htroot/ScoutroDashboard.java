/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.htroot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Overview of the signed-in identity (docs/SCOUTRO_USERS_ACCESS.md): the collection cards come from the
 * collection catalog and the identity's collections; the administrator also gets the index, crawler and
 * system figures and YaCy's tools. Never delegates to responders with side effects.
 */
public final class ScoutroDashboard {
    private static final long CACHE_MS = 30000;
    private static final int CACHE_ENTRIES = 16;
    private static EmbeddedSolrConnector cachedConnector;
    /** per set of collections and scope, so that accounts never see another scope's numbers */
    private static final Map<String, Snapshot> cachedSnapshots = new LinkedHashMap<>();
    private static final Map<String, Long> cachedAt = new LinkedHashMap<>();

    private ScoutroDashboard() { }

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final servletProperties prop = new servletProperties();
        final ResponseHeader responseHeader = new ResponseHeader(200);
        responseHeader.put("Cache-Control", "no-store");
        prop.setOutgoingHeader(responseHeader);
        // Parameters are deliberately ignored, including any legacy action parameters.
        final net.yacy.scoutro.access.Caller caller = net.yacy.scoutro.access.Caller.of(header);
        final boolean admin = sb.verifyAuthentication(header) || caller.admin();
        final net.yacy.scoutro.access.Scope scope = admin ? net.yacy.scoutro.access.Scope.ALL : caller.scope();
        // the cards: the selectable collections of the catalog that the identity may use, in catalog order
        final List<String> ids = new ArrayList<>();
        final Map<String, String> names = new LinkedHashMap<>();
        try {
            for (final net.yacy.scoutro.api.CollectionCatalog.Entry e : net.yacy.scoutro.api.CollectionCatalog.current().entries(false)) {
                if (e.selectable() && scope.allows(e.id)) {
                    ids.add(e.id);
                    names.put(e.id, e.name == null || e.name.isEmpty() ? e.id : e.name);
                }
            }
        } catch (final net.yacy.scoutro.api.ApiException | RuntimeException e) {
            // the overview stays usable without the catalog
        }
        final EmbeddedSolrConnector connector = sb.index.fulltext().getDefaultEmbeddedConnector();
        final List<String> limit = scope.isAll() ? null : scope.collections();
        final Snapshot snapshot = snapshot(connector, ids, limit);
        renderIndex(prop, snapshot, ids, names);
        prop.put("dashAdmin", admin ? 1 : 0);
        prop.put("dashCollect", admin || caller.has(net.yacy.scoutro.access.Permission.COLLECT) ? 1 : 0);
        prop.put("scoped", limit == null ? 0 : 1);
        prop.put("dashAdmin_dashChat", caller.has(net.yacy.scoutro.access.Permission.CHAT) ? 1 : 0);
        prop.put("dashAdmin_dashCollect", caller.has(net.yacy.scoutro.access.Permission.COLLECT) ? 1 : 0);
        if (admin) {
            // system figures only for the administrator; every value also under the block prefix of the template
            final Map<String, String> system = new LinkedHashMap<>();
            system.put("network", sb.getConfig(SwitchboardConstants.NETWORK_NAME, ""));
            system.put("core", "collection1");
            system.put("indexSize", indexBytes(connector));
            system.put("heapUsed", bytes(MemoryControl.used()));
            system.put("heapMax", bytes(MemoryControl.maxMemory()));
            system.put("dashboardUptime", uptime(System.currentTimeMillis() - sb.startupTime));
            system.put("yacyVersion", yacyBuildProperties.getVersion());
            system.put("build", yacyBuildProperties.getReleaseStub());
            system.put("scoutroVersion", scoutroVersion(sb));
            system.put("pages", count(snapshot.index.pages));
            system.put("hosts", count(snapshot.index.hosts));
            for (final Map.Entry<String, String> v : system.entrySet()) {
                prop.putHTML(v.getKey(), v.getValue());
                prop.putHTML("dashAdmin_" + v.getKey(), v.getValue());
            }
            renderCrawler(prop, sb);
            for (final String k : new String[] {"crawlerState", "running", "active", "workers", "pending", "terminated"}) {
                prop.put("dashAdmin_" + k, prop.get(k, ""));
            }
        } else {
            prop.putHTML("dashAdmin_pages", count(snapshot.index.pages));
            prop.putHTML("dashAdmin_hosts", count(snapshot.index.hosts));
        }
        return prop;
    }

    private static Snapshot snapshot(final EmbeddedSolrConnector connector, final List<String> ids, final List<String> limit) {
        final String key = ids + "|" + (limit == null ? "*" : limit.toString());
        synchronized (cachedSnapshots) {
            final long now = System.nanoTime() / 1000000;
            if (connector != cachedConnector) {
                cachedSnapshots.clear();
                cachedAt.clear();
                cachedConnector = connector;
            }
            final Snapshot cached = cachedSnapshots.get(key);
            final Long at = cachedAt.get(key);
            if (cached != null && at != null && now - at < CACHE_MS) return cached;
            final Snapshot fresh = DashboardMetrics.read(params -> {
                if (connector == null || connector.isClosed()) throw new IOException("Local index unavailable");
                try (SolrQueryRequest request = connector.request(params)) {
                    final SolrQueryResponse response = connector.query(request);
                    if (response.getException() != null) throw new IOException("Solr aggregate failed", response.getException());
                    return response.getValues();
                }
            }, ids, limit);
            cachedSnapshots.remove(key);
            cachedAt.remove(key);
            cachedSnapshots.put(key, fresh);
            cachedAt.put(key, now);
            while (cachedSnapshots.size() > CACHE_ENTRIES) {
                final String oldest = cachedSnapshots.keySet().iterator().next();
                cachedSnapshots.remove(oldest);
                cachedAt.remove(oldest);
            }
            return fresh;
        }
    }

    public static void renderIndex(final serverObjects prop, final Snapshot snapshot, final List<String> ids,
            final Map<String, String> names) {
        prop.putHTML("pages", count(snapshot.index.pages));
        prop.putHTML("hosts", count(snapshot.index.hosts));
        prop.put("indexUnavailable", snapshot.index.pages < 0 || snapshot.index.hosts < 0 ? 1 : 0);
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            final Counts c = snapshot.collections.getOrDefault(id, new Counts(-1, -1));
            final String prefix = "collections_" + i + "_";
            prop.putHTML(prefix + "name", names.getOrDefault(id, id));
            prop.putHTML(prefix + "id", id);
            prop.putHTML(prefix + "pages", count(c.pages));
            prop.putHTML(prefix + "hasHosts_hosts", count(c.hosts));
            prop.put(prefix + "hasHosts", c.hosts >= 0 ? 1 : 0);
            prop.put(prefix + "empty", c.pages == 0 ? 1 : 0);
            final double share = DashboardMetrics.share(c.pages, snapshot.index.pages);
            prop.putHTML(prefix + "share", share < 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", share));
            prop.putHTML(prefix + "bar", share < 0 ? "0" : String.format(Locale.ROOT, "%.1f", Math.min(100, share)));
        }
        prop.put("collections", ids.size());
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

    static String scoutroVersion(final Switchboard sb) {
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

    static String bytes(final long value) {
        if (value > 0 && value < 1048576L) return String.format(Locale.ROOT, "%.3f MiB", value / 1048576.0);
        return value >= 1073741824L ? String.format(Locale.ROOT, "%.1f GiB", value / 1073741824.0)
                : String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    static String uptime(final long millis) {
        final long minutes = Math.max(0, millis / 60000);
        return String.format(Locale.ROOT, "%dd %02dh %02dm", minutes / 1440, minutes / 60 % 24, minutes % 60);
    }
}
