#!/usr/bin/env bash
# Scoutro checks for pull requests of this fork (docs/SCOUTRO_CI.md): a build from clean directories, every offline
# Scoutro JUnit target of build.xml and the offline Python suites. No network service, no model, no production data.
# Usage: tools/scoutro/ci/scoutro-checks.sh [java|python|all]   (default all)
set -u
cd "$(dirname "$0")/../../.."
what="${1:-all}"
failed=()
run() {
  local name="$1"; shift
  echo "::group::$name"
  local start=$(date +%s)
  if "$@"; then
    echo "PASS $name ($(( $(date +%s) - start )) s)"
  else
    echo "FAIL $name ($(( $(date +%s) - start )) s)"
    failed+=("$name")
  fi
  echo "::endgroup::"
}
if [ "$what" = all ] || [ "$what" = java ]; then
  rm -rf build/classes build/jt-classes build/scoutro-* build/*-tests
  run "ant clean compile" ant -q clean compile
  # every offline JUnit target the branch defines (a target missing on a branch is simply not there)
  for t in $(grep -o '<target name="scoutro-[a-z0-9-]*-test"' build.xml | sed 's/<target name="//; s/"//' | sort -u); do
    run "ant $t" ant -q "$t"
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
