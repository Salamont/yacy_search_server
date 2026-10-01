/*
 *  ScopedActions
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

package net.yacy.scoutro.api;

import net.yacy.scoutro.agents.AgentException;

/**
 * The only way an agent request reaches {@link ScoutroActions}: every data
 * scope and limit is set here on the server side.
 */
class ScopedActions {

    AgentApi.Response execute(final String action, final String id, final AgentApi.Call call)
            throws ApiException, AgentException {
        throw new ApiException(501, "not_implemented", "The action '" + action + "' is not available yet.");
    }
}
