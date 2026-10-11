/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.ai.rag.ChatCollections;
import net.yacy.http.servlets.LLMAccess;

/**
 * The chat of an account without the administrator role (docs/SCOUTRO_USERS_ACCESS.md): its session decides
 * the collections, whatever the AI Shield admits for its address.
 */
public class ChatMemberTest {

    private static ScoutroPrincipal principal(final Role role, final Scope scope) {
        final SessionStore store = new SessionStore(() -> 1L, 60_000, 3_600_000);
        final SessionStore.Session s = store.create(SessionStore.Kind.ACCOUNT, "anna", "1", "c", "a").session;
        return new ScoutroPrincipal(s, "Anna", role, role.permissions(false), scope, false);
    }

    @Test
    public void accountsChatWithinTheirCollections() {
        final ScoutroPrincipal anna = principal(Role.RESEARCH, Scope.of(List.of("a-web", "b-web")));
        final Scope scope = ChatCollections.member(TestRequests.get("/v1/chat/completions").principal(anna, Set.of(anna.containerRoles())).build());
        assertEquals(List.of("a-web", "b-web"), scope.collections());
        assertTrue(LLMAccess.Shield.ACCOUNT.allowed());
        assertTrue("accounts get no local privileges", !LLMAccess.Shield.ACCOUNT.privileged);
    }

    @Test
    public void administratorsGuestsAndDigestKeepTheAiShieldRules() {
        final ScoutroPrincipal admin = principal(Role.ADMINISTRATOR, Scope.ALL);
        assertNull(ChatCollections.member(TestRequests.get("/").principal(admin, Set.of(admin.containerRoles())).build()));
        assertNull(ChatCollections.member(TestRequests.get("/").principal(() -> "admin", Set.of("adminRight")).build()));
        assertNull(ChatCollections.member(TestRequests.get("/").build()));
    }

    @Test
    public void anAccountWithoutTheChatRightHasNoCollection() {
        final ScoutroPrincipal guest = principal(Role.GUEST, Scope.of(List.of("a-web")));
        assertTrue(ChatCollections.member(TestRequests.get("/").principal(guest, Set.of(guest.containerRoles())).build()).isEmpty());
    }
}
