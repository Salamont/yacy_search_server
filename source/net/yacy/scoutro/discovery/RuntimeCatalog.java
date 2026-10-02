/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.api.ApiException;

/** Validated, immutable-by-copy snapshot. No source-tree fallback, geo mapping or portal list. */
public final class RuntimeCatalog {
    public static final List<String> FILES = List.of("profiles.json", "profiles.conf", "osm_profiles.json",
            "regions.txt", "osm_regions.txt");
    public static final Set<String> SOURCES = Set.of("osm", "freeworld");
    private final Map<String, byte[]> contents;
    private final JsonObject catalog;
    private final JsonObject profiles;

    private RuntimeCatalog(final Map<String, byte[]> contents) throws Exception {
        this.contents = contents;
        this.profiles = object("profiles.json").getJSONObject("profiles");
        final JsonObject osm = object("osm_profiles.json");
        final JsonObject osmProfiles = osm.optJSONObject("profiles");
        final Map<String, List<String>> terms = new LinkedHashMap<>();
        for (final String line : text("profiles.conf").split("\\R")) {
            if (line.strip().startsWith("#") || !line.contains("=")) continue;
            final String[] kv = line.split("=", 2);
            terms.put(kv[0].strip(), List.of(kv[1].split(";")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        final JsonArray entries = new JsonArray();
        for (final String id : this.profiles.keySet().stream().sorted().toList()) {
            final JsonObject profile = this.profiles.getJSONObject(id);
            if (!id.matches("[a-z][a-z0-9_-]{0,31}") || !profile.optString("collection").matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IOException("Invalid profile identity or explicit collection");
            }
            final JsonObject rule = osmProfiles == null ? null : osmProfiles.optJSONObject(id);
            final boolean osmSupported = !osm.optString("extract").isBlank() && usableRule(rule);
            entries.put(new JsonObject().put("id", id).put("collection", profile.getString("collection"))
                    .put("sources", new JsonObject().put("osm", osmSupported)
                            .put("freeworld", !terms.getOrDefault(id, List.of()).isEmpty())));
        }
        final JsonArray sourceEntries = new JsonArray();
        final JsonObject regions = new JsonObject();
        sourceEntries.put(source("osm", "OpenStreetMap / Geofabrik", "extract", "osm_regions.txt", regions));
        sourceEntries.put(source("freeworld", "YaCy Network Search", "search_text", "regions.txt", regions));
        final MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (final String file : FILES) {
            hash.update(file.getBytes(StandardCharsets.UTF_8));
            final byte[] bytes = contents.get(file);
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) hash.update(bytes);
        }
        this.catalog = new JsonObject().put("revision", java.util.HexFormat.of().formatHex(hash.digest()))
                .put("profiles", entries).put("sources", sourceEntries).put("regions", regions);
    }

    private static boolean usableRule(final JsonObject rule) {
        if (rule == null) return false;
        final JsonArray text = rule.optJSONArray("text_any");
        if (text != null) for (final Object word : text) if (word instanceof String && !((String) word).isBlank()) return true;
        final JsonArray tags = rule.optJSONArray("tags");
        if (tags != null) for (final Object tag : tags) {
            if (tag instanceof JsonObject && !((JsonObject) tag).isEmpty()) {
                for (final String key : ((JsonObject) tag).keySet()) {
                    final JsonArray values = ((JsonObject) tag).optJSONArray(key);
                    if (!key.startsWith("_") && values != null && !values.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private JsonObject source(final String id, final String display, final String type, final String file,
            final JsonObject regions) throws IOException {
        final JsonArray values = new JsonArray();
        final JsonArray labels = new JsonArray();
        for (final String value : text(file).lines().map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#")).distinct().toList()) {
            if (value.length() > 120 || value.chars().anyMatch(Character::isISOControl)
                    || ("osm".equals(id) && !value.matches("[a-z0-9][a-z0-9-]{0,99}"))) {
                throw new IOException("Invalid source region");
            }
            values.put(value);
            final String label = "osm".equals(id) ? String.join("-", java.util.Arrays.stream(value.split("-"))
                    .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).toList()) : value;
            labels.put(new JsonObject().put("id", value).put("label", label));
        }
        if (values.length() > 10000) throw new IOException("Too many source regions");
        regions.put(id, values);
        return new JsonObject().put("id", id).put("display_name", display).put("region_type", type)
                .put("regions", labels).put("capabilities", new JsonObject().put("mode_all", true));
    }

    private String text(final String file) { return new String(this.contents.getOrDefault(file, new byte[0]), StandardCharsets.UTF_8); }
    private JsonObject object(final String file) { final String s = text(file); return s.isBlank() ? new JsonObject() : new JsonObject(s); }

    public static RuntimeCatalog load(final Path root) throws ApiException {
        try {
            final Map<String, byte[]> first = read(root);
            final Map<String, byte[]> second = read(root);
            for (final String file : FILES) if (!java.util.Arrays.equals(first.get(file), second.get(file))) {
                throw new IOException("Config changed while reading");
            }
            if (!first.containsKey("profiles.json")) throw new IOException("Missing canonical catalog");
            return new RuntimeCatalog(first);
        } catch (final Exception e) {
            throw new ApiException(503, "runtime_config_invalid", "Runtime configuration is missing, invalid or changing.");
        }
    }

    private static Map<String, byte[]> read(final Path root) throws IOException {
        final Map<String, byte[]> out = new LinkedHashMap<>();
        for (final String file : FILES) {
            final Path path = root.resolve(file);
            if (Files.exists(path)) {
                if (!Files.isRegularFile(path) || Files.size(path) > 2_000_000) throw new IOException("Invalid config file");
                out.put(file, Files.readAllBytes(path));
            }
        }
        return out;
    }

    public JsonObject json() {
        final JsonObject copy = new JsonObject(this.catalog.toString());
        for (final Object item : copy.getJSONArray("sources")) {
            final JsonObject source = (JsonObject) item;
            final JsonArray supported = new JsonArray();
            for (final Object profile : copy.getJSONArray("profiles")) {
                final JsonObject p = (JsonObject) profile;
                if (p.getJSONObject("sources").getBoolean(source.getString("id"))) supported.put(p.getString("id"));
            }
            source.put("supported_profiles", supported);
        }
        return copy;
    }
    public String revision() { return this.catalog.getString("revision"); }
    public String collection(final String id) throws ApiException {
        final JsonObject p = this.profiles.optJSONObject(id);
        if (p == null) throw new ApiException(409, "profile_removed", "The configured profile no longer exists.");
        return p.getString("collection");
    }
    public void validate(final JsonObject job) throws ApiException {
        collection(job.getString("profile"));
        final JsonObject selected = job.getJSONObject("sources");
        for (final String source : selected.keySet()) {
            JsonObject profile = null;
            for (final Object entry : this.catalog.getJSONArray("profiles")) {
                final JsonObject p = (JsonObject) entry;
                if (p.getString("id").equals(job.getString("profile"))) profile = p;
            }
            if (!SOURCES.contains(source) || profile == null || !profile.getJSONObject("sources").optBoolean(source)) {
                throw new ApiException(409, "source_unsupported", "Profile does not support the selected source.");
            }
            final JsonArray allowed = this.catalog.getJSONObject("regions").getJSONArray(source);
            if (allowed.isEmpty()) throw new ApiException(409, "regions_missing", "Source has no configured regions.");
            final Set<String> ids = new java.util.HashSet<>();
            for (final Object id : allowed) ids.add(id.toString());
            for (final Object region : selected.getJSONObject(source).getJSONArray("regions")) {
                if (!ids.contains(region)) throw new ApiException(409, "region_removed", "Job refers to an unavailable source region.");
            }
        }
    }

    public Path freeze(final Path base) throws IOException {
        final Path directory = base.resolve(revision());
        if (Files.isDirectory(directory)) {
            final Map<String, byte[]> existing = read(directory);
            for (final String file : FILES) if (!java.util.Arrays.equals(this.contents.get(file), existing.get(file))) {
                throw new IOException("Stored configuration snapshot does not match its fingerprint");
            }
            return directory;
        }
        Files.createDirectories(base);
        final Path temporary = Files.createTempDirectory(base, ".snapshot-");
        try {
            for (final String file : FILES) {
                final byte[] bytes = this.contents.get(file);
                if (bytes != null) JobStore.atomicWrite(temporary.resolve(file), bytes);
            }
            Files.move(temporary, directory, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try (var channel = java.nio.channels.FileChannel.open(base, java.nio.file.StandardOpenOption.READ)) { channel.force(true); }
        } finally {
            if (Files.exists(temporary)) {
                for (final String file : FILES) Files.deleteIfExists(temporary.resolve(file));
                Files.deleteIfExists(temporary);
            }
        }
        return directory;
    }
}
