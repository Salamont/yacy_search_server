#!/usr/bin/env python3
"""Offline real GDAL/GeoJSONSeq smoke. Run with repository root as sole argument.
GPL-2.0-or-later. Only a tiny synthetic OSM XML file and temporary directory.
"""
import importlib.machinery
import importlib.util
from pathlib import Path
import sys
import tempfile

root = Path(sys.argv[1]).resolve()
loader = importlib.machinery.SourceFileLoader('runtime_gdal_fixture', str(root / 'tools/scoutro/scoutro-discovery'))
spec = importlib.util.spec_from_loader(loader.name, loader)
engine = importlib.util.module_from_spec(spec)
sys.modules[loader.name] = engine
loader.exec_module(engine)
with tempfile.TemporaryDirectory(prefix='scoutro-gdal-') as temporary:
    osm = Path(temporary) / 'tiny.osm'
    osm.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="offline-test">
<node id="1" lat="50.1" lon="10.1"><tag k="name" v="Beratung Eins"/><tag k="website" v="https://firm-one.de/"/></node>
<node id="2" lat="50.2" lon="10.2"><tag k="name" v="Beratung Zwei"/><tag k="website" v="https://firm-two.de/"/></node>
</osm>''')
    extracted = engine.extract_region(str(osm), 'name IS NOT NULL', temporary)
    features = [feature for file in extracted.values() for feature in engine.osm_features(file)]
    assert len(features) == 2, features
    assert {f['properties']['name'] for f in features} == {'Beratung Eins','Beratung Zwei'}
    assert all('website' in engine.parse_hstore(f['properties']['other_tags']) for f in features)
    print('PASS: 3 real GDAL/OSM/GeoJSONSeq checks; non-root, offline, temporary files only')
