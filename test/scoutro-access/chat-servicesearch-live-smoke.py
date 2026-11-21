#!/usr/bin/env python3
"""Disposable check of the chat's service search through the normal chat endpoint, with a SIMULATED model. GPL-2.0-or-later.

No language model is involved. The stub plays a model that never calls a tool, although the tools are offered: it names
the first source whose text names the service and the town, or says that no provider was found. What is tested is the
server (docs/SCOUTRO_RAG_QUALITY.md, section 13): for a question for a provider ("Wer prüft in Musterstadt, ob eine Wand
tragend ist?") whose automatic search finds only a house gallery, it derives the service and the place and searches the
local index again ("Tragwerksplanung Musterstadt", "Statiker Musterstadt", ...) with the release of the search tool and the
collection of the request, and gives the provider's page to the answer as the first numbered source.

Invented pages (hosts that do not exist), pushed through YaCy's parser and index; the knowledge graph reads them:
bau-web: a house gallery with the words of the question, an engineering office in Musterstadt, one in Nebenstadt;
other-web: an engineering office in Musterstadt that a chat of bau-web must never see. Through /v1/chat/completions:

1. an account of bau-web asks the question: the automatic search finds the gallery only; the service search
   determines the providers in the graph and the whole index: the office located in Musterstadt (its address in the graph)
   first, the one in Nebenstadt after it; each once; the model cites the office's page;
2. the follow-up "Such noch mal" in the same chat searches with the goal of the conversation;
3. a question for a service no page offers: the answer says that no provider was found;
4. the collection boundary: nothing of other-web for the account, the administrator's whole index finds it (control);
5. no service search where none belongs (a question that is not for a provider); a job question keeps the job search;
6. more than eight providers (one on two pages), a question for their number after strong first hits, the tool, and the
   index's search events cold and warm: the same providers, each once;
7. provider_search with "Tragwerksplanung in Musterstadt", "Tragwerksplanung Musterstadt" and the place field: the same place,
   providers and categories; a place of several words; no place; long pages within the tool room of a 4,096-token model;
   only the providers given become numbered sources; a count question names no service "viele";
8. a plain count question is answered by the server without a model request; a tool call for the search the server already
   made in the same request reuses its result;
9. more providers name the place than the search reads (135 providers, 133 naming the place, 120 read): the total stays
   complete; located and serving are lower bounds; 14 providers are open (13 not read, one read in part), not mere mentions;
   the same in the server's count answer, provider_search with and without "in", and the tool's reuse in one request;
10. service and place by the same provider's identity: an address on a provider's site is its location only when it is its
   own (its imprint), not a listed company's, a plot's or a project's; an address without a name is open; the place has
   its own source beside the page naming the service; the count answer (no model request) and the tool say the same.
Requires a compiled repository and a JDK 17+. Optional: JAVA; SCOUTRO_SERVICES_DUMP=<file> writes what the stub received.
"""
import importlib.util
import json
import urllib.parse
import os
import re
import shutil
import socket
import sys
import tempfile
import threading
import http.server
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("chat_tools_smoke", HERE / "chat-tools-live-smoke.py")
tools = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tools)
smoke, check, Client = tools.smoke, tools.check, tools.Client

GALLERY, OSTERMANN, RHEINBLICK, FOREIGN = ("www.hausgalerie-scoutro-test.de", "www.ostermann-statik-scoutro-test.de",
                                           "www.rheinblick-statik-scoutro-test.de", "www.fremd-statik-scoutro-test.de")
BAU = "bau-web"
QUESTION = "Wer prüft in Musterstadt, ob eine Wand tragend ist?"
FOREIGN_MARKERS = ("FREMDMARKE", "fremd-statik")
# a long page of an office: the evidence the rc.4 provider_search could not fit into the tool room of a 4,096-token model
LONG_EVIDENCE = "Tragwerksplanung, Statik und Bauphysik für Wohnhäuser, Gewerbebauten und Bestandsgebäude. " * 12
ENTRY = re.compile(r"^\[(\d+)\] (.+)\nURL: (\S+)(?:\nCollection: \S+)?\nText: (.*)$", re.M)


def page(title, organisation, host, text, town):
    data = {"@context": "https://schema.org", "@type": "Organization", "name": organisation, "url": f"https://{host}/",
            "address": {"@type": "PostalAddress", "addressLocality": town}}
    return (f'<html><head><title>{title}</title><script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script></head>'
            f"<body><h1>{title}</h1><p>{text}</p></body></html>")


PAGES = [
    (f"https://{GALLERY}/galerie/musterstadt", BAU, page("Hausgalerie: Einfamilienhaus in Musterstadt", "Hausgalerie Rhein-Erft", GALLERY,
     "Unsere Hausgalerie zeigt Einfamilienhäuser in Musterstadt. Jede Wand aus Ziegel, offene Grundrisse, große Fenster.", "Musterstadt")),
    (f"https://{OSTERMANN}/", BAU, page("Ingenieurbüro Ostermann – Tragwerksplanung in Musterstadt", "Ingenieurbüro Ostermann", OSTERMANN,
     "Ingenieurbüro Ostermann: Tragwerksplanung und Statik für Neubau, Umbau und Sanierung in Musterstadt. Als Statiker prüfen wir "
     "die Standsicherheit von Bauteilen.", "Musterstadt")),
    (f"https://{RHEINBLICK}/", BAU, page("Statikbüro Rheinblick", "Statikbüro Rheinblick", RHEINBLICK,
     "Statikbüro Rheinblick: Tragwerksplanung für Wohnhäuser in Nebenstadt.", "Nebenstadt")),
    (f"https://{FOREIGN}/", "other-web", page("Statik Musterstadt GmbH", "Statik Musterstadt GmbH", FOREIGN,
     "Tragwerksplanung und Statik in Musterstadt. FREMDMARKE", "Musterstadt")),
]


def wait_entity(digest, name, timeout=180):
    """until the knowledge graph has read the organisation of a pushed page (its address), or the timeout"""
    deadline = tools.time.monotonic() + timeout
    while tools.time.monotonic() < deadline:
        if tools.kg_get(digest, "entities?q=" + urllib.parse.quote(name)).get("total", 0) >= 1:
            return True
        tools.time.sleep(0.5)
    return False


