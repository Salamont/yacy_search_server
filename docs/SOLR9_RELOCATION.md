# Complete private Solr/Jetty relocation

Acceptance on 2026-10-10 reproduced the same failure on unchanged main
`7847fe1ed54eda64348eaa64cd15d7eb8c107639` and A/B/C head
`264dc3038559373feddcb40abb7f141b2cb28f63`:

```
JAVA_HOME=<Temurin-17.0.20.1+1> PATH="$JAVA_HOME/bin:$PATH" test/jetty-solr-dependency-guard.sh
FAIL: unrelocated Jetty reference in lib/solr9-bridge-jetty-alpn-java-client-10.0.26.jar
```

Rebuilding the bridge on unchanged main reproduced the failure. Dependency
coordinates, guard and relocator were identical across the chain. Runtime classes
were relocated; JPMS module/package identities and folded OSGi manifest fields
were not. The broad resource assertion also caught unchanged upstream Maven
coordinates and attribution. This was a pre-existing build/packaging defect,
not a Scoutro extraction, dependency-version or JDK mismatch.

The relocator now handles JPMS names/packages, unfolds and rewrites manifest
attributes before valid serialization, and relocates service/text resources.
Original Maven coordinates are not published as the identity of a modified
runtime artifact. Original manifests, Maven provenance and attribution are
preserved verbatim under `lib/solr9-bridge-upstream/<artifact>/`. Attribution
with original namespace references is represented inside the runtime jar by a
pointer to that unchanged distributed file. Other licenses remain unchanged
inside the jar as well. `copyMain4Dist` already copies **all** `lib/**`, including
these files; distribute this directory with the bridge jars. Signatures invalid
after bytecode rewriting are removed as before. No dependency version changed.

The guard and its assertions are unchanged. `ant solr9-relocation-test` checks
real jars, wrapped manifest values, JPMS, service descriptors, resource values,
preserved attribution/provenance and untouched binary assets (18 checks).
`ant all` includes this packaging regression.

Using the isolated, pre-resolved dependency set and a fresh bridge build:

- Relocation packaging regression: **18 checks passed**.
- `test/jetty-solr-dependency-guard.sh`: **passed**.
- `test/solr9-jetty-bridge-spike.sh`: **4 tests passed**, including the guard.

Temurin 17.0.20.1+1, Ant 1.10.15, ASM 9.9, Linux x86_64. No production runtime,
release or deployment was changed. The separate packaging PR must be included
before claiming the Scoutro chain's previously blocked integration check passes.
