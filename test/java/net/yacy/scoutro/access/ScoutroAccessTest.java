/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.cora.order.Digest;

/**
 * Accounts, sign-in, sessions and the rules around them, without a running peer
 * (docs/SCOUTRO_USERS_ACCESS.md, sections 2-6).
 */
public class ScoutroAccessTest {

    private File dir;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final Map<String, String> config = new HashMap<>();
    private String adminName = "admin";
    private String adminHash;
    private ScoutroAccess access;

    @Before
    public void setUp() throws Exception {
        this.dir = Files.createTempDirectory("scoutro-access-test").toFile();
        this.adminHash = "MD5:" + Digest.encodeMD5Hex("admin:TestRealm:admin-secret-1");
        this.access = open();
    }

    @After
    public void tearDown() {
        ScoutroAccess.resetForTest();
        deleteRecursively(this.dir);
    }

    private ScoutroAccess open() throws Exception {
        return ScoutroAccess.createForTest(this.dir, this.clock::get, this.config::get, new ScoutroAccess.BuiltinAdmin() {
            @Override public String name() { return ScoutroAccessTest.this.adminName; }
            @Override public String hash() { return ScoutroAccessTest.this.adminHash; }
            @Override public String realm() { return "TestRealm"; }
        }, new PasswordHasher(256, 1));
    }

    private Account create(final String name, final Role role, final List<String> collections, final boolean mustChange)
            throws Exception {
        final AccountStore.Spec s = new AccountStore.Spec();
        s.username = name;
        s.role = role;
        s.collections = collections;
        s.password = name + "-password-1";
        s.mustChangePassword = mustChange;
        return this.access.accounts.create(s, "test", this.access);
    }

    private void protectedMode(final boolean on) {
        this.config.put(AccessSettings.PROTECTED, String.valueOf(on));
    }

    // ------------------------------------------------------------------

    @Test
    public void builtinAdministratorSignsInWithItsDigestPasswordAndGetsAdminRight() throws Exception {
        final ScoutroAccess.Login login = this.access.login("admin", "admin-secret-1", "10.0.0.1", "test");
        assertTrue(login.principal.builtin());
        assertEquals(Role.ADMINISTRATOR, login.principal.role());
        assertTrue(Arrays.asList(login.principal.containerRoles()).contains("adminRight"));
        assertNotNull(this.access.resolve(login.created.cookieValue));
        try {
            this.access.login("admin", "wrong", "10.0.0.1", "test");
            fail("wrong password accepted");
        } catch (final AccessException e) {
            assertEquals(401, e.status());
            assertEquals("invalid_credentials", e.code());
        }
    }

    @Test
    public void builtinSessionsEndWhenItsPasswordChangesOrItsFormSignInIsSwitchedOff() throws Exception {
        final String cookie = this.access.login("admin", "admin-secret-1", "c", "a").created.cookieValue;
        assertNotNull(this.access.resolve(cookie));
        this.adminHash = "MD5:" + Digest.encodeMD5Hex("admin:TestRealm:other-secret-2");
        assertNull("a changed admin password ends the session", this.access.resolve(cookie));

        final String second = this.access.login("admin", "other-secret-2", "c", "a").created.cookieValue;
        this.config.put(AccessSettings.BUILTIN_ADMIN_LOGIN, "false");
        assertNull(this.access.resolve(second));
        try {
            this.access.login("admin", "other-secret-2", "c", "a");
            fail("built-in form sign-in still possible");
        } catch (final AccessException e) {
            assertEquals("invalid_credentials", e.code());
        }
    }

