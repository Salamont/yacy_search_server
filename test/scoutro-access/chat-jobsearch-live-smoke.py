#!/usr/bin/env python3
"""Disposable check of the chat's job search through the normal chat endpoint, with a SIMULATED model. GPL-2.0-or-later.

No language model is involved. The stub plays a model that never calls a tool, although the tools are offered: it
only reads the sources the server put into its prompt and names the job postings there with their numbers, or says that
there are none. What is tested is the server (docs/SCOUTRO_RAG_QUALITY.md, section 12): for a question about jobs whose
automatic search matches only weakly, it searches the job postings of the knowledge graph itself (type=job, short title
variants, the release of kg_search, the collection of the request) and gives them to the answer as numbered sources.

The invented pages of chat-tools-live-smoke.py (alpha-web: a consultancy, its services page with the words of the
original failure, two job postings; beta-web: another company with one posting). Through POST /v1/chat/completions:

1. an account of alpha-web asks for jobs: the automatic search finds only the company's home page (stage any); the job
   search finds both postings, the full match first, the partial one marked; the model cites the posting's page. The long
   question of the original failure, whose automatic search finds nothing at all, gets the same postings;
2. the follow-up "Such noch mal gezielt nach Stellenanzeigen und prüfe die Originalquellen" in the same chat: the search
   takes the goal of the conversation and finds the postings again;
3. a real zero (no posting for the subject anywhere): the answer says so, no posting is invented;
4. the collection boundary: the account asks for the posting of beta-web and gets nothing of it; the administrator's
   chat of the whole index finds it (control);
5. a job question whose automatic search matched a majority of its words, but only on a company page: the job search runs;
   a question that is not about jobs: no job search.

Requires a compiled repository and a JDK 17+. Optional: JAVA; SCOUTRO_JOBS_DUMP=<file> writes what the stub received.
"""
import importlib.util
import json
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

QUESTION = "Welche Stellenanzeigen gibt es zu SAP und KI bei Beratungsunternehmen mit Prozessautomatisierung?"
LONG = ("Ich suche für eine Marktanalyse Stellenanzeigen von Beratungsunternehmen, die sich mit Geschäftsfeldentwicklung und "
        "Prozessautomatisierung beschäftigen und dabei SAP sowie künstliche Intelligenz einsetzen. Bitte nenne die "
        "Stellenbezeichnungen, die Arbeitgeber und die Quelle mit Link.")
RETRY = "Such noch mal gezielt nach Stellenanzeigen und prüfe die Originalquellen"
ENTRY = re.compile(r"^\[(\d+)\] Scoutro knowledge graph: job posting (.+)$", re.M)


def no_tools_model(body):
    """A model that never calls a tool: it names the job postings of its prompt with their numbers, or says there are none."""
    user = next((tools.content_text(m) for m in reversed(body["messages"]) if m.get("role") == "user"), "")
    postings = ENTRY.findall(user)
    if not postings:
        return "answer", "In den Quellen ist keine passende Stellenanzeige belegt."
    lines = []
    for number, title in postings:
        block = user.split(f"[{number}] Scoutro knowledge graph: job posting", 1)[1].split("\n\n[", 1)[0]
        partial = " (erfüllt nur einen Teil der Kriterien)" if "PARTIAL MATCH" in block else ""
        lines.append(f"- {title.strip()}{partial} [{number}]")
    return "answer", "Gefundene Stellenanzeigen:\n" + "\n".join(lines) + "\nOb die Stellen noch offen sind, ist nicht geprüft."


def jobsearch(answer):
    return answer["meta"].get("scoutro-jobsearch")


def sources(answer):
    return {s.get("id"): s for s in answer["meta"].get("scoutro-sources", [])}


