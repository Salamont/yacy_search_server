/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.search.Switchboard;

/**
 * The collections of this Scoutro instance, defined in one place (package 6.1).
 * <p>
 * A collection exists when the index holds pages of it, when it was created
 * explicitly ({@code POST /scoutro/api/v1/collections}, kept with its name and
 * description in {@code DATA/SCOUTRO/collections.json}) or when a Discovery
 * profile names it. YaCy's own collections ({@code robot_…}) are internal: never
 * offered for choice, chat, knowledge graph or crawl. Every page and API takes
 * its choices from here, so no page filters collections on its own, and a
 * collection name never has to be typed: a new one is created explicitly.
 * <p>
 * A collection can be hidden (0.10.3, {@link #hide}): it leaves every choice,
 * the dashboard and the knowledge graph, its pages stay in the index, and it can
 * be shown again. The hidden ones are kept in {@code DATA/SCOUTRO/collections-hidden.json},
 * apart from the created ones, so an older version never rewrites them.
 */
public final class CollectionCatalog {

    /** Any collection name the index may hold. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** A new collection: lower-case letters, digits and single hyphens, 2–64 characters (YaCy advises '-' over '_'). */
    public static final Pattern NEW_ID = Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,63}");
    /** Prefixes of YaCy's internal collections. */
    static final List<String> INTERNAL_PREFIXES = List.of("robot_");
    /** Names a new collection may not take: words with a meaning of their own in filters and YaCy's default crawl collection. */
    // "reassign" is a path of the collections API (POST /v1/collections/reassign), never a collection
    static final Set<String> RESERVED = Set.of("all", "none", "default", "user", "any", "new", "reassign", "decisions", "autocorrect");
    public static final int MAX_NAME = 80;
    public static final int MAX_DESCRIPTION = 500;
    /** Alphabetical regardless of case, then by code point. */
    public static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private static final long INDEX_CACHE_MS = 10_000L;
    private static volatile CollectionCatalog current;

    /** The collections of the index with their document counts. */
    public interface Index {
        Map<String, Long> counts() throws ApiException;

        /** The pages of one collection, counted exactly (the facet of {@link #counts()} may leave small ones out). */
        default long count(final String id) throws ApiException {
            return counts().getOrDefault(id, 0L);
        }

        /** The other collections of the pages of {@code id}, each with the number of pages it shares with {@code id}. */
        default Map<String, Long> sharedWith(final String id) throws ApiException {
            throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was deleted.");
        }

        /** Deletes the pages of {@code id} that belong to none of {@code others}; returns how many there were. */
        default long deleteOnly(final String id, final Collection<String> others) throws ApiException {
            throw new ApiException(503, "index_unavailable", "The index cannot be changed; nothing was deleted.");
        }
    }

    /** One collection as the catalog knows it. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String description;
        public final long documents;
        public final boolean indexed;
        public final boolean created;
        public final boolean profile;
        public final String createdAt;
        /** Hidden (0.10.3): in no choice and not on the dashboard; its pages stay in the index. */
        public final boolean hidden;

        Entry(final String id, final String name, final String description, final long documents, final boolean indexed,
                final boolean created, final boolean profile, final String createdAt) {
            this(id, name, description, documents, indexed, created, profile, createdAt, false);
        }

        Entry(final String id, final String name, final String description, final long documents, final boolean indexed,
                final boolean created, final boolean profile, final String createdAt, final boolean hidden) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.description = description == null ? "" : description;
            this.documents = documents;
            this.indexed = indexed;
            this.created = created;
            this.profile = profile;
            this.createdAt = createdAt;
            this.hidden = hidden;
        }

        /** YaCy's own collection: never a choice. */
        public boolean internal() {
            return isInternal(this.id);
        }

        /** Offered in every collection choice, as a chat scope, for the knowledge graph and as a crawl target; never when hidden. */
        public boolean selectable() {
            return !internal() && !this.hidden;
        }

        public JSONObject json() {
            final JSONArray sources = new JSONArray();
            if (this.indexed) sources.put("index");
            if (this.created) sources.put("created");
            if (this.profile) sources.put("profile");
            final boolean use = selectable();
            // the fallback collection (sonstige) takes corrected pages through collection decisions only, never a new crawl
            final boolean fallback = this.id.equals(fallbackName());
            final JSONObject o = Json.obj("id", this.id, "name", this.name, "description", this.description, "documents", this.documents,
                    "internal", internal(), "selectable", use, "chat", use, "knowledgeGraph", use, "crawlTarget", use && !fallback,
                    "sources", sources, "hidden", this.hidden, "role", fallback ? "fallback" : "regular");
            Json.put(o, "createdAt", this.createdAt);
            return o;
        }
    }

    private final Path registry;
    private final Index index;
    private final Supplier<? extends Collection<String>> profiles;
    private final Map<String, JSONObject> memory = new LinkedHashMap<>(); // without a registry file (tests)
    private Map<String, JSONObject> loaded = Collections.emptyMap();
    private long loadedModified = -1L;
    private long loadedSize = -1L;
    private volatile Map<String, Long> indexCache;
    private volatile long indexCachedAt;
    private final Path hiddenFile;
    private final Map<String, JSONObject> hiddenMemory = new LinkedHashMap<>(); // without a registry file (tests)
    private Map<String, JSONObject> hiddenLoaded = Collections.emptyMap();
    private long hiddenModified = -1L;
    private long hiddenSize = -1L;

    /**
     * @param registry the file of the created collections; null keeps them in memory
     * @param index    the collections of the index
     * @param profiles the collections of the Discovery profiles
     */
    public CollectionCatalog(final Path registry, final Index index, final Supplier<? extends Collection<String>> profiles) {
        this.registry = registry;
        this.index = index;
        this.profiles = profiles == null ? List::of : profiles;
        this.hiddenFile = registry == null ? null : registry.resolveSibling("collections-hidden.json");
    }

    /** The catalog of the running peer: index by loopback, registry in DATA/SCOUTRO, Discovery profiles. */
    public static CollectionCatalog current() {
        CollectionCatalog c = current;
        if (c == null) {
            synchronized (CollectionCatalog.class) {
                c = current;
                if (c == null) {
                    final Switchboard sb = Switchboard.getSwitchboard();
                    c = new CollectionCatalog(sb == null ? null : registryPath(sb.getDataPath().toPath()),
                            new Index() {
                                @Override public Map<String, Long> counts() throws ApiException { return loopbackCounts(); }
                                @Override public long count(final String id) throws ApiException { return loopbackCount(id); }
                                @Override public Map<String, Long> sharedWith(final String id) throws ApiException { return loopbackShared(id); }
                                @Override public long deleteOnly(final String id, final Collection<String> others) throws ApiException {
                                    return embeddedDeleteOnly(id, others);
                                }
                            }, AgentAdmin::profilePrimaryCollections);
                    current = c;
                }
            }
        }
        return c;
    }

    /** {@code DATA/SCOUTRO/collections.json} below the peer's data path. */
    public static Path registryPath(final Path dataPath) {
        return dataPath.resolve("DATA").resolve("SCOUTRO").resolve("collections.json");
    }

    /** The name of the fallback collection ({@code scoutro.collections.fallback}, default sonstige). */
    public static String fallbackName() {
        final net.yacy.scoutro.collections.CollectionDecisions d = net.yacy.scoutro.collections.CollectionDecisions.current();
        if (d != null) return d.fallback();
        return net.yacy.scoutro.collections.CollectionDecisions.DEFAULT_FALLBACK;
    }

    public static boolean isInternal(final String id) {
        if (id == null) return false;
        for (final String prefix : INTERNAL_PREFIXES) {
            if (id.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Every collection, sorted. {@code strict}: an unreadable index is an error
     * (the API); otherwise the created and profile collections still count (pages).
     */
    public List<Entry> entries(final boolean strict) throws ApiException {
        Map<String, Long> counts;
        try {
            counts = indexCounts();
        } catch (final ApiException | RuntimeException e) {
            if (strict) throw e;
            counts = Map.of();
        }
        return entries(counts);
    }

    private List<Entry> entries(final Map<String, Long> counts) {
        final Map<String, JSONObject> created = created();
        final Map<String, JSONObject> hidden = hidden();
        final Set<String> fromProfiles = new java.util.HashSet<>();
        for (final String p : this.profiles.get()) {
            if (p != null && ID.matcher(p).matches()) fromProfiles.add(p);
        }
        final TreeMap<String, Entry> out = new TreeMap<>(ORDER);
        final Set<String> ids = new java.util.HashSet<>(counts.keySet());
        ids.addAll(created.keySet());
        ids.addAll(fromProfiles);
        ids.addAll(hidden.keySet()); // a hidden one stays listed until it is shown again or removed
        for (final String id : ids) {
            if (!ID.matcher(id).matches()) continue;
            final JSONObject c = created.get(id);
            final Long n = counts.get(id);
            out.put(id, new Entry(id, c == null ? null : c.optString("name", null), c == null ? null : c.optString("description", null),
                    n == null ? 0L : n, n != null, c != null, fromProfiles.contains(id), c == null ? null : c.optString("createdAt", null),
                    hidden.containsKey(id) && !isInternal(id)));
        }
        return new ArrayList<>(out.values());
    }

    /** The ids every choice offers (not internal), sorted. */
    public List<String> selectable() {
        final List<String> ids = new ArrayList<>();
        try {
            for (final Entry e : entries(false)) {
                if (e.selectable()) ids.add(e.id);
            }
        } catch (final ApiException e) {
            // entries(false) does not throw for the index
        }
        return ids;
    }

    /**
     * Whether a collection exists and may be chosen; an unreadable index is an
     * error rather than "unknown", so a collection of the index is never refused
     * for a passing Solr failure.
     */
    public boolean known(final String id) throws ApiException {
        if (id == null) return false;
        for (final Entry e : entries(true)) {
            if (e.id.equals(id)) return e.selectable();
        }
        return false;
    }

    /**
     * Creates a collection: validated, never over an existing one (also not one
     * differing only in case), never internal or reserved; kept with its name and
     * description. It exists from now on even before the index holds a page of it.
     */
    public Entry create(final JSONObject body) throws ApiException {
        for (final String key : body.keySet()) {
            if (!List.of("id", "name", "description").contains(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        final String name = text(body, "name", MAX_NAME, true);
        final String description = text(body, "description", MAX_DESCRIPTION, false);
        final Object rawId = body.opt("id");
        final String id = rawId instanceof String && !((String) rawId).trim().isEmpty() ? ((String) rawId).trim() : suggest(name);
        if (isInternal(id) || RESERVED.contains(id.toLowerCase(Locale.ROOT))) {
            throw new ApiException(400, "collection_reserved", "This collection name is reserved for Scoutro or YaCy; choose another one.",
                    Json.obj("field", "id"));
        }
        if (!NEW_ID.matcher(id).matches()) {
            throw new ApiException(400, "collection_id_invalid",
                    "Use 2–64 lower-case letters, digits and single hyphens, starting and ending with a letter or digit.", Json.obj("field", "id"));
        }
        synchronized (this) {
            for (final Entry e : entries(true)) {
                if (e.id.equalsIgnoreCase(id) && e.hidden) {
                    throw new ApiException(409, "collection_hidden", "A hidden collection has this name; show it again instead.",
                            Json.obj("field", "id", "collection", e.id));
                }
                if (e.id.equalsIgnoreCase(id)) {
                    throw new ApiException(409, "collection_exists", "A collection with this name exists already; choose it from the list.",
                            Json.obj("field", "id"));
                }
            }
            final JSONObject record = Json.obj("id", id, "name", name, "description", description, "createdAt", Instant.now().toString());
            final Map<String, JSONObject> next = new LinkedHashMap<>(created());
            next.put(id, record);
            store(next);
            for (final Entry e : entries(false)) {
                if (e.id.equals(id)) return e;
            }
            throw new ApiException(500, "collection_not_stored", "The collection could not be stored.");
        }
    }

    /** What still names a collection explicitly, as kind → count (e.g. {@code accounts → 2}); empty when nothing does. */
    public interface Uses {
        Map<String, Integer> of(String id) throws ApiException;
    }

    /**
     * Removes a collection from the list: only one created here ({@link #create}) that holds no page in the index,
     * no Discovery profile names and nothing else names explicitly ({@code uses}). Nothing in the index, the
     * knowledge graph or any other store is deleted or changed; only its entry with name and description goes.
     * The page count is read anew from the index, not from the short-lived cache; an unreadable index refuses.
     * A collection removed here can be created again under the same id.
     */
    public Entry remove(final String id, final Uses uses) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        synchronized (this) {
            final Map<String, Long> counts;
            try {
                counts = Collections.unmodifiableMap(new LinkedHashMap<>(this.index.counts()));
            } catch (final RuntimeException e) {
                throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was removed.");
            }
            this.indexCache = counts;
            this.indexCachedAt = System.currentTimeMillis();
            Entry found = null;
            for (final Entry e : entries(counts)) {
                if (e.id.equals(id)) found = e;
            }
            if (found == null || found.internal()) {
                throw new ApiException(404, "collection_not_found", "This collection does not exist.", Json.obj("field", "id"));
            }
            // the facet lists at most 500 collections: the pages of this one are counted exactly before anything changes
            final long documents;
            try {
                documents = found.documents > 0 ? found.documents : this.index.count(id);
            } catch (final RuntimeException e) {
                throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was removed.");
            }
            if (documents > 0) {
                throw new ApiException(409, "collection_has_documents",
                        "The index holds pages of this collection; hide it or delete its pages instead.", Json.obj("documents", documents));
            }
            if (found.profile) {
                throw new ApiException(409, "collection_from_profile", "A Discovery profile names this collection; change the profile first.");
            }
            if (!found.created && !found.hidden) {
                throw new ApiException(409, "collection_not_created", "Only a collection created in Scoutro can be removed.");
            }
            final Map<String, Integer> using = uses == null ? Map.of() : uses.of(id);
            if (!using.isEmpty()) {
                throw new ApiException(409, "collection_in_use", "Still in use; remove it there first.", Json.obj("uses", new JSONObject(using)));
            }
            forget(id);
            return found;
        }
    }

    /** Takes a collection without pages out of both lists: the created one and the hidden one. */
    private void forget(final String id) throws ApiException {
        if (created().containsKey(id)) {
            final Map<String, JSONObject> next = new LinkedHashMap<>(created());
            next.remove(id);
            store(next);
        }
        if (hidden().containsKey(id)) {
            final Map<String, JSONObject> next = new LinkedHashMap<>(hidden());
            next.remove(id);
            storeHidden(next);
        }
    }

    /** The result of {@link #hide} and {@link #deletePages}. */
    public static final class Outcome {
        public final Entry collection;
        /** Gone from both lists: no page of it is left. */
        public final boolean removed;
        /** Hidden: pages of it are left in the index. */
        public final boolean hidden;
        public final long deleted;
        public final long kept;
        /** The other collections of the pages that were kept, with how many pages each shares. */
        public final Map<String, Long> sharedWith;

        Outcome(final Entry collection, final boolean removed, final boolean hidden, final long deleted, final long kept,
                final Map<String, Long> sharedWith) {
            this.collection = collection;
            this.removed = removed;
            this.hidden = hidden;
            this.deleted = deleted;
            this.kept = kept;
            this.sharedWith = sharedWith;
        }

        public JSONObject json() {
            return Json.obj("collection", this.collection.json(), "removed", this.removed, "hidden", this.hidden, "deletedPages", this.deleted,
                    "keptPages", this.kept, "sharedWith", new JSONObject(this.sharedWith));
        }
    }

    /**
     * The collection to hide or to empty: it exists, is not YaCy's own, no Discovery profile names it (a profile would
     * crawl into it again) and nothing names it explicitly ({@code uses}). Read anew from the index.
     */
    private Entry changeable(final String id, final Uses uses) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        final Map<String, Long> counts;
        try {
            counts = Collections.unmodifiableMap(new LinkedHashMap<>(this.index.counts()));
        } catch (final RuntimeException e) {
            throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was changed.");
        }
        this.indexCache = counts;
        this.indexCachedAt = System.currentTimeMillis();
        Entry found = null;
        for (final Entry e : entries(counts)) {
            if (e.id.equals(id)) found = e;
        }
        if (found == null || found.internal()) {
            throw new ApiException(404, "collection_not_found", "This collection does not exist.", Json.obj("field", "id"));
        }
        if (found.profile) {
            throw new ApiException(409, "collection_from_profile", "A Discovery profile names this collection; change the profile first.");
        }
        final Map<String, Integer> using = uses == null ? Map.of() : uses.of(id);
        if (!using.isEmpty()) {
            throw new ApiException(409, "collection_in_use", "Still in use; remove it there first.", Json.obj("uses", new JSONObject(using)));
        }
        return found;
    }

    private long exactCount(final Entry e) throws ApiException {
        try {
            return e.documents > 0 ? e.documents : this.index.count(e.id);
        } catch (final RuntimeException x) {
            throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was changed.");
        }
    }

    /**
     * Hides a collection (0.10.3): it leaves every choice and the dashboard; its pages, crawl profiles and graph data
     * stay. Hiding one that is hidden already changes nothing. Shown again with {@link #show}.
     */
    public Outcome hide(final String id, final Uses uses) throws ApiException {
        synchronized (this) {
            final Entry found = changeable(id, uses);
            final long documents = exactCount(found);
            if (!found.hidden) {
                final Map<String, JSONObject> next = new LinkedHashMap<>(hidden());
                next.put(id, Json.obj("id", id, "hiddenAt", Instant.now().toString(), "graphSwitchedOff", false));
                storeHidden(next);
            }
            return new Outcome(entry(id, found), false, true, 0L, documents, Map.of());
        }
    }

    /**
     * Deletes the pages of a collection from the index (0.10.3), confirmed by its id: only the pages that belong to no
     * other collection, so no other collection loses a page. Without pages left the collection goes from both lists;
     * with shared pages left it is hidden. Irreversible; the knowledge graph drops the deleted pages through its sync.
     */
    public Outcome deletePages(final String id, final String confirm, final Uses uses) throws ApiException {
        return deletePages(id, confirm, uses, 0);
    }

    /**
     * {@link #deletePages(String, String, Uses)} for a collection that {@code decisions} active collection decisions name:
     * refused before anything is deleted when no page of another collection would keep it in the lists (the decisions'
     * pages would have no collection); with shared pages it is hidden as usual.
     */
    public Outcome deletePages(final String id, final String confirm, final Uses uses, final int decisions) throws ApiException {
        synchronized (this) {
            final Entry found = changeable(id, uses);
            if (!id.equals(confirm)) {
                throw new ApiException(400, "confirm_mismatch", "Type the collection id exactly to confirm the deletion.", Json.obj("field", "confirm"));
            }
            final Map<String, Long> shared = new TreeMap<>(ORDER);
            long deleted = 0L;
            long kept;
            try {
                if (exactCount(found) > 0) {
                    shared.putAll(this.index.sharedWith(id));
                    shared.remove(id);
                }
            } catch (final RuntimeException e) {
                throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was deleted.");
            }
            if (decisions > 0 && shared.isEmpty()) {
                throw new ApiException(409, "collection_in_use", "Collection decisions put pages into this collection; deleting all its pages"
                        + " would take it from the lists. Revoke the decisions first (POST /v1/collections/decisions/revoke); nothing was deleted.",
                        Json.obj("uses", Json.obj("decisions", decisions)));
            }
            try {
                if (exactCount(found) > 0) {
                    deleted = this.index.deleteOnly(id, shared.keySet());
                }
                kept = this.index.count(id);
            } catch (final RuntimeException e) {
                throw new ApiException(503, "index_unavailable", "The index cannot be changed; check the pages of the collection.");
            }
            this.indexCache = null;
            if (kept > 0) {
                if (!found.hidden) {
                    final Map<String, JSONObject> next = new LinkedHashMap<>(hidden());
                    next.put(id, Json.obj("id", id, "hiddenAt", Instant.now().toString(), "graphSwitchedOff", false));
                    storeHidden(next);
                }
                return new Outcome(entry(id, found), false, true, deleted, kept, shared);
            }
            forget(id);
            return new Outcome(found, true, false, deleted, 0L, Map.of());
        }
    }

    /** The hidden record of a collection (0.10.3); null if it is not hidden. */
    public JSONObject hiddenRecord(final String id) {
        final JSONObject r = hidden().get(id);
        return r == null ? null : copy(r);
    }

    private static JSONObject copy(final JSONObject o) {
        final JSONObject out = new JSONObject();
        for (final String k : o.keySet()) Json.put(out, k, o.opt(k));
        return out;
    }

    /** Notes that hiding switched the knowledge graph off for the collection, so that showing it switches it on again. */
    public void hiddenSwitchedGraphOff(final String id) throws ApiException {
        synchronized (this) {
            final JSONObject r = hidden().get(id);
            if (r == null) return;
            final Map<String, JSONObject> next = new LinkedHashMap<>(hidden());
            next.put(id, Json.put(copy(r), "graphSwitchedOff", true));
            storeHidden(next);
        }
    }

    /** Shows a hidden collection again; returns its former record (with {@code graphSwitchedOff}). */
    public JSONObject show(final String id) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        synchronized (this) {
            final JSONObject r = hidden().get(id);
            if (r == null) {
                throw new ApiException(404, "collection_not_hidden", "This collection is not hidden.", Json.obj("field", "id"));
            }
            final Map<String, JSONObject> next = new LinkedHashMap<>(hidden());
            next.remove(id);
            storeHidden(next);
            return copy(r);
        }
    }

    /** The entry of {@code id} as it is now (after a change), else {@code fallback}. */
    private Entry entry(final String id, final Entry fallback) {
        try {
            for (final Entry e : entries(false)) {
                if (e.id.equals(id)) return e;
            }
        } catch (final ApiException e) {
            // entries(false) does not throw for the index
        }
        return fallback;
    }

    /**
     * An id suggestion for a display name: lower case, umlauts and accents
     * written out, every other character a hyphen ("Mein neues Portal" →
     * "mein-neues-portal"). Only a suggestion; the server validates the id.
     */
    public static String suggest(final String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 64) s = s.substring(0, 64).replaceAll("-+$", "");
        return s;
    }

    // ------------------------------------------------------------------ index

    private Map<String, Long> indexCounts() throws ApiException {
        final long now = System.currentTimeMillis();
        Map<String, Long> cached = this.indexCache;
        if (cached == null || now - this.indexCachedAt > INDEX_CACHE_MS) {
            cached = Collections.unmodifiableMap(new LinkedHashMap<>(this.index.counts()));
            this.indexCache = cached;
            this.indexCachedAt = now;
        }
        return cached;
    }

    /** The collection facet of the embedded Solr core, by loopback with the administrator's rights. */
    static Map<String, Long> loopbackCounts() throws ApiException {
        try {
            final String body = new YaCyLoopback().getAdmin("solr/select", new YaCyLoopback.Params()
                    .add("q", "*:*").add("rows", 0).add("wt", "json").add("facet", "true")
                    .add("facet.field", "collection_sxt").add("facet.limit", 500).add("facet.mincount", 1));
            return facetCounts(new JSONObject(body));
        } catch (final JSONException e) {
            throw new ApiException(503, "index_unavailable", "Collection catalog is unavailable.");
        }
    }

    /** The pages of one collection in the embedded Solr core, counted exactly (no facet limit). */
    static long loopbackCount(final String id) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        try {
            final String body = new YaCyLoopback().getAdmin("solr/select", new YaCyLoopback.Params()
                    .add("q", "*:*").add("fq", "collection_sxt:\"" + id + "\"").add("rows", 0).add("wt", "json"));
            final JSONObject response = new JSONObject(body).optJSONObject("response");
            if (response == null || !response.has("numFound")) throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was removed.");
            return response.getLong("numFound");
        } catch (final JSONException e) {
            throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was removed.");
        }
    }

    /** The other collections of the pages of {@code id} in the embedded Solr core, with the pages each shares. */
    static Map<String, Long> loopbackShared(final String id) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        try {
            final String body = new YaCyLoopback().getAdmin("solr/select", new YaCyLoopback.Params()
                    .add("q", "*:*").add("fq", "collection_sxt:\"" + id + "\"").add("rows", 0).add("wt", "json").add("facet", "true")
                    .add("facet.field", "collection_sxt").add("facet.limit", -1).add("facet.mincount", 1));
            final Map<String, Long> out = new LinkedHashMap<>(facetCounts(new JSONObject(body)));
            out.remove(id);
            return out;
        } catch (final JSONException e) {
            throw new ApiException(503, "index_unavailable", "The index cannot be read; nothing was deleted.");
        }
    }

    /** The query of the pages of {@code id} that belong to none of {@code others}. */
    static String onlyQuery(final String id, final Collection<String> others) {
        final StringBuilder q = new StringBuilder("collection_sxt:\"").append(id).append('"');
        for (final String o : others) {
            if (ID.matcher(o).matches() && !o.equals(id)) q.append(" AND -collection_sxt:\"").append(o).append('"');
        }
        return q.toString();
    }

    /**
     * Deletes the pages of {@code id} that belong to none of {@code others} from the embedded index (and their webgraph
     * edges), as YaCy's own deletion by collection does, and commits. The knowledge graph sees the deletion by query.
     */
    static long embeddedDeleteOnly(final String id, final Collection<String> others) throws ApiException {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.invalid("id", "Unknown collection id.");
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultConnector() == null) {
            throw new ApiException(503, "index_unavailable", "The local index is unavailable; nothing was deleted.");
        }
        final String query = onlyQuery(id, others);
        try {
            final net.yacy.cora.federate.solr.connector.SolrConnector pages = sb.index.fulltext().getDefaultConnector();
            final long n = pages.getCountByQuery(query);
            if (n > 0) {
                pages.deleteByQuery(query);
                pages.commit(true);
                if (sb.index.fulltext().useWebgraph()) {
                    final net.yacy.cora.federate.solr.connector.SolrConnector edges = sb.index.fulltext().getWebgraphConnector();
                    if (edges != null) {
                        edges.deleteByQuery(query);
                        edges.commit(true);
                    }
                }
                sb.index.fulltext().clearCaches();
                try {
                    sb.index.loadTimeIndex().clear();
                } catch (final IOException e) {
                    // only a cache of load times
                }
            }
            return n;
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be changed completely; check the pages of the collection.");
        }
    }

    /** The counts of a Solr response with a {@code collection_sxt} facet. */
    static Map<String, Long> facetCounts(final JSONObject solr) throws ApiException {
        final JSONObject counts = solr.optJSONObject("facet_counts");
        final JSONObject fields = counts == null ? null : counts.optJSONObject("facet_fields");
        final JSONArray facets = fields == null ? null : fields.optJSONArray("collection_sxt");
        if (facets == null) throw new ApiException(503, "index_unavailable", "Collection catalog is unavailable.");
        final Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < facets.length(); i += 2) {
            final String id = facets.optString(i);
            if (ID.matcher(id).matches()) out.put(id, facets.optLong(i + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ registry

    private synchronized Map<String, JSONObject> created() {
        if (this.registry == null) return new LinkedHashMap<>(this.memory);
        try {
            if (!Files.exists(this.registry)) {
                this.loaded = Collections.emptyMap();
                this.loadedModified = -1L;
                this.loadedSize = -1L;
                return this.loaded;
            }
            final long modified = Files.getLastModifiedTime(this.registry).toMillis();
            final long size = Files.size(this.registry);
            if (modified != this.loadedModified || size != this.loadedSize) {
                final Map<String, JSONObject> out = new LinkedHashMap<>();
                final JSONArray list = new JSONObject(new String(Files.readAllBytes(this.registry), StandardCharsets.UTF_8))
                        .optJSONArray("collections");
                for (int i = 0; list != null && i < list.length(); i++) {
                    final JSONObject c = list.optJSONObject(i);
                    if (c != null && ID.matcher(c.optString("id")).matches()) out.put(c.optString("id"), c);
                }
                this.loaded = Collections.unmodifiableMap(out);
                this.loadedModified = modified;
                this.loadedSize = size;
            }
            return this.loaded;
        } catch (final IOException | JSONException e) {
            return this.loaded; // a damaged file is never overwritten silently: create() fails on store()
        }
    }

    private synchronized void store(final Map<String, JSONObject> next) throws ApiException {
        if (this.registry == null) {
            this.memory.clear();
            this.memory.putAll(next);
            return;
        }
        final JSONArray list = new JSONArray();
        for (final JSONObject c : next.values()) list.put(c);
        try {
            final byte[] content = Json.obj("schema", "scoutro.collections.v1", "collections", list).toString(2).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(this.registry.getParent());
            if (Files.exists(this.registry)) {
                new JSONObject(new String(Files.readAllBytes(this.registry), StandardCharsets.UTF_8)); // refuse to replace a damaged file
            }
            final Path tmp = this.registry.resolveSibling(this.registry.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, this.registry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException | JSONException e) {
            throw new ApiException(503, "collection_store_unavailable", "The collection list cannot be written.");
        }
        this.loadedModified = -1L; // read back at the next access
    }

    // ------------------------------------------------------------------ hidden (0.10.3)

    private synchronized Map<String, JSONObject> hidden() {
        if (this.hiddenFile == null) return new LinkedHashMap<>(this.hiddenMemory);
        try {
            if (!Files.exists(this.hiddenFile)) {
                this.hiddenLoaded = Collections.emptyMap();
                this.hiddenModified = -1L;
                this.hiddenSize = -1L;
                return this.hiddenLoaded;
            }
            final long modified = Files.getLastModifiedTime(this.hiddenFile).toMillis();
            final long size = Files.size(this.hiddenFile);
            if (modified != this.hiddenModified || size != this.hiddenSize) {
                final Map<String, JSONObject> out = new LinkedHashMap<>();
                final JSONArray list = new JSONObject(new String(Files.readAllBytes(this.hiddenFile), StandardCharsets.UTF_8)).optJSONArray("hidden");
                for (int i = 0; list != null && i < list.length(); i++) {
                    final JSONObject c = list.optJSONObject(i);
                    if (c != null && ID.matcher(c.optString("id")).matches()) out.put(c.optString("id"), c);
                }
                this.hiddenLoaded = Collections.unmodifiableMap(out);
                this.hiddenModified = modified;
                this.hiddenSize = size;
            }
            return this.hiddenLoaded;
        } catch (final IOException | JSONException e) {
            return this.hiddenLoaded; // a damaged file is never overwritten silently: storeHidden() fails
        }
    }

    private synchronized void storeHidden(final Map<String, JSONObject> next) throws ApiException {
        if (this.hiddenFile == null) {
            this.hiddenMemory.clear();
            this.hiddenMemory.putAll(next);
            return;
        }
        final JSONArray list = new JSONArray();
        for (final JSONObject c : next.values()) list.put(c);
        try {
            final byte[] content = Json.obj("schema", "scoutro.collections.hidden.v1", "hidden", list).toString(2).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(this.hiddenFile.getParent());
            if (Files.exists(this.hiddenFile)) {
                new JSONObject(new String(Files.readAllBytes(this.hiddenFile), StandardCharsets.UTF_8)); // refuse to replace a damaged file
            }
            final Path tmp = this.hiddenFile.resolveSibling(this.hiddenFile.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, this.hiddenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException | JSONException e) {
            throw new ApiException(503, "collection_store_unavailable", "The collection list cannot be written.");
        }
        this.hiddenModified = -1L; // read back at the next access
    }

    private static String text(final JSONObject body, final String field, final int max, final boolean required) throws ApiException {
        final Object raw = body.opt(field);
        if (raw == null || raw == JSONObject.NULL) {
            if (required) throw ApiException.invalid(field, "A name is required.");
            return "";
        }
        if (!(raw instanceof String)) throw ApiException.invalid(field, "Use text.");
        final String s = ((String) raw).replaceAll("\\s+", " ").trim();
        if (required && s.isEmpty()) throw ApiException.invalid(field, "A name is required.");
        if (s.length() > max) throw ApiException.invalid(field, "At most " + max + " characters.");
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) throw ApiException.invalid(field, "No control characters.");
        }
        return s;
    }
}
