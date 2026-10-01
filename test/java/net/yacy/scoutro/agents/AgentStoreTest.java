package net.yacy.scoutro.agents;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Agent identities, token lifecycle and persistence (work package 1). */
public class AgentStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private File dir;
    private AgentStore store;

    @Before
    public void setUp() throws Exception {
        this.dir = this.tmp.newFolder("SETTINGS");
        this.store = new AgentStore(this.dir, this.now::get);
    }

    private static Agent.Builder draft(final String name) {
        final Agent.Builder b = new Agent.Builder();
        b.name = name;
        b.description = "test agent";
        b.scope = new Agent.Scope(new LinkedHashSet<>(Arrays.asList("edelsenior-web")), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("search", "index.evidence"));
        b.presetLabel = "research";
        return b;
    }

    @Test
    public void tokenRoundTripAndFormat() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 90 * AgentStore.DAY);
        Assert.assertTrue(issued.plainText().matches("sca_[a-z2-7]{16}\\.[A-Za-z0-9_-]{43}"));
        Assert.assertFalse("toString must not reveal the secret", issued.toString().contains(issued.secret));

        final AgentStore.AuthResult r = this.store.authenticate(issued.plainText(), "10.0.0.1");
        Assert.assertTrue(r.ok());
        Assert.assertEquals(a.id, r.agent.id);
        Assert.assertEquals("10.0.0.1", r.token.lastUsedIp);
        Assert.assertEquals(this.now.get(), r.token.lastUsedAt);
    }

    @Test
    public void onlyTheKeyedHashIsPersisted() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        final String raw = this.store.rawStoreContent();
        Assert.assertTrue(raw.contains(issued.publicId));
        Assert.assertFalse(raw.contains(issued.secret));
        Assert.assertFalse(raw.contains(issued.plainText()));
        // the stored hash is keyed: a plain SHA-256 of the secret must not appear either
        final String sha = AgentTokens.hex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(issued.secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        Assert.assertFalse(raw.contains(sha));
    }

    @Test
    public void storeAndPepperAreOwnerOnly() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        this.store.issueToken(a.id, 30 * AgentStore.DAY);
        for (final String name : new String[] {AgentStore.STORE_FILE, AgentStore.PEPPER_FILE}) {
            final Set<PosixFilePermission> p = Files.getPosixFilePermissions(new File(this.dir, name).toPath());
            Assert.assertEquals(name, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), p);
        }
    }

    @Test
    public void wrongMalformedAndForeignTokensAreRejected() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        Assert.assertEquals("invalid_token", this.store.authenticate(null, "").reason);
        Assert.assertEquals("invalid_token", this.store.authenticate("", "").reason);
        Assert.assertEquals("invalid_token", this.store.authenticate("Bearer " + issued.plainText(), "").reason);
        Assert.assertEquals("invalid_token", this.store.authenticate("ak_" + issued.secret, "").reason);
        // right public id, wrong secret
        final String wrong = "sca_" + issued.publicId + "." + "A".repeat(43);
        Assert.assertEquals("invalid_token", this.store.authenticate(wrong, "").reason);
        Assert.assertEquals(401, this.store.authenticate(wrong, "").httpStatus());
        // unknown public id
        final String unknown = AgentTokens.generate().plainText();
        Assert.assertEquals("invalid_token", this.store.authenticate(unknown, "").reason);
    }

    @Test
    public void expiry() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        this.now.addAndGet(30 * AgentStore.DAY - 1);
        Assert.assertTrue(this.store.authenticate(issued.plainText(), "").ok());
        this.now.addAndGet(1);
        Assert.assertEquals("token_expired", this.store.authenticate(issued.plainText(), "").reason);
    }

    @Test
    public void tokenLifetimeIsBounded() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        try {
            this.store.issueToken(a.id, 366 * AgentStore.DAY);
            Assert.fail("tokens must expire within 365 days");
        } catch (final AgentException e) {
            Assert.assertEquals("invalid_parameter", e.code());
        }
        try {
            this.store.issueToken(a.id, 0);
            Assert.fail("tokens need a lifetime");
        } catch (final AgentException e) {
            Assert.assertEquals(400, e.status());
        }
    }

    @Test
    public void pauseResumeAndFinalRevocation() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 30 * AgentStore.DAY);

        this.store.pause(a.id);
        final AgentStore.AuthResult paused = this.store.authenticate(issued.plainText(), "");
        Assert.assertEquals("agent_paused", paused.reason);
        Assert.assertEquals(403, paused.httpStatus());

        this.store.resume(a.id);
        Assert.assertTrue(this.store.authenticate(issued.plainText(), "").ok());

        this.store.revoke(a.id);
        Assert.assertEquals("token_revoked", this.store.authenticate(issued.plainText(), "").reason);
        try {
            this.store.resume(a.id);
            Assert.fail("a revoked agent must never be resumed");
        } catch (final AgentException e) {
            Assert.assertEquals("invalid_transition", e.code());
        }
        try {
            this.store.issueToken(a.id, 30 * AgentStore.DAY);
            Assert.fail("a revoked agent gets no new tokens");
        } catch (final AgentException e) {
            Assert.assertEquals(409, e.status());
        }
        try {
            this.store.updateAgent(a.id, draft("Neu"));
            Assert.fail("a revoked agent cannot be edited");
        } catch (final AgentException e) {
            Assert.assertEquals("agent_revoked", e.code());
        }
    }

    @Test
    public void resumeOnlyFromPaused() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        try {
            this.store.resume(a.id);
            Assert.fail("resume of an active agent is not a valid transition");
        } catch (final AgentException e) {
            Assert.assertEquals(409, e.status());
        }
    }

    @Test
    public void rotationWithoutGraceEndsOldTokenImmediately() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued old = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        final AgentTokens.Issued fresh = this.store.rotateToken(a.id, 30 * AgentStore.DAY, 0);
        Assert.assertEquals("token_revoked", this.store.authenticate(old.plainText(), "").reason);
        Assert.assertTrue(this.store.authenticate(fresh.plainText(), "").ok());
    }

    @Test
    public void rotationWithGrace() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued old = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        final AgentTokens.Issued fresh = this.store.rotateToken(a.id, 30 * AgentStore.DAY, 15 * 60 * 1000L);
        Assert.assertTrue(this.store.authenticate(old.plainText(), "").ok());
        Assert.assertEquals("rotating", this.store.tokens(a.id).get(0).state(this.now.get()));
        this.now.addAndGet(15 * 60 * 1000L);
        Assert.assertEquals("token_revoked", this.store.authenticate(old.plainText(), "").reason);
        Assert.assertTrue(this.store.authenticate(fresh.plainText(), "").ok());
        try {
            this.store.rotateToken(a.id, 30 * AgentStore.DAY, 61 * 60 * 1000L);
            Assert.fail("grace is limited to one hour");
        } catch (final AgentException e) {
            Assert.assertEquals(400, e.status());
        }
    }

    @Test
    public void singleTokenRevocationIsScopedToItsAgent() throws Exception {
        final Agent a = this.store.createAgent(draft("A"));
        final Agent b = this.store.createAgent(draft("B"));
        final AgentTokens.Issued ta = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        try {
            this.store.revokeToken(b.id, ta.publicId);
            Assert.fail("a token can only be revoked through its own agent");
        } catch (final AgentException e) {
            Assert.assertEquals(404, e.status());
        }
        this.store.revokeToken(a.id, ta.publicId);
        Assert.assertEquals("token_revoked", this.store.authenticate(ta.plainText(), "").reason);
    }

    @Test
    public void persistenceSurvivesReopen() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final AgentTokens.Issued issued = this.store.issueToken(a.id, 30 * AgentStore.DAY);
        this.store.recordCrawl(new CrawlRecord("c1", a.id, "edelsenior-web", "example.com", "run-1", this.now.get()));

        final AgentStore reopened = new AgentStore(this.dir, this.now::get);
        Assert.assertTrue(reopened.authenticate(issued.plainText(), "").ok());
        final Agent loaded = reopened.agent(a.id);
        Assert.assertEquals(a.actions, loaded.actions);
        Assert.assertEquals(a.scope.collections, loaded.scope.collections);
        Assert.assertEquals(a.limits.maxPages, loaded.limits.maxPages);
        Assert.assertEquals("c1", reopened.crawlByClientRef(a.id, "run-1").crawlId);
        Assert.assertNull(reopened.crawl("agt_other", "c1"));
    }

    @Test
    public void updateIncreasesRevisionAndReplacesGrantSnapshot() throws Exception {
        final Agent a = this.store.createAgent(draft("Recherche"));
        final Agent.Builder change = a.toBuilder();
        change.actions = new LinkedHashSet<>(Arrays.asList("search"));
        final Agent updated = this.store.updateAgent(a.id, change);
        Assert.assertEquals(a.revision + 1, updated.revision);
        Assert.assertEquals(Set.of("search"), updated.actions);
    }

    @Test
    public void validation() throws Exception {
        final Agent.Builder noScope = draft("X");
        noScope.scope = new Agent.Scope(new LinkedHashSet<>(), false);
        assertInvalid(noScope, "scope");

        final Agent.Builder badCollection = draft("X");
        badCollection.scope = new Agent.Scope(new LinkedHashSet<>(Arrays.asList("a b")), false);
        assertInvalid(badCollection, "scope");

        final Agent.Builder badDomain = draft("X");
        badDomain.limits = new Agent.Limits(Arrays.asList("*.example.com"), 1, 50, 1, 60, 300, false);
        assertInvalid(badDomain, "domains");

        final Agent.Builder deep = draft("X");
        deep.limits = new Agent.Limits(Arrays.asList("example.com"), 4, 50, 1, 60, 300, false);
        assertInvalid(deep, "maxDepth");

        final Agent.Builder noName = draft(" ");
        assertInvalid(noName, "name");

        this.store.createAgent(draft("Dup"));
        try {
            this.store.createAgent(draft("dup"));
            Assert.fail("names are unique (case-insensitive)");
        } catch (final AgentException e) {
            Assert.assertEquals("name_taken", e.code());
        }
        // all-collections scope without explicit collections is valid
        final Agent.Builder all = draft("All");
        all.scope = new Agent.Scope(new LinkedHashSet<>(), true);
        Assert.assertTrue(this.store.createAgent(all).scope.allCollections);
    }

    @Test
    public void domainAllowlistMatchesSubdomainsOnly() {
        final Agent.Limits l = new Agent.Limits(Arrays.asList("Example.com"), 1, 50, 1, 60, 300, false);
        Assert.assertTrue(l.allowsHost("example.com"));
        Assert.assertTrue(l.allowsHost("www.example.com"));
        Assert.assertFalse(l.allowsHost("badexample.com"));
        Assert.assertFalse(l.allowsHost("example.com.evil.org"));
        Assert.assertFalse(l.allowsHost(null));
    }

    private void assertInvalid(final Agent.Builder b, final String field) throws Exception {
        try {
            this.store.createAgent(b);
            Assert.fail("expected invalid " + field);
        } catch (final AgentException e) {
            Assert.assertEquals(field, e.field());
        }
    }
}
