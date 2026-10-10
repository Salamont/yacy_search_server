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
