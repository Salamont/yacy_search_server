#!/usr/bin/env python3
"""Measures what a question for job postings or providers costs at the normal chat endpoint. GPL-2.0-or-later.

Not a test: it prints one JSON line per question. A disposable peer with invented pages and a SIMULATED model (a local
stub that records what it receives; no language model is called). Per question: the model requests the server made, the
characters of each request body as the stub received it (exact), the tool calls, the characters of the answer, the
duration, and the search's own numbers (found, shown, lower bound, milliseconds, steps). Tokens are not measured: a stub
has none; characters / 3 is the estimate the chat's context budget uses, and is printed as an estimate.

The same script runs against another checkout of the repository (--repo), so that two versions answer the same questions
on the same pages: the before and after of a change to the search.

The simulated model's context is set to 131,072 tokens, as a production model with a large context: the room for sources
is then not what limits the context, and the size of a request shows what the server chose to send.

Usage: chat-search-measure.py [--repo <checkout with a compiled build>] [--out <file>]
"""
import argparse
import importlib.util
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
import urllib.parse
import http.server
from pathlib import Path

ALPHA, BAU = "alpha-web", "bau-web"
FILLER = ("Wir planen, bauen, beraten und begleiten Projekte für Kunden aus Industrie, Handel, Handwerk und Verwaltung mit Erfahrung "
          "und Sorgfalt. Unsere Leistungen reichen von der ersten Idee über die Ausführung bis zur Übergabe. ") * 12


def page(title, data, text):
    return (f'<html><head><title>{title}</title><script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script></head>'
            f"<body><h1>{title}</h1><p>{text}</p></body></html>")


def org(name, host, town=None):
    data = {"@context": "https://schema.org", "@type": "Organization", "name": name, "url": f"https://{host}/"}
    if town:
        data["address"] = {"@type": "PostalAddress", "streetAddress": "Hauptstraße 1", "postalCode": "12345", "addressLocality": town}
    return data


def pages():
    out = []
    # alpha-web: employers with a home page, pages about their work and postings; 14 postings of the subject Kotlin
    for e in range(8):
        host = f"www.firma-{e}-scoutro-measure.de"
        name = f"Firma {e} GmbH"
        o = org(name, host, "Nebenstadt")
        out.append((f"https://{host}/", ALPHA, page(name, o, f"{name}: Softwareentwicklung, Stellenanzeigen und Karriere. Kotlin im Einsatz. " + FILLER)))
        out.append((f"https://{host}/karriere", ALPHA, page(f"Karriere bei {name}", o, f"Karriere und Stellenanzeigen bei {name}: Kotlin, Java. " + FILLER)))
        for j in range(5):
            n = e * 5 + j
            subject = "Kotlin" if n % 3 == 0 else "Java"
            title = f"{subject} Entwickler (m/w/d) Team {n:02d}"
            posting = {"@context": "https://schema.org", "@type": "JobPosting", "title": title, "description": "Stellenangebot in Vollzeit.",
                       "employmentType": "FULL_TIME", "datePosted": "2026-10-01", "hiringOrganization": o}
            out.append((f"https://{host}/stellen/{n}", ALPHA, page(title, posting, f"{title}. Stellenangebot in Vollzeit bei {name}. " + FILLER)))
    # bau-web: engineering offices (one located in Musterstadt, one serving it), and house galleries that name the town
    for i in range(10):
        host = f"www.statik-{i}-scoutro-measure.de"
        name = f"Statikbüro {i}"
        town = "Musterstadt" if i == 0 else "Nebenstadt"
        area = " Einsatzgebiet: Musterstadt und Umgebung." if i == 1 else ""
        out.append((f"https://{host}/", BAU, page(name, org(name, host, town),
                    f"{name}: Tragwerksplanung und Statik für Wohnhäuser. Als Statiker prüfen wir, ob eine Wand tragend ist.{area} " + FILLER)))
    for i in range(6):
        host = f"www.hausgalerie-{i}-scoutro-measure.de"
        out.append((f"https://{host}/", BAU, page(f"Hausgalerie {i}", org(f"Hausgalerie {i}", host, "Musterstadt"),
                    f"Hausgalerie {i}: Einfamilienhäuser in Musterstadt. Jede Wand aus Ziegel, eine tragende Wand im Erdgeschoss. " + FILLER)))
    return out


def no_tools(body):
    return "answer", "SIMULATED: Antwort aus den Quellen [1]."


def once(tool, args, times=1):
    """a model that calls the tool `times` times (one call per round), then answers"""
    def script(body):
        done = [m for m in body["messages"] if m.get("role") == "tool"]
        return ("call", tool, args) if len(done) < times else ("answer", "SIMULATED: Antwort aus dem Werkzeugergebnis [1].")
    return script


