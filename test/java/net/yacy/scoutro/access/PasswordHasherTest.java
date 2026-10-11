/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordHasherTest {

    @Test
    public void hashesAreArgon2idPhcStringsWithSaltAndVerify() {
        final PasswordHasher h = new PasswordHasher(256, 1);
        final String a = h.hash("correct horse battery");
        final String b = h.hash("correct horse battery");
        assertTrue(a, a.startsWith("$argon2id$v=19$m=256,t=1,p=1$"));
        assertNotEquals("salted", a, b);
        assertTrue(h.verify("correct horse battery", a));
        assertTrue(h.verify("correct horse battery", b));
        assertFalse(h.verify("correct horse batterY", a));
        assertFalse(a.contains("correct"));
    }

    @Test
    public void malformedOrForeignHashesNeverVerify() {
        final PasswordHasher h = new PasswordHasher(256, 1);
        assertFalse(h.verify("x", null));
        assertFalse(h.verify(null, h.hash("x")));
        assertFalse(h.verify("x", "MD5:8cffbc0d66567a0987a4aba1ec46d63c"));
        assertFalse(h.verify("x", "$argon2i$v=19$m=256,t=1,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertFalse(h.verify("x", "$argon2id$v=19$m=2,t=1,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void defaultParametersFollowOwaspMinimum() {
        final PasswordHasher h = new PasswordHasher();
        final String hash = h.hash("a long enough password");
        assertTrue(hash, hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(h.verify("a long enough password", hash));
        assertFalse(h.needsRehash(hash));
        assertTrue(h.needsRehash(new PasswordHasher(256, 1).hash("x")));
    }

    @Test
    public void parallelChecksWaitForAFreeSlot() throws Exception {
        final PasswordHasher h = new PasswordHasher(256, 1);
        final String stored = h.hash("slot-password-1");
        assertTrue(PasswordHasher.CONCURRENT >= 1 && PasswordHasher.CONCURRENT <= 4);
        PasswordHasher.SLOTS.acquireUninterruptibly(PasswordHasher.CONCURRENT); // every slot busy
        final boolean[] done = new boolean[1];
        final Thread check = new Thread(() -> done[0] = h.verify("slot-password-1", stored));
        try {
            check.start();
            check.join(300);
            assertTrue("no Argon2 computation while all slots are taken", check.isAlive());
        } finally {
            PasswordHasher.SLOTS.release(PasswordHasher.CONCURRENT);
        }
        check.join(10_000);
        assertFalse(check.isAlive());
        assertTrue(done[0]);
    }
}
