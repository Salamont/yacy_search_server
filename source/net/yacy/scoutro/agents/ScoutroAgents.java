/*
 *  ScoutroAgents
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

package net.yacy.scoutro.agents;

import java.io.File;
import java.io.IOException;
import java.util.function.LongSupplier;

import net.yacy.search.Switchboard;

/**
 * The agent store, authorizer and audit log of this Scoutro instance, kept
 * in {@code DATA/SETTINGS}. Created on first use.
 */
public final class ScoutroAgents {

    private static volatile ScoutroAgents instance;

    public final File settingsDir;
    public final AgentStore store;
    public final AgentAuthorizer authorizer;
    public final AgentAudit audit;
    public final AgentRuntimeSecrets runtimeSecrets;

    ScoutroAgents(final File settingsDir, final LongSupplier clock) throws IOException, AgentException {
        this.settingsDir = settingsDir;
        this.store = new AgentStore(settingsDir, clock);
        this.authorizer = new AgentAuthorizer(clock);
        this.audit = new AgentAudit(settingsDir, clock);
        this.runtimeSecrets = new AgentRuntimeSecrets(new File(settingsDir, AgentRuntimeSecrets.DIRECTORY));
    }

    public static ScoutroAgents get() throws AgentException {
        ScoutroAgents i = instance;
        if (i != null) {
            return i;
        }
        synchronized (ScoutroAgents.class) {
            if (instance == null) {
                final Switchboard sb = Switchboard.getSwitchboard();
                if (sb == null) {
                    throw new AgentException(503, "unavailable", "Scoutro is not initialized yet.");
                }
                try {
                    instance = new ScoutroAgents(new File(sb.getDataPath(), "DATA/SETTINGS"), System::currentTimeMillis);
                } catch (final IOException e) {
                    throw new AgentException(503, "agent_store_unavailable",
                            "The agent store cannot be opened: " + e.getMessage());
                }
            }
            return instance;
        }
    }

    /** For tests: use an isolated directory and clock. */
    public static ScoutroAgents createForTest(final File settingsDir, final LongSupplier clock)
            throws IOException, AgentException {
        final ScoutroAgents a = new ScoutroAgents(settingsDir, clock);
        instance = a;
        return a;
    }
}
