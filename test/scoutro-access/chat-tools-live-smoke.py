#!/usr/bin/env python3
"""Disposable check of the chat's tools through the normal chat endpoint, with a SIMULATED model. GPL-2.0-or-later.

No language model is involved. A local OpenAI-compatible stub (127.0.0.1, no key) plays the model: for each test
question a small script decides, from the tool results the server sent back, which tool to call next and what to
answer, as a model would. What is tested is the server: which tools it offers, what it returns for a tool call, which
scope it enforces, which sources it numbers and what its citation check says about the scripted answer.

A NEW temporary offline peer in protected mode gets invented pages of two collections, alpha-web and beta-web (hosts
that do not exist, pushed through YaCy's parser and index; the knowledge graph reads them). Then, through
POST /v1/chat/completions as the chat page sends it:

1. a question about SAP/AI job postings: weak automatic hits, then search -> kg_search -> kg_entity -> kg_evidence
   and an answer that cites the pages of the tool results;
2. a question about the SAP use of a company, and about a company the graph does not hold;
3. an account limited to alpha-web whose (simulated) model asks for a page, an entity and the evidence of beta-web,
   also with collection arguments: nothing of beta-web reaches the model or the client;
4. limits: calls per tool, the scripted model that never stops calling tools still gets an answer turn.

Peer and stub are stopped and the DATA removed in a finally block. Requires a compiled repository and a JDK 17+.
Optional: JAVA; SCOUTRO_TOOLS_DUMP=<file> writes what the stub received; --ui also opens the chat page in Chromium
(test/scoutro-ui/chat-tools-ui-test.mjs; needs NODE_PATH with playwright and SCOUTRO_CHROMIUM_PATH).
"""
import http.server
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("access_smoke", HERE / "access-live-smoke.py")
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)
check, Client = smoke.check, smoke.Client

# invented hosts; the push path of a peer in the webportal network takes no host under .example
TAPIR, OKAPI = "www.tapir-consulting-scoutro-test.de", "www.okapi-systems-scoutro-test.de"
# what only beta-web holds: its marker text, its host and its company name
BETA_MARKERS = ("BETAGEHEIM", "okapi-systems", "Okapi Systems")
TOOLS = ["search", "kg_search", "kg_entity", "kg_evidence"]
REQUESTS = []
PLAN = {"next": None}  # the script of the simulated model for the current question


def content_text(message):
    content = message.get("content", "")
    if isinstance(content, list):
        return " ".join(part.get("text", "") for part in content if isinstance(part, dict))
    return str(content)


def tool_results(messages):
    """The tool results the server sent back so far, parsed, in order: [(name, result)]."""
    out = []
    for m in messages:
        if m.get("role") == "tool":
            try:
                out.append((m.get("name"), json.loads(m.get("content") or "{}")))
            except ValueError:
                out.append((m.get("name"), {"unparsed": m.get("content")}))
    return out