def run(base, digest):
    for url, collection, html in tools.PAGES:
        check(tools.push(digest, url, html, collection), "push " + url)
    deadline = tools.time.monotonic() + 180
    while True:
        jobs = tools.kg_get(digest, "entities?type=job").get("total", 0)
        if jobs >= 3 or tools.time.monotonic() > deadline:
            break
        tools.time.sleep(0.5)
    check(jobs == 3, "knowledge graph holds the three job postings", str(jobs))
    admin = Client(base)
    admin.login("admin", "yacy")
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "rita", "role": "research", "collections": ["alpha-web"],
                                                                  "password": "rita-password-1", "mustChangePassword": False})
    check(status == 201, "create the account of alpha-web", str(body)[:200])
    rita = Client(base)
    rita.login("rita", "rita-password-1")
    ki_url = f"https://{tools.TAPIR}/karriere/ki-entwickler-sap"
    sap_url = f"https://{tools.TAPIR}/karriere/sap-senior-berater-finance"

    # 1. the job question: a weak company page, the job search, a model without tool calls
    status, answer, requests = tools.chat(rita, QUESTION, no_tools_model)
    js, retrieval, listed = jobsearch(answer) or {}, answer["meta"].get("scoutro-retrieval", {}), sources(answer)
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    prompt = tools.content_text(requests[0]["body"]["messages"][-1]) if requests else ""
    check(status == 200 and len(requests) == 1, "jobs: one model call, no tool round", f"{status} {len(requests)}")
    check(tools.offered(requests) == tools.TOOLS and not tools.tool_results(requests[-1]["body"]["messages"]),
          "jobs: the tools were offered and the model called none", str(tools.offered(requests)))
    company = [s for s in answer["meta"].get("scoutro-sources", []) if s.get("url") == f"https://{tools.TAPIR}/" and "kind" not in s]
    check(retrieval.get("stage") == "any" and retrieval.get("followUp") is False and company,
          "jobs: the automatic search found only the company page (weak)", json.dumps(retrieval))
    check(js.get("triggered") and js.get("scope") == "collection alpha-web" and js.get("found") == 2 and js.get("full") == 1
          and js.get("partial") == 1 and js.get("criteria") == ["SAP", "KI", "Automatisierung"], "jobs: the server searched the postings", json.dumps(js))
    check(js.get("searched", [None])[0] == "SAP KI Automatisierung" and "SAP" in js.get("searched", []) and "KI" in js.get("searched", [])
          and len(js.get("searched", [])) <= 6, "jobs: a few short title variants, the subjects separately", str(js.get("searched")))
    check(prompt.find("[1] Scoutro knowledge graph: job posting KI-Entwickler") >= 0 and f"URL: {ki_url}" in prompt
          and 0 <= prompt.find("job posting KI-Entwickler") < prompt.find("[3] Tapir Consulting AG"),
          "jobs: the postings are the first sources of the prompt, the company page after them")
    check("PARTIAL MATCH: not shown for KI, Automatisierung" in prompt and "Application status: not checked" in prompt,
          "jobs: the partial match is marked, the application status is a separate question")
    check("Job postings: the automatic search of this question matched weakly" in system and "separate question" in system,
          "jobs: the server rules tell the model how to use the postings")
    check(listed.get(1, {}).get("url") == ki_url and listed.get(1, {}).get("kind") == "graph" and listed.get(2, {}).get("url") == sap_url,
          "jobs: the client's sources [1] and [2] are the postings' original pages", json.dumps(listed.get(1)))
    citations = answer["meta"].get("scoutro-citations", {})
    check("[1]" in answer["text"] and citations.get("invalid") == [] and 1 in citations.get("valid", [])
          and next((s for s in citations.get("support", []) if s.get("id") == 1), {}).get("verdict") == "supported",
          "jobs: the answer cites the posting's page and the check finds it supported", answer["text"] + " " + json.dumps(citations))
    check("erfüllt nur einen Teil" in answer["text"], "jobs: the partial match reaches the answer", answer["text"])
    first_answer = answer["text"]
    status, answer, requests = tools.chat(rita, LONG, no_tools_model)
    js = jobsearch(answer) or {}
    check(status == 200 and answer["meta"].get("scoutro-retrieval", {}).get("stage") == "none" and js.get("found") == 2
          and sources(answer).get(1, {}).get("url") == ki_url and "[1]" in answer["text"],
          "jobs: the long question without any search hit gets the same postings", json.dumps(js))

    # 2. the follow-up in the same chat
    history = [{"role": "user", "content": QUESTION}, {"role": "assistant", "content": first_answer}]
    status, answer, requests = tools.chat(rita, RETRY, no_tools_model, messages=history)
    js, retrieval = jobsearch(answer) or {}, answer["meta"].get("scoutro-retrieval", {})
    check(status == 200 and retrieval.get("followUp") is True and js.get("followUp") is True, "follow-up: the search takes the goal of the chat",
          json.dumps(retrieval) + json.dumps(js))
    check(js.get("found") == 2 and js.get("criteria") == ["SAP", "KI", "Automatisierung"] and js.get("scope") == "collection alpha-web",
          "follow-up: the same postings, the same scope", json.dumps(js))
    check(sources(answer).get(1, {}).get("url") == ki_url and "[1]" in answer["text"], "follow-up: the answer cites the posting again", answer["text"])

    # 3. a real zero
    status, answer, requests = tools.chat(rita, "Gibt es Stellenanzeigen für Tiefseetaucher mit Unterwasserschweißen?", no_tools_model)
    js = jobsearch(answer) or {}
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    check(status == 200 and js.get("triggered") and js.get("found") == 0 and js.get("searched"), "zero: searched, nothing found", json.dumps(js))
    check("found none" in system and "never invent one" in system, "zero: the model is told that there is none")
    check(not any("job posting" in (s.get("title") or "") for s in answer["meta"].get("scoutro-sources", []))
          and "keine passende Stellenanzeige" in answer["text"], "zero: no posting in sources or answer", answer["text"])

    # 4. the collection boundary
    question = "Gibt es eine Stelle als SAP Basis Administrator? Nenne Arbeitgeber und Quelle."
    status, answer, requests = tools.chat(rita, question, no_tools_model)
    js = jobsearch(answer) or {}
    sent = json.dumps([r["body"] for r in requests], ensure_ascii=False).replace(question, "")
    check(status == 200 and js.get("triggered") and js.get("scope") == "collection alpha-web", "boundary: searched within alpha-web", json.dumps(js))
    check(tools.none_of(sent, tools.BETA_MARKERS) and tools.none_of(json.dumps(answer["meta"], ensure_ascii=False), tools.BETA_MARKERS)
          and tools.none_of(answer["text"], tools.BETA_MARKERS), "boundary: nothing of beta-web reaches the model or the client")
    check(all(s.get("collection") in (None, "alpha-web") for s in answer["meta"].get("scoutro-sources", [])), "boundary: every source of alpha-web")
    status, answer, requests = tools.chat(digest, question, no_tools_model)
    okapi = f"https://{tools.OKAPI}/jobs/sap-basis-administrator"
    check(status == 200 and any(s.get("url") == okapi for s in answer["meta"].get("scoutro-sources", []))
          and (jobsearch(answer) or {}).get("scope") == "all collections of the local index", "boundary: the administrator's whole index finds it (control)",
          json.dumps(jobsearch(answer)))
    status, answer, requests = tools.chat(rita, question, no_tools_model, collection="beta-web")
    check(status == 403 and not requests, "boundary: the other collection as request scope is refused before the model", str(status))

    # 5. no job search where none belongs
    status, answer, requests = tools.chat(rita, "Wie ist das Wetter in Freiburg?", no_tools_model)
    check(status == 200 and jobsearch(answer) is None, "not a job question: no job search", json.dumps(answer["meta"].get("scoutro-retrieval")))
    status, answer, requests = tools.chat(rita, "Stellenangebot Tapir Consulting SAP", no_tools_model)
    stage = answer["meta"].get("scoutro-retrieval", {}).get("stage")
    js = jobsearch(answer) or {}
    check(status == 200 and stage == "majority" and js.get("found") == 2 and {sources(answer).get(1, {}).get("url"),
          sources(answer).get(2, {}).get("url")} == {ki_url, sap_url}, "a majority match on a company page only: the job search runs",
          f"{stage} {json.dumps(js)}")


