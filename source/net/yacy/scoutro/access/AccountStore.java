/*
 *  AccountStore
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

package net.yacy.scoutro.access;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent Scoutro accounts in {@value #STORE_FILE} (settings directory,
 * owner-only, written atomically). Passwords are kept as Argon2id hashes
 * ({@link PasswordHasher}); nothing here is kept in yacy.conf.
 * <p>
 * Rules enforced here, independent of any page:
 * <ul>
 * <li>user names are unique (case-insensitive) and never the name of the
 * built-in administrator;</li>
 * <li>Research and Operator accounts can only be active in protected mode;</li>
 * <li>at least one administrator stays able to sign in (the last active
 * administrator account cannot be locked, demoted or removed unless the
 * built-in administrator may still sign in);</li>
 * <li>nobody locks, demotes or removes their own account.</li>
 * </ul>
 */
public final class AccountStore {

    public static final String STORE_FILE = "scoutro-users.json";
    /** Lower-case names; e-mail style names are allowed. */
    public static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._@-]{1,63}");
    public static final int MAX_DISPLAY_NAME = 80;
    public static final int MAX_COLLECTIONS = 200;

    /** Facts outside the store that some rules depend on. */
    public interface Context {
        /** True when the built-in YaCy administrator may sign in on the login page. */
        boolean builtinAdminLogin();

        /** True in protected access mode. */
        boolean protectedMode();

        /** The configured name of the built-in YaCy administrator (reserved). */
        String builtinAdminName();
    }

    /** A new account. */
    public static final class Spec {
        public String username;
        public String displayName;
        public Role role;
        public boolean allCollections;
        public List<String> collections = new ArrayList<>();
        public boolean export;
        public String password;
        public boolean mustChangePassword = true;
    }

    /** Changes to an existing account; null fields stay unchanged. */
    public static final class Patch {
        public String displayName;
        public Role role;
        public Boolean allCollections;
        public List<String> collections;
        public Boolean export;
        public Account.Status status;
    }

    /** Result of an update: the account after the change, the changed fields and whether sessions must end. */
    public static final class Change {
        public final Account account;
        public final List<String> fields;
        public final boolean endSessions;

        Change(final Account account, final List<String> fields, final boolean endSessions) {
            this.account = account;
            this.fields = fields;
            this.endSessions = endSessions;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final PasswordHasher hasher;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public AccountStore(final File settingsDir, final LongSupplier clock, final PasswordHasher hasher) throws IOException {
        if (!settingsDir.isDirectory() && !settingsDir.mkdirs()) {
            throw new IOException("cannot create " + settingsDir);
        }
        this.file = new File(settingsDir, STORE_FILE).toPath();
        this.clock = clock;
        this.hasher = hasher;
        load();
    }

    public PasswordHasher hasher() {
        return this.hasher;
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    public synchronized List<Account> list() {
        final List<Account> out = new ArrayList<>();
        for (final Account a : this.accounts.values()) {
            out.add(a.copy());
        }
        return out;
    }

    public synchronized Account get(final String username) {
        final Account a = username == null ? null : this.accounts.get(normalize(username));
        return a == null ? null : a.copy();
    }

    public synchronized boolean isEmpty() {
        return this.accounts.isEmpty();
    }

    /** True when an active Research or Operator account exists (protected mode is then enforced). */
    public synchronized boolean anyActiveRestricted() {
        for (final Account a : this.accounts.values()) {
            if (a.active() && a.role != Role.ADMINISTRATOR) {
                return true;
            }
        }
        return false;
    }

    public synchronized int activeAdministrators() {
        int n = 0;
        for (final Account a : this.accounts.values()) {
            if (a.active() && a.role == Role.ADMINISTRATOR) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // authentication
    // ------------------------------------------------------------------

    /**
     * Checks a password. Unknown names cost the same time as known ones.
     *
     * @return a copy of the account when the password matches (also for locked accounts; the caller decides), else null
     */
    public Account verifyPassword(final String username, final String password) {
        final Account a = get(username);
        if (a == null || a.passwordHash() == null || a.passwordHash().isEmpty()) {
            this.hasher.verifyDummy(password);
            return null;
        }
        return this.hasher.verify(password, a.passwordHash()) ? a : null;
    }

    public synchronized void recordLogin(final String username) {
        final Account a = this.accounts.get(normalize(username));
        if (a == null) {
            return;
        }
        a.lastLoginAt = this.clock.getAsLong();
        saveQuietly();
    }

    // ------------------------------------------------------------------
    // changes
    // ------------------------------------------------------------------

    public Account create(final Spec spec, final String actor, final Context ctx) throws AccessException, IOException {
        final String username = normalize(spec.username);
        if (!USERNAME.matcher(username).matches()) {
            throw AccessException.invalid("username",
                    "User names have 2 to 64 characters: lower-case letters, digits, '.', '_', '-' and '@', starting with a letter or digit.");
        }
        if (username.equals(normalize(ctx.builtinAdminName()))) {
            throw new AccessException(409, "username_reserved", "This name belongs to the built-in administrator.", "username");
        }
        if (spec.role == null || !spec.role.assignable()) {
            throw AccessException.invalid("role", "Choose the role research, operator or administrator.");
        }
        final List<String> collections = collections(spec.collections);
        checkScope(spec.role, spec.allCollections, collections);
        PasswordPolicy.check(username, spec.password, "password");
        final String displayName = displayName(spec.displayName);
        // hash outside the lock: Argon2id takes a noticeable moment
        final String hash = this.hasher.hash(spec.password);
        synchronized (this) {
            if (this.accounts.containsKey(username)) {
                throw new AccessException(409, "username_taken", "An account with this name already exists.", "username");
            }
            if (spec.role != Role.ADMINISTRATOR && !ctx.protectedMode()) {
                throw new AccessException(409, "protected_mode_required",
                        "Research and Operator accounts need the protected access mode. Switch it on first.");
            }
            final long now = this.clock.getAsLong();
            final Account a = new Account();
            a.id = newId();
            a.username = username;
            a.displayName = displayName;
            a.role = spec.role;
            a.allCollections = spec.role == Role.ADMINISTRATOR || spec.allCollections;
            a.collections = a.allCollections ? new ArrayList<>() : collections;
            a.export = spec.export;
            a.status = Account.Status.ACTIVE;
            a.passwordHash = hash;
            a.mustChangePassword = spec.mustChangePassword;
            a.createdAt = now;
            a.updatedAt = now;
            a.passwordChangedAt = now;
            a.createdBy = actor == null ? "" : actor;
            this.accounts.put(username, a);
            try {
                save();
            } catch (final IOException e) {
                this.accounts.remove(username);
                throw e;
            }
            return a.copy();
        }
    }

    public synchronized Change update(final String username, final Patch patch, final String actor, final Context ctx)
            throws AccessException, IOException {
        final Account current = this.accounts.get(normalize(username));
        if (current == null) {
            throw new AccessException(404, "user_not_found", "There is no account with this name.");
        }
        final Account next = current.copy();
        final List<String> fields = new ArrayList<>();
        if (patch.displayName != null) {
            final String d = displayName(patch.displayName);
            if (!d.equals(next.displayName == null ? "" : next.displayName)) {
                next.displayName = d;
                fields.add("displayName");
            }
        }
        if (patch.role != null) {
            if (!patch.role.assignable()) {
                throw AccessException.invalid("role", "Choose the role research, operator or administrator.");
            }
            if (patch.role != next.role) {
                next.role = patch.role;
                fields.add("role");
            }
        }
        if (patch.allCollections != null && patch.allCollections != next.allCollections) {
            next.allCollections = patch.allCollections;
            fields.add("allCollections");
        }
        if (patch.collections != null) {
            final List<String> c = collections(patch.collections);
            if (!c.equals(next.collections)) {
                next.collections = c;
                fields.add("collections");
            }
        }
        if (next.role == Role.ADMINISTRATOR || next.allCollections) {
            next.allCollections = next.role == Role.ADMINISTRATOR || next.allCollections;
            if (next.role == Role.ADMINISTRATOR) {
                next.collections = new ArrayList<>();
            }
        }
        if (patch.export != null && patch.export != next.export) {
            next.export = patch.export;
            fields.add("export");
        }
        if (patch.status != null && patch.status != next.status) {
            next.status = patch.status;
            fields.add("status");
        }
        if (fields.isEmpty()) {
            return new Change(current.copy(), fields, false);
        }
        checkScope(next.role, next.allCollections, next.collections);
        final boolean self = actor != null && normalize(actor).equals(current.username);
        if (self && (next.status != Account.Status.ACTIVE || next.role != current.role)) {
            throw new AccessException(409, "own_account", "You cannot lock your own account or change your own role.");
        }
        if (next.active() && next.role != Role.ADMINISTRATOR && !ctx.protectedMode()) {
            throw new AccessException(409, "protected_mode_required",
                    "Research and Operator accounts need the protected access mode. Switch it on first.");
        }
        final boolean wasAdmin = current.active() && current.role == Role.ADMINISTRATOR;
        final boolean isAdmin = next.active() && next.role == Role.ADMINISTRATOR;
        if (wasAdmin && !isAdmin && activeAdministrators() - 1 + (ctx.builtinAdminLogin() ? 1 : 0) < 1) {
            throw new AccessException(409, "last_admin",
                    "This is the last administrator who can sign in. Create or activate another administrator first.");
        }
        final boolean endSessions = fields.contains("role") || fields.contains("allCollections")
                || fields.contains("collections") || fields.contains("export")
                || (fields.contains("status") && next.status == Account.Status.LOCKED);
        if (endSessions) {
            next.authVersion = current.authVersion + 1;
        }
        next.updatedAt = this.clock.getAsLong();
        this.accounts.put(next.username, next);
        try {
            save();
        } catch (final IOException e) {
            this.accounts.put(current.username, current);
            throw e;
        }
        return new Change(next.copy(), fields, endSessions);
    }

    /**
     * Sets a new password (own change or administrator reset). All sessions of
     * the account end; a self-service change renews the caller's session afterwards.
     */
    public Account setPassword(final String username, final String password, final boolean mustChange)
            throws AccessException, IOException {
        final String name = normalize(username);
        if (get(name) == null) {
            throw new AccessException(404, "user_not_found", "There is no account with this name.");
        }
        PasswordPolicy.check(name, password, "password");
        final String hash = this.hasher.hash(password);
        synchronized (this) {
            final Account current = this.accounts.get(name);
            if (current == null) {
                throw new AccessException(404, "user_not_found", "There is no account with this name.");
            }
            final Account next = current.copy();
            next.passwordHash = hash;
            next.mustChangePassword = mustChange;
            next.authVersion = current.authVersion + 1;
            next.passwordChangedAt = this.clock.getAsLong();
            next.updatedAt = next.passwordChangedAt;
            this.accounts.put(name, next);
            try {
                save();
            } catch (final IOException e) {
                this.accounts.put(name, current);
                throw e;
            }
            return next.copy();
        }
    }

    public synchronized Account delete(final String username, final String actor, final Context ctx)
            throws AccessException, IOException {
        final String name = normalize(username);
        final Account current = this.accounts.get(name);
        if (current == null) {
            throw new AccessException(404, "user_not_found", "There is no account with this name.");
        }
        if (actor != null && normalize(actor).equals(name)) {
            throw new AccessException(409, "own_account", "You cannot remove your own account.");
        }
        if (current.active() && current.role == Role.ADMINISTRATOR
                && activeAdministrators() - 1 + (ctx.builtinAdminLogin() ? 1 : 0) < 1) {
            throw new AccessException(409, "last_admin",
                    "This is the last administrator who can sign in. Create or activate another administrator first.");
        }
        this.accounts.remove(name);
        try {
            save();
        } catch (final IOException e) {
            this.accounts.put(name, current);
            throw e;
        }
        return current.copy();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    public static String normalize(final String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(final String name) throws AccessException {
        final String d = name == null ? "" : name.trim().replaceAll("\\p{Cntrl}", "");
        if (d.length() > MAX_DISPLAY_NAME) {
            throw AccessException.invalid("displayName", "The display name must not have more than " + MAX_DISPLAY_NAME + " characters.");
        }
        return d;
    }

    private static List<String> collections(final Collection<String> names) throws AccessException {
        final List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (final String n : names) {
            final String c = n == null ? "" : n.trim();
            if (!Scope.NAME.matcher(c).matches()) {
                throw AccessException.invalid("collections", "Collection names have 1 to 64 letters, digits, '-' or '_'.");
            }
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        if (out.size() > MAX_COLLECTIONS) {
            throw AccessException.invalid("collections", "At most " + MAX_COLLECTIONS + " collections per account.");
        }
        out.sort(Scope.ORDER);
        return out;
    }

    private static void checkScope(final Role role, final boolean all, final List<String> collections) throws AccessException {
        if (role != Role.ADMINISTRATOR && !all && collections.isEmpty()) {
            throw AccessException.invalid("collections", "Choose at least one collection or all collections.");
        }
    }

    private String newId() {
        final char[] alphabet = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();
        final StringBuilder sb = new StringBuilder("u_");
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet[this.random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        final JSONObject root = Js.parse(new String(Files.readAllBytes(this.file), StandardCharsets.UTF_8));
        final JSONArray users = root.optJSONArray("users");
        if (users == null) {
            throw new IOException(this.file + " has no user list; restore it from a backup");
        }
        for (int i = 0; i < users.length(); i++) {
            final JSONObject o = users.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final Account a = Account.fromStoreJson(o);
            if (a.role == null || a.status == null || !USERNAME.matcher(a.username).matches()) {
                // an unreadable entry can never sign in; keep the rest usable
                continue;
            }
            this.accounts.put(a.username, a);
        }
    }

    private void save() throws IOException {
        final JSONArray users = new JSONArray();
        for (final Account a : this.accounts.values()) {
            users.put(a.toStoreJson());
        }
        Js.writeAtomically(this.file, Js.obj("version", 1, "users", users).toString().getBytes(StandardCharsets.UTF_8));
    }

    private void saveQuietly() {
        try {
            save();
        } catch (final IOException e) {
            // lastLoginAt is informational
        }
    }

    /** For tests: the stored file content must never contain a clear-text password. */
    String rawStoreContent() throws IOException {
        return Files.isRegularFile(this.file) ? new String(Files.readAllBytes(this.file), StandardCharsets.UTF_8) : "";
    }
}
