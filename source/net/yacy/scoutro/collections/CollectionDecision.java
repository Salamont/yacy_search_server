/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.collections;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One durable collection decision: which collections the pages of a scope (a registrable domain, optionally narrowed
 * to one host and a path prefix) belong to, whatever crawl writes them (docs/SCOUTRO_COLLECTION_DECISIONS.md).
 * <p>
 * {@link Mode#EXACT}: the pages are in exactly {@link #collections}; the crawl profile's collections do not count.
 * {@link Mode#TRANSFORM}: what a collections.reassign add/remove persists. A page in one of the {@link #denied}
 * collections gets them replaced by {@link #collections}; other pages keep their collections. Without denied
 * collections every page gets {@link #collections} in addition (a decided multiple assignment).
 * <p>
 * Immutable; a change is a new revision of the same id. The discovery origin is never stored here (it stays in the
 * Discovery state); {@link #origin} only refers to it.
 */
public final class CollectionDecision {

    public enum Mode { EXACT, TRANSFORM }

    public enum Status { ACTIVE, REVOKED }

    public enum Basis { MANUAL, AUTOMATIC }

    /** Where the decision's application to the existing index stands (write-ahead: saved first, then applied). */
    public enum Apply { PENDING, PARTIAL, APPLIED, NONE }

    static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final Pattern ID = Pattern.compile("kcd_[a-z0-9]{8,32}");
    static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    static final Pattern REASON = Pattern.compile("[a-z0-9_]{1,64}");
    static final int MAX_COLLECTIONS = 5;
    static final int MAX_NOTE = 500;
    static final int MAX_PATH = 200;

    public final String id;
    public final int revision;
    public final String domain;
    /** Exact host (the domain itself or a subdomain), or null for the whole domain. */
    public final String host;
    /** Path prefix starting with "/", only with a host; null for the whole host. */
    public final String pathPrefix;
    public final Mode mode;
    public final List<String> collections;
    public final List<String> denied;
    public final Status status;
    public final Basis basis;
    public final String reason;
    public final String note;
    public final JSONObject evidence;
    public final JSONObject origin;
    public final long decidedAt;
    public final String decidedBy;
    public final Apply apply;
    /** Counters of the last application run: updated, failed, skipped and the resume cursor (page id). */
    public final JSONObject applyState;

    private CollectionDecision(final Builder b) {
        this.id = b.id;
        this.revision = b.revision;
        this.domain = b.domain;
        this.host = b.host;
        this.pathPrefix = b.pathPrefix;
        this.mode = b.mode;
        this.collections = Collections.unmodifiableList(new ArrayList<>(b.collections));
        this.denied = Collections.unmodifiableList(new ArrayList<>(b.denied));
        this.status = b.status;
        this.basis = b.basis;
        this.reason = b.reason;
        this.note = b.note;
        this.evidence = copy(b.evidence);
        this.origin = copy(b.origin);
        this.decidedAt = b.decidedAt;
        this.decidedBy = b.decidedBy;
        this.apply = b.apply;
        this.applyState = copy(b.applyState);
    }

    /** A deep copy (null: empty object). */
    public static JSONObject copyOf(final JSONObject o) {
        return copy(o);
    }

    static JSONObject copy(final JSONObject o) {
        if (o == null) return new JSONObject();
        try {
            return new JSONObject(o.toString());
        } catch (final JSONException e) {
            throw new IllegalArgumentException("json", e);
        }
    }

    public boolean active() {
        return this.status == Status.ACTIVE;
    }

    /** The scope as one comparable key: domain, host, path. Two active decisions never share it. */
    public String scopeKey() {
        return this.domain + "|" + (this.host == null ? "" : this.host) + "|" + (this.pathPrefix == null ? "" : this.pathPrefix);
    }

    /** 0 domain, 1 host, 2 host and path: the most specific active decision of a URL wins. */
    public int specificity() {
        return this.pathPrefix != null ? 2 : this.host != null ? 1 : 0;
    }

    /** True if the decision covers a page of {@code host} with {@code path} (both lower case host, raw path). */
    public boolean covers(final String pageHost, final String path) {
        if (pageHost == null) return false;
        if (this.host == null) return pageHost.equals(this.domain) || pageHost.endsWith("." + this.domain);
        if (!pageHost.equals(this.host)) return false;
        if (this.pathPrefix == null) return true;
        final String p = path == null || path.isEmpty() ? "/" : path;
        return p.startsWith(this.pathPrefix);
    }

    /**
     * The collections of a page in scope, given the collections the crawl profile (or the page now) has; empty if the
     * decision leaves none (the caller then never writes the page).
     */
    public List<String> effective(final Collection<String> profile, final String fallback) {
        final Set<String> out = new LinkedHashSet<>();
        if (this.mode == Mode.EXACT) {
            out.addAll(this.collections);
        } else {
            // like collections.reassign: with denied collections only a page in one of them is moved (denied replaced by
            // collections); without, every page gets the collections in addition (a decided multiple assignment)
            final boolean moves = this.denied.isEmpty() || profile.stream().anyMatch(this.denied::contains);
            for (final String c : profile) if (!this.denied.contains(c)) out.add(c);
            if (moves) out.addAll(this.collections);
            // the fallback collection only through an explicit decision of this scope, never carried over from a profile
            if (fallback != null && !this.collections.contains(fallback)) out.remove(fallback);
        }
        return new ArrayList<>(out);
    }

    public Builder toBuilder() {
        final Builder b = new Builder();
        b.id = this.id;
        b.revision = this.revision;
        b.domain = this.domain;
        b.host = this.host;
        b.pathPrefix = this.pathPrefix;
        b.mode = this.mode;
        b.collections = new ArrayList<>(this.collections);
        b.denied = new ArrayList<>(this.denied);
        b.status = this.status;
        b.basis = this.basis;
        b.reason = this.reason;
        b.note = this.note;
        b.evidence = this.evidence;
        b.origin = this.origin;
        b.decidedAt = this.decidedAt;
        b.decidedBy = this.decidedBy;
        b.apply = this.apply;
        b.applyState = this.applyState;
        return b;
    }

    public JSONObject toJson() {
        try {
            return json();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject json() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("id", this.id);
        o.put("revision", this.revision);
        final JSONObject scope = new JSONObject();
        scope.put("domain", this.domain);
        if (this.host != null) scope.put("host", this.host);
        if (this.pathPrefix != null) scope.put("pathPrefix", this.pathPrefix);
        o.put("scope", scope);
        o.put("mode", this.mode.name().toLowerCase(Locale.ROOT));
        o.put("collections", new JSONArray(this.collections));
        o.put("denied", new JSONArray(this.denied));
        o.put("status", this.status.name().toLowerCase(Locale.ROOT));
        o.put("basis", this.basis.name().toLowerCase(Locale.ROOT));
        o.put("reason", this.reason);
        if (this.note != null) o.put("note", this.note);
        o.put("evidence", copy(this.evidence));
        o.put("origin", copy(this.origin));
        o.put("decidedAt", this.decidedAt);
        if (this.decidedBy != null) o.put("decidedBy", this.decidedBy);
        o.put("apply", this.apply.name().toLowerCase(Locale.ROOT));
        o.put("applyState", copy(this.applyState));
        return o;
    }

    /** Parses and validates a stored decision; throws IllegalArgumentException for anything malformed. */
    public static CollectionDecision fromJson(final JSONObject o) {
        try {
            return parse(o);
        } catch (final JSONException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static CollectionDecision parse(final JSONObject o) throws JSONException {
        final Builder b = new Builder();
        b.id = o.getString("id");
        b.revision = o.getInt("revision");
        final JSONObject scope = o.getJSONObject("scope");
        b.domain = scope.getString("domain");
        b.host = scope.has("host") ? scope.getString("host") : null;
        b.pathPrefix = scope.has("pathPrefix") ? scope.getString("pathPrefix") : null;
        b.mode = Mode.valueOf(o.getString("mode").toUpperCase(Locale.ROOT));
        b.collections = strings(o.getJSONArray("collections"));
        b.denied = strings(o.optJSONArray("denied"));
        b.status = Status.valueOf(o.getString("status").toUpperCase(Locale.ROOT));
        b.basis = Basis.valueOf(o.getString("basis").toUpperCase(Locale.ROOT));
        b.reason = o.getString("reason");
        b.note = o.has("note") ? o.getString("note") : null;
        b.evidence = o.optJSONObject("evidence");
        b.origin = o.optJSONObject("origin");
        b.decidedAt = o.getLong("decidedAt");
        b.decidedBy = o.has("decidedBy") ? o.getString("decidedBy") : null;
        b.apply = Apply.valueOf(o.optString("apply", "none").toUpperCase(Locale.ROOT));
        b.applyState = o.optJSONObject("applyState");
        return b.build();
    }

    private static List<String> strings(final JSONArray a) throws JSONException {
        final List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    public static final class Builder {
        public String id;
        public int revision = 1;
        public String domain;
        public String host;
        public String pathPrefix;
        public Mode mode = Mode.EXACT;
        public List<String> collections = new ArrayList<>();
        public List<String> denied = new ArrayList<>();
        public Status status = Status.ACTIVE;
        public Basis basis = Basis.MANUAL;
        public String reason;
        public String note;
        public JSONObject evidence;
        public JSONObject origin;
        public long decidedAt;
        public String decidedBy;
        public Apply apply = Apply.NONE;
        public JSONObject applyState;

        /** Validates every field; IllegalArgumentException names the first problem. */
        public CollectionDecision build() {
            if (this.id == null || !ID.matcher(this.id).matches()) throw new IllegalArgumentException("id");
            if (this.revision < 1) throw new IllegalArgumentException("revision");
            if (this.domain == null || !HOST.matcher(this.domain).matches()) throw new IllegalArgumentException("domain");
            if (this.host != null && (!HOST.matcher(this.host).matches()
                    || !(this.host.equals(this.domain) || this.host.endsWith("." + this.domain)))) {
                throw new IllegalArgumentException("host");
            }
            if (this.pathPrefix != null && (this.host == null || !this.pathPrefix.startsWith("/") || this.pathPrefix.length() > MAX_PATH
                    || this.pathPrefix.chars().anyMatch(ch -> ch < 0x21 || ch == 0x7f))) {
                throw new IllegalArgumentException("pathPrefix");
            }
            if (this.mode == null || this.status == null || this.basis == null || this.apply == null) throw new IllegalArgumentException("mode");
            this.collections = distinct(this.collections, "collections");
            this.denied = distinct(this.denied, "denied");
            if (this.mode == Mode.EXACT && this.collections.isEmpty()) throw new IllegalArgumentException("collections");
            if (this.mode == Mode.EXACT && !this.denied.isEmpty()) throw new IllegalArgumentException("denied");
            if (this.mode == Mode.TRANSFORM && this.collections.isEmpty() && this.denied.isEmpty()) throw new IllegalArgumentException("collections");
            for (final String c : this.collections) if (this.denied.contains(c)) throw new IllegalArgumentException("denied");
            if (this.reason == null || !REASON.matcher(this.reason).matches()) throw new IllegalArgumentException("reason");
            if (this.note != null && this.note.length() > MAX_NOTE) throw new IllegalArgumentException("note");
            if (this.decidedBy != null && this.decidedBy.length() > 200) throw new IllegalArgumentException("decidedBy");
            return new CollectionDecision(this);
        }

        private static List<String> distinct(final List<String> in, final String field) {
            final Set<String> out = new LinkedHashSet<>();
            if (in != null) for (final String c : in) {
                if (c == null || !COLLECTION.matcher(c).matches()) throw new IllegalArgumentException(field);
                out.add(c);
            }
            if (out.size() > MAX_COLLECTIONS) throw new IllegalArgumentException(field);
            return new ArrayList<>(out);
        }
    }
}
