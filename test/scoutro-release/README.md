# Local Scoutro release acceptance

`check-attribution.py` compares every original manifest, Maven metadata and
NOTICE/LICENSE/COPYING file from `build/solr9-bridge/input/*.jar` with the
actual Ant distribution and an exported local container root. It also verifies
Scoutro version metadata, operator help/tools/license packaging and the absence
of build-only Git/CA material. Example (not a registry push):

```sh
python3 test/scoutro-release/check-attribution.py \
  --inputs build/solr9-bridge/input \
  --distribution RELEASE/yacy_v1.942_<candidate>.tar.gz \
  --image-root <exported-local-container-root>
```

Release acceptance uses NEW disposable data and controlled fixtures only.
The current prepared candidate is 0.10.9 / 1.942-scoutro.33, on main after #73;
its release/upgrade/rollback gates are in `docs/SCOUTRO_RELEASE_0.10.9.md`.
Complete application/package preparation instructions are in
`docs/OPENCODE_SCOUTRO_0.10.9_HANDOFF.md`. The additional standalone
price/provider fix is explicitly deferred; no acceptance claim closes it.
The integration targets, `test/scoutro-api/kg-chain-acceptance.py` and
`kg-llm-schedule-live.py` retain their assertions and timing. Use real packaged
classes/resources for candidate smoke; a source-checkout test alone does not
prove a container or tarball. Upgrade evidence must name the old schema/image
and distinguish captured legacy job facts from newly extracted system signals.

The Draft release PR records actual commands, tested candidate SHA, image ID,
base digest, before/after upgrade snapshots and checks. A later registry digest
has not been checked until it is pulled and tested after the separately
approved publication. Production upgrades, real disk-full and production load
are not covered by these bounded fixtures.

For the existing image assertions on a non-root host, with the package's
UID/GID 1000 and **no external networking**, use:

```sh
python3 test/scoutro-release/run-isolated-image-smoke.py <unique-local-image-tag>
```

This adapter runs the unchanged `test/scoutro-api/kg-image-smoke.py`, using
container-loopback HTTP because network=none does not expose its host port.
It refuses to remove a preexisting fixture container. The fresh-DATA fixture
uses only its public synthetic credentials; no operator credential/config is
read. Create the isolated public-source build checkout with umask 022 so its
image files are readable to UID 1000. Preserve Docker settings/credentials and
TLS verification; use the existing optional session-CA build mount. Use a new
local tag, not an existing release alias. Actual image source/labels and later
published-image acceptance remain distinct from this bounded test.
