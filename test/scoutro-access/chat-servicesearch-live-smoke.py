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
   index's search events cold and warm: the same providers, each once.

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
    check(gallery and retrieval.get("collection") == BAU and retrieval.get("followUp") is False,
          "service: the automatic search found the house gallery", json.dumps(retrieval))
    check(sv.get("triggered") and sv.get("scope") == f"collection {BAU}" and sv.get("services") == ["Tragwerksplanung", "Statiker", "Statik"]
          and sv.get("place") == "Musterstadt" and "index: Tragwerksplanung" in sv.get("searched", []) and "graph service: Statik" in sv.get("searched", [])
          and sv.get("lowerBound") is False, "service: service and place derived; the graph and the whole index searched", json.dumps(sv))
    check(sv.get("found") == 2 and sv.get("shown") == 2 and sv.get("placeNamedBy") == [1] and sv.get("perPlace", {}).get("location") == 1,
          "service: two offices found, each once; only the first located in Musterstadt (its address in the graph)", json.dumps(sv))
    check(listed.get(1, {}).get("url") == office and listed.get(2, {}).get("url") == f"https://{RHEINBLICK}/"
          and 0 <= prompt.find("[1] Provider Ingenieurbüro Ostermann") < prompt.find("Hausgalerie: Einfamilienhaus")
          and "Located in Musterstadt: address in the graph" in prompt,
          "service: the office's page is source [1], before the gallery", json.dumps(listed.get(1)))
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
             f"www.statik-{n}-scoutro-test.de", f"Statikbüro Nummer {n}: Tragwerksplanung für Wohnhäuser.", "Nebenstadt")) for n in range(1, 10)]
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
    check(status == 200 and result.get("found") == 11 and result.get("shown") == 6 and result.get("lower_bound") is False
          and tools.none_of(json.dumps(result, ensure_ascii=False), FOREIGN_MARKERS), "tool: provider_search determines the same providers",
          json.dumps({k: v for k, v in result.items() if k != "providers"}))
    print(f"PROVIDERS: offices pushed=12 pages (11 providers in bau-web, one of them on two pages; one in other-web), found={warm.get('found')},"
          f" shown={warm.get('shown')}, perPlace={warm.get('perPlace')}; search ms cold={cold.get('millis')} warm={warm.get('millis')};"
          f" chat request s={timings}", flush=True)


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
            "scoutro.kg.enabled=true", f"scoutro.kg.collections={BAU},other-web", "scoutro.kg.jsonld.enabled=true",
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
