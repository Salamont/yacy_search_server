#!/usr/bin/env python3
# Generate htroot/env/scoutro/api/openapi.json and actions.json from one set
# of definitions, so that the OpenAPI description and the action catalog for
# agents never diverge.
#
# Copyright (C) 2026 Scoutro contributors
# Scoutro is an independent community project based on YaCy.
# Licensed under the GNU General Public License, version 2 or (at your option)
# any later version.
#
# Usage: python3 tools/scoutro/generate_api_description.py htroot/env/scoutro/api
import json, collections
O = collections.OrderedDict

def ref(n): return {"$ref": "#/components/schemas/" + n}

err = lambda desc: {"description": desc, "content": {"application/json": {"schema": ref("Error")}}}
E = {
 "400": err("Invalid request (validation error; details.field names the parameter)."),
 "401": {"description": "Administrator authentication required (HTTP Digest). The body is the server's HTML error page."},
 "403": err("Forbidden (cross-origin request or setting not on the allowlist)."),
 "404": err("Not found."),
 "405": err("Method not allowed."),
 "409": err("Conflict (e.g. the crawl is not running)."),
 "413": err("Request body too large (max 16 KiB)."),
 "415": err("Request body must be application/json."),
 "422": err("YaCy refused the request (e.g. crawl rejected; message contains YaCy's reason)."),
 "502": err("YaCy returned an error or an unreadable answer on the loopback interface."),
 "503": err("YaCy is not available yet."),
}
def errs(*codes): return {c: E[c] for c in codes}

schemas = O()
schemas["Error"] = {"type": "object", "required": ["error"], "properties": {"error": {"type": "object", "required": ["code", "message"], "properties": {
    "code": {"type": "string", "description": "Stable machine-readable error code.", "examples": ["invalid_request", "crawl_not_found"]},
    "message": {"type": "string"},
    "details": {"type": "object", "additionalProperties": True}}}}}
schemas["Version"] = {"type": "object", "properties": {
    "scoutro": {"type": ["string", "null"], "examples": ["1.942-scoutro.1"]},
    "yacy": {"type": "string", "examples": ["1.942"]},
    "build": {"type": "string"}}}
schemas["Health"] = {"type": "object", "required": ["status", "apiVersion"], "properties": {
    "status": {"type": "string", "enum": ["ok"]}, "service": {"type": "string", "enum": ["scoutro"]},
    "apiVersion": {"type": "string", "enum": ["1"]}, "version": ref("Version")}}
schemas["CrawlerQueues"] = {"type": "object", "properties": {
    "state": {"type": "string", "description": "State of the local crawler queue as reported by YaCy (running or paused)."},
    "localQueue": {"type": ["integer", "null"]}, "limitQueue": {"type": ["integer", "null"]},
    "remoteQueue": {"type": ["integer", "null"]}, "noloadQueue": {"type": ["integer", "null"]}, "loader": {"type": ["integer", "null"]}}}
schemas["System"] = {"type": "object", "properties": {
    "version": ref("Version"),
    "peer": {"type": "object", "properties": {"name": {"type": "string"}, "network": {"type": "string"}}},
    "resources": {"type": "object", "properties": {
        "processors": {"type": ["integer", "null"]}, "load": {"type": ["number", "null"]},
        "memory": {"type": "object", "properties": {k: {"type": ["integer", "null"]} for k in ["usedBytes", "freeBytes", "totalBytes", "maxBytes"]}},
        "disk": {"type": "object", "properties": {k: {"type": ["integer", "null"]} for k in ["usedBytes", "freeBytes"]}}}},
    "crawler": ref("CrawlerQueues"), "pagesPerMinute": {"type": ["integer", "null"]}}}
schemas["SearchResult"] = {"type": "object", "properties": {
    "title": {"type": "string"}, "url": {"type": "string"}, "snippet": {"type": "string", "description": "Plain text, HTML removed."},
    "host": {"type": "string"}, "date": {"type": ["string", "null"], "format": "date-time"},
    "sizeBytes": {"type": ["integer", "null"]}, "id": {"type": "string", "description": "YaCy URL hash."}}}
schemas["SearchResponse"] = {"type": "object", "required": ["query", "total", "results"], "properties": {
    "query": {"type": "string"}, "source": {"type": "string", "enum": ["local", "network"]},
    "offset": {"type": "integer"}, "limit": {"type": "integer"}, "total": {"type": "integer"},
    "results": {"type": "array", "items": ref("SearchResult")}}}
