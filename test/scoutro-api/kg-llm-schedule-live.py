#!/usr/bin/env python3
"""Disposable local peer + controlled model + real browser. No crawl or production DATA. GPL-2.0-or-later."""
import importlib.util
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("kg_local",ROOT/"test/scoutro-api/kg-e2e-live.py")
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
STARTS=[];RELEASE=threading.Event()
class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        if self.path!="/release":self.send_response(404);self.end_headers();return
        RELEASE.set();self.send_response(200);self.end_headers()
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers.get("Content-Length","0"))))
        STARTS.append({"at":time.monotonic(),"path":self.path})
        if len(STARTS)==1:RELEASE.wait(45)
        answer=json.dumps({"entities":[],"claims":[]})
        if self.path=="/api/chat":out={"model":body.get("model"),"done":True,"message":{"role":"assistant","content":answer}}
        else:out={"choices":[{"message":{"content":answer},"finish_reason":"stop"}]}
        data=json.dumps(out).encode();self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)

def main():
    evidence=Path(os.environ.get("SCOUTRO_SCHEDULE_EVIDENCE") or tempfile.mkdtemp(prefix="scoutro-schedule-live-"));evidence.mkdir(parents=True,exist_ok=True)
    peer_root=evidence/"peer"
    if peer_root.exists():raise ValueError("Refusing existing DATA")
    report={"checks":[],"model_requests":[],"tested_commit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip()}
    def check(value,message):
        assert value,message;report["checks"].append(message)
    model=http.server.ThreadingHTTPServer(("127.0.0.1",helper.LLM_PORT),Model);threading.Thread(target=model.serve_forever,daemon=True).start()
    peer=helper.Peer(peer_root,["scoutro.kg.enabled=true","scoutro.kg.collections=timing-a,timing-b","scoutro.kg.llm.collections=timing-a",
        "scoutro.kg.cache.maxPercent=0","scoutro.kg.llm.parallel=2","scoutro.kg.llm.timeoutSeconds=60",
        "scoutro.kg.gate.maxLoad=1000","scoutro.kg.gate.minFreeHeapMB=1","scoutro.kg.reconcile.debounceSeconds=1"],"LLM timing")
    (peer_root/".scoutro-schedule-disposable").write_text("local scheduling acceptance\n")
    try:
        peer.start();peer.wait("base ready",peer.settled)
        plan=peer.kg("/llm-schedule")["plan"];check(plan["mode"]=="automatic" and plan["minStartSeconds"]==0,"absent settings retain automatic default")
        plan["mode"]="manual";status,_,raw=peer.call("PUT","/scoutro/api/v1/kg/llm-schedule",plan);check(status==200,"admin schedule hot update")
        for path in ("llm-schedule","llm-run"):
            method="PUT" if path=="llm-schedule" else "POST";body=plan if method=="PUT" else {"action":"start"}
            check(peer.call(method,"/scoutro/api/v1/kg/"+path,body,opener=helper.ANON)[0]==401,"anonymous denied: "+path)
            check(peer.call(method,"/scoutro/api/v1/kg/"+path,body,{"Origin":"https://untrusted.invalid"})[0]==403,"cross-origin denied: "+path)
        token=peer.create_agent("timing read fixture",["kg.read"],["timing-a"])
        for path in ("llm-schedule","llm-run"):
            check(peer.agent(token,"/kg/"+path)[0] in (403,404),"agent cannot access admin timing: "+path)
        text="Impressum. Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. Das Haus Lindenhof bietet Tagespflege an. "
        long_text=text+"Information über Unternehmen und Tagespflege. "*190
        def page(name,text):
            ld=json.dumps({"@context":"https://schema.org","@type":"Organization","name":name})
            return '<html><head><title>Impressum</title><script type="application/ld+json">'+ld+'</script></head><body><h1>'+name+'</h1><p>'+text+'</p></body></html>'
        peer.push([("https://first.fixture.test/impressum",page("Muster Pflege gGmbH",long_text)),("https://second.fixture.test/impressum",page("Zweite Pflege gGmbH",long_text+"Weitere Einrichtung."))],"timing-a")
        peer.push([("https://excluded.fixture.test/impressum",page("Ausgeschlossene Pflege gGmbH",text))],"timing-b")
        peer.wait("base extraction while manual-only",lambda s:peer.settled(s) and sum((s.get("collections") or [{}])[i].get("documents",0) or 0 for i in range(len(s.get("collections") or [])))>=3)
        check(len(STARTS)==0,"source parser and base extraction work while automatic LLM starts are closed")
        env=dict(os.environ,SCOUTRO_URL=peer.base,SCOUTRO_SCHEDULE_MODEL=f"http://127.0.0.1:{helper.LLM_PORT}",SCOUTRO_SCHEDULE_MARKER=str(peer_root/".scoutro-schedule-disposable"))
        try:
            output=subprocess.check_output(["node",str(ROOT/"test/scoutro-ui/knowledge-schedule-live.mjs")],cwd=ROOT,env=env,text=True,stderr=subprocess.STDOUT,timeout=180)
        except subprocess.CalledProcessError as error:
            print(error.output,flush=True);report["browser_error"]=error.output;raise
        print(output,flush=True);report["browser"]=json.loads(output.strip().splitlines()[-1])
        finished,_=peer.wait("all eligible docs done",lambda s:s.get("llm",{}).get("documents",{}).get("done")==2)
        check(finished["llm"]["processed"]["callFailures"]==0,"manual deferrals/stop produce no failed attempts")
        check(all(b["at"]-a["at"]>=1.95 for a,b in zip(STARTS,STARTS[1:])),"actual local HTTP starts obey shared minimum interval")
        check(finished["llm"]["processed"]["requestStarts"]==len(STARTS),"actual transport counters match controlled server")
        requests=len(STARTS);peer.stop();peer.start();peer.wait("restart reconciliation",peer.settled)
        check(peer.kg("/llm-schedule")["plan"]["mode"]=="manual","schedule survives restart")
        time.sleep(2);check(len(STARTS)==requests,"restart neither reevaluates done docs nor resumes manual override")
        check(peer.status()["llm"]["timing"]["lastActualStart"]==finished["llm"]["timing"]["lastActualStart"],"last actual start survives restart")
        report["model_requests"]=STARTS;report["final_status"]=peer.status();report["result"]="passed"
        print(json.dumps({"result":"passed","api_checks":len(report["checks"]),"browser_checks":report["browser"]["checks"],"model_requests":len(STARTS)}),flush=True)
    finally:
        report["model_requests"]=STARTS
        try:report["last_status"]=peer.status()
        except Exception:pass
        RELEASE.set();peer.stop();model.shutdown();(evidence/"result.json").write_text(json.dumps(report,indent=2)+"\n")
if __name__=="__main__":main()
