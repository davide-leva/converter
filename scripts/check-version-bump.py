#!/usr/bin/env python3
"""Check committed code/version changes; never inspect uncommitted VERSION."""
import subprocess
import sys
from version import semver, validate
from xml.etree.ElementTree import ParseError

def git(*args):
    return subprocess.check_output(['git', *args], text=True, stderr=subprocess.PIPE).strip()

def empty_tree():
    return subprocess.check_output(['git', 'hash-object', '-t', 'tree', '--stdin'], input=b'').decode().strip()

def new_ref_base(head, remote='origin', excluded=''):
    refs = git('for-each-ref', '--format=%(refname)', 'refs/remotes/' + remote + '/').splitlines()
    candidates = []
    for ref in refs:
        if ref == excluded or ref.endswith('/HEAD'):
            continue
        try:
            base = git('merge-base', head, ref)
            candidates.append((int(git('rev-list', '--count', base + '..' + head)), base))
        except subprocess.CalledProcessError:
            continue
    return min(candidates)[1] if candidates else empty_tree()

def default_base(head):
    # Exclude tags at the checked tip, so tagging cannot bypass the check.
    parent = git('rev-list', '--parents', '-n', '1', head).split()[1:]
    if parent:
        try:
            return git('describe', '--tags', '--abbrev=0', parent[0])
        except subprocess.CalledProcessError:
            pass
    return git('rev-list', '--max-parents=0', head).splitlines()[-1] if parent else empty_tree()

def production(path):
    return path.startswith('src/main/') or path in ('pom.xml', 'Dockerfile', 'compose.yaml', '.dockerignore', '.env.example')

try:
    if len(sys.argv) >= 3 and sys.argv[1] == '--new-ref':
        head = sys.argv[2]
        base = new_ref_base(head, sys.argv[3] if len(sys.argv) > 3 else 'origin', sys.argv[4] if len(sys.argv) > 4 else '')
    elif len(sys.argv) == 2:
        if '..' not in sys.argv[1] or '...' in sys.argv[1]:
            raise ValueError('Usare un intervallo BASE..HEAD')
        base, head = sys.argv[1].split('..', 1)
        base, head = base or 'HEAD', head or 'HEAD'
    elif len(sys.argv) == 1:
        head = 'HEAD'
        base = default_base(head)
    else:
        raise ValueError('Uso: check-version-bump.sh [BASE..HEAD] oppure --new-ref SHA [remote] [ref esclusa]')
    head = git('rev-parse', head + '^{commit}')
    version = validate(lambda name: git('show', head + ':' + name))
    changed = git('diff', '--name-only', base, head).splitlines()
    code = [name for name in changed if production(name)]
    try:
        previous = git('show', base + ':VERSION')
    except subprocess.CalledProcessError:
        previous = None
    if previous is not None:
        old = semver(previous)
        new = semver(version)
        if new < old or (code and new <= old):
            raise ValueError(f'Codice modificato senza incremento VERSION: {previous} → {version}.\nEsegui ./scripts/bump.sh patch --no-commit e includi i manifest nel commit.')
        if new > old:
            print(f'OK VERSION: {previous} → {version}')
        else:
            print('OK: nessuna modifica di produzione che richieda bump')
    else:
        # First publication: a synchronized version is sufficient.
        print(f'OK: versione iniziale {version}')
except (ValueError, OSError, subprocess.CalledProcessError, KeyError, ParseError) as error:
    sys.exit('Controllo versione fallito: ' + str(error))
