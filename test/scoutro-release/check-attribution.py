#!/usr/bin/env python3
"""Verify shipped upstream provenance against original JAR bytes (GPL-2.0-or-later)."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import zipfile


def originals(inputs):
    expected = {}
    jars = sorted(inputs.glob('*.jar'))
    assert jars, 'No original bridge JARs'
    for jar in jars:
        with zipfile.ZipFile(jar) as source:
            for entry in source.infolist():
                name = entry.filename
                base = name.rsplit('/', 1)[-1].upper()
                if entry.is_dir() or not (name.upper() == 'META-INF/MANIFEST.MF'
                        or name.startswith('META-INF/maven/')
                        or base.startswith(('NOTICE', 'LICENSE', 'COPYING'))):
                    continue
                expected['lib/solr9-bridge-upstream/' + jar.stem + '/' + name] = source.read(entry)
    assert expected, 'No original attribution/provenance files'
    return expected


def verify(read, expected, version):
    for name, content in expected.items():
        assert read(name) == content, 'Changed or missing upstream provenance: ' + name
    properties = read('scoutro.properties').decode()
    upstream, release = version.split('-scoutro.')
    assert 'scoutro.release=' + release + '\n' in properties
    assert 'scoutro.upstream.version=' + upstream + '\n' in properties
    for name in ['CHANGELOG.md', 'docs/SCOUTRO_RELEASE_0.9.0.md',
                 'help/ScoutroKnowledge_p.md', 'tools/scoutro/scoutroctl', 'LICENSES/GPL-2.0-or-later.txt']:
        assert read(name), 'Missing release artifact: ' + name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', required=True, type=Path)
    parser.add_argument('--distribution', type=Path)
    parser.add_argument('--image-root', type=Path, help='Actual locally exported container root')
    parser.add_argument('--version', default='1.942-scoutro.22')
    args = parser.parse_args()
    assert args.distribution or args.image_root, 'Choose an actual distribution and/or exported image'
    expected = originals(args.inputs)
    report = {'upstream_files': len(expected), 'original_sha256': {
        name: hashlib.sha256(content).hexdigest() for name, content in expected.items()}, 'verified': []}
    if args.distribution:
        with tarfile.open(args.distribution, 'r:gz') as archive:
            def read(name):
                member = archive.extractfile('yacy/' + name)
                assert member is not None, name
                return member.read()
            verify(read, expected, args.version)
        report['verified'].append('distribution')
    if args.image_root:
        app = args.image_root / 'opt/yacy_search_server'
        verify(lambda name: (app / name).read_bytes(), expected, args.version)
        for forbidden in [app / '.git', args.image_root / 'tmp/scoutro-build-cacerts',
                          args.image_root / 'run/secrets/proxy_ca']:
            assert not forbidden.exists(), 'Build-only material leaked into image: ' + str(forbidden)
        report['verified'].append('actual_exported_local_image')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
