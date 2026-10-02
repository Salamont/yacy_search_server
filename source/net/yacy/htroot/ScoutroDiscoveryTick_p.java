/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.htroot;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.data.TransactionManager;
import net.yacy.data.WorkTables;
import net.yacy.scoutro.api.ApiException;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/** WorkTables form adapter; GET only issues a token, authenticated POST wakes the coordinator. */
public final class ScoutroDiscoveryTick_p {
    private ScoutroDiscoveryTick_p() { }
    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        if (!((Switchboard) env).verifyAuthentication(header)) { prop.authenticationRequired(); return prop; }
        prop.put(TransactionManager.TRANSACTION_TOKEN_PARAM, TransactionManager.getTransactionToken(header));
        prop.put("accepted", 0); prop.putJSON("reason", "token_only");
        if (post == null || !post.containsKey("tick")) return prop;
        TransactionManager.checkPostTransaction(header, post);
        try {
            final DiscoveryService service = DiscoveryService.get();
            final String key = post.get(WorkTables.TABLE_API_COL_APICALL_PK, "");
            if (!key.isEmpty()) service.recordHeartbeat(key);
            final org.json.JSONObject result = service.tick();
            prop.put("accepted", result.optBoolean("accepted") ? 1 : 0);
            prop.putJSON("reason", result.optString("reason", "scheduled"));
        } catch (final ApiException e) { prop.putJSON("reason", e.code()); }
        return prop;
    }
}
