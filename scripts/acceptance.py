#!/usr/bin/env python3
"""Test HTTP reale. Richiede app Docker già avviata e fixture create da mvn test."""
import argparse, hashlib, http.cookiejar, json, pathlib, urllib.request, urllib.error, subprocess, uuid

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://localhost:8080')
parser.add_argument('--username', default='admin')
parser.add_argument('--password', required=True)
parser.add_argument('--container', help='Se specificato, verifica anche la persistenza riavviando questo container')
args = parser.parse_args()
base = args.url.rstrip('/')
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf = None

def request(path, method='GET', data=None, content_type=None, expected=200):
    global csrf
    headers = {}
    if method != 'GET':
        if csrf is None:
            csrf = json.loads(request('/api/auth/csrf'))
        headers[csrf['headerName']] = csrf['token']
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with client.open(req) as response:
            assert response.status == expected, (path, response.status)
            return response.read()
    except urllib.error.HTTPError as e:
        assert e.code == expected, (path, e.code, e.read().decode())
        return e.read()

def login():
    global csrf
    result=json.loads(request('/api/auth/login', 'POST', json.dumps({'username': args.username, 'password': args.password}).encode(), 'application/json'))
    csrf=None
    assert result['token'].count('.') == 2
    assert json.loads(request('/api/auth/me'))['username'] == args.username
    return result['token']

assert b'Converti' in request('/')
login()
records=[]
for ext in ('docx','pptx'):
    original=pathlib.Path('target/fixtures/sample.'+ext).read_bytes()
    boundary='boundary'+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.{ext}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+original+f'\r\n--{boundary}--\r\n'.encode())
    row=json.loads(request('/api/conversions','POST',body,'multipart/form-data; boundary='+boundary,201))
    assert request('/api/conversions/'+row['id']+'/original')==original
    pdf=request('/api/conversions/'+row['id']+'/pdf')
    assert pdf.startswith(b'%PDF-')
    pathlib.Path('target/fixtures/result.'+ext+'.pdf').write_bytes(pdf)
    if subprocess.run(['which','pdftotext'],capture_output=True).returncode==0:
        text=subprocess.check_output(['pdftotext','target/fixtures/result.'+ext+'.pdf','-']).decode()
        assert 'accettazione Forma' in text, text
    records.append((row,original,hashlib.sha256(pdf).digest()))
    print('OK conversione e download',ext.upper())
request('/api/auth/logout','POST',expected=204)
csrf=None
request('/api/conversions',expected=401)
login()

def verify():
    ids={c['id'] for c in json.loads(request('/api/conversions'))}
    for row,original,pdf_hash in records:
        assert row['id'] in ids
        assert request('/api/conversions/'+row['id']+'/original')==original
        assert hashlib.sha256(request('/api/conversions/'+row['id']+'/pdf')).digest()==pdf_hash
verify()
print('OK logout, nuovo login, storico e quattro download identici')
if args.container:
    subprocess.run(['docker','restart',args.container],check=True,stdout=subprocess.DEVNULL)
    import time
    for _ in range(60):
        try:
            request('/health')
            break
        except (OSError,AssertionError):
            time.sleep(1)
    else:
        raise RuntimeError('Container non disponibile dopo il riavvio')
    # Il cookie JWT deve restare valido: chiave e sessione sono persistite.
    verify()
    request('/api/auth/logout','POST',expected=204)
    csrf=None
    login()
    verify()
    print('OK riavvio Docker: DB, chiave JWT, originali e PDF persistenti')
print('TEST DI ACCETTABILITÀ SUPERATI')
