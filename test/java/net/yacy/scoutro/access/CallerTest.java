/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.Principal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** The one decision of every Scoutro servlet: who, which permission, which collections. */
public class CallerTest {

    private static ScoutroPrincipal principal(final Role role, final Scope scope, final boolean mustChange) {
        final SessionStore store = new SessionStore(() -> 1L, 60_000, 3_600_000);
        final SessionStore.Session s = store.create(SessionStore.Kind.ACCOUNT, "anna", "1", "c", "a").session;
        return new ScoutroPrincipal(s, "Anna", role, role.permissions(false), scope, mustChange);
    }

    @Test
    public void researchSessionReadsOnlyItsCollections() throws Exception {
        final ScoutroPrincipal p = principal(Role.RESEARCH, Scope.of(List.of("b-web", "a-web")), false);
        final Caller c = Caller.of(TestRequests.get("/").principal(p, Set.of(p.containerRoles())).build(), null);
        assertEquals(Caller.Kind.ACCOUNT, c.kind);
        c.require(Permission.READ);
        try {
            c.require(Permission.COLLECT);
            fail();
        } catch (final AccessException e) {
            assertEquals(403, e.status());
            assertEquals("forbidden", e.code());
        }
        assertEquals("no request = all allowed, never the whole index", List.of("a-web", "b-web"), c.view(null));
        assertEquals(List.of("a-web"), c.view("a-web"));
        for (final String bad : new String[] {"c-web", "a-web\"", "*", "a-web|c-web", "collection:c-web"}) {
            try {
                c.view(bad);
                fail(bad);
            } catch (final AccessException e) {
                assertEquals(bad, "collection_not_allowed", e.code());
            }
        }
    }

    @Test
    public void mustChangePasswordSessionsHaveNoRights() {
        final ScoutroPrincipal p = principal(Role.OPERATOR, Scope.ALL, true);
        assertEquals(List.of(Permission.SIGNED_IN_ROLE), List.of(p.containerRoles()));
        final Caller c = Caller.of(TestRequests.get("/").principal(p, Set.of(p.containerRoles())).build(), null);
        try {
            c.require(Permission.SEARCH);
            fail();
        } catch (final AccessException e) {
            assertEquals("password_change_required", e.code());
        }
    }

    @Test
    public void digestAdministratorHasEverythingAndAnonymousNothing() throws Exception {
        final Principal digest = () -> "admin";
        final Caller admin = Caller.of(TestRequests.get("/").principal(digest, Set.of("adminRight")).build(), null);
        assertEquals(Caller.Kind.DIGEST_ADMIN, admin.kind);
        assertTrue(admin.admin());
        assertNull(admin.view(null));
        final Caller anon = Caller.of(TestRequests.get("/").build(), null);
        assertEquals(Caller.Kind.ANONYMOUS, anon.kind);
        assertFalse(anon.has(Permission.SEARCH));
        try {
            anon.require(Permission.SEARCH);
            fail();
        } catch (final AccessException e) {
            assertEquals(401, e.status());
        }
        // a principal without the admin role (unknown mechanism) is not trusted as administrator
        final Caller other = Caller.of(TestRequests.get("/").principal(digest, Set.of()).build(), null);
        assertEquals(Caller.Kind.ANONYMOUS, other.kind);
        // a session principal never becomes administrator through the role check: its own permissions decide
        final ScoutroPrincipal research = principal(Role.RESEARCH, Scope.ALL, false);
        final Caller viaSession = Caller.of(TestRequests.get("/").principal(research, Set.of(research.containerRoles())).build(), null);
        assertFalse(viaSession.admin());
    }

    @Test
    public void exportIsASeparateFlagAndAdministratorsAlwaysHaveIt() {
        assertFalse(Role.RESEARCH.permissions(false).contains(Permission.EXPORT));
        assertTrue(Role.RESEARCH.permissions(true).contains(Permission.EXPORT));
        assertTrue(Role.OPERATOR.permissions(true).contains(Permission.EXPORT));
        assertFalse(Role.OPERATOR.permissions(true).contains(Permission.ADMIN));
        assertEquals(EnumSet.allOf(Permission.class), Role.ADMINISTRATOR.permissions(false));
        assertEquals(EnumSet.of(Permission.SEARCH), Role.GUEST.permissions(true));
    }
}
