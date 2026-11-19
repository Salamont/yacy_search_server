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
   takes the goal of the conversation and finds the postings again, also when the follow-up names no search mode; with
   search "no" it does not search, nor does a retry as the first message or an ordinary message without a search mode;
3. a real zero (no posting for the subject anywhere): the answer says so, no posting is invented;
4. the collection boundary: the account asks for the posting of beta-web and gets nothing of it; the administrator's
   chat of the whole index finds it (control);
5. a job question whose automatic search matched a majority of its words, but only on a company page: the job search runs;
   a question that is not about jobs: no job search;
6. a larger stock: more than 12 matching postings of one employer, postings found by title, by a fact and by the text of
   their page, a duplicate, no dates; the automatic search and job_search determine the same 17 before the answer's limit.

Requires a compiled repository and a JDK 17+. Optional: JAVA; SCOUTRO_JOBS_DUMP=<file> writes what the stub received;
--ui also opens the chat page in Chromium (test/scoutro-ui/chat-followup-ui-test.mjs: the search mode of a follow-up).
"""
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import urllib.parse
import threading
import http.server
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("chat_tools_smoke", HERE / "chat-tools-live-smoke.py")
tools = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tools)
smoke, check, Client = tools.smoke, tools.check, tools.Client

QUESTION = "Welche Stellenanzeigen gibt es zu Java und ML bei Beratungsunternehmen mit Prozessrobotik?"
LONG = ("Ich suche für eine Marktanalyse Stellenanzeigen von Beratungsunternehmen, die sich mit Geschäftsfeldentwicklung und "
        "Prozessrobotik beschäftigen und dabei Java sowie maschinelles Lernen einsetzen. Bitte nenne die "
        "Stellenbezeichnungen, die Arbeitgeber und die Quelle mit Link.")
SUBJECTS = "scoutro.chat.jobs.subjects=ML: Machine Learning, maschinelles Lernen; Robotik: Robotics"  # invented groups
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
    ml_url = f"https://{tools.TAPIR}/karriere/ml-entwickler-java"
    java_url = f"https://{tools.TAPIR}/karriere/java-senior-berater-finance"

    # 0. first, before any job search of this peer: the job search's own text searches use the index's search events, and a
    # later search of the same words may then give more pages of one host (here the postings), so it is no longer weak
    status, answer, requests = tools.chat(rita, "Stellenangebot Tapir Consulting Java", no_tools_model)
    stage = answer["meta"].get("scoutro-retrieval", {}).get("stage")
    js = jobsearch(answer) or {}
    check(status == 200 and stage == "majority" and js.get("found") == 2 and {sources(answer).get(1, {}).get("url"),
          sources(answer).get(2, {}).get("url")} == {ml_url, java_url}, "a majority match on a company page only: the job search runs",
          f"{stage} {json.dumps(js)} {[s.get('url') for s in answer['meta'].get('scoutro-sources', [])]}")

    # 1. the job question: a weak company page, the job search, a model without tool calls
    status, answer, requests = tools.chat(rita, QUESTION, no_tools_model)
    js, retrieval, listed = jobsearch(answer) or {}, answer["meta"].get("scoutro-retrieval", {}), sources(answer)
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    prompt = tools.content_text(requests[0]["body"]["messages"][-1]) if requests else ""
    check(status == 200 and len(requests) == 1, "jobs: one model call, no tool round", f"{status} {len(requests)}")
    check(tools.offered(requests) == tools.OFFERED and not tools.tool_results(requests[-1]["body"]["messages"]),
          "jobs: the tools were offered and the model called none", str(tools.offered(requests)))
    company = [s for s in answer["meta"].get("scoutro-sources", []) if s.get("url") == f"https://{tools.TAPIR}/" and "kind" not in s]
    check(retrieval.get("stage") == "any" and retrieval.get("followUp") is False and company,
          "jobs: the automatic search found only the company page (weak)", json.dumps(retrieval))
    check(js.get("triggered") and js.get("scope") == "collection alpha-web" and js.get("found") == 2 and js.get("full") == 1
          and js.get("partial") == 1 and js.get("criteria") == ["Java", "ML", "Robotik"] and js.get("shown") == 2 and js.get("lowerBound") is False
          and js.get("scanned") == 2, "jobs: the server searched all postings of alpha-web and counts found and shown", json.dumps(js))
    names = [w for w in js.get("searched", []) if not w.startswith("text: ")]
    texts = [w for w in js.get("searched", []) if w.startswith("text: ")]
    check(names[:1] == ["Java ML Robotik"] and "Java" in names and "ML" in names and len(names) <= 6
          and texts[:3] == ["text: Java", "text: ML", "text: Robotik"], "jobs: a few title variants and one text search per subject",
          str(js.get("searched")))
    check(prompt.find("[1] Scoutro knowledge graph: job posting ML-Entwickler") >= 0 and f"URL: {ml_url}" in prompt
          and 0 <= prompt.find("job posting ML-Entwickler") < prompt.find("[3] Tapir Consulting AG"),
          "jobs: the postings are the first sources of the prompt, the company page after them")
    check("PARTIAL MATCH: not shown for ML, Robotik" in prompt and "Application status" not in prompt and "not stated" not in prompt,
          "jobs: the partial match is marked; no remark about the posting's state or a missing date")
    check("Job postings: the automatic search of this question matched weakly" in system and "found 2 matching postings; 2 are given" in system
          and "count as current" in system, "jobs: the server rules give found and shown and how to use the postings")
    check(listed.get(1, {}).get("url") == ml_url and listed.get(1, {}).get("kind") == "graph" and listed.get(2, {}).get("url") == java_url,
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
          and sources(answer).get(1, {}).get("url") == ml_url and "[1]" in answer["text"],
          "jobs: the long question without any search hit gets the same postings", json.dumps(js))

    # 2. the follow-up in the same chat
    history = [{"role": "user", "content": QUESTION}, {"role": "assistant", "content": first_answer}]
    status, answer, requests = tools.chat(rita, RETRY, no_tools_model, messages=history)
    js, retrieval = jobsearch(answer) or {}, answer["meta"].get("scoutro-retrieval", {})
    check(status == 200 and retrieval.get("followUp") is True and js.get("followUp") is True, "follow-up: the search takes the goal of the chat",
          json.dumps(retrieval) + json.dumps(js))
    check(js.get("found") == 2 and js.get("criteria") == ["Java", "ML", "Robotik"] and js.get("scope") == "collection alpha-web",
          "follow-up: the same postings, the same scope", json.dumps(js))
    check(sources(answer).get(1, {}).get("url") == ml_url and "[1]" in answer["text"], "follow-up: the answer cites the posting again", answer["text"])
    # the follow-up without a search mode (the lens of the chat page off after the first question): it searches all the same
    status, answer, requests = tools.chat(rita, RETRY, no_tools_model, messages=history, search=None)
    js, retrieval = jobsearch(answer) or {}, answer["meta"].get("scoutro-retrieval", {})
    check(status == 200 and retrieval.get("followUp") is True and js.get("followUp") is True and js.get("found") == 2
          and js.get("scope") == "collection alpha-web" and sources(answer).get(1, {}).get("url") == ml_url,
          "follow-up without a search mode: searches locally with the goal of the chat", json.dumps(retrieval) + json.dumps(js))
    # switched off for this message: no search, also for a follow-up
    status, answer, requests = tools.chat(rita, RETRY, no_tools_model, messages=history, search="no")
    check(status == 200 and "scoutro-retrieval" not in answer["meta"] and jobsearch(answer) is None,
          "follow-up with search no: no search", json.dumps(answer["meta"].get("scoutro-retrieval")))
    # a retry as the first message has no earlier goal: no search without a search mode
    status, answer, requests = tools.chat(rita, RETRY, no_tools_model, search=None)
    check(status == 200 and "scoutro-retrieval" not in answer["meta"] and jobsearch(answer) is None,
          "a retry as the first message, no search mode: no search", json.dumps(answer["meta"].get("scoutro-retrieval")))
    # an ordinary message without a search mode after a search: no search ("no search" as the default of the chat page)
    status, answer, requests = tools.chat(rita, "Danke. Fasse das bitte kurz zusammen.", no_tools_model, messages=history, search=None)
    check(status == 200 and "scoutro-retrieval" not in answer["meta"], "an ordinary message without a search mode: no search",
          json.dumps(answer["meta"].get("scoutro-retrieval")))

    # 3. a real zero
    status, answer, requests = tools.chat(rita, "Gibt es Stellenanzeigen für Tiefseetaucher mit Unterwasserschweißen?", no_tools_model)
    js = jobsearch(answer) or {}
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    check(status == 200 and js.get("triggered") and js.get("found") == 0 and js.get("searched"), "zero: searched, nothing found", json.dumps(js))
    check("found none" in system and "never invent one" in system, "zero: the model is told that there is none")
    check(not any("job posting" in (s.get("title") or "") for s in answer["meta"].get("scoutro-sources", []))
          and "keine passende Stellenanzeige" in answer["text"], "zero: no posting in sources or answer", answer["text"])

    # 4. the collection boundary
    question = "Gibt es eine Stelle als Java Basis Administrator? Nenne Arbeitgeber und Quelle."
    status, answer, requests = tools.chat(rita, question, no_tools_model)
    js = jobsearch(answer) or {}
    sent = json.dumps([r["body"] for r in requests], ensure_ascii=False).replace(question, "")
    check(status == 200 and js.get("triggered") and js.get("scope") == "collection alpha-web", "boundary: searched within alpha-web", json.dumps(js))
    check(tools.none_of(sent, tools.BETA_MARKERS) and tools.none_of(json.dumps(answer["meta"], ensure_ascii=False), tools.BETA_MARKERS)
          and tools.none_of(answer["text"], tools.BETA_MARKERS), "boundary: nothing of beta-web reaches the model or the client")
    check(all(s.get("collection") in (None, "alpha-web") for s in answer["meta"].get("scoutro-sources", [])), "boundary: every source of alpha-web")
    status, answer, requests = tools.chat(digest, question, no_tools_model)
    okapi = f"https://{tools.OKAPI}/jobs/java-basis-administrator"
    # the posting is found, by the job search of the whole index or, when the automatic search already holds the posting's page
    # (no weak match, see 0.), by that search
    check(status == 200 and any(s.get("url") == okapi for s in answer["meta"].get("scoutro-sources", []))
          and (jobsearch(answer) is None or jobsearch(answer).get("scope") == "all collections of the local index"),
          "boundary: the administrator's whole index finds it (control)", json.dumps(jobsearch(answer)))
    status, answer, requests = tools.chat(rita, question, no_tools_model, collection="beta-web")
    check(status == 403 and not requests, "boundary: the other collection as request scope is refused before the model", str(status))

    # 5. no job search where none belongs
    status, answer, requests = tools.chat(rita, "Wie ist das Wetter in Nordstadt?", no_tools_model)
    check(status == 200 and jobsearch(answer) is None, "not a job question: no job search", json.dumps(answer["meta"].get("scoutro-retrieval")))

    # 6. a larger stock (invented): 14 Kotlin postings of one employer, a sensor posting by title, the same on a second page, one
    # by its field only (a graph fact) and one by the text of its page only; a long question that the automatic search answers
    # weakly: every matching posting is determined before the answer's limit, both subjects are shown
    def posting_page(title, text, field=None):
        org = {"@type": "Organization", "name": "Tapir Consulting AG", "url": f"https://{tools.TAPIR}/"}
        data = {"@context": "https://schema.org", "@type": "JobPosting", "title": title, "description": "Stellenangebot in Vollzeit.",
                "hiringOrganization": org}
        if field:
            data["occupationalCategory"] = field
        return (f'<html><head><title>{title}</title><script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script>'
                f"</head><body><h1>{title}</h1><p>{text}</p></body></html>")
    stock = [(f"https://{tools.TAPIR}/stellen/kotlin-entwickler-{n:02d}", posting_page(f"Kotlin Entwickler (m/w/d) Gruppe {n:02d}",
              f"Kotlin Entwickler (m/w/d) Gruppe {n:02d}. Stellenangebot in Vollzeit.")) for n in range(1, 15)]
    stock += [(f"https://{tools.TAPIR}/stellen/sensorik-ingenieur", posting_page("Sensorik Ingenieur (m/w/d)", "Sensorik Ingenieur (m/w/d).")),
              (f"https://{tools.TAPIR}/jobs/sensorik-ingenieur", posting_page("Sensorik Ingenieur (m/w/d)", "Sensorik Ingenieur (m/w/d).")),
              (f"https://{tools.TAPIR}/stellen/ingenieur-pruefstand", posting_page("Ingenieur (m/w/d) Prüfstand", "Ingenieur (m/w/d) Prüfstand.",
                                                                                  field="Sensorik")),
              (f"https://{tools.TAPIR}/stellen/techniker-labor", posting_page("Techniker (m/w/d) Labor",
                                                                             "Techniker (m/w/d) Labor. Sie kalibrieren Messgeräte der Sensorik."))]
    for url, html in stock:
        check(tools.push(digest, url, html, "alpha-web"), "push " + url)
    deadline = tools.time.monotonic() + 180
    while tools.kg_get(digest, "entities?type=job").get("total", 0) < 3 + len(stock) - 1 and tools.time.monotonic() < deadline:
        tools.time.sleep(0.5)
    while tools.time.monotonic() < deadline:  # and searchable in the index (the text of the pages)
        n = 0
        for url, _ in stock:
            status, solr, _ = digest.call("GET", "/solr/select?wt=json&rows=0&q=" + urllib.parse.quote(f'sku:"{url}"'))
            n += 1 if isinstance(solr, dict) and solr.get("response", {}).get("numFound") else 0
        if n == len(stock):
            break
        tools.time.sleep(0.5)
    big = ("Ich suche für eine Marktanalyse Stellenanzeigen von Beratungsunternehmen, die sich mit Geschäftsfeldentwicklung "
           "beschäftigen und dabei Kotlin oder Sensorik einsetzen. Bitte nenne die Stellenbezeichnungen, die Arbeitgeber und die Quelle mit Link.")
    status, answer, requests = tools.chat(rita, big, no_tools_model)
    js = jobsearch(answer) or {}
    titles = [s.get("title", "") for s in answer["meta"].get("scoutro-sources", []) if "job posting" in s.get("title", "")]
    per = js.get("perSubject") or {}
    check(status == 200 and js.get("triggered") and js.get("found") == 17 and js.get("lowerBound") is False and js.get("shown") <= 6
          and per.get("Kotlin") == 14 and per.get("Sensorik") == 3 and js.get("scope") == "collection alpha-web"
          and any(w.startswith("text: site:") for w in js.get("searched", [])),
          "stock: 17 postings determined before the limit (14 Kotlin, sensor by title, fact and page text; the duplicate once)",
          json.dumps(js))
    check(any("Sensorik Ingenieur" in t for t in titles) and any("Kotlin Entwickler" in t for t in titles) and len(titles) == len(set(titles))
          and len(titles) <= 6, "stock: the answer shows both subjects, no posting twice", str(titles))
    system = tools.content_text(requests[0]["body"]["messages"][0]) if requests else ""
    check(f"found 17 matching postings; {js.get('shown')} are given" in system, "stock: the model is told how many were found and shown")
    # the same search as the tool: the model calls job_search
    status, answer, requests = tools.chat(rita, "Welche Stellen gibt es zu Kotlin oder Sensorik?", lambda body: (
        ("call", "job_search", {"query": "Kotlin oder Sensorik"}) if not tools.tool_results(body["messages"]) else ("answer", "SIMULATED")))
    done = tools.tool_results(requests[-1]["body"]["messages"]) if requests else []
    result = done[0][1] if done else {}
    check(status == 200 and result.get("found") == 17 and result.get("lower_bound") is False and result.get("shown") == 6
          and result.get("per_subject") == {"Kotlin": 14, "Sensorik": 3}, "stock: job_search determines the same postings",
          json.dumps({k: v for k, v in result.items() if k != "postings"}) + " " + str([p.get("url") for p in result.get("postings", [])]))


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
            "scoutro.access.protected=true", SUBJECTS, "ai.production_models=" + json.dumps(models, separators=(",", ":")),
            *[f"ai.tools.{tool}.enabled=true" for tool in tools.TOOLS],
            "scoutro.kg.enabled=true", "scoutro.kg.collections=alpha-web,beta-web", "scoutro.kg.jsonld.enabled=true",
            "scoutro.kg.jobs.collections=alpha-web,beta-web", "scoutro.kg.jobs.display.collections=alpha-web,beta-web"])
        run(base, Client(base, digest=("admin", "yacy")))
        if "--ui" in sys.argv[1:]:
            # the chat page: the search mode it sends for a follow-up (test/scoutro-ui/chat-followup-ui-test.mjs)
            tools.PLAN["next"] = no_tools_model
            ui = subprocess.run(["node", str(HERE.parent / "scoutro-ui/chat-followup-ui-test.mjs")], cwd=HERE.parents[1],
                                env=dict(os.environ, SCOUTRO_URL=base), timeout=600, capture_output=True, text=True)
            tools.PLAN["next"] = None
            print((ui.stdout + ui.stderr).strip()[-4000:], flush=True)
            check(ui.returncode == 0, "chat page (Chromium): the search mode of a follow-up")
        # the server log of the follow-ups: the search mode as sent and as used, and the goal of the chat
        log = (root / "peer.log").read_text(errors="replace")
        check(re.search(r"event=rag-request phase=messages .*ragMode=local searchMode=followUp", log)
              and re.search(r"event=rag-retrieval phase=end ragMode=local followUp=true", log),
              "log: a follow-up without a search mode searches locally with followUp=true")
        check(re.search(r"event=rag-request phase=messages .*ragMode=no searchMode=sent", log), "log: search no as sent is respected")
        check(re.search(r"event=rag-request phase=messages .*ragMode=no searchMode=absent", log), "log: no search mode and no earlier goal: no search")
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
