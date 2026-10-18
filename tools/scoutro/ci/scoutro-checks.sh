#!/usr/bin/env bash
# Scoutro checks for pull requests of this fork (docs/SCOUTRO_CI.md): a build from clean directories, every offline
# Scoutro JUnit target of build.xml and the offline Python suites. No network service, no model, no production data.
# Usage: tools/scoutro/ci/scoutro-checks.sh [java|python|all]   (default all)
set -u
cd "$(dirname "$0")/../../.."
what="${1:-all}"
failed=()
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
run() {
  local name="$1"; shift
  echo "::group::$name"
  local start=$(date +%s)
  if "$@" > "$log" 2>&1; then
    tail -n 5 "$log"
    echo "PASS $name ($(( $(date +%s) - start )) s)"
  else
    # the failing tests by name with their first stack lines (JUnit's summary), then the end of the output
    sed -E 's/^[[:space:]]*\[(java|junit)\] //' "$log" \
      | grep -E '^[0-9]+\) |^[a-z.]+\.[A-Za-z]*(Error|Exception)|^[[:space:]]+at net\.yacy|^Tests run:|^OK \(' | head -n 120 || true
    tail -n 40 "$log"
    echo "FAIL $name ($(( $(date +%s) - start )) s)"
    failed+=("$name")
  fi
  echo "::endgroup::"
}
if [ "$what" = all ] || [ "$what" = java ]; then
  rm -rf build/classes build/jt-classes build/scoutro-* build/*-tests
  run "ant clean compile" ant clean compile
  # every offline JUnit target the branch defines (a target missing on a branch is simply not there)
  for t in $(grep -o '<target name="scoutro-[a-z0-9-]*-test"' build.xml | sed 's/<target name="//; s/"//' | sort -u); do
    run "ant $t" ant "$t"
  done
fi
if [ "$what" = all ] || [ "$what" = python ]; then
  for f in test/scoutro-discovery/test_*.py test/scoutro-agent/test_bridge.py test/scoutro-api/test_mcp_adapter.py \
           test/scoutro-api/test_flow_contract.py; do
    [ -f "$f" ] && run "python $f" python3 "$f"
  done
fi
echo
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}"
  exit 1
fi
echo "ALL SCOUTRO CHECKS PASSED"