schemas["IndexStatus"] = {"type": "object", "properties": {
    "documents": {"type": ["integer", "null"], "description": "Documents in the full-text index."},
    "webgraphEdges": {"type": ["integer", "null"]}, "citations": {"type": ["integer", "null"]}, "rwiWords": {"type": ["integer", "null"]},
    "crawler": ref("CrawlerQueues"),
    "postprocessing": {"type": "object", "properties": {"status": {"type": "string"}, "remaining": {"type": ["integer", "null"]}}}}}
schemas["IndexLookup"] = {"oneOf": [
    {"type": "object", "required": ["url", "indexed"], "properties": {"url": {"type": "string"}, "indexed": {"type": "boolean"},
        "document": {"type": ["object", "null"], "properties": {"url": {"type": "string"}, "title": {"type": "string"}, "host": {"type": "string"},
            "lastModified": {"type": "string"}, "collections": {"type": "array", "items": {"type": "string"}}}}}},
    {"type": "object", "required": ["host", "documents"], "properties": {"host": {"type": "string"}, "documents": {"type": "integer"}}}]}
schemas["Crawl"] = {"type": "object", "required": ["id", "state"], "properties": {
    "id": {"type": "string", "description": "YaCy crawl profile handle."},
    "name": {"type": "string", "description": "Crawl name as shown by YaCy (usually the host)."},
    "state": {"type": "string", "enum": ["running", "paused", "terminated"]},
    "depth": {"type": ["integer", "null"]}, "maxPages": {"type": ["integer", "null"]},
    "pagesLoaded": {"type": ["integer", "null"], "description": "URLs loaded for this crawl so far (running crawls only)."},
    "collections": {"type": "array", "items": {"type": "string"}},
    "startUrl": {"type": "string", "description": "Only in the answer of crawl.start."},
    "scope": {"type": "string", "description": "Only in the answer of crawl.start."},
    "links": {"type": "object", "properties": {"self": {"type": "string"}, "stop": {"type": "string"}}}}}