    @Test
    public void restrictedAccountsNeedProtectedModeAndNeverGetAdminRight() throws Exception {
        try {
            create("anna", Role.RESEARCH, List.of("a-web"), false);
            fail("research account in compatible mode");
        } catch (final AccessException e) {
            assertEquals("protected_mode_required", e.code());
        }
        protectedMode(true);
        create("anna", Role.RESEARCH, List.of("a-web"), false);
        create("otto", Role.OPERATOR, List.of("a-web", "b-web"), false);
        final ScoutroPrincipal anna = this.access.login("anna", "anna-password-1", "c", "a").principal;
        final ScoutroPrincipal otto = this.access.login("OTTO", "otto-password-1", "c", "a").principal;
        assertFalse(Arrays.asList(anna.containerRoles()).contains("adminRight"));
        assertFalse(Arrays.asList(otto.containerRoles()).contains("adminRight"));
        assertTrue(anna.has(Permission.READ));
        assertFalse(anna.has(Permission.COLLECT));
        assertFalse(anna.has(Permission.EXPORT));
        assertTrue(otto.has(Permission.COLLECT));
        assertFalse(otto.has(Permission.ADMIN));
        assertTrue(anna.scope().allows("a-web"));
        assertFalse(anna.scope().allows("b-web"));
        // leaving protected mode is impossible for the store rules and fail-closed for the mode
        this.config.put(AccessSettings.PROTECTED, "false");
        assertTrue("active restricted accounts keep the protected rules", this.access.protectedMode());
    }

    @Test
    public void rightsChangesAndLockingEndSessionsImmediately() throws Exception {
        protectedMode(true);
        create("anna", Role.RESEARCH, List.of("a-web"), false);
        final String cookie = this.access.login("anna", "anna-password-1", "c", "a").created.cookieValue;
        assertNotNull(this.access.resolve(cookie));

        final AccountStore.Patch rename = new AccountStore.Patch();
        rename.displayName = "Anna A.";
        final AccountStore.Change c1 = this.access.accounts.update("anna", rename, "admin", this.access);
        assertFalse(c1.endSessions);
        assertEquals("Anna A.", this.access.resolve(cookie).displayName());

        final AccountStore.Patch widen = new AccountStore.Patch();
        widen.collections = List.of("a-web", "b-web");
        assertTrue(this.access.accounts.update("anna", widen, "admin", this.access).endSessions);
        assertNull("a scope change ends the session", this.access.resolve(cookie));

        final String again = this.access.login("anna", "anna-password-1", "c", "a").created.cookieValue;
        final AccountStore.Patch lock = new AccountStore.Patch();
        lock.status = Account.Status.LOCKED;
        assertTrue(this.access.accounts.update("anna", lock, "admin", this.access).endSessions);
        assertNull(this.access.resolve(again));
        try {
            this.access.login("anna", "anna-password-1", "c", "a");
            fail("locked account signed in");
        } catch (final AccessException e) {
            assertEquals(403, e.status());
            assertEquals("account_locked", e.code());
        }
        try {
            this.access.login("anna", "wrong-password", "c", "a");
            fail();
        } catch (final AccessException e) {
            assertEquals("a locked account is named only after the right password", "invalid_credentials", e.code());
        }
    }

    @Test
    public void passwordResetForcesChangeAndOwnChangeRenewsOnlyTheCurrentSession() throws Exception {
        protectedMode(true);
        create("anna", Role.RESEARCH, List.of("a-web"), true);
        final ScoutroAccess.Login first = this.access.login("anna", "anna-password-1", "c", "a");
        assertTrue(first.principal.mustChangePassword());
        assertTrue("no permissions until the password is changed", first.principal.permissions().isEmpty());
        assertTrue(first.principal.scope().isEmpty());
        final ScoutroAccess.Login other = this.access.login("anna", "anna-password-1", "c2", "a");

        try {
            this.access.changeOwnPassword(first.principal, "wrong", "anna-new-password-2", "c", "a");
            fail();
        } catch (final AccessException e) {
            assertEquals("wrong_password", e.code());
        }
        final ScoutroAccess.Login renewed = this.access.changeOwnPassword(first.principal, "anna-password-1",
                "anna-new-password-2", "c", "a");
        assertFalse(renewed.principal.mustChangePassword());
        assertTrue(renewed.principal.has(Permission.READ));
        assertNull("old session ended", this.access.resolve(first.created.cookieValue));
        assertNull("other session ended", this.access.resolve(other.created.cookieValue));
        assertNotNull(this.access.resolve(renewed.created.cookieValue));

        this.access.accounts.setPassword("anna", "admin-reset-password-3", true);
        assertNull("an administrator reset ends every session", this.access.resolve(renewed.created.cookieValue));
        assertTrue(this.access.login("anna", "admin-reset-password-3", "c", "a").principal.mustChangePassword());
    }

