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
 */
public final class CollectionCatalog {

    /** Any collection name the index may hold. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** A new collection: lower-case letters, digits and single hyphens, 2–64 characters (YaCy advises '-' over '_'). */
    public static final Pattern NEW_ID = Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,63}");
    /** Prefixes of YaCy's internal collections. */
    static final List<String> INTERNAL_PREFIXES = List.of("robot_");
    /** Names a new collection may not take: words with a meaning of their own in filters and YaCy's default crawl collection. */
    static final Set<String> RESERVED = Set.of("all", "none", "default", "user", "any", "new");
    public static final int MAX_NAME = 80;
    public static final int MAX_DESCRIPTION = 500;
    /** Alphabetical regardless of case, then by code point. */
    public static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private static final long INDEX_CACHE_MS = 10_000L;
    private static volatile CollectionCatalog current;

    /** The collections of the index with their document counts. */
    public interface Index {
        Map<String, Long> counts() throws ApiException;
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

        Entry(final String id, final String name, final String description, final long documents, final boolean indexed,
                final boolean created, final boolean profile, final String createdAt) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.description = description == null ? "" : description;
            this.documents = documents;
            this.indexed = indexed;
            this.created = created;
            this.profile = profile;
            this.createdAt = createdAt;
        }

        /** YaCy's own collection: never a choice. */
        public boolean internal() {
            return isInternal(this.id);
        }

        /** Offered in every collection choice, as a chat scope, for the knowledge graph and as a crawl target. */
        public boolean selectable() {
            return !internal();
        }

        public JSONObject json() {
            final JSONArray sources = new JSONArray();
            if (this.indexed) sources.put("index");
            if (this.created) sources.put("created");
            if (this.profile) sources.put("profile");
            final boolean use = selectable();
            final JSONObject o = Json.obj("id", this.id, "name", this.name, "description", this.description, "documents", this.documents,
                    "internal", internal(), "selectable", use, "chat", use, "knowledgeGraph", use, "crawlTarget", use, "sources", sources);
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

    /**
     * @param registry the file of the created collections; null keeps them in memory
     * @param index    the collections of the index
     * @param profiles the collections of the Discovery profiles
     */
    public CollectionCatalog(final Path registry, final Index index, final Supplier<? extends Collection<String>> profiles) {
        this.registry = registry;
        this.index = index;
        this.profiles = profiles == null ? List::of : profiles;
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
                            CollectionCatalog::loopbackCounts, AgentAdmin::profilePrimaryCollections);
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
        final Map<String, JSONObject> created = created();
        final Set<String> fromProfiles = new java.util.HashSet<>();
        for (final String p : this.profiles.get()) {
            if (p != null && ID.matcher(p).matches()) fromProfiles.add(p);
        }
        final TreeMap<String, Entry> out = new TreeMap<>(ORDER);
        final Set<String> ids = new java.util.HashSet<>(counts.keySet());
        ids.addAll(created.keySet());
        ids.addAll(fromProfiles);
        for (final String id : ids) {
            if (!ID.matcher(id).matches()) continue;
            final JSONObject c = created.get(id);
            final Long n = counts.get(id);
            out.put(id, new Entry(id, c == null ? null : c.optString("name", null), c == null ? null : c.optString("description", null),
                    n == null ? 0L : n, n != null, c != null, fromProfiles.contains(id), c == null ? null : c.optString("createdAt", null)));
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
