/*
 *  Upstream
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

import org.w3c.dom.Document;

/**
 * The YaCy HTTP APIs the Scoutro actions call. Implemented by
 * {@link YaCyLoopback}; tests use a fake to check the exact upstream
 * parameters (for example the enforced collection filters).
 */
interface Upstream {

    String getAdmin(String path, YaCyLoopback.Params params) throws ApiException;

    String postAdmin(String path, YaCyLoopback.Params params) throws ApiException;

    Document getAdminXml(String path, YaCyLoopback.Params params) throws ApiException;

    Document getPublicXml(String path, YaCyLoopback.Params params) throws ApiException;
}
