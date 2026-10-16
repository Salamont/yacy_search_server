/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.htroot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.data.TransactionManager;
import net.yacy.data.BadTransactionException;
import net.yacy.http.servlets.DisallowedMethodException;
import net.yacy.server.serverObjects;

/** Existing YaCy transaction tokens, enforced also for local AI settings/test requests. */
final class AiSettingsTransaction {
    private AiSettingsTransaction() { }
    static void check(final RequestHeader header, final serverObjects post) {
        if (!"POST".equals(header.getMethod())) throw new DisallowedMethodException("Einstellungen und Modelltests erfordern POST.");
        final String supplied = post == null ? "" : post.get(TransactionManager.TRANSACTION_TOKEN_PARAM, "");
        final String expected = TransactionManager.getTransactionToken(header);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BadTransactionException("Ungültiger Transaktionsschutz. Bitte die Einstellungsseite neu laden.");
        TransactionManager.checkPostTransaction(header, post);
    }
}
