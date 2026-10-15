#!/usr/bin/env python3
# GPL-2.0-or-later.
"""Run the existing image smoke assertions via container loopback with network=none.

Transport and the package's non-root UID/GID are adapted. Existing kg-image-smoke.py creates disposable DATA
and performs its original lifecycle/backup assertions; no external peer/model
can be reached. No existing container with the fixture name may be touched.
"""
import io
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[2]
NAME = "scoutro-kg-image-smoke"
IMAGE = sys.argv[1]
DOCKER = shutil.which("docker")
assert DOCKER, "Docker CLI missing"
LOCAL = [DOCKER, "--host=unix:///var/run/docker.sock"]
existing = subprocess.run(LOCAL + ["ps", "-a", "--filter", f"name=^/{NAME}$", "--format", "{{.Names}}"],
                          capture_output=True, text=True, check=True).stdout.strip()
assert not existing, "Refusing to touch a preexisting fixture container"


class LoopbackOpener:
    def open(self, request, timeout=30):
        if isinstance(request, str):
            request = urllib.request.Request(request)
        prefix = "http://127.0.0.1:18091/"
        assert request.full_url.startswith(prefix), "Only the isolated fixture endpoint is allowed"
        url = "http://127.0.0.1:8090/" + request.full_url[len(prefix):]
        command = LOCAL + ["exec", "-i", NAME, "curl", "--noproxy", "*", "--silent", "--show-error",
                           "--digest", "--user", "admin:yacy", "--max-time", str(timeout),
                           "--request", request.get_method()]
        # These are the public synthetic credentials of the existing fresh-DATA fixture, never an operator's credentials.
        for key, value in request.header_items():
            command += ["--header", key + ": " + value]
        if request.data is not None:
            command += ["--data-binary", "@-"]
        command += ["--write-out", "\nSCOUTRO_STATUS:%{http_code}", url]
        result = subprocess.run(command, input=request.data or b"", capture_output=True)
        if result.returncode:
            raise urllib.error.URLError("isolated loopback transport unavailable")
        body, code = result.stdout.rsplit(b"\nSCOUTRO_STATUS:", 1)
        status = int(code)
        if status >= 400:
            raise urllib.error.HTTPError(request.full_url, status, "isolated fixture response", {}, io.BytesIO(body))
        response = io.BytesIO(body)
        response.status = status
        return response


with tempfile.TemporaryDirectory(prefix="scoutro-image-loopback-") as work:
    wrapper = Path(work) / "docker"
    wrapper.write_text("#!/bin/sh\nif [ \"$1\" = run ]; then\n shift\n exec " + DOCKER
                       + " --host=unix:///var/run/docker.sock run --network=none --user=1000:1000 \"$@\"\nfi\nexec "
                       + DOCKER + " --host=unix:///var/run/docker.sock \"$@\"\n")
    wrapper.chmod(0o755)
    original_path = os.environ["PATH"]
    original_opener = urllib.request.build_opener
    original_argv = sys.argv
    try:
        os.environ["PATH"] = work + os.pathsep + original_path
        urllib.request.build_opener = lambda *handlers: LoopbackOpener()
        sys.argv = [str(REPO / "test/scoutro-api/kg-image-smoke.py"), IMAGE]
        runpy.run_path(sys.argv[0], run_name="__main__")
    finally:
        os.environ["PATH"] = original_path
        urllib.request.build_opener = original_opener
        sys.argv = original_argv