    @Test
    public void lastAdministratorAndOwnAccountAreProtected() throws Exception {
        create("ben", Role.ADMINISTRATOR, null, false);
        this.config.put(AccessSettings.BUILTIN_ADMIN_LOGIN, "false");
        final AccountStore.Patch lock = new AccountStore.Patch();
        lock.status = Account.Status.LOCKED;
        try {
            this.access.accounts.update("ben", lock, "someone", this.access);
            fail("last administrator locked");
        } catch (final AccessException e) {
            assertEquals("last_admin", e.code());
        }
        final AccountStore.Patch demote = new AccountStore.Patch();
        demote.role = Role.OPERATOR;
        demote.collections = List.of("a-web");
        protectedMode(true);
        try {
            this.access.accounts.update("ben", demote, "someone", this.access);
            fail("last administrator demoted");
        } catch (final AccessException e) {
            assertEquals("last_admin", e.code());
        }
        try {
            this.access.accounts.delete("ben", "someone", this.access);
            fail("last administrator removed");
        } catch (final AccessException e) {
            assertEquals("last_admin", e.code());
        }
        create("clara", Role.ADMINISTRATOR, null, false);
        try {
            this.access.accounts.update("ben", lock, "ben", this.access);
            fail("own account locked");
        } catch (final AccessException e) {
            assertEquals("own_account", e.code());
        }
        try {
            this.access.accounts.delete("ben", "ben", this.access);
            fail("own account removed");
        } catch (final AccessException e) {
            assertEquals("own_account", e.code());
        }
        assertEquals(Account.Status.LOCKED, this.access.accounts.update("ben", lock, "clara", this.access).account.status());
        this.config.put(AccessSettings.BUILTIN_ADMIN_LOGIN, "true");
        assertEquals("ben", this.access.accounts.delete("ben", "clara", this.access).username());
    }

    @Test
    public void namesPasswordsAndScopesAreValidated() throws Exception {
        protectedMode(true);
        final String[][] cases = {
                {"admin", "username_reserved"}, {"A", "invalid_request"}, {"bad name", "invalid_request"},
        };
        for (final String[] c : cases) {
            try {
                create(c[0], Role.RESEARCH, List.of("a-web"), false);
                fail(c[0]);
            } catch (final AccessException e) {
                assertEquals(c[0], c[1], e.code());
            }
        }
        final AccountStore.Spec s = new AccountStore.Spec();
        s.username = "dora";
        s.role = Role.RESEARCH;
        s.collections = List.of("a-web");
        for (final String[] p : new String[][] {{"short", "password_too_short"}, {"dora", "password_too_short"},
                {"1234567890", "password_too_common"}}) {
            s.password = p[0];
            try {
                this.access.accounts.create(s, "test", this.access);
                fail(p[0]);
            } catch (final AccessException e) {
                assertEquals(p[0], p[1], e.code());
            }
        }
        final AccountStore.Spec same = new AccountStore.Spec();
        same.username = "dorothea-ab";
        same.role = Role.RESEARCH;
        same.collections = List.of("a-web");
        same.password = "Dorothea-AB";
        try {
            this.access.accounts.create(same, "test", this.access);
            fail("password equals user name");
        } catch (final AccessException e) {
            assertEquals("password_equals_username", e.code());
        }
        s.password = "a-good-password";
        s.collections = List.of();
        try {
            this.access.accounts.create(s, "test", this.access);
            fail("research without collections");
        } catch (final AccessException e) {
            assertEquals("collections", e.field());
        }
        s.collections = List.of("bad name");
        try {
            this.access.accounts.create(s, "test", this.access);
            fail("invalid collection");
        } catch (final AccessException e) {
            assertEquals("collections", e.field());
        }
        s.collections = List.of("a-web");
        this.access.accounts.create(s, "test", this.access);
        try {
            s.username = "DORA";
            this.access.accounts.create(s, "test", this.access);
            fail("duplicate");
        } catch (final AccessException e) {
            assertEquals("username_taken", e.code());
        }
    }