class SimulatedModel(http.server.BaseHTTPRequestHandler):
    """OpenAI-compatible streaming stub: runs the current script; it is not a language model."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        REQUESTS.append({"path": self.path, "body": body})
        step = PLAN["next"](body) if PLAN["next"] else ("answer", "SIMULATED-ANSWER")
        if step[0] == "call" and body.get("tool_choice") != "none":
            call = {"index": 0, "id": f"call_{len(REQUESTS)}", "type": "function",
                    "function": {"name": step[1], "arguments": json.dumps(step[2], ensure_ascii=False)}}
            chunks = [{"choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [call]}}]},
                      {"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]}]
        else:
            text = step[1] if step[0] == "answer" else "SIMULATED-ANSWER after the last tool round."
            chunks = [{"choices": [{"index": 0, "delta": {"role": "assistant", "content": text}}]},
                      {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}]
        data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def page(title, organisation, host, text, posting=None):
    org = {"@type": "Organization", "name": organisation, "url": f"https://{host}/"}
    data = dict(org, **{"@context": "https://schema.org"})
    if posting:
        data = {"@context": "https://schema.org", "@type": "JobPosting", "title": posting[0], "description": posting[1],
                "employmentType": "FULL_TIME", "hiringOrganization": org}
    return (f'<html><head><title>{title}</title><script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script></head>'
            f"<body><h1>{title}</h1><p>{text}</p></body></html>")


PAGES = [
    (f"https://{TAPIR}/", "alpha-web",
     page("Tapir Consulting AG", "Tapir Consulting AG", TAPIR,
          "Tapir Consulting AG. Wir nutzen SAP S/4HANA intern. Wir beraten den Mittelstand zu Prozessen und Organisation.")),
    (f"https://{TAPIR}/leistungen", "alpha-web",
     page("Leistungen der Tapir Consulting", "Tapir Consulting AG", TAPIR,
          "Unsere Leistungen für Beratungsunternehmen und Industrieunternehmen: Geschäftsfeldentwicklung, Prozessautomatisierung "
          "und Produktverantwortung auf Unternehmenswebseiten.")),
    (f"https://{TAPIR}/karriere/sap-senior-berater-finance", "alpha-web",
     page("SAP Senior Berater (m/w/d) Finance", "Tapir Consulting AG", TAPIR,
          "Stellenangebot in Vollzeit. Sie führen SAP-Finance-Projekte bei unseren Kunden.",
          ("SAP Senior Berater (m/w/d) Finance", "Sie führen SAP-Finance-Projekte bei unseren Kunden."))),
    (f"https://{TAPIR}/karriere/ki-entwickler-sap", "alpha-web",
     page("KI-Entwickler (m/w/d) SAP Prozessautomatisierung", "Tapir Consulting AG", TAPIR,
          "Stellenangebot in Vollzeit. Sie entwickeln KI-Agenten für die Automatisierung von SAP-Prozessen.",
          ("KI-Entwickler (m/w/d) SAP Prozessautomatisierung", "Sie entwickeln KI-Agenten für die Automatisierung von SAP-Prozessen."))),
    (f"https://{OKAPI}/", "beta-web",
     page("Okapi Systems GmbH", "Okapi Systems GmbH", OKAPI,
          "Okapi Systems GmbH. Wir nutzen SAP S/4HANA intern. BETAGEHEIM Standort Freiburg.")),
    (f"https://{OKAPI}/jobs/sap-basis-administrator", "beta-web",
     page("SAP Basis Administrator (m/w/d)", "Okapi Systems GmbH", OKAPI,
          "Stellenangebot in Vollzeit. Sie betreiben unsere SAP-Systeme. BETAGEHEIM",
          ("SAP Basis Administrator (m/w/d)", "Sie betreiben unsere SAP-Systeme. BETAGEHEIM"))),
]


def push(digest, url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items())
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="data-0"; filename="page.html"\r\n'
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(digest.base + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with digest.opener.open(request, timeout=120) as response:
        return json.loads(response.read()).get("countsuccess") == 1


def chat(client, question, script, collection=None, extra=None, messages=None):
    """POST /v1/chat/completions as the chat page does, with `script` as the simulated model of this question (after `messages`)."""
    PLAN["next"] = script
    body = {"model": "chat", "stream": True, "messages": (messages or []) + [{"role": "user", "content": question, "search": "local"}]}
    if collection is not None:
        body["collection"] = collection
    body.update(extra or {})
    before = len(REQUESTS)
    request = urllib.request.Request(client.base + "/v1/chat/completions", data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Origin": client.base})
    try:
        with client.opener.open(request, timeout=180) as response:
            status, raw = response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode("utf-8", "replace")
    answer = {"text": "", "meta": {}, "raw": raw, "tool_results": [], "first_sources": None}
    for line in raw.splitlines():
        if not line.startswith("data:") or "[DONE]" in line:
            continue
        try:
            chunk = json.loads(line[5:].strip())
        except ValueError:
            continue
        for key, value in chunk.items():
            if key == "scoutro-sources" and answer["first_sources"] is None:
                answer["first_sources"] = value  # the sources of the automatic retrieval, before any tool ran
            if key == "tool-results":
                answer["tool_results"] += value
            elif key != "choices":
                answer["meta"][key] = value  # a later line replaces an earlier one (the sources after the tools)
        for choice in chunk.get("choices", []):
            answer["text"] += (choice.get("delta") or {}).get("content") or ""
    PLAN["next"] = None
    return status, answer, REQUESTS[before:]


def model_saw(requests):
    return json.dumps([r["body"] for r in requests], ensure_ascii=False)


def none_of(text, markers):
    return not any(m in text for m in markers)


def offered(requests):
    return [t.get("function", {}).get("name") for t in (requests[0]["body"].get("tools") or [])] if requests else []


def sources(answer):
    return {f"[{s.get('id')}]": s for s in answer["meta"].get("scoutro-sources", []) if isinstance(s, dict)}


def verdicts(answer):
    return {f"[{s.get('id')}]": s.get("verdict") for s in answer["meta"].get("scoutro-citations", {}).get("support", [])}


# ------------------------------------------------------------------ scripts of the simulated model

def jobs_script(body):
    """search again, then the graph: job postings, the chosen job's employer, the evidence of that relation."""
    results = tool_results(body["messages"])
    if len(results) == 0:
        return "call", "search", {"query": "SAP KI Stellenangebot"}
    if len(results) == 1:
        return "call", "kg_search", {"query": "SAP", "type": "job"}
    if len(results) == 2:
        job = next((e for e in results[1][1].get("entities", []) if e.get("name", "").startswith("KI-Entwickler")), None)
        return ("call", "kg_entity", {"entity": job["entity"]}) if job else ("answer", "SIMULATED: kein passender Job im Graphen.")
    if len(results) == 3:
        fact = next((f for f in results[2][1].get("facts", []) if f.get("predicate") == "hiring_organization"), None)
        return ("call", "kg_evidence", {"statement": fact["statement"]}) if fact else ("answer", "SIMULATED: kein Arbeitgeber belegt.")
    page_cite = results[0][1]["sources"][0]["cite"]
    fact = next(f for f in results[2][1]["facts"] if f["predicate"] == "hiring_organization")
    evidence_cite = results[3][1]["evidence"][0]["cite"]
    return "answer", (f"Die {fact['object']['name']} sucht einen KI-Entwickler (m/w/d) für SAP Prozessautomatisierung {evidence_cite}. "
                      f"Die Stelle umfasst die Entwicklung von KI-Agenten für die Automatisierung von SAP-Prozessen {page_cite}. "
                      f"Das Jahresgehalt beträgt 95.000 Euro zuzüglich Dienstwagen {page_cite}. "
                      "Zu Arbeitsort und Bewerbungsfrist ist in den Quellen nichts belegt.")