def no_tools_model(body):
    """A model that never calls a tool: the first source naming the service and Musterstadt, or none."""
    user = next((tools.content_text(m) for m in reversed(body["messages"]) if m.get("role") == "user"), "")
    if "and found none" in tools.content_text(body["messages"][0]):  # the server's rule: no provider of this service
        return "answer", "In den Quellen ist kein passender Anbieter belegt."
    for number, title, url, text in ENTRY.findall(user):
        if re.search(r"tragwerksplanung|statik", text, re.I) and "Musterstadt" in text:
            return "answer", f"{title.split(' – ')[0]} bietet Tragwerksplanung und Statik in Musterstadt an [{number}]."
    return "answer", "In den Quellen ist kein passender Anbieter belegt."


def services(answer):
    return answer["meta"].get("scoutro-servicesearch")


def sources(answer):
    return {s.get("id"): s for s in answer["meta"].get("scoutro-sources", [])}


def run(base, digest):
    for url, collection, html in PAGES:
        check(tools.push(digest, url, html, collection), "push " + url)
    deadline = tools.time.monotonic() + 180
    while True:  # pushed pages become searchable with the next commit of the index queue: wait for each of them
        n = 0
        for url, _, _ in PAGES:
            status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=" + urllib.parse.quote(f'sku:"{url}"'))
            n += 1 if isinstance(solr, dict) and solr.get("response", {}).get("numFound") else 0
        if n == len(PAGES) or tools.time.monotonic() > deadline:
            break
        tools.time.sleep(0.5)
    check(n == len(PAGES), "four invented pages searchable", str(n))
    wait_entity(digest, "Ostermann")  # the graph's address of the office (its location), not only the text of its page
    admin = Client(base)
    admin.login("admin", "yacy")
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "bert", "role": "research", "collections": [BAU],
                                                                  "password": "bert-password-1", "mustChangePassword": False})
    check(status == 201, "create the account of bau-web", str(body)[:200])
    bert = Client(base)
    bert.login("bert", "bert-password-1")
    office = f"https://{OSTERMANN}/"

    # 1. the question: a gallery first, the service search, a model without tool calls
    status, answer, requests = tools.chat(bert, QUESTION, no_tools_model)
    sv, retrieval, listed = services(answer) or {}, answer["meta"].get("scoutro-retrieval", {}), sources(answer)
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    prompt = tools.content_text(requests[0]["body"]["messages"][-1]) if requests else ""
    gallery = [s for s in answer["meta"].get("scoutro-sources", []) if GALLERY in (s.get("url") or "")]
    check(status == 200 and len(requests) == 1 and tools.offered(requests) == tools.OFFERED
          and not tools.tool_results(requests[-1]["body"]["messages"]), "service: one model call, tools offered, none called",
          f"{status} {len(requests)} {tools.offered(requests)}")
    check(not gallery and retrieval.get("collection") == BAU and retrieval.get("followUp") is False and retrieval.get("kind") == "recommend"
          and retrieval.get("focused") is True and "Hausgalerie" not in prompt,
          "service: a recommendation: the providers found are the context, the house gallery of the automatic search stays out", json.dumps(retrieval))
    check(sv.get("triggered") and sv.get("scope") == f"collection {BAU}" and sv.get("services") == ["Tragwerksplanung", "Statiker", "Statik"]
          and sv.get("place") == "Musterstadt" and "index: Tragwerksplanung" in sv.get("searched", []) and "graph: Statik" in sv.get("searched", [])
          and sv.get("lowerBound") is False, "service: service and place derived; the graph and the whole index searched", json.dumps(sv))
    check(sv.get("found") == 2 and sv.get("shown") == 2 and sv.get("placeNamedBy") == [1] and sv.get("perPlace", {}).get("location") == 1,
          "service: two offices found, each once; only the first located in Musterstadt (its address in the graph)", json.dumps(sv))
    check(listed.get(1, {}).get("url") == office and listed.get(2, {}).get("url") == f"https://{RHEINBLICK}/" and len(listed) == 2
          and prompt.find("[1] Provider Ingenieurbüro Ostermann") >= 0 and "Located in Musterstadt: address in the graph" in prompt
          and "The question asks for a recommendation" in system,
          "service: the office's page is source [1], the model is told to weigh the evidence", json.dumps(listed.get(1)))
    usage = answer["meta"].get("scoutro-usage", {})
    check(usage.get("modelCalls") == 1 and usage.get("tokens") == "estimated" and usage.get("requestChars", 0) > 0 and sv.get("phases", {}).keys()
          >= {"graph", "index", "place", "selection", "evidence"}, "service: the model calls and the steps of the search are reported",
          json.dumps(usage) + " " + json.dumps(sv.get("phases")))
    check("Providers: Scoutro searched the providers" in system and "found 2 providers; 2 are given" in system
          and "Only [1] have an evidenced location in or service area of Musterstadt" in system,
          "service: the server rules give found and shown and which provider is evidenced in the place")
    citations = answer["meta"].get("scoutro-citations", {})
    check("[1]" in answer["text"] and citations.get("invalid") == [] and next((s for s in citations.get("support", []) if s.get("id") == 1), {})
          .get("verdict") == "supported", "service: the answer cites the office's page, the check finds it supported",
          answer["text"] + " " + json.dumps(citations))
    first_answer = answer["text"]

    # 2. the follow-up in the same chat
    history = [{"role": "user", "content": QUESTION}, {"role": "assistant", "content": first_answer}]
    status, answer, requests = tools.chat(bert, "Such noch mal", no_tools_model, messages=history)
    sv = services(answer) or {}
    check(status == 200 and answer["meta"].get("scoutro-retrieval", {}).get("followUp") is True and sv.get("followUp") is True
          and sv.get("place") == "Musterstadt" and sources(answer).get(1, {}).get("url") == office and "[1]" in answer["text"],
          "follow-up: the same service, place and office", json.dumps(sv))

    # 3. a service no page offers
    status, answer, requests = tools.chat(bert, "Wer prüft in Musterstadt den Schornstein?", no_tools_model)
    sv = services(answer) or {}
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    check(status == 200 and sv.get("triggered") and sv.get("found") == 0 and sv.get("lowerBound") is False and "and found none" in system
          and "kein passender Anbieter" in answer["text"], "zero: searched, no provider, the answer says so", json.dumps(sv))

    # 4. the collection boundary
    status, answer, requests = tools.chat(bert, QUESTION, no_tools_model)
    sent = json.dumps([r["body"] for r in requests], ensure_ascii=False)
    check(tools.none_of(sent, FOREIGN_MARKERS) and tools.none_of(json.dumps(answer["meta"], ensure_ascii=False), FOREIGN_MARKERS)
          and all(s.get("collection") in (None, BAU) for s in answer["meta"].get("scoutro-sources", [])),
          "boundary: nothing of other-web reaches the model or the client")
    status, answer, requests = tools.chat(digest, QUESTION, no_tools_model)
    check(status == 200 and any(FOREIGN in (s.get("url") or "") for s in answer["meta"].get("scoutro-sources", [])),
          "boundary: the administrator's whole index finds the other office (control)", json.dumps(services(answer)))

    # 5. no service search where none belongs
    status, answer, requests = tools.chat(bert, "Was kostet ein Fertighaus?", no_tools_model)
    check(status == 200 and services(answer) is None, "not a question for a provider: no service search")
    status, answer, requests = tools.chat(bert, "Welche Stellenanzeigen für Statiker gibt es in Musterstadt?", no_tools_model)
    check(status == 200 and services(answer) is None and (answer["meta"].get("scoutro-jobsearch") or {}).get("triggered"),
          "a job question keeps the job search (type=job), no service search", json.dumps(answer["meta"].get("scoutro-jobsearch")))

    # 6. more than eight providers (nine more offices, each its own host; one of them twice, on two pages), a question for all
    # of them: the full search after strong first hits; the tool; the index's search events cold and warm
    more = [(f"https://www.statik-{n}-scoutro-test.de/", BAU, page(f"Statikbüro Nummer {n}", f"Statikbüro Nummer {n}",
             f"www.statik-{n}-scoutro-test.de", f"Statikbüro Nummer {n}: Tragwerksplanung für Wohnhäuser. " + LONG_EVIDENCE, "Nebenstadt"))
            for n in range(1, 10)]
    more.append(("https://www.statik-1-scoutro-test.de/leistungen", BAU, page("Leistungen", "Statikbüro Nummer 1", "www.statik-1-scoutro-test.de",
                 "Tragwerksplanung, Statik und Brandschutz.", "Nebenstadt")))
    for url, collection, html in more:
        check(tools.push(digest, url, html, collection), "push " + url)
    deadline = tools.time.monotonic() + 180
    while tools.time.monotonic() < deadline:
        n = 0
        for url, _, _ in more:
            status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=" + urllib.parse.quote(f'sku:"{url}"'))
            n += 1 if isinstance(solr, dict) and solr.get("response", {}).get("numFound") else 0
        if n == len(more):
            break
        tools.time.sleep(0.5)
    timings = {}
    all_q = "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"
    t0 = tools.time.monotonic()
    status, answer, requests = tools.chat(bert, all_q, no_tools_model)
    timings["all, cold"] = round(tools.time.monotonic() - t0, 2)
    cold = services(answer) or {}
    check(status == 200 and cold.get("triggered") and cold.get("found") == 11 and cold.get("shown", 0) <= 6 and cold.get("lowerBound") is False
          and cold.get("perPlace", {}).get("location") == 1, "many: 11 providers determined (more than 8), each office once over its pages",
          json.dumps(cold)[:700])
    direct = answer["meta"].get("scoutro-answer", {})
    cites = answer["meta"].get("scoutro-citations", {})
    check(len(requests) == 0 and direct.get("by") == "server" and answer["meta"].get("scoutro-usage", {}).get("modelCalls") == 0
          and "gibt es **11** Anbieter für Tragwerksplanung" in answer["text"] and "Standort in Musterstadt: **1**" in answer["text"]
          and "Angezeigt werden 6 davon" in answer["text"] and "[1]" in answer["text"] and cites.get("invalid") == []
          and tools.none_of(answer["text"], FOREIGN_MARKERS),
          "count: the plain count question is answered by the server, no model request; found, shown and the place categories",
          f"requests={len(requests)} {json.dumps(direct)} {answer['text'][:400]}")
    status, answer, requests = tools.chat(bert, all_q + " Nenne die drei besten.", no_tools_model)
    check(status == 200 and len(requests) == 1 and "scoutro-answer" not in answer["meta"] and (services(answer) or {}).get("found") == 11
          and "The question asks for a number" in tools.content_text(requests[0]["body"]["messages"][0]),
          "count: a count question that asks for more goes to the model with the number found", f"requests={len(requests)}")
    for words in ("Tragwerksplanung", "Tragwerksplanung Musterstadt"):
        digest.call("GET", "/yacysearch.json?resource=local&maximumRecords=50&query=" + urllib.parse.quote(words))
    t0 = tools.time.monotonic()
    status, answer, requests = tools.chat(bert, all_q, no_tools_model)
    timings["all, warm"] = round(tools.time.monotonic() - t0, 2)
    warm = services(answer) or {}
    check(warm.get("found") == cold.get("found") and warm.get("perPlace") == cold.get("perPlace"),
          "cache: the same providers with the index's search events cold and warm", json.dumps(warm.get("perPlace")))
    status, answer, requests = tools.chat(bert, "Welche Statiker gibt es?", lambda body: (
        ("call", "provider_search", {"query": "Tragwerksplanung Musterstadt"}) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")))
    done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
    result = done[0][1] if done else {}
    check(status == 200 and "error" not in result and result.get("found") == 11 and 1 <= result.get("shown", 0) <= 6
          and result.get("lower_bound") is False and tools.none_of(json.dumps(result, ensure_ascii=False), FOREIGN_MARKERS),
          "tool: provider_search determines the same providers", json.dumps({k: v for k, v in result.items() if k != "providers"}))

    # 7. the tool with and without "in", the place field, a place of several words, no place, within the tool room of the
    # simulated model's context (the default 4,096 tokens: 4,096 characters for all tool results of an answer)
    auen = "www.auenblick-statik-scoutro-test.de"
    check(tools.push(digest, f"https://{auen}/", page("Statikbüro Auenblick", "Statikbüro Auenblick", auen,
          "Statikbüro Auenblick: Statik und Tragwerksplanung in Neustadt an der Musterau. " + LONG_EVIDENCE, "Neustadt an der Musterau"), BAU),
          "push the office in Neustadt an der Musterau")
    deadline = tools.time.monotonic() + 180
    while tools.time.monotonic() < deadline:
        status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=" + urllib.parse.quote(f'sku:"https://{auen}/"'))
        if isinstance(solr, dict) and solr.get("response", {}).get("numFound"):
            break
        tools.time.sleep(0.5)
    wait_entity(digest, "Auenblick")

    def tool(args, question="Welche Statiker gibt es?"):
        status, answer, requests = tools.chat(bert, question, lambda body: (
            ("call", "provider_search", args) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")))
        raw = next((m.get("content") for m in requests[-1]["body"]["messages"] if m.get("role") == "tool"), "") if requests else ""
        try:
            result = json.loads(raw or "{}")
        except ValueError:
            result = {"invalid": raw[:200]}
        cites = [int(x.get("cite", "[0]").strip("[]")) for x in result.get("providers", [])]
        ids = [s.get("id", 0) for s in answer["meta"].get("scoutro-sources", [])]
        # a provider whose page is already a source of the answer keeps its number; no source after the tool's own is uncited
        uncited = [i for i in ids if i not in cites]
        leaked = [i for i in ids if uncited and i > max(uncited) and i not in cites] + [c for c in cites if c not in ids]
        tail = [i for i in ids if not uncited or i > max(uncited)]
        return status, result, len(raw), cites, (leaked, tail)

    forms = {}
    for label, args in (("in", {"query": "Tragwerksplanung in Musterstadt"}), ("bare", {"query": "Tragwerksplanung Musterstadt"}),
                        ("field", {"query": "Tragwerksplanung", "place": "Musterstadt"})):
        status, result, size, cites, later = tool(args)
        forms[label] = result
        first = (result.get("providers") or [{}])[0]
        check(status == 200 and "error" not in result and "invalid" not in result and result.get("place") == "Musterstadt"
              and result.get("found") == 12 and result.get("lower_bound") is False and (result.get("per_place") or {}).get("location") == 1
              and 3 <= result.get("shown", 0) <= 6 and first.get("name") == "Ingenieurbüro Ostermann" and first.get("place") == "location"
              and size <= 4096 and result.get("shortened") is True,
              f"tool {label}: {json.dumps(args, ensure_ascii=False)}: the place Musterstadt, 12 providers each once, within the room",
              f"size={size} " + json.dumps({k: v for k, v in result.items() if k != "providers"}, ensure_ascii=False)[:600])
        leaked, tail = later
        check(bool(cites) and not leaked and len(set(cites)) == len(cites) and all(i in cites for i in tail),
              f"tool {label}: only the providers given became numbered sources", f"cites={cites} leaked={leaked} tail={tail}")
    check(forms["in"].get("per_place") == forms["bare"].get("per_place") == forms["field"].get("per_place")
          and [x.get("name") for x in forms["in"]["providers"]] == [x.get("name") for x in forms["bare"]["providers"]],
          "tool: with and without \"in\" and with the place field the same providers and place categories",
          json.dumps({k: v.get("per_place") for k, v in forms.items()}))
    for args in ({"query": "Statik Neustadt an der Musterau"}, {"query": "Statik in Neustadt an der Musterau"}):
        status, result, size, cites, later = tool(args)
        first = (result.get("providers") or [{}])[0]
        check(status == 200 and "error" not in result and result.get("place") == "Neustadt an der Musterau"
              and (result.get("per_place") or {}).get("location") == 1 and first.get("name") == "Statikbüro Auenblick" and size <= 4096,
              f"tool: a place of several words, {json.dumps(args, ensure_ascii=False)}",
              f"size={size} per_place={result.get('per_place')} first={first.get('name')}/{first.get('place')} found={result.get('found')}"
              f" shown={result.get('shown')} error={result.get('error')}")
    status, result, size, cites, later = tool({"query": "Tragwerksplanung"})
    check(status == 200 and "error" not in result and result.get("place") is None and "per_place" not in result and result.get("found") == 12
          and size <= 4096, "tool: no place: all providers, no place categories", json.dumps({k: v for k, v in result.items() if k != "providers"})[:400])
    # the automatic path with a place of several words, and a count question names no service "viele"
    status, answer, requests = tools.chat(bert, "Wie viele Anbieter für Tragwerksplanung gibt es in Neustadt an der Musterau?", no_tools_model)
    sv = services(answer) or {}
    check(status == 200 and sv.get("triggered") and sv.get("place") == "Neustadt an der Musterau" and sv.get("found") == 12
          and (sv.get("perPlace") or {}).get("location") == 1, "automatic: a place of several words", json.dumps(sv)[:500])
    status, answer, requests = tools.chat(bert, "Wie viele Anbieter für Fliesenarbeiten gibt es in Musterstadt?", no_tools_model)
    sv = services(answer) or {}
    check(status == 200 and not any(s.lower() in ("viele", "wie") for s in sv.get("services", [])),
          "automatic: a count question names no service \"viele\"", json.dumps(sv.get("services")))
    # 8. the same search again in one request: the model calls provider_search for what the server already searched
    status, answer, requests = tools.chat(bert, "Welche Anbieter für Tragwerksplanung gibt es in Musterstadt? Nenne Namen und Quelle.", lambda body: (
        ("call", "provider_search", {"query": "Tragwerksplanung Musterstadt"}) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")))
    auto = services(answer) or {}
    done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
    result = done[0][1] if done else {}
    check(status == 200 and auto.get("found") == 12 and result.get("reused") is True and result.get("found") == auto.get("found")
          and result.get("per_place") == auto.get("perPlace") and answer["meta"].get("scoutro-usage", {}).get("modelCalls") == 2,
          "reuse: provider_search for the same service and place returns the result of this request",
          json.dumps({k: v for k, v in result.items() if k != "providers"}, ensure_ascii=False)[:500])
    open_place(digest)
    own_place(digest)
    two_companies(digest)
    print(f"PROVIDERS: offices pushed=12 pages (11 providers in bau-web, one of them on two pages; one in other-web), found={warm.get('found')},"
          f" shown={warm.get('shown')}, perPlace={warm.get('perPlace')}; search ms cold={cold.get('millis')} warm={warm.get('millis')};"
          f" chat request s={timings}", flush=True)


PLACE_WEB = "place-web"


def plain(title, text):
    return f"<html><head><title>{title}</title></head><body><h1>{title}</h1><p>{text}</p></body></html>"


def open_place(digest):
    """9. More providers name the place than the search reads (120): the total stands, located and serving are at least,
    the providers whose pages naming the place were not all read are open, not mere mentions; the same for the server's
    count answer, provider_search with and without "in", and the tool's reuse of the result in one request."""
    stock = [("https://www.am-ort-statik-scoutro-test.de/", "Statikbüro am Ort", "Tragwerksplanung und Statik für Wohnhäuser."),
             ("https://www.am-ort-statik-scoutro-test.de/kontakt", "Kontakt", "Tragwerksplanung. Statikbüro am Ort, Hauptstraße 1, 12345 Musterstadt."),
             ("https://www.umland-statik-scoutro-test.de/", "Statikbüro Umland", "Tragwerksplanung für Wohnhäuser."),
             ("https://www.umland-statik-scoutro-test.de/gebiet", "Gebiet", "Tragwerksplanung. Einsatzgebiet: Musterstadt und Umgebung.")]
    stock += [(f"https://www.viele-statik-scoutro-test.de/projekt-{i}", f"Projekt {i}",
               ("Tragwerksplanung. " if i < 3 else "") + "Ein Wohnhaus, die Bauherren kommen aus Musterstadt.") for i in range(5)]
    stock += [(f"https://www.galerie-{i:03d}-scoutro-test.de/", f"Hausgalerie {i}",
               "Ein Einfamilienhaus aus Musterstadt. Die Tragwerksplanung kam vom Architekten.") for i in range(130)]
    stock += [(f"https://www.fern-{c}-statik-scoutro-test.de/", f"Statikbüro Fern {c}", "Tragwerksplanung in Neustadt.") for c in "ab"]
    pushed = sum(1 for url, title, text in stock if tools.push(digest, url, plain(title, text), PLACE_WEB))
    check(pushed == len(stock), f"open place: push {len(stock)} invented pages to {PLACE_WEB}", str(pushed))
    deadline = tools.time.monotonic() + 300
    n = 0
    while tools.time.monotonic() < deadline:
        status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=*:*&fq=" + urllib.parse.quote(f'collection_sxt:"{PLACE_WEB}"'))
        n = solr.get("response", {}).get("numFound", 0) if isinstance(solr, dict) else 0
        if n >= len(stock):
            break
        tools.time.sleep(1)
    check(n == len(stock), f"open place: {len(stock)} pages of 135 providers searchable in {PLACE_WEB}", str(n))
    question = "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"
    status, answer, requests = tools.chat(digest, question, no_tools_model, collection=PLACE_WEB)
    sv = services(answer) or {}
    per = sv.get("perPlace") or {}
    expected = {"location": 1, "service_area": 1, "undecided": 14, "mentioned": 117, "none": 2}
    check(status == 200 and sv.get("found") == 135 and sv.get("lowerBound") is False and sv.get("placeComplete") is False and per == expected
          and len(sv.get("placeGaps") or []) == 2,
          "open place: 135 providers, complete; located 1, serving 1, open 14 (13 not read, one read in part), mentioned 117, none 2",
          json.dumps({k: sv.get(k) for k in ("found", "lowerBound", "placeComplete", "perPlace", "placeGaps", "phases")}, ensure_ascii=False))
    text = answer["text"]
    check(len(requests) == 0 and (answer["meta"].get("scoutro-answer") or {}).get("by") == "server"
          and answer["meta"].get("scoutro-usage", {}).get("modelCalls") == 0,
          "open place: the plain count question is answered by the server, no model request", f"requests={len(requests)}")
    check("gibt es **135** Anbieter für Tragwerksplanung" in text and "Diese Gesamtzahl ist vollständig" in text and "Untergrenze" not in text
          and "Standort in Musterstadt: mindestens **1**" in text and "Einsatzgebiet: mindestens **1**" in text
          and "Bei **14** Anbietern ist der Ortsbezug offen" in text and "Höchstgrenze von 120" in text
          and "117 nennen Musterstadt nur beiläufig" in text and "(Ortsbezug offen)" in text and "[1]" in text,
          "open place: the answer keeps the total apart, says at least for located and serving, and counts the open ones with the reason",
          text[:900])
    forms = {}
    for label, args in (("in", {"query": "Tragwerksplanung in Musterstadt"}), ("bare", {"query": "Tragwerksplanung Musterstadt"})):
        status, answer, requests = tools.chat(digest, "Welche Statiker gibt es?", lambda body, a=args: (
            ("call", "provider_search", a) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")), collection=PLACE_WEB)
        done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
        forms[label] = done[0][1] if done else {}
    check(all(f.get("found") == 135 and f.get("lower_bound") is False and f.get("place_complete") is False and f.get("per_place") == expected
              and len(f.get("place_gaps") or []) == 2 and "open for 14 providers" in (f.get("note") or "") for f in forms.values()),
          "open place: provider_search with and without \"in\": the same total, the same open relation and its reasons",
          json.dumps({k: {x: v.get(x) for x in ("found", "lower_bound", "place_complete", "per_place")} for k, v in forms.items()}))
    status, answer, requests = tools.chat(digest, "Welche Anbieter für Tragwerksplanung gibt es in Musterstadt? Nenne Namen und Quelle.",
                                          lambda body: (("call", "provider_search", {"query": "Tragwerksplanung Musterstadt"})
                                                        if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")), collection=PLACE_WEB)
    auto = services(answer) or {}
    done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
    result = done[0][1] if done else {}
    auto_urls = [s.get("url") for s in answer["meta"].get("scoutro-sources", [])[:auto.get("shown", 0)]]
    tool_urls = [p.get("url") for p in result.get("providers", [])]
    print("OPEN PLACE: " + json.dumps({"found": sv.get("found"), "lowerBound": sv.get("lowerBound"), "placeComplete": sv.get("placeComplete"),
          "perPlace": per, "placeGaps": sv.get("placeGaps"), "searchMs": sv.get("millis"), "phases": sv.get("phases"),
          "answer": text[:700]}, ensure_ascii=False), flush=True)
    check(status == 200 and result.get("reused") is True and result.get("found") == auto.get("found") == 135
          and result.get("per_place") == auto.get("perPlace") == expected and bool(tool_urls) and bool(auto_urls)
          and tool_urls[:min(len(tool_urls), len(auto_urls))] == auto_urls[:min(len(tool_urls), len(auto_urls))],
          "open place: the tool reuses the search of the request: the same total, categories and providers in the same order",
          json.dumps({"auto": auto_urls, "tool": tool_urls}))


IDENT_WEB = "ident-web"


def own_place(digest):
    """10. Service and place by the same provider's identity: an address in the place on a provider's site is its location
    only when it is its own. Invented sites of the index: an office with its address on its imprint (located, with the
    imprint as the source of the place beside the page naming the service), a directory that lists other companies'
    addresses, a builder with a plot for sale, a contractor with reference projects (all three: named, not located), a site
    with an address without a name on a news page (open) and an office elsewhere (none). The same for the server's count
    answer (no model request) and provider_search with and without "in"."""
    listing = ("Karl Beispiel (1 Bewertung). Offen für Aufträge. Kringsweg 12, 12345 Musterstadt. Profil ansehen. Berta Muster GmbH (5 Bewertungen)."
               " Hafenweg 3, 12345 Musterstadt. Profil ansehen. Anton Bau. Ring 5, 54321 Nebenstadt. Dora Hochbau. Weg 9, 55555 Oststadt.")
    stock = [("https://www.eins-statik-scoutro-test.de/", "Statikbüro Eins", "Tragwerksplanung für Wohnhäuser und Hallen."),
             ("https://www.eins-statik-scoutro-test.de/impressum", "Impressum", "Angaben gemäß § 5 DDG. Hauptstraße 1, 12345 Musterstadt. Telefon 01234 5678."),
             ("https://www.bauverzeichnis-scoutro-test.de/nordstadt", "Bauunternehmen in Nordstadt » Fachbetriebe",
              "Muss der Bauunternehmer einen Statiker hinzuziehen? Fragen Sie nach der Tragwerksplanung."),
             ("https://www.bauverzeichnis-scoutro-test.de/region", "Bauunternehmen der Region » Fachbetriebe", listing),
             ("https://www.hausbau-zwei-scoutro-test.de/", "Hausbau Zwei", "Erst die Statik, dann der Grundriss: Tragwerksplanung inklusive."),
             ("https://www.hausbau-zwei-scoutro-test.de/service/grundstuecksboerse", "Freie Grundstücke finden",
              "12345 Musterstadt. Grundstücksgröße: ab 2500 m². Begehrte Wohnlage. 54321 Nebenstadt. Grundstücksgröße: ab 480 m²."),
             ("https://www.hochbau-drei-scoutro-test.de/hochbau", "Hochbau in Nebenstadt",
              "Mit den von Ihnen beauftragten Architekten und Statikern arbeiten wir Hand in Hand. Tragwerksplanung auf Wunsch."),
             ("https://www.hochbau-drei-scoutro-test.de/referenzen", "Referenzen",
              "Unsere Bauvorhaben. Supermarkt-Filiale – 12345 Musterstadt-Süd. Mehrfamilienhaus – 12345 Musterstadt."),
             ("https://www.dachs-planung-scoutro-test.de/", "Dachs Planung", "Tragwerksplanung für Umbauten."),
             ("https://www.dachs-planung-scoutro-test.de/aktuelles", "Aktuelles",
              "Tag der offenen Tür: Marktplatz 5, 12345 Musterstadt. Im Herbst in 54321 Nebenstadt."),
             ("https://www.igel-statik-scoutro-test.de/", "Igel Statik", "Tragwerksplanung in Nebenstadt.")]
    pushed = sum(1 for url, title, text in stock if tools.push(digest, url, plain(title, text), IDENT_WEB))
    check(pushed == len(stock), f"own place: push {len(stock)} invented pages to {IDENT_WEB}", str(pushed))
    deadline = tools.time.monotonic() + 300
    n = 0
    while tools.time.monotonic() < deadline:
        status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=*:*&fq=" + urllib.parse.quote(f'collection_sxt:"{IDENT_WEB}"'))
        n = solr.get("response", {}).get("numFound", 0) if isinstance(solr, dict) else 0
        if n >= len(stock):
            break
        tools.time.sleep(1)
    question = "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"
    status, answer, requests = tools.chat(digest, question, no_tools_model, collection=IDENT_WEB)
    sv = services(answer) or {}
    expected = {"location": 1, "service_area": 0, "undecided": 1, "mentioned": 3, "none": 1}
    text = answer["text"]
    check(status == 200 and sv.get("found") == 6 and sv.get("lowerBound") is False and sv.get("perPlace") == expected
          and sv.get("placeComplete") is False and sv.get("scope") == f"collection {IDENT_WEB}",
          "own place: 6 providers; located 1 (its imprint), open 1 (an address without a name), named 3 (a directory, a plot, projects), none 1",
          json.dumps({k: sv.get(k) for k in ("found", "lowerBound", "perPlace", "placeComplete", "placeGaps", "phases")}, ensure_ascii=False))
    listed = answer["meta"].get("scoutro-sources", [])
    check(len(requests) == 0 and (answer["meta"].get("scoutro-answer") or {}).get("by") == "server"
          and answer["meta"].get("scoutro-usage", {}).get("modelCalls") == 0 and "gibt es **6** Anbieter" in text
          and "Standort in Musterstadt: mindestens **1**" in text and "Bei **1** Anbietern ist der Ortsbezug offen" in text
          and "Beleg für den Ort: https://www.eins-statik-scoutro-test.de/impressum" in text
          and listed and listed[0].get("url") == "https://www.eins-statik-scoutro-test.de/"
          and all(s.get("collection") in (None, IDENT_WEB) for s in listed),
          "own place: the count is answered by the server without a model request; the place has its own source beside the service's page",
          f"requests={len(requests)} {text[:900]}")
    forms = {}
    for label, args in (("in", {"query": "Tragwerksplanung in Musterstadt"}), ("bare", {"query": "Tragwerksplanung Musterstadt"})):
        status, answer, requests = tools.chat(digest, "Welche Statiker gibt es?", lambda body, a=args: (
            ("call", "provider_search", a) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")), collection=IDENT_WEB)
        done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
        forms[label] = done[0][1] if done else {}
    first = (forms["in"].get("providers") or [{}])[0]
    check(all(f.get("found") == 6 and f.get("lower_bound") is False and f.get("per_place") == expected for f in forms.values())
          and forms["in"].get("providers") == forms["bare"].get("providers") and first.get("place") == "location"
          and first.get("place_source") == "https://www.eins-statik-scoutro-test.de/impressum"
          and not any(p.get("place") == "location" for p in (forms["in"].get("providers") or [])[1:]),
          "own place: provider_search with and without \"in\": the same providers, one located with the source of its place",
          json.dumps({k: {x: v.get(x) for x in ("found", "lower_bound", "per_place")} for k, v in forms.items()})
          + " " + json.dumps([{x: p.get(x) for x in ("name", "place", "place_source", "place_note")} for p in forms["in"].get("providers") or []],
                             ensure_ascii=False)[:900])
    print("OWN PLACE: " + json.dumps({"found": sv.get("found"), "perPlace": sv.get("perPlace"), "placeGaps": sv.get("placeGaps"),
          "searchMs": sv.get("millis"), "phases": sv.get("phases")}, ensure_ascii=False), flush=True)


TWIN_WEB = "twin-web"


def two_companies(digest):
    """11. Two companies on one domain: one offers the service, the other one has the evidenced address or service area in
    the place. The combination is no local provider: not in the server's count answer, not in what a recommendation is
    given, not in provider_search. Three such sites (the site's operator with its address in the knowledge graph, an
    imprint of another company, a service area of another company) and two sites that stay local (one company; two
    companies of which the one with the address offers the service too)."""
    stock = [("https://www.elster-bau-scoutro-test.de/", page("Elster Bau GmbH", "Elster Bau GmbH", "www.elster-bau-scoutro-test.de",
                                                              "Elster Bau GmbH: Hochbau und Schlüsselfertigbau.", "Musterstadt")),
             ("https://www.elster-bau-scoutro-test.de/partner/finken-statik", plain("Finken Statik GmbH – Tragwerksplanung",
              "Die Finken Statik GmbH erstellt die Tragwerksplanung für Wohnhäuser. Sitz in Nebenstadt.")),
             ("https://www.gimpel-haus-scoutro-test.de/leistungen/statik", plain("Habicht Statik GmbH – Tragwerksplanung",
              "Die Habicht Statik GmbH übernimmt die Tragwerksplanung für Ihr Haus.")),
             ("https://www.gimpel-haus-scoutro-test.de/impressum", plain("Impressum", "Angaben gemäß § 5 DDG. Gimpel Haus GmbH. Hauptstraße 1,"
                                                                                       " 12345 Musterstadt.")),
             ("https://www.kleiber-hof-scoutro-test.de/statik", plain("Kuckuck Statik GmbH – Tragwerksplanung", "Die Kuckuck Statik GmbH plant Tragwerke.")),
             ("https://www.kleiber-hof-scoutro-test.de/dach", plain("Kleiber Dach GmbH – Dacharbeiten",
                                                                    "Kleiber Dach GmbH. Einsatzgebiet: Musterstadt und Umgebung.")),
             ("https://www.meise-statik-scoutro-test.de/leistungen", plain("Tragwerksplanung – Meise Statik GmbH", "Tragwerksplanung für Neubau und Bestand.")),
             ("https://www.meise-statik-scoutro-test.de/impressum", plain("Impressum", "Meise Statik GmbH. Hauptstraße 1, 12345 Musterstadt.")),
             ("https://www.sperber-hof-scoutro-test.de/statik", plain("Sperber Statik GmbH – Tragwerksplanung", "Die Sperber Statik GmbH plant Tragwerke.")),
             ("https://www.sperber-hof-scoutro-test.de/bau", plain("Storch Bau GmbH – Bauen aus einer Hand",
                                                                   "Die Storch Bau GmbH übernimmt auch die Tragwerksplanung.")),
             ("https://www.sperber-hof-scoutro-test.de/impressum", plain("Impressum", "Storch Bau GmbH. Hauptstraße 1, 12345 Musterstadt."))]
    pushed = sum(1 for url, html in stock if tools.push(digest, url, html, TWIN_WEB))
    check(pushed == len(stock), f"two companies: push {len(stock)} invented pages to {TWIN_WEB}", str(pushed))
    deadline = tools.time.monotonic() + 300
    n = 0
    while tools.time.monotonic() < deadline:
        status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=*:*&fq=" + urllib.parse.quote(f'collection_sxt:"{TWIN_WEB}"'))
        n = solr.get("response", {}).get("numFound", 0) if isinstance(solr, dict) else 0
        if n >= len(stock):
            break
        tools.time.sleep(1)
    check(n == len(stock) and wait_entity(digest, "Elster Bau"), "two companies: the pages are searchable and the graph has read the site's operator",
          str(n))
    apart = ("elster-bau-scoutro-test.de", "gimpel-haus-scoutro-test.de", "kleiber-hof-scoutro-test.de")
    local = {"www.meise-statik-scoutro-test.de", "www.sperber-hof-scoutro-test.de"}
    host = lambda url: urllib.parse.urlparse(url or "").hostname
    expected = {"location": 2, "service_area": 0, "undecided": 0, "mentioned": 3, "none": 0}
    question = "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"
    status, answer, requests = tools.chat(digest, question, no_tools_model, collection=TWIN_WEB)
    sv = services(answer) or {}
    text = answer["text"]
    listed = sv.get("placeHosts") or {}
    check(status == 200 and sv.get("found") == 5 and sv.get("lowerBound") is False and sv.get("perPlace") == expected
          and sv.get("scope") == f"collection {TWIN_WEB}"
          and {"www." + h for h in listed.get("location") or []} == local and sorted(listed.get("mentioned") or []) == sorted(apart)
          and not listed.get("service_area") and not listed.get("undecided"),
          "two companies: 5 providers; located 2 (one company each for service and address), 3 named only (the address or area is another company's)",
          json.dumps({k: sv.get(k) for k in ("found", "lowerBound", "perPlace", "placeComplete", "placeGaps", "placeHosts", "phases")},
                     ensure_ascii=False))
    check(len(requests) == 0 and (answer["meta"].get("scoutro-answer") or {}).get("by") == "server"
          and answer["meta"].get("scoutro-usage", {}).get("modelCalls") == 0 and "gibt es **5** Anbieter" in text
          and "Standort in Musterstadt: **2**" in text and "3 nennen Musterstadt nur beiläufig" in text
          and not any(f"{h}/" in line and "Standort in Musterstadt" in line for h in apart for line in text.splitlines()),
          "two companies: the count is answered by the server without a model request; none of the three combinations is counted as located",
          f"requests={len(requests)} {text[:1200]}")
    # a recommendation: the model is given the hits; of the three sites none as located or serving
    status, answer, requests = tools.chat(digest, "Welchen Anbieter für Tragwerksplanung in Musterstadt empfiehlst du?", no_tools_model,
                                          collection=TWIN_WEB)
    given = next((tools.content_text(m) for m in reversed(requests[-1]["body"]["messages"]) if m.get("role") == "user"), "") if requests else ""
    entries = ENTRY.findall(given)
    rec = services(answer) or {}
    located = {url for number, title, url, body in entries if "Located in Musterstadt" in body or "Serves Musterstadt" in body}
    wrong = [url for number, title, url, body in entries if any(h in url for h in apart)
             and ("Located in Musterstadt" in body or "Serves Musterstadt" in body)]
    noted = [url for number, title, url, body in entries if any(h in url for h in apart) and "another company on the same site" in body]
    by = {s.get("id"): s.get("url") for s in answer["meta"].get("scoutro-sources", [])}
    # of the two companies of one site, the one with the address is shown by its own page naming the service
    check(status == 200 and len(requests) == 1 and rec.get("perPlace") == expected and {host(u) for u in located} == local and not wrong
          and len(noted) >= 1 and {host(by.get(i)) for i in rec.get("placeNamedBy") or []} == local
          and "https://www.sperber-hof-scoutro-test.de/bau" in located and not any(h in answer["text"] for h in apart),
          "two companies: a recommendation is given two located providers; the three combinations are not located, and say whose the address is",
          json.dumps({"located": sorted(located), "wrong": wrong, "noted": noted, "placeNamedBy": rec.get("placeNamedBy"),
                      "answer": answer["text"][:200]}, ensure_ascii=False) + " " + given[:1500])
    forms = {}
    for label, args in (("in", {"query": "Tragwerksplanung in Musterstadt"}), ("bare", {"query": "Tragwerksplanung Musterstadt"})):
        status, answer, requests = tools.chat(digest, "Welche Statiker gibt es?", lambda body, a=args: (
            ("call", "provider_search", a) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")), collection=TWIN_WEB)
        done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
        forms[label] = done[0][1] if done else {}
    shown = forms["in"].get("providers") or []
    check(all(f.get("found") == 5 and f.get("lower_bound") is False and f.get("per_place") == expected for f in forms.values())
          and forms["in"].get("providers") == forms["bare"].get("providers")
          and {host(p.get("url")) for p in shown if p.get("place") in ("location", "service_area")} == local
          and all(p.get("place") == "mentioned" and "another company on the same site" in (p.get("place_note") or "") and "entity" not in p
                  for p in shown if any(h in (p.get("url") or "") for h in apart)),
          "two companies: provider_search with and without \"in\": the same two located providers; the others with whose the address is",
          json.dumps({k: {x: v.get(x) for x in ("found", "lower_bound", "per_place")} for k, v in forms.items()})
          + " " + json.dumps([{x: p.get(x) for x in ("name", "url", "place", "place_source", "place_note")} for p in shown], ensure_ascii=False)[:1500])
    print("TWO COMPANIES: " + json.dumps({"found": sv.get("found"), "perPlace": sv.get("perPlace"), "placeGaps": sv.get("placeGaps"),
          "searchMs": sv.get("millis"), "phases": sv.get("phases")}, ensure_ascii=False), flush=True)


def main():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    stub = http.server.ThreadingHTTPServer(("127.0.0.1", 0), tools.SimulatedModel)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    models = [{"service": "OLLAMA", "model": "simulated", "hoststub": f"http://127.0.0.1:{stub.server_address[1]}", "api_key": "",
               "max_tokens": "512", "chat": True, "tooling": True, "tldr": False, "logreport": False, "knowledge": False}]
    root = Path(tempfile.mkdtemp(prefix="scoutro-chat-services-"))
    process = None
    try:
        process, base = smoke.start_peer(root, port, extra=[
            "scoutro.access.protected=true", "ai.production_models=" + json.dumps(models, separators=(",", ":")),
            *[f"ai.tools.{tool}.enabled=true" for tool in tools.TOOLS],
            "scoutro.kg.enabled=true", f"scoutro.kg.collections={BAU},other-web,{TWIN_WEB}", "scoutro.kg.jsonld.enabled=true",
            f"scoutro.kg.jobs.collections={BAU},other-web"])
        run(base, Client(base, digest=("admin", "yacy")))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except Exception:
                process.kill()
                process.wait()
        stub.shutdown()
        if os.environ.get("SCOUTRO_SERVICES_KEEP_LOG"):
            shutil.copyfile(root / "peer.log", os.environ["SCOUTRO_SERVICES_KEEP_LOG"])
        shutil.rmtree(root, ignore_errors=True)
        if os.environ.get("SCOUTRO_SERVICES_DUMP"):
            Path(os.environ["SCOUTRO_SERVICES_DUMP"]).write_text(json.dumps(tools.REQUESTS, ensure_ascii=False, indent=1))
    print(f"{smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed; the simulated model received {len(tools.REQUESTS)} requests"
          " and called no tool (no language model was called)", flush=True)
    return 1 if smoke.RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