    @Test
    public void storeKeepsOnlyHashesAndSurvivesARestart() throws Exception {
        protectedMode(true);
        create("anna", Role.RESEARCH, List.of("b-web", "a-web"), false);
        final String raw = this.access.accounts.rawStoreContent();
        assertFalse(raw.contains("anna-password-1"));
        assertTrue(raw.contains("$argon2id$"));
        ScoutroAccess.resetForTest();
        this.access = open();
        final Account a = this.access.accounts.get("anna");
        assertEquals(List.of("a-web", "b-web"), a.scope().collections());
        assertNotNull(this.access.login("anna", "anna-password-1", "c", "a"));
    }

    @Test
    public void signInLimitsSlowDownGuessingWithoutLockingTheAccount() throws Exception {
        protectedMode(true);
        create("anna", Role.RESEARCH, List.of("a-web"), false);
        for (int i = 0; i < LoginThrottle.ACCOUNT_FAILURES; i++) {
            try {
                this.access.login("anna", "wrong-" + i, "10.0.0." + i, "a");
                fail();
            } catch (final AccessException e) {
                assertEquals("invalid_credentials", e.code());
            }
        }
        try {
            this.access.login("anna", "anna-password-1", "10.0.0.99", "a");
            fail("throttle ignored");
        } catch (final AccessException e) {
            assertEquals(429, e.status());
            assertEquals("too_many_attempts", e.code());
        }
        this.clock.addAndGet(LoginThrottle.WINDOW + 1000);
        assertNotNull("the wait ends by itself", this.access.login("anna", "anna-password-1", "10.0.0.99", "a"));
        assertEquals(Account.Status.ACTIVE, this.access.accounts.get("anna").status());
    }

    @Test
    public void sessionsExpireWhenIdleOrTooOld() throws Exception {
        this.config.put(AccessSettings.IDLE_MINUTES, "10");
        this.config.put(AccessSettings.MAX_HOURS, "1");
        final String cookie = this.access.login("admin", "admin-secret-1", "c", "a").created.cookieValue;
        this.clock.addAndGet(9 * 60_000L);
        assertNotNull(this.access.resolve(cookie));
        this.clock.addAndGet(9 * 60_000L);
        assertNotNull("activity extends the idle window", this.access.resolve(cookie));
        this.clock.addAndGet(11 * 60_000L);
        assertNull("idle too long", this.access.resolve(cookie));

        final String second = this.access.login("admin", "admin-secret-1", "c", "a").created.cookieValue;
        for (int i = 0; i < 7; i++) {
            this.clock.addAndGet(9 * 60_000L);
            this.access.resolve(second);
        }
        assertNull("absolute lifetime", this.access.resolve(second));
    }

    @Test
    public void auditRecordsSignInsWithoutSecrets() throws Exception {
        try {
            this.access.login("admin", "guess-password", "10.1.1.1", "a");
        } catch (final AccessException e) {
            // expected
        }
        this.access.login("admin", "admin-secret-1", "10.1.1.1", "a");
        final String log = new String(Files.readAllBytes(new File(this.dir, AccessAudit.AUDIT_FILE).toPath()));
        assertTrue(log.contains("\"auth.login\""));
        assertTrue(log.contains("\"failed\""));
        assertTrue(log.contains("\"ok\""));
        assertFalse(log.contains("guess-password"));
        assertFalse(log.contains("admin-secret-1"));
    }

    @Test
    public void guestAccessNeedsProtectedModeAndOnlyReleasedCollections() throws Exception {
        this.config.put(AccessSettings.GUEST, "true");
        this.config.put(AccessSettings.GUEST_COLLECTIONS, "pub-web, bad!name, pub-web");
        assertFalse("guest without protected mode", this.access.guestEnabled());
        protectedMode(true);
        assertTrue(this.access.guestEnabled());
        assertEquals(List.of("pub-web"), this.access.guestScope().collections());
        final Caller guest = Caller.of(TestRequests.get("/").build(), this.access);
        assertEquals(Caller.Kind.GUEST, guest.kind);
        assertTrue(guest.has(Permission.SEARCH));
        assertFalse(guest.has(Permission.READ));
        try {
            guest.view("other-web");
            fail();
        } catch (final AccessException e) {
            assertEquals("collection_not_allowed", e.code());
        }
        assertEquals(List.of("pub-web"), guest.view(null));
    }

    private static void deleteRecursively(final File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