schemas["CrawlList"] = {"type": "object", "required": ["crawls"], "properties": {"crawls": {"type": "array", "items": ref("Crawl")}}}
schemas["CrawlStart"] = {"type": "object", "required": ["url"], "additionalProperties": False, "properties": {
    "url": {"type": "string", "format": "uri", "maxLength": 2048, "description": "Start URL, http or https."},
    "depth": {"type": "integer", "minimum": 0, "maximum": 10, "default": 2},
    "scope": {"type": "string", "enum": ["domain", "subpath", "wide"], "default": "domain",
              "description": "domain: stay on the host; subpath: stay below the start path; wide: follow links to other hosts."},
    "maxPages": {"type": "integer", "minimum": 1, "maximum": 1000000, "description": "Maximum pages per domain (unlimited if omitted)."},
    "collection": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,64}$", "default": "user"}}}
schemas["CrawlStopped"] = {"type": "object", "properties": {"id": {"type": "string"}, "name": {"type": "string"}, "state": {"type": "string", "enum": ["stopped"]}}}
schemas["Settings"] = {"type": "object", "properties": {"settings": {"type": "object", "additionalProperties": {"type": "object", "properties": {
    "value": {}, "type": {"type": "string", "enum": ["string", "integer"]}, "description": {"type": "string"}, "writable": {"type": "boolean"}}}}}}
schemas["SettingsUpdate"] = {"type": "object", "minProperties": 1, "additionalProperties": False, "properties": {
    "search.greeting": {"type": "string", "maxLength": 120},
    "search.itemsPerPage": {"type": "integer", "minimum": 1, "maximum": 100}}}
schemas["UiRoute"] = {"type": "object", "properties": {
    "name": {"type": "string"}, "group": {"type": "string"}, "title": {"type": "string"},
    "path": {"type": "string", "description": "Path relative to the Scoutro base URL."},
    "auth": {"type": "string", "enum": ["public", "admin"]}, "description": {"type": "string"}}}
schemas["UiRouteList"] = {"type": "object", "properties": {"routes": {"type": "array", "items": ref("UiRoute")}}}

def ok(desc, schema, code="200"): return {code: {"description": desc, "content": {"application/json": {"schema": ref(schema)}}}}
ADMIN = [{"digest": []}]
def op(opid, summary, desc, tags, responses, params=None, body=None, admin=True, mutating=False):
    o = O(operationId=opid, summary=summary, description=desc, tags=tags)
    if params: o["parameters"] = params
    if body: o["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(body)}}}
    o["responses"] = responses
    o["security"] = ADMIN if admin else []
    o["x-scoutro-mutating"] = mutating
    return o
def q(name, schema, desc, required=False): return {"name": name, "in": "query", "required": required, "description": desc, "schema": schema}
idp = {"name": "id", "in": "path", "required": True, "description": "Crawl id.", "schema": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,64}$"}}

paths = O()
paths["/v1/health"] = {"get": op("health", "Health check", "Public. Confirms that Scoutro and YaCy answer.", ["system"], {**ok("Scoutro is up.", "Health"), **errs("503")}, admin=False)}
paths["/v1/system"] = {"get": op("system.status", "System status", "Versions, peer, memory, disk, load and crawler queues.", ["system"], {**ok("System status.", "System"), **errs("401", "502", "503")})}
paths["/v1/search"] = {"get": op("search", "Search the index", "Full-text search (YaCy yacysearch.json).", ["search"], {**ok("Search results.", "SearchResponse"), **errs("400", "401", "502", "503")}, params=[
    q("q", {"type": "string", "minLength": 1, "maxLength": 200}, "Search query (YaCy query syntax, e.g. site:example.com).", True),
    q("limit", {"type": "integer", "minimum": 1, "maximum": 100, "default": 10}, "Number of results."),
    q("offset", {"type": "integer", "minimum": 0, "maximum": 10000, "default": 0}, "Index of the first result."),
    q("source", {"type": "string", "enum": ["local", "network"], "default": "local"}, "local index only, or also the peer-to-peer network."),
    q("lang", {"type": "string", "pattern": "^[a-z]{2}$"}, "Restrict to a language (two-letter code).")])}
paths["/v1/index"] = {"get": op("index.status", "Index status", "Document counts and crawler queues.", ["index"], {**ok("Index status.", "IndexStatus"), **errs("401", "502", "503")})}
paths["/v1/index/lookup"] = {"get": op("index.lookup", "Look up a URL or host", "Whether a URL is indexed, or how many documents a host has. Give exactly one of url or host.", ["index"], {**ok("Lookup result.", "IndexLookup"), **errs("400", "401", "502", "503")}, params=[
    q("url", {"type": "string", "maxLength": 2048}, "URL to look up (http or https)."),
    q("host", {"type": "string", "maxLength": 253}, "Host name to count documents for.")])}
paths["/v1/crawls"] = {
    "get": op("crawl.list", "List crawls", "Running, paused and terminated crawls.", ["crawls"], {**ok("Crawl list.", "CrawlList"), **errs("401", "502", "503")}),
    "post": op("crawl.start", "Start a crawl", "Starts a crawl like the YaCy site crawl start. As in the web interface, YaCy removes the start URL from the index and loads it again, and drops queued URLs of the same host from other crawls; other documents of the site are kept (the API never sets deleteold).", ["crawls"], {**ok("Crawl started.", "Crawl", "201"), **errs("400", "401", "403", "413", "415", "422", "502", "503")}, body="CrawlStart", mutating=True)}
paths["/v1/crawls/{id}"] = {"get": op("crawl.status", "Crawl status", "State of one crawl.", ["crawls"], {**ok("Crawl.", "Crawl"), **errs("400", "401", "404", "502", "503")}, params=[idp])}
paths["/v1/crawls/{id}/stop"] = {"post": op("crawl.stop", "Stop a crawl", "Terminates a running or paused crawl. YaCy removes the crawl profile; afterwards crawl.status answers 404. Send an empty JSON object as body.", ["crawls"], {**ok("Crawl stopped.", "CrawlStopped"), **errs("400", "401", "403", "404", "409", "415", "502", "503")}, params=[idp], mutating=True)}
paths["/v1/config"] = {
    "get": op("config.get", "Read settings", "The allowlisted settings that the API may read and change.", ["config"], {**ok("Settings.", "Settings"), **errs("401", "503")}),
    "patch": op("config.set", "Change settings", "All-or-nothing update of allowlisted settings only.", ["config"], {**ok("Settings after the update.", "Settings"), **errs("400", "401", "403", "413", "415", "503")}, body="SettingsUpdate", mutating=True)}
paths["/v1/ui/routes"] = {"get": op("ui.routes", "UI routes", "Public. Stable names and paths of the pages of the web interface.", ["ui"], ok("Routes.", "UiRouteList"), admin=False)}
paths["/v1/ui/routes/{name}"] = {"get": op("ui.route", "One UI route", "Public. Path of one page, e.g. config.accounts.", ["ui"], {**ok("Route.", "UiRoute"), **errs("404")}, params=[{"name": "name", "in": "path", "required": True, "description": "Route name, e.g. config.accounts.", "schema": {"type": "string"}}], admin=False)}

openapi = O()
openapi["openapi"] = "3.1.0"
openapi["info"] = {"title": "Scoutro API", "version": "1.0.0",
    "description": "Stable, machine-readable action layer of Scoutro (an independent community project based on YaCy). Every action is translated into existing YaCy functions. Mutating and administrative actions require the Scoutro/YaCy administrator account (HTTP Digest). See docs/API.md.",
    "license": {"name": "GPL-2.0-or-later", "identifier": "GPL-2.0-or-later"}}
openapi["servers"] = [{"url": "/scoutro/api", "description": "Relative to the Scoutro base URL, e.g. http://scoutro:8090/scoutro/api"}]
openapi["paths"] = paths
openapi["components"] = {"schemas": schemas, "securitySchemes": {"digest": {"type": "http", "scheme": "digest", "description": "YaCy administrator account (user 'admin' by default)."}}}
openapi["tags"] = [{"name": t} for t in ["system", "search", "index", "crawls", "config", "ui"]]

# action catalog derived from the same definitions
actions = []
mcp = {"search": "scoutro_search", "crawl.start": "scoutro_crawl_start", "crawl.list": "scoutro_crawl_list", "crawl.status": "scoutro_crawl_status",
       "crawl.stop": "scoutro_crawl_stop", "index.status": "scoutro_index_status", "system.status": "scoutro_system_status", "health": "scoutro_health",
       "index.lookup": "scoutro_index_lookup", "config.get": "scoutro_config_get", "config.set": "scoutro_config_set", "ui.routes": "scoutro_ui_routes", "ui.route": "scoutro_ui_route"}
cli = {"health": "scoutroctl health", "system.status": "scoutroctl system", "search": "scoutroctl search QUERY [--limit N] [--network] [--lang de]",
       "index.status": "scoutroctl index status", "index.lookup": "scoutroctl index lookup (--url URL | --host HOST)",
       "crawl.list": "scoutroctl crawl list", "crawl.start": "scoutroctl crawl start URL [--depth N] [--scope domain|subpath|wide] [--max-pages N] [--collection NAME]",
       "crawl.status": "scoutroctl crawl status ID", "crawl.stop": "scoutroctl crawl stop ID", "config.get": "scoutroctl config get",
       "config.set": "scoutroctl config set KEY VALUE", "ui.routes": "scoutroctl ui routes", "ui.route": "scoutroctl ui route NAME"}
for path, methods in paths.items():
    for method, o in methods.items():
        params = O()
        for p in o.get("parameters", []):
            params[p["name"]] = {**p["schema"], "in": p["in"], "required": p["required"], "description": p["description"]}
        if "requestBody" in o:
            name = o["requestBody"]["content"]["application/json"]["schema"]["$ref"].split("/")[-1]
            b = schemas[name]
            for k, v in b["properties"].items():
                params[k] = {**v, "in": "body", "required": k in b.get("required", [])}
        ok_code = [c for c in o["responses"] if c.startswith("2")][0]
        ret = o["responses"][ok_code]["content"]["application/json"]["schema"]["$ref"].split("/")[-1]
        actions.append(O(
            name=o["operationId"], description=o["description"], mutating=o["x-scoutro-mutating"],
            auth="admin" if o["security"] else "public",
            http={"method": method.upper(), "path": "/scoutro/api" + path, "successStatus": int(ok_code)},
            parameters=params, returns=ret,
            errors=sorted(int(c) for c in o["responses"] if not c.startswith("2")),
            cli=cli[o["operationId"]], mcpTool=mcp[o["operationId"]]))
catalog = O(service="scoutro", apiVersion="1",
    description="Actions an agent can perform on Scoutro. Every action maps to one HTTP call of the Scoutro API; parameter schemas follow JSON Schema. Details: openapi.json.",
    openapi="/scoutro/api/openapi.json",
    authentication={"type": "http-digest", "account": "Scoutro/YaCy administrator", "publicActions": [a["name"] for a in actions if a["auth"] == "public"]},
    errorFormat={"error": {"code": "string", "message": "string", "details": "object (optional)"}},
    actions=actions, schemas=schemas)
import sys
out = sys.argv[1]
json.dump(openapi, open(out + "/openapi.json", "w"), indent=2, ensure_ascii=False); open(out + "/openapi.json", "a").write("\n")
json.dump(catalog, open(out + "/actions.json", "w"), indent=2, ensure_ascii=False); open(out + "/actions.json", "a").write("\n")
print(len(actions), "actions")
