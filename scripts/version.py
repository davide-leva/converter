#!/usr/bin/env python3
"""Shared version validation and manifest synchronization (Python standard library)."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
FILES = ('VERSION', 'pom.xml', 'package.json', 'package-lock.json')

def semver(value):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
        raise ValueError(f'Versione SemVer non valida: {value!r} (richiesto major.minor.patch)')
    return tuple(map(int, value.split('.')))

def validate(read):
    version = read('VERSION').strip()
    semver(version)
    pom = ET.fromstring(read('pom.xml'))
    maven = pom.find('{http://maven.apache.org/POM/4.0.0}version')
    package = json.loads(read('package.json'))
    lock = json.loads(read('package-lock.json'))
    actual = (maven.text if maven is not None else None, package.get('version'),
              lock.get('version'), lock.get('packages', {}).get('', {}).get('version'))
    if any(v != version for v in actual):
        raise ValueError(f'Versioni non sincronizzate con VERSION={version}: Maven/npm/lock={actual}')
    return version

def synchronize(version):
    semver(version)
    pom = (ROOT / 'pom.xml').read_text()
    pom, count = re.subn(r'(<artifactId>converter</artifactId>\s*<version>)[^<]+(</version>)',
                        lambda m: m[1] + version + m[2], pom)
    if count != 1:
        raise ValueError('Impossibile trovare la versione del progetto Maven converter')
    updates = {'VERSION': version + '\n', 'pom.xml': pom}
    for name in ('package.json', 'package-lock.json'):
        data = json.loads((ROOT / name).read_text())
        data['version'] = version
        if name == 'package-lock.json':
            data['packages']['']['version'] = version
        updates[name] = json.dumps(data, indent=2) + '\n'
    for name, content in updates.items():
        (ROOT / name).write_text(content)

if __name__ == '__main__':
    try:
        if len(sys.argv) == 3 and sys.argv[1] == 'sync':
            synchronize(sys.argv[2])
        elif len(sys.argv) == 3 and sys.argv[1] == 'bump':
            values = list(semver(validate(lambda name: (ROOT / name).read_text())))
            index = ('major', 'minor', 'patch').index(sys.argv[2])
            values[index] += 1
            for i in range(index + 1, 3):
                values[i] = 0
            version = '.'.join(map(str, values))
            synchronize(version)
            print(version)
        elif sys.argv[1:] == ['check']:
            print(validate(lambda name: (ROOT / name).read_text()))
        else:
            raise ValueError('Uso: version.py check | bump major/minor/patch | sync VERSION')
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        sys.exit(str(error))