def sap_use_script(body):
    """the company by name, its systems, and a company the graph does not hold."""
    results = tool_results(body["messages"])
    if len(results) == 0:
        return "call", "kg_search", {"query": "Tapir Consulting", "type": "organization"}
    if len(results) == 1:
        entities = results[0][1].get("entities", [])
        return ("call", "kg_entity", {"entity": entities[0]["entity"], "predicate": "system_signal"}) if entities \
            else ("answer", "SIMULATED: Unternehmen nicht im Graphen.")
    if len(results) == 2:
        return "call", "kg_search", {"query": "Phantasia Holding", "type": "organization"}
    facts = results[1][1].get("facts", [])
    if not facts:
        return "answer", "SIMULATED: Zur SAP-Nutzung der Tapir Consulting AG ist im Wissensgraphen nichts belegt."
    missing = "Zur Phantasia Holding enthält der Wissensgraph keinen Eintrag; ihre SAP-Nutzung ist nicht belegt." \
        if not results[2][1].get("entities") else "SIMULATED-UNEXPECTED: Phantasia gefunden."
    return "answer", f"Die Tapir Consulting AG nutzt SAP S/4HANA intern {facts[0]['cite']}. {missing}"


def foreign_script(ids):
    """a model that asks for the other collection in every way a tool call allows."""
    calls = [("search", {"query": "collection:beta-web SAP Basis Administrator Okapi BETAGEHEIM", "collection": "beta-web", "global": True}),
             ("kg_search", {"query": "Okapi", "collection": "beta-web", "scope": "all"}),
             ("kg_entity", {"entity": ids["entity"], "collection": "beta-web"}),
             ("kg_evidence", {"statement": ids["statement"], "collections": ["beta-web"]})]

    def script(body):
        results = tool_results(body["messages"])
        if len(results) < len(calls):
            return ("call",) + calls[len(results)]
        found = [name for name, result in results if result.get("entities") or result.get("evidence") or result.get("facts")
                 or any("okapi-systems" in json.dumps(s) for s in result.get("sources", []))]
        return "answer", ("SIMULATED-LEAK " + ",".join(found)) if found else \
            "Zu Okapi Systems ist in dieser Sammlung nichts zu finden; die angefragte Aussage ist hier nicht belegt."
    return script


