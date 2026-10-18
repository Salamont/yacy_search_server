/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.collections;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.search.Switchboard;

/**
 * The durable collection decisions of the peer and the one place that turns them into the collections of an indexed
 * page (docs/SCOUTRO_COLLECTION_DECISIONS.md).
 * <p>
 * Every way that writes a page into the index (YaCy crawl, Scoutro and Discovery crawl starts, WorkTables replays,
 * YaCy's recrawl job, error documents) passes {@link #resolve} with the crawl profile's collections. Without a decision
 * of the page's scope they stay as they are, except that the fallback collection ({@code sonstige}) is never taken from
 * a profile: content enters it only through an explicit decision. With a decision the page gets the decision's
 * collections. If the decisions cannot be read reliably ({@link DecisionStore.Health#UNAVAILABLE}), every write is
 * refused ({@link Blocked}) and the page in the index stays as it was: a damaged file never restores a corrected
 * membership. Reads (search) are not affected.
 */
public final class CollectionDecisions {

    public static final String FALLBACK_KEY = "scoutro.collections.fallback";
    public static final String DEFAULT_FALLBACK = "sonstige";
    /** May Discovery recrawl a domain whose decision puts it into the fallback collection only (default: no). */
    public static final String FALLBACK_RECRAWL_KEY = "scoutro.collections.fallback.recrawl";
    static final Pattern CATCHALL = Pattern.compile(".*");

    /** A write that must not happen: the page stays as it is in the index. */
    public static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String code;