def main():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    stub = http.server.ThreadingHTTPServer(("127.0.0.1", 0), tools.SimulatedModel)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    models = [{"service": "OLLAMA", "model": "simulated", "hoststub": f"http://127.0.0.1:{stub.server_address[1]}", "api_key": "",
               "max_tokens": "512", "chat": True, "tooling": True, "tldr": False, "logreport": False, "knowledge": False}]
    root = Path(tempfile.mkdtemp(prefix="scoutro-chat-jobs-"))
    process = None
    try:
        process, base = smoke.start_peer(root, port, extra=[
            "scoutro.access.protected=true", "ai.production_models=" + json.dumps(models, separators=(",", ":")),
            *[f"ai.tools.{tool}.enabled=true" for tool in tools.TOOLS],
            "scoutro.kg.enabled=true", "scoutro.kg.collections=alpha-web,beta-web", "scoutro.kg.jsonld.enabled=true",
            "scoutro.kg.jobs.collections=alpha-web,beta-web", "scoutro.kg.jobs.display.collections=alpha-web,beta-web"])
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
        if os.environ.get("SCOUTRO_JOBS_KEEP_LOG"):
            shutil.copyfile(root / "peer.log", os.environ["SCOUTRO_JOBS_KEEP_LOG"])
        shutil.rmtree(root, ignore_errors=True)
        if os.environ.get("SCOUTRO_JOBS_DUMP"):
            Path(os.environ["SCOUTRO_JOBS_DUMP"]).write_text(json.dumps(tools.REQUESTS, ensure_ascii=False, indent=1))
    print(f"{smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed; the simulated model received {len(tools.REQUESTS)} requests"
          " and called no tool (no language model was called)", flush=True)
    return 1 if smoke.RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