QUESTIONS = [
    ("job count", ALPHA, "Wie viele Stellenanzeigen zu Kotlin gibt es?", no_tools),
    ("job list", ALPHA, "Zeige alle Stellenanzeigen zu Kotlin.", no_tools),
    ("job list, the model calls job_search twice", ALPHA, "Welche Stellenanzeigen zu Kotlin gibt es? Nenne die Arbeitgeber.",
     once("job_search", {"query": "Stellenanzeigen Kotlin"}, 2)),
    ("provider count", BAU, "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?", no_tools),
    ("provider recommendation", BAU, "Wer prüft in Musterstadt, ob eine Wand tragend ist?", no_tools),
    ("provider list, the model calls provider_search", BAU, "Welche Anbieter für Tragwerksplanung gibt es in Musterstadt? Nenne Namen und Quelle.",
     once("provider_search", {"query": "Tragwerksplanung Musterstadt"})),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--out")
    a = ap.parse_args()
    repo = Path(a.repo).resolve()
    here = repo / "test/scoutro-access"
    spec = importlib.util.spec_from_file_location("chat_tools_smoke", here / "chat-tools-live-smoke.py")
    tools = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tools)
    smoke = tools.smoke
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    stub = http.server.ThreadingHTTPServer(("127.0.0.1", 0), tools.SimulatedModel)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    hoststub = f"http://127.0.0.1:{stub.server_address[1]}"
    models = [{"service": "OLLAMA", "model": "simulated", "hoststub": hoststub, "api_key": "", "max_tokens": "512", "chat": True,
               "tooling": True, "tldr": False, "logreport": False, "knowledge": False}]
    root = Path(tempfile.mkdtemp(prefix="scoutro-chat-measure-"))
    rows = []
    process = None
    try:
        process, base = smoke.start_peer(root, port, extra=[
            "scoutro.access.protected=true", "ai.production_models=" + json.dumps(models, separators=(",", ":")),
            "ai.service_num_ctx=" + json.dumps({hoststub: 131072}, separators=(",", ":")),
            *[f"ai.tools.{tool}.enabled=true" for tool in tools.TOOLS],
            "scoutro.kg.enabled=true", f"scoutro.kg.collections={ALPHA},{BAU}", "scoutro.kg.jsonld.enabled=true",
            f"scoutro.kg.jobs.collections={ALPHA},{BAU}", f"scoutro.kg.jobs.display.collections={ALPHA},{BAU}"])
        digest = tools.Client(base, digest=("admin", "yacy"))
        stock = pages()
        for url, collection, html in stock:
            assert tools.push(digest, url, html, collection), url
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=*:*")
            jobs = tools.kg_get(digest, "entities?type=job&limit=1").get("total", 0)
            orgs = tools.kg_get(digest, "entities?type=organization&limit=1").get("total", 0)
            if isinstance(solr, dict) and solr.get("response", {}).get("numFound", 0) >= len(stock) and jobs >= 40 and orgs >= 24:
                break
            time.sleep(1)
        time.sleep(3)
        for label, collection, question, script in QUESTIONS:
            t0 = time.monotonic()
            status, answer, requests = tools.chat(digest, question, script, collection=collection)
            seconds = round(time.monotonic() - t0, 2)
            chars = [len(json.dumps(r["body"], ensure_ascii=False)) for r in requests]
            search = answer["meta"].get("scoutro-jobsearch") or answer["meta"].get("scoutro-servicesearch") or {}
            retrieval = answer["meta"].get("scoutro-retrieval") or {}
            done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
            row = {"question": label, "text": question, "collection": collection, "status": status, "seconds": seconds,
                   "modelCalls": len(requests), "requestChars": chars, "requestCharsTotal": sum(chars),
                   "inputTokensEstimate": sum(chars) // 3, "answerChars": len(answer["text"]), "outputTokensEstimate": len(answer["text"]) // 3,
                   "answeredBy": (answer["meta"].get("scoutro-answer") or {}).get("by", "model"),
                   "sources": len(answer["meta"].get("scoutro-sources", [])), "contextChars": retrieval.get("contextChars"),
                   "toolCalls": [{"tool": name, "found": r.get("found"), "shown": r.get("shown"), "reused": r.get("reused", False),
                                  "chars": len(json.dumps(r, ensure_ascii=False))} for name, r in done],
                   "found": search.get("found"), "shown": search.get("shown"), "lowerBound": search.get("lowerBound"),
                   "searchMs": search.get("millis"), "phases": search.get("phases"), "perPlace": search.get("perPlace"),
                   "usage": answer["meta"].get("scoutro-usage")}
            rows.append(row)
            print("MEASURE " + json.dumps(row, ensure_ascii=False), flush=True)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except Exception:
                process.kill()
                process.wait()
        stub.shutdown()
        shutil.rmtree(root, ignore_errors=True)
    if a.out:
        Path(a.out).write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