def greedy_script(body):
    """a model that never stops searching."""
    return "call", "search", {"query": "SAP Berater " + str(len(tool_results(body["messages"])))}


# --------------------------------------------------------------------------------------------- run

def kg_get(digest, path):
    status, body, _ = digest.call("GET", "/scoutro/api/v1/kg/" + path)
    return body if status == 200 and isinstance(body, dict) else {}


def run(base, digest):
    for url, collection, html in PAGES:
        check(push(digest, url, html, collection), "push " + url)
    deadline = time.monotonic() + 180
    while True:
        visible = [digest.call("GET", f"/solr/select?wt=json&rows=0&q=collection_sxt:{c}")[1] for c in ("alpha-web", "beta-web")]
        counts = [v.get("response", {}).get("numFound", 0) if isinstance(v, dict) else 0 for v in visible]
        if counts == [4, 2] or time.monotonic() > deadline:
            break
        time.sleep(0.5)
    check(counts == [4, 2], "six invented pages searchable", str(counts))
    while True:
        jobs = kg_get(digest, "entities?type=job").get("total", 0)
        if jobs >= 3 or time.monotonic() > deadline:
            break
        time.sleep(0.5)
    check(jobs == 3, "knowledge graph holds the three job postings", str(jobs))

    admin = Client(base)
    admin.login("admin", "yacy")
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "rita", "role": "research", "collections": ["alpha-web"],
                                                                  "password": "rita-password-1", "mustChangePassword": False})
    check(status == 201, "create the account of alpha-web", str(body)[:200])
    rita = Client(base)
    rita.login("rita", "rita-password-1")

    # 1. SAP/AI job postings (administrator, the whole local index)
    question = ("Welche Stellenanzeigen gibt es zu SAP und künstlicher Intelligenz bei Beratungsunternehmen mit "
                "Prozessautomatisierung und Geschäftsfeldentwicklung?")
    status, answer, requests = chat(digest, question, jobs_script)
    check(status == 200 and len(requests) == 5, "jobs: four tool rounds and the answer", f"{status} {len(requests)} {answer['raw'][:300]}")
    check(offered(requests) == TOOLS, "jobs: the released tools are offered", str(offered(requests)))
    system = content_text(requests[0]["body"]["messages"][0]) if requests else ""
    check("Tools of this chat" in system and "all collections of the local index" in system, "jobs: the system prompt names tools and scope")
    stage = answer["meta"].get("scoutro-retrieval", {}).get("stage")
    check(("Note on the search results" in system) == (stage in ("any", "none")), "jobs: weak automatic hits are announced to the model",
          f"stage={stage}")
    results = tool_results(requests[-1]["body"]["messages"]) if requests else []
    check([name for name, _ in results] == TOOLS, "jobs: search, kg_search, kg_entity, kg_evidence ran in this order", str([n for n, _ in results]))
    if len(results) == 4:
        search, names, entity, evidence = (r for _, r in results)
        check(search.get("local") is True and search.get("scope") == "all collections of the local index", "jobs: the search is local", str(search)[:200])
        check(any("ki-entwickler-sap" in s.get("url", "") for s in search.get("sources", [])), "jobs: the second search finds the job page",
              str(search)[:400])
        check(names.get("total") == 3 and all(e.get("employer") for e in names.get("entities", [])), "jobs: the graph lists the postings with employer",
              str(names)[:400])
        fact = next((f for f in entity.get("facts", []) if f.get("predicate") == "hiring_organization"), {})
        check(fact.get("object", {}).get("name") == "Tapir Consulting AG" and fact.get("url", "").endswith("/karriere/ki-entwickler-sap")
              and fact.get("cite"), "jobs: the employer relation comes with its page as a numbered source", str(fact))
        row = (evidence.get("evidence") or [{}])[0]
        check(row.get("url", "").endswith("/karriere/ki-entwickler-sap") and row.get("collections") == ["alpha-web"]
              and row.get("cite") == fact.get("cite") and row.get("loaded_at"), "jobs: the evidence names page, collection and load time", str(row))
        listed = sources(answer)
        check(fact.get("cite") in listed and listed[fact["cite"]].get("kind") == "graph"
              and listed[fact["cite"]].get("url", "").endswith("/karriere/ki-entwickler-sap"),
              "jobs: the client's source list holds the graph evidence under the cited number", str(listed.get(fact.get("cite"))))
        page_cite = search["sources"][0]["cite"]
        check(page_cite in listed and "kind" not in listed[page_cite], "jobs: and the page of the second search", str(listed.get(page_cite)))
        citations = answer["meta"].get("scoutro-citations", {})
        check(citations.get("invalid") == [] and fact["cite"] in answer["text"], "jobs: every cited number is a source of the answer", str(citations))
        check(verdicts(answer).get(fact["cite"]) == "supported", "jobs: the employer statement is supported by its source", str(citations))
        check(verdicts(answer).get(page_cite) == "unsupported" and int(page_cite[1:-1]) in citations.get("unsupported", []),
              "jobs: the invented salary behind a real source is reported as unsupported", str(citations))
        check("nichts belegt" in answer["text"], "jobs: what the sources do not state stays visible in the answer", answer["text"])
        tools = answer["meta"].get("scoutro-tools", {})
        added = len(listed) - len(answer["first_sources"] or [])
        check(tools.get("calls") == 4 and tools.get("local") is True and tools.get("sources") == added, "jobs: the tool summary of the answer",
              f"{tools} added={added}")
        numbers = [s.get("id") for s in answer["meta"].get("scoutro-sources", [])]
        check(numbers == list(range(1, len(numbers) + 1)), "jobs: the sources are numbered once, in one row", str(numbers))
        check(row.get("passage", "").startswith("(no passage recorded") and "passage" not in fact,
              "jobs: evidence read from structured data says that it has no passage", str(row.get("passage")))

    # 2. the SAP use of a company; a company the graph does not hold
    status, answer, requests = chat(digest, "Nutzt die Tapir Consulting AG SAP, und die Phantasia Holding?", sap_use_script)
    results = tool_results(requests[-1]["body"]["messages"]) if requests else []
    check(status == 200 and [name for name, _ in results] == ["kg_search", "kg_entity", "kg_search"], "sap: entity, its systems, the unknown company",
          str([n for n, _ in results]))
    if len(results) == 3:
        fact = (results[1][1].get("facts") or [{}])[0]
        check(fact.get("predicate") == "system_signal" and "sap-s4hana" in str(fact.get("value")) and "SAP S/4HANA" in fact.get("passage", "")
              and fact.get("url") == f"https://{TAPIR}/", "sap: the system fact with the passage of its page", str(fact))
        check(results[2][1].get("total") == 0 and "no such entry" in results[2][1].get("note", ""), "sap: an unknown company is an explicit empty result",
              str(results[2][1]))
        check(verdicts(answer).get(fact.get("cite")) == "supported" and answer["meta"].get("scoutro-citations", {}).get("unsupported") == [],
              "sap: the cited page supports the statement", str(answer["meta"].get("scoutro-citations")))
        check("nicht belegt" in answer["text"] and "Phantasia" in answer["text"], "sap: the missing evidence is named in the answer", answer["text"])

    # 3. an account of alpha-web: evidence of beta-web
    entity = (kg_get(digest, "entities?q=Okapi&type=organization").get("items") or [{}])[0].get("id", "")
    statements = kg_get(digest, f"entities/{entity}/statements?predicate=system_signal").get("items") or [{}]
    ids = {"entity": entity, "statement": statements[0].get("id", "")}
    check(ids["entity"].startswith("kge_") and ids["statement"].startswith("kgs_"), "foreign: the fact of beta-web exists", str(ids))
    status, answer, requests = chat(digest, "Welche Belege gibt es zu Okapi Systems?", foreign_script(ids))
    control = tool_results(requests[-1]["body"]["messages"]) if requests else []
    check(len(control) == 4 and control[3][1].get("evidence") and "okapi-systems" in json.dumps(control[3][1]),
          "foreign: the administrator's chat reads that evidence (control)", str(control[3:])[:300])
    status, answer, requests = chat(rita, "Welche Belege gibt es zu Okapi Systems?", foreign_script(ids))
    saw = model_saw(requests)
    results = tool_results(requests[-1]["body"]["messages"]) if requests else []
    check(status == 200 and len(results) == 4, "foreign: the account's model made the four calls", f"{status} {len(results)}")
    check(offered(requests) == TOOLS and "collection alpha-web" in content_text(requests[0]["body"]["messages"][0]),
          "foreign: the account gets the tools within its collection", str(offered(requests)))
    if len(results) == 4:
        check(all(r.get("scope", "collection alpha-web") == "collection alpha-web" for _, r in results), "foreign: every result is of alpha-web",
              str([r.get("scope") for _, r in results]))
        check(results[1][1].get("total") == 0, "foreign: the company of beta-web is not found by name", str(results[1][1]))
        check(results[2][1].get("error") == "not_found" and results[3][1].get("error") == "not_found",
              "foreign: entity and evidence of beta-web answer like unknown ones", str(results[2:]))
        check("cannot be verified" in results[3][1].get("note", ""), "foreign: the model is told there is nothing to cite", str(results[3][1]))
    for marker in BETA_MARKERS[:2]:
        check(marker not in saw.replace("Okapi BETAGEHEIM", "").replace("collection:beta-web SAP Basis Administrator", ""),
              "foreign: nothing of beta-web reaches the model: " + marker)
    tool_text = json.dumps([r for _, r in results], ensure_ascii=False)
    check(none_of(tool_text.replace("Okapi", ""), BETA_MARKERS) and "beta-web" not in tool_text, "foreign: no tool result names beta-web", tool_text[:400])
    client_text = json.dumps(answer["meta"], ensure_ascii=False) + json.dumps(answer["tool_results"], ensure_ascii=False)
    check(none_of(client_text, ("BETAGEHEIM", "okapi-systems")), "foreign: nothing of beta-web reaches the client")
    check(all(s.get("collection") == "alpha-web" for s in answer["meta"].get("scoutro-sources", [])), "foreign: all sources are of alpha-web")
    check("SIMULATED-LEAK" not in answer["text"] and "nicht belegt" in answer["text"], "foreign: the answer says that it is not verified", answer["text"])
    status, answer, requests = chat(rita, "Welche Belege gibt es zu Okapi Systems?", foreign_script(ids), collection="beta-web")
    check(status == 403 and not requests, "foreign: the other collection as request scope is refused before the model", str(status))
    status, answer, requests = chat(rita, "Welche Stellenanzeigen gibt es zu SAP?", lambda body: (
        ("call", "kg_search", {"query": "SAP", "type": "job"}) if not tool_results(body["messages"]) else ("answer", "SIMULATED")))
    results = tool_results(requests[-1]["body"]["messages"]) if requests else []
    check(len(results) == 1 and results[0][1].get("total") == 2, "foreign: the account sees its two of the three postings", str(results)[:300])

    # 4. limits
    status, answer, requests = chat(digest, "Welche SAP Berater gibt es?", greedy_script)
    results = tool_results(requests[-1]["body"]["messages"]) if requests else []
    ran = [r for _, r in results if r.get("error") != "tool_budget"]
    check(status == 200 and len(ran) == 3, "limits: the search runs three times per answer", f"{len(ran)} of {len(results)}")
    check(len(requests) <= 9 and requests[-1]["body"].get("tool_choice") == "none" and answer["text"].startswith("SIMULATED-ANSWER"),
          "limits: after the last tool round the model is asked for the answer itself", f"{len(requests)} {answer['text'][:80]}")
    check(answer["meta"].get("scoutro-tools", {}).get("calls") == 3, "limits: refused calls are not counted as run", str(answer["meta"].get("scoutro-tools")))
    status, answer, requests = chat(Client(base), "Welche SAP Berater gibt es?", greedy_script)
    check(status == 401 and not requests, "anonymous chat refused in protected mode", str(status))


