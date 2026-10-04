/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.SolrConnector;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.crawler.CrawlSwitchboard;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.scoutro.agents.CrawlRecord;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.scoutro.report.CaptureService;
import net.yacy.scoutro.report.CrawlOutcome;
import net.yacy.scoutro.report.DomainTable;
import net.yacy.scoutro.report.ExclusionTracker;
import net.yacy.scoutro.report.PrecheckResult;
import net.yacy.search.Switchboard;
import net.yacy.search.schema.CollectionConfiguration;

/**
 * Runs the crawl report capture beside YaCy on its own daemon thread. Towards YaCy it
 * only reads (crawl profiles, ErrorCache, index); it writes nothing but the
 * {@code scoutro_domains} table. Disable with {@code scoutro.report.capture=false}.
 */
public final class CaptureRuntime {
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-REPORT");
    private static ScheduledExecutorService executor;
    private static CaptureService service;

    private CaptureRuntime() {}

    /** Starts the monitor once; called when the Scoutro API servlet is initialized. */
    public static synchronized void start() {
        if (executor != null) return;
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb != null && !sb.getConfigBool("scoutro.report.capture", true)) {
            LOG.info("crawl report capture disabled (scoutro.report.capture=false)");
            return;
        }
        final long interval = clamp(sb == null ? 20 : sb.getConfigLong("scoutro.report.captureIntervalSeconds", 20), 5, 300);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "ScoutroReport.capture");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(CaptureRuntime::tick, interval, interval, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        service = null;
    }

    private static void tick() {
        try {
            final CaptureService current = service();
            if (current != null) current.tick();
        } catch (final IOException | RuntimeException e) {
            LOG.warn("crawl report capture step failed: " + e.getClass().getSimpleName());
        }
    }

    /** The capture service once YaCy is ready, or null while it is not or capture is off. */
    static synchronized CaptureService service() {
        if (executor == null) return null;
        if (service != null) return service;
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.tables == null || sb.crawler == null || sb.crawlQueues == null
                || sb.crawlQueues.errorURL == null || sb.index.fulltext().getDefaultConnector() == null) return null;
        final CollectionConfiguration schema = sb.index.fulltext().getDefaultConfiguration();
        final CrawlOutcome outcome = new CrawlOutcome(p -> {
            final SolrConnector connector = sb.index.fulltext().getDefaultConnector();
            if (connector == null || connector.isClosed()) throw new IOException("index unavailable");
            return connector.getResponseByParams(p).getResponse();
        }, field -> schema == null || schema.isEmpty() || schema.contains(field));
        final long settle = clamp(sb.getConfigLong("scoutro.report.settleSeconds", 120), 0, 3600) * 1000L;
        service = new CaptureService(new YaCyCrawls(sb), new ExclusionTracker(errors(sb)), outcome,
                new DomainTable(sb.tables), System::currentTimeMillis, settle);
        return service;
    }

    /** Forwards Discovery events; does nothing while capture is not running. */
    public static DiscoveryService.CrawlObserver observer() {
        return new DiscoveryService.CrawlObserver() {
            @Override public void accepted(final String marker, final String job, final String domain) {
                final CaptureService s = service();
                if (s != null) s.discoveryAccepted(marker, job, domain);
            }

            @Override public void terminated(final String marker, final String job, final String domain) {
                final CaptureService s = service();
                if (s != null) s.discoveryTerminated(marker, job, domain);
            }

            @Override public void precheck(final String url, final String collection, final String job, final String domain,
                    final String result, final String detail) throws IOException {
                final CaptureService s = service();
                if (s == null) return;
                s.precheck(URI.create(url).getHost(), collection,
                        new PrecheckResult(System.currentTimeMillis(), result, PrecheckResult.detail(detail), job, domain));
            }
        };
    }

    private static ExclusionTracker.Source errors(final Switchboard sb) {
        return max -> {
            final List<ExclusionTracker.Event> out = new ArrayList<>();
            for (final CollectionConfiguration.FailDoc doc : sb.crawlQueues.errorURL.list(max)) {
                final DigestURL url = doc.getDigestURL();
                if (url == null) continue;
                final Date at = doc.getFailDate();
                out.add(new ExclusionTracker.Event(ASCII.String(url.hash()), url.getHost(),
                        doc.getCollections() == null ? Set.of() : new HashSet<>(doc.getCollections().keySet()),
                        doc.getFailReason(), at == null ? 0 : at.getTime()));
            }
            return out;
        };
    }

    private static long clamp(final long value, final long min, final long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Scoutro crawls as seen in YaCy's crawl profiles, identified by their start marker in the ledger. */
    static final class YaCyCrawls implements CaptureService.Crawls {
        private final Switchboard sb;
        private final CrawlLedger ledger;

        YaCyCrawls(final Switchboard sb) {
            this.sb = sb;
            this.ledger = new CrawlLedger(() -> sb.getDataPath().toPath().resolve("DATA/SCOUTRO/crawls.ndjson"));
        }

        @Override public List<CrawlOutcome.Crawl> active() throws IOException {
            return crawls(this.sb.crawler.getActive(), true);
        }

        @Override public List<CrawlOutcome.Crawl> terminated() throws IOException {
            return crawls(this.sb.crawler.getPassive(), false);
        }

        @Override public CrawlOutcome.Crawl byMarker(final String marker) throws IOException {
            return crawl(marker, null);
        }

        private List<CrawlOutcome.Crawl> crawls(final Set<byte[]> handles, final boolean active) throws IOException {
            final List<CrawlOutcome.Crawl> out = new ArrayList<>();
            for (final byte[] handle : handles) {
                final CrawlProfile profile = active ? this.sb.crawler.getActive(handle) : this.sb.crawler.getPassive(handle);
                if (profile == null || CrawlSwitchboard.DEFAULT_PROFILES.contains(profile.name())) continue;
                final String marker = ScoutroActions.startMarker(profile.get(CrawlProfile.CrawlAttribute.CRAWLER_URL_MUSTNOTMATCH.key));
                if (marker == null) continue; // not started by Scoutro
                final CrawlOutcome.Crawl crawl = crawl(marker, ASCII.String(handle));
                if (crawl != null) out.add(crawl);
            }
            return out;
        }

        /** The started ledger record of a marker, optionally only if it belongs to this profile handle. */
        private CrawlOutcome.Crawl crawl(final String marker, final String handle) throws IOException {
            final CrawlLedger.Entry entry;
            try {
                entry = this.ledger.byMarker(marker);
            } catch (final ApiException unavailable) {
                throw new IOException("crawl ledger unavailable");
            }
            if (entry == null) return null;
            final CrawlRecord record = entry.record;
            if (!record.isStarted() || handle != null && !record.crawlId.equals(handle)) return null;
            try {
                return new CrawlOutcome.Crawl(record.crawlId, record.startMarker, record.host, record.collection,
                        record.createdAt, null, record.depth, record.maxPages);
            } catch (final IllegalArgumentException invalid) {
                return null;
            }
        }
    }
}
