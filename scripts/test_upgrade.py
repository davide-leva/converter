#!/usr/bin/env python3
"""Upgrade Docker da un'immagine precedente a Flyway, mantenendo lo stesso volume.
Richiede le fixture prodotte da mvn test. I container e il volume sono temporanei.
"""
import argparse
import hashlib
import http.cookiejar
import json
import pathlib
import secrets
import sqlite3
import subprocess
import tempfile
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--old-image', required=True, help='Immagine della versione precedente senza Flyway')
parser.add_argument('--new-image', default='forma-converter:local')
parser.add_argument('--port', type=int, default=18083)
args = parser.parse_args()
base = f'http://localhost:{args.port}'
name = 'forma-upgrade-test-' + secrets.token_hex(4)
old_name, new_name, volume = name + '-old', name + '-new', name + '-data'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def get(path):
    with client.open(base + path, timeout=5) as response:
        return response.read()

def post(path, body, content_type):
    csrf = json.loads(get('/api/auth/csrf'))
    req = urllib.request.Request(base + path, data=body, method='POST', headers={
        'Content-Type': content_type, csrf['headerName']: csrf['token']})
    with client.open(req, timeout=150) as response:
        return json.loads(response.read())

def start(container, image, password=None):
    cmd = ['docker', 'run', '-d', '--name', container, '-p', f'127.0.0.1:{args.port}:8080',
           '-v', volume + ':/data', '-e', 'PUBLIC_URL=' + base]
    if password:
        cmd += ['-e', 'LOCAL_PASSWORD=' + password]
    subprocess.run(cmd + [image], check=True, stdout=subprocess.DEVNULL)
    for _ in range(45):
        try:
            get('/health')
            return
        except OSError:
            time.sleep(1)
    raise RuntimeError('Container non pronto: ' + container)

def upload(ext):
    original = pathlib.Path('target/fixtures/sample.' + ext).read_bytes()
    boundary = secrets.token_hex(20)
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.{ext}"\r\n\r\n'.encode()
            + original + f'\r\n--{boundary}--\r\n'.encode())
    row = post('/api/conversions', body, 'multipart/form-data; boundary=' + boundary)
    pdf = get('/api/conversions/' + row['id'] + '/pdf')
    assert pdf.startswith(b'%PDF-')
    return row['id'], original, hashlib.sha256(pdf).digest()

try:
    with tempfile.TemporaryDirectory(prefix='forma-upgrade-db-') as temp:
        start(old_name, args.old_image, 'upgrade-test-password-long')
        post('/api/auth/login', json.dumps({'username': 'admin', 'password': 'upgrade-test-password-long'}).encode(), 'application/json')
        records = [upload('docx'), upload('pptx')]
        subprocess.run(['docker', 'stop', old_name], check=True, stdout=subprocess.DEVNULL)
        old_db = pathlib.Path(temp) / 'old.db'
        subprocess.run(['docker', 'cp', old_name + ':/data/converter.db', str(old_db)], check=True)
        with sqlite3.connect(old_db) as db:
            managed = db.execute("SELECT count(*) FROM sqlite_master WHERE name='flyway_schema_history'").fetchone()[0] == 1
            previous_history = db.execute('SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank').fetchall() if managed else []
            if managed:
                assert previous_history[-1][0] == '1', 'Usare una versione precedente alla migrazione V2' 
        subprocess.run(['docker', 'rm', old_name], check=True, stdout=subprocess.DEVNULL)
        # Nessuna password di bootstrap: si riutilizzano utenti e chiave nel volume.
        start(new_name, args.new_image)
        assert json.loads(get('/api/auth/me'))['username'] == 'admin'
        assert len(json.loads(get('/api/conversions'))) == 2
        for id, original, digest in records:
            assert get('/api/conversions/' + id + '/original') == original
            assert hashlib.sha256(get('/api/conversions/' + id + '/pdf')).digest() == digest
        print('OK upgrade Docker: utente, JWT esistente, storico e quattro download identici')
        upload('docx')
        assert len(json.loads(get('/api/conversions'))) == 3
        print('OK nuove conversioni dopo migrazione')
        subprocess.run(['docker', 'stop', new_name], check=True, stdout=subprocess.DEVNULL)
        new_db = pathlib.Path(temp) / 'new.db'
        subprocess.run(['docker', 'cp', new_name + ':/data/converter.db', str(new_db)], check=True)
        with sqlite3.connect(new_db) as db:
            assert db.execute('SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank').fetchall() == (previous_history + [('2', 'SQL', 1)] if previous_history else [('0', 'BASELINE', 1), ('1', 'SQL', 1), ('2', 'SQL', 1)])
        print('OK adozione Flyway: baseline 0 e migrazioni V1/V2 registrate')
except Exception:
    for container in (old_name, new_name):
        subprocess.run(['docker', 'logs', '--tail', '80', container])
    raise
finally:
    for container in (old_name, new_name):
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['docker', 'volume', 'rm', volume], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