def main():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    stub = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SimulatedModel)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    models = [{"service": "OLLAMA", "model": "simulated", "hoststub": f"http://127.0.0.1:{stub.server_address[1]}", "api_key": "",
               "max_tokens": "512", "chat": True, "tooling": True, "tldr": False, "logreport": False, "knowledge": False}]
    root = Path(tempfile.mkdtemp(prefix="scoutro-chat-tools-"))
    process = None
    try:
        process, base = smoke.start_peer(root, port, extra=[
            "scoutro.access.protected=true", "ai.production_models=" + json.dumps(models, separators=(",", ":")),
            *[f"ai.tools.{tool}.enabled=true" for tool in TOOLS],
            "scoutro.kg.enabled=true", "scoutro.kg.collections=alpha-web,beta-web", "scoutro.kg.jsonld.enabled=true",
            "scoutro.kg.jobs.collections=alpha-web,beta-web", "scoutro.kg.jobs.display.collections=alpha-web,beta-web",
            "locale.language=browser"], languages=["de"])
        run(base, Client(base, digest=("admin", "yacy")))
        if "--ui" in sys.argv[1:]:
            # the chat page with the job question; the simulated model answers every chat request with the job script
            PLAN["next"] = jobs_script
            ui = subprocess.run(["node", str(HERE.parent / "scoutro-ui/chat-tools-ui-test.mjs")], cwd=HERE.parents[1],
                                env=dict(os.environ, SCOUTRO_URL=base), timeout=600, capture_output=True, text=True)
            PLAN["next"] = None
            print((ui.stdout + ui.stderr).strip()[-3000:], flush=True)
            check(ui.returncode == 0, "chat page (Chromium): tool sources and the support note")
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except Exception:
                process.kill()
                process.wait()
        stub.shutdown()
        if os.environ.get("SCOUTRO_TOOLS_KEEP_LOG"):
            shutil.copyfile(root / "peer.log", os.environ["SCOUTRO_TOOLS_KEEP_LOG"])
        shutil.rmtree(root, ignore_errors=True)
        if os.environ.get("SCOUTRO_TOOLS_DUMP"):  # what the simulated model received, for reading
            Path(os.environ["SCOUTRO_TOOLS_DUMP"]).write_text(json.dumps(REQUESTS, ensure_ascii=False, indent=1))
    print(f"{smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed; the simulated model received {len(REQUESTS)} requests"
          " (no language model was called)", flush=True)
    return 1 if smoke.RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