        Blocked(final String code, final String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** A refused crawl start or decision change (code and HTTP status for the API). */
    public static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;

        public Refusal(final int status, final String code, final String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static volatile CollectionDecisions current;

    private final DecisionStore store;
    private final String fallback;
    private final boolean fallbackRecrawl;
    private final LongSupplier clock;
    private volatile DecisionStore.State state;
    /** Active decisions by registrable domain. */
    private volatile Map<String, List<CollectionDecision>> active = Map.of();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong decided = new AtomicLong();
    private final Map<String, Long> blockedByCode = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastBlockLog;
    private static final SecureRandom RANDOM = new SecureRandom();

    public CollectionDecisions(final DecisionStore store, final String fallback, final boolean fallbackRecrawl, final LongSupplier clock) {
        this.store = store;
        this.fallback = fallback == null || fallback.isEmpty() ? DEFAULT_FALLBACK : fallback;
        this.fallbackRecrawl = fallbackRecrawl;
        this.clock = clock;
        reload();
    }

    /** The decisions of the running peer: files below DATA, loaded once; without a peer (unit tests of YaCy) none. */
    public static CollectionDecisions current() {
        CollectionDecisions c = current;
        if (c == null) {
            synchronized (CollectionDecisions.class) {
                c = current;
                if (c == null) {
                    final Switchboard sb = Switchboard.getSwitchboard();
                    if (sb == null) return null;
                    final Path data = sb.getDataPath().toPath().resolve("DATA");
                    c = new CollectionDecisions(new DecisionStore(data.resolve("SCOUTRO").resolve("collections"),
                            data.resolve("SETTINGS").resolve("scoutro-collection-decisions.marker")),
                            sb.getConfig(FALLBACK_KEY, DEFAULT_FALLBACK), sb.getConfigBool(FALLBACK_RECRAWL_KEY, false),
                            System::currentTimeMillis);
                    current = c;
                    if (c.state.health != DecisionStore.Health.OK) {
                        ConcurrentLog.warn("SCOUTRO-COLLECTIONS", "collection decisions " + c.state.health + ": " + c.state.problems);
                    }
                }
            }
        }
        return c;
    }

    /** Replaces the peer's instance (tests); null forgets it. */
    public static void install(final CollectionDecisions decisions) {
        current = decisions;
    }

    /**
     * The collections a page of {@code url} gets, given the crawl profile's {@code profile} collections (YaCy patterns).
     * Static hook for the indexer; without a peer the profile's collections are returned unchanged.
     */
    public static Map<String, Pattern> forIndexing(final String url, final String host, final String path, final Map<String, Pattern> profile) {
        final CollectionDecisions c = current();
        return c == null ? profile : c.resolve(url, host, path, profile);
    }

    public synchronized void reload() {
        apply(this.store.load());
    }

    private void apply(final DecisionStore.State s) {
        final Map<String, List<CollectionDecision>> m = new HashMap<>();
        for (final CollectionDecision d : s.decisions) {
            if (d.active()) m.computeIfAbsent(d.domain, k -> new ArrayList<>()).add(d);
        }
        for (final List<CollectionDecision> l : m.values()) l.sort((a, b) -> Integer.compare(b.specificity(), a.specificity()));
        this.active = m;
        this.state = s;
    }

    public String fallback() {
        return this.fallback;
    }

    public boolean fallbackRecrawl() {
        return this.fallbackRecrawl;
    }

    public DecisionStore.State state() {
        return this.state;
    }

    public boolean available() {
        return this.state.health != DecisionStore.Health.UNAVAILABLE;
    }

    /** The most specific active decision for a page, or null. */
    public CollectionDecision find(final String host, final String path) {
        if (host == null) return null;
        final Map<String, List<CollectionDecision>> m = this.active;
        if (m.isEmpty()) return null;
        final String h = host.toLowerCase(Locale.ROOT);
        final String domain = Normalizers.registrableDomain(h);
        final List<CollectionDecision> list = domain == null ? null : m.get(domain);
        if (list == null) return null;
        CollectionDecision best = null;
        for (final CollectionDecision d : list) {
            if (!d.covers(h, path)) continue;
            if (best == null || d.specificity() > best.specificity()
                    || d.specificity() == best.specificity() && d.pathPrefix != null && d.pathPrefix.length() > best.pathPrefix.length()) {
                best = d;
            }
        }
        return best;
    }

    /** Index hook: see the class comment. Throws {@link Blocked} if the page must not be written. */
    public Map<String, Pattern> resolve(final String url, final String host, final String path, final Map<String, Pattern> profile) {
        if (this.state.health == DecisionStore.Health.UNAVAILABLE) {
            throw block("decisions_unavailable", "collection decisions cannot be read reliably; " + url + " is not written");
        }
        final Map<String, Pattern> p = profile == null ? Map.of() : profile;
        final CollectionDecision d = this.active.isEmpty() ? null : find(host, path);
        if (d == null) {
            if (!p.containsKey(this.fallback)) return profile; // the usual case: nothing to do
            final Map<String, Pattern> rest = new LinkedHashMap<>(p);
            rest.remove(this.fallback);
            if (rest.isEmpty()) {
                throw block("fallback_without_decision", "the fallback collection " + this.fallback
                        + " takes pages only through a collection decision; " + url + " is not written");
            }
            return rest;
        }
        final List<String> matched = new ArrayList<>();
        for (final Map.Entry<String, Pattern> e : p.entrySet()) {
            if (url == null || e.getValue().matcher(url).matches()) matched.add(e.getKey());
        }
        final List<String> eff = d.effective(matched, this.fallback);
        if (eff.isEmpty()) {
            throw block("decision_empty", "the collection decision " + d.id + " leaves no collection for " + url);
        }
        this.decided.incrementAndGet();
        final Map<String, Pattern> out = new LinkedHashMap<>();
        for (final String c : eff) out.put(c, CATCHALL);
        return out;
    }

    private Blocked block(final String code, final String message) {
        this.blocked.incrementAndGet();
        this.blockedByCode.merge(code, 1L, Long::sum);
        final long now = this.clock.getAsLong();
        if (now - this.lastBlockLog > 60_000L) {
            this.lastBlockLog = now;
            ConcurrentLog.warn("SCOUTRO-COLLECTIONS", message + " (" + this.blocked.get() + " refused writes so far)");
        }
        return new Blocked(code, message);
    }

    /**
     * May a crawl of {@code host} write into {@code collection}? Called by every Scoutro crawl start
     * ({@code discovery}: started by Discovery's automation or CLI).
     */
    public void checkCrawl(final String host, final String collection, final boolean discovery) throws Refusal {
        if (!available()) {
            throw new Refusal(503, "decisions_unavailable", "Collection decisions cannot be read reliably; crawls are refused until an"
                    + " administrator restores or accepts them (GET /scoutro/api/v1/collections/decisions).");
        }
        final CollectionDecision d = find(host, "/");
        if (d == null) {
            if (this.fallback.equals(collection)) {
                throw new Refusal(409, "collection_fallback_only", "The collection " + this.fallback + " takes only corrected pages through a"
                        + " collection decision; new crawls cannot target it.");
            }
            return;
        }
        final boolean consistent = d.mode == CollectionDecision.Mode.EXACT ? d.collections.contains(collection)
                : !d.denied.contains(collection) && (!this.fallback.equals(collection) || d.collections.contains(collection));
        if (consistent) return;
        // a Discovery recrawl of a corrected domain: the decision would write it into the fallback collection only
        if (discovery && d.effective(List.of(collection), this.fallback).equals(List.of(this.fallback))) {
            if (this.fallbackRecrawl) return;
            throw new Refusal(409, "collection_decided", "The domain was moved to " + this.fallback + " by decision " + d.id
                    + "; Discovery does not crawl it again (" + FALLBACK_RECRAWL_KEY + "=false).");
        }
        throw new Refusal(409, "collection_decided", "The collection decision " + d.id + " puts " + d.domain + " into "
                + d.collections + (d.denied.isEmpty() ? "" : " and not into " + d.denied) + "; a crawl into " + collection
                + " is refused. Change the decision first.");
    }

    /** All decisions (active and revoked), newest generation. */
    public List<CollectionDecision> all() {
        return this.state.decisions;
    }

    public CollectionDecision byId(final String id) {
        for (final CollectionDecision d : this.state.decisions) if (d.id.equals(id)) return d;
        return null;
    }

    /** The active decision with exactly this scope, or null. */
    public CollectionDecision byScope(final String domain, final String host, final String pathPrefix) {
        final String key = domain + "|" + (host == null ? "" : host) + "|" + (pathPrefix == null ? "" : pathPrefix);
        for (final CollectionDecision d : this.state.decisions) if (d.active() && d.scopeKey().equals(key)) return d;
        return null;
    }

    /**
     * Stores a new decision or a new revision of one (same id): validated, at most one active decision per scope.
     * The change is durable when this returns.
     */
    public synchronized CollectionDecision put(final CollectionDecision d, final String op) throws Refusal {
        if (!available()) throw new Refusal(503, "decisions_unavailable", "Collection decisions cannot be read reliably.");
        final List<CollectionDecision> next = new ArrayList<>();
        boolean replaced = false;
        for (final CollectionDecision o : this.state.decisions) {
            if (o.id.equals(d.id)) {
                next.add(d);
                replaced = true;
            } else {
                if (d.active() && o.active() && o.scopeKey().equals(d.scopeKey())) {
                    throw new Refusal(409, "decision_exists", "Decision " + o.id + " already covers this scope; change that one.");
                }
                next.add(o);
            }
        }
        if (!replaced) next.add(d);
        try {
            apply(this.store.commit(this.state.generation, next, op, d.id, this.clock.getAsLong()));
        } catch (final IOException e) {
            // the files may be half written: read them again, the store decides what is valid
            reload();
            throw new Refusal(503, "decisions_unwritable", "The collection decision could not be stored: " + e.getMessage());
        }
        ConcurrentLog.info("SCOUTRO-COLLECTIONS", "event=collections.decision op=" + op + " id=" + d.id + " revision=" + d.revision
                + " scope=" + d.scopeKey() + " mode=" + d.mode + " collections=" + d.collections + " denied=" + d.denied + " status=" + d.status
                + " apply=" + d.apply);
        return d;
    }

    /** After an UNAVAILABLE load: accepts the readable decisions as the new state (an explicit administrator action). */
    public synchronized void recover(final String detail) throws Refusal {
        if (available()) throw new Refusal(409, "decisions_available", "The collection decisions are readable; nothing to recover.");
        try {
            apply(this.store.recover(this.state.decisions, this.state.markerGeneration, detail, this.clock.getAsLong()));
        } catch (final IOException e) {
            reload();
            throw new Refusal(503, "decisions_unwritable", "The collection decisions could not be written: " + e.getMessage());
        }
        ConcurrentLog.warn("SCOUTRO-COLLECTIONS", "event=collections.decisions.recover generation=" + this.state.generation + " " + detail);
    }

    public static String newId() {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        final StringBuilder b = new StringBuilder("kcd_");
        for (int i = 0; i < 16; i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return b.toString();
    }

    public long now() {
        return this.clock.getAsLong();
    }

    /** Store health, generation and counters (GET .../collections/decisions). */
    public JSONObject status() {
        try {
            return statusJson();
        } catch (final org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject statusJson() throws org.json.JSONException {
        final JSONObject o = this.state.toJson();
        o.put("fallback", this.fallback);
        o.put("fallbackRecrawl", this.fallbackRecrawl);
        o.put("active", this.active.values().stream().mapToInt(List::size).sum());
        o.put("refusedWrites", this.blocked.get());
        o.put("refusedWritesByReason", new JSONObject(new HashMap<>(this.blockedByCode)));
        o.put("decidedWrites", this.decided.get());
        int pending = 0;
        for (final CollectionDecision d : this.state.decisions) {
            if (d.active() && (d.apply == CollectionDecision.Apply.PENDING || d.apply == CollectionDecision.Apply.PARTIAL)) pending++;
        }
        o.put("pendingApply", pending);
        return o;
    }

    public static JSONArray toJson(final Collection<CollectionDecision> list) {
        final JSONArray a = new JSONArray();
        for (final CollectionDecision d : list) a.put(d.toJson());
        return a;
    }

    /** True if the files exist (a peer that never decided anything has none). */
    public boolean hasFiles() {
        return Files.exists(this.store.directory());
    }

    public List<CollectionDecision> activeOf(final String domain) {
        final List<CollectionDecision> l = this.active.get(domain);
        return l == null ? List.of() : Collections.unmodifiableList(l);
    }
}
