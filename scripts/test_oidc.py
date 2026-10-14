#!/usr/bin/env python3
"""Test OIDC Authorization Code con provider locale firmato RSA e app Docker.
Non richiede Authentik né dipendenze Python. Usa rete host (Docker Linux).
"""
import argparse, base64, hashlib, http.cookiejar, http.server, json, pathlib, secrets, subprocess, tempfile, threading, time, urllib.parse, urllib.request, urllib.error

p=argparse.ArgumentParser();p.add_argument('--image',default='forma-converter:local');p.add_argument('--app-port',type=int,default=18081);p.add_argument('--provider-port',type=int,default=19090);args=p.parse_args()
issuer=f'http://localhost:{args.provider_port}'
public=f'http://localhost:{args.app_port}'
def b64(data):return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
with tempfile.TemporaryDirectory(prefix='forma-oidc-') as temp:
    key=pathlib.Path(temp)/'key.pem'
    subprocess.run(['openssl','genrsa','-out',str(key),'2048'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    modulus=subprocess.check_output(['openssl','rsa','-in',str(key),'-modulus','-noout']).decode().strip().split('=')[1]
    jwk={'kty':'RSA','kid':'test-key','use':'sig','alg':'RS256','n':b64(bytes.fromhex(modulus)),'e':'AQAB'}
    codes={};state={'sub':'oidc-user-1','name':'Utente OIDC','bad_nonce':False}
    class Provider(http.server.BaseHTTPRequestHandler):
        def log_message(self,*a):pass
        def respond(self,data,status=200):
            data=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data)
        def do_GET(self):
            path=urllib.parse.urlparse(self.path)
            if path.path=='/.well-known/openid-configuration':self.respond({'issuer':issuer,'authorization_endpoint':issuer+'/authorize','token_endpoint':issuer+'/token','jwks_uri':issuer+'/jwks','userinfo_endpoint':issuer+'/userinfo','response_types_supported':['code'],'subject_types_supported':['public'],'id_token_signing_alg_values_supported':['RS256'],'token_endpoint_auth_methods_supported':['client_secret_basic'],'scopes_supported':['openid','profile','email']})
            elif path.path=='/jwks':self.respond({'keys':[jwk]})
            elif path.path=='/authorize':
                q=urllib.parse.parse_qs(path.query);assert q['client_id']==['test-client'];assert q['redirect_uri']==[public+'/login/oauth2/code/authentik']
                code=secrets.token_urlsafe(24);codes[code]={'nonce':q['nonce'][0],**state}
                self.send_response(302);self.send_header('Location',q['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code':code,'state':q['state'][0]}));self.end_headers()
            elif path.path=='/userinfo':
                sub=self.headers.get('Authorization','').removeprefix('Bearer access-');self.respond({'sub':sub,'name':state['name'],'email':'user@example.test'})
            else:self.respond({},404)
        def do_POST(self):
            if self.path!='/token':return self.respond({},404)
            assert self.headers.get('Authorization')=='Basic '+base64.b64encode(b'test-client:test-secret').decode()
            q=urllib.parse.parse_qs(self.rfile.read(int(self.headers['Content-Length'])).decode());user=codes.pop(q['code'][0]);now=int(time.time())
            claims={'iss':issuer,'sub':user['sub'],'aud':'test-client','iat':now,'exp':now+300,'nonce':'wrong' if user['bad_nonce'] else user['nonce'],'name':user['name']}
            unsigned=b64(json.dumps({'alg':'RS256','kid':'test-key'}).encode())+'.'+b64(json.dumps(claims).encode())
            signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=unsigned.encode())
            self.respond({'access_token':'access-'+user['sub'],'token_type':'Bearer','expires_in':300,'id_token':unsigned+'.'+b64(signature),'scope':'openid profile email'})
    server=http.server.ThreadingHTTPServer(('127.0.0.1',args.provider_port),Provider)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    name='forma-oidc-test-'+secrets.token_hex(4);volume=name+'-data'
    def client():return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def get(c,path):
        with c.open(public+path) as r:return r.read()
    def jsonget(c,path):return json.loads(get(c,path))
    def post(c,path,body=None,content_type=None):
        csrf=jsonget(c,'/api/auth/csrf');headers={csrf['headerName']:csrf['token']}
        if content_type:headers['Content-Type']=content_type
        with c.open(urllib.request.Request(public+path,data=body or b'',headers=headers,method='POST')) as r:return r.read()
    def unavailable(c,path,expected=401):
        try:get(c,path);raise AssertionError('Accesso doveva essere negato')
        except urllib.error.HTTPError as e:assert e.code==expected,(path,e.code)
    def ready():
        for _ in range(60):
            try:get(client(),'/health');return
            except OSError:time.sleep(1)
        raise RuntimeError('Container OIDC non pronto')
    try:
        subprocess.run(['docker','run','-d','--name',name,'--network','host','-v',volume+':/data','-e','PORT='+str(args.app_port),'-e','PUBLIC_URL='+public,'-e','OIDC_ISSUER_URI='+issuer,'-e','OIDC_CLIENT_ID=test-client','-e','OIDC_CLIENT_SECRET=test-secret',args.image],check=True,stdout=subprocess.DEVNULL)
        ready();c=client();assert jsonget(c,'/api/auth/config')['mode']=='oidc';get(c,'/oauth2/authorization/authentik');user=jsonget(c,'/api/auth/me');assert user['username']=='Utente OIDC'
        # Gli endpoint locali sono disabilitati anche dopo un'autenticazione valida.
        try:post(c,'/api/auth/login',b'{"username":"admin","password":"irrelevant"}','application/json');raise AssertionError('Login locale attivo')
        except urllib.error.HTTPError as e:assert e.code==404
        ids=[]
        for ext in ('docx','pptx'):
            original=pathlib.Path('target/fixtures/sample.'+ext).read_bytes();boundary=secrets.token_hex(20)
            body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.{ext}"\r\n\r\n'.encode()+original+f'\r\n--{boundary}--\r\n'.encode())
            row=json.loads(post(c,'/api/conversions',body,'multipart/form-data; boundary='+boundary));pdf=get(c,'/api/conversions/'+row['id']+'/pdf');assert pdf.startswith(b'%PDF-');ids.append((row['id'],original,hashlib.sha256(pdf).digest()))
        def verify(c):
            assert len(jsonget(c,'/api/conversions'))==2
            for id,original,pdfhash in ids:
                assert get(c,'/api/conversions/'+id+'/original')==original
                assert hashlib.sha256(get(c,'/api/conversions/'+id+'/pdf')).digest()==pdfhash
        post(c,'/api/auth/logout');unavailable(c,'/api/auth/me');state['name']='Nome aggiornato';get(c,'/oauth2/authorization/authentik');assert jsonget(c,'/api/auth/me')['id']==user['id'];verify(c)
        print('OK OIDC: discovery, firma RSA, callback pubblica, login/logout e archivio stabile al cambio nome')
        state['sub']='oidc-user-2';other=client();get(other,'/oauth2/authorization/authentik');assert jsonget(other,'/api/conversions')==[];unavailable(other,'/api/conversions/'+ids[0][0]+'/pdf',404)
        print('OK isolamento utenti OIDC')
        state['bad_nonce']=True;bad=client();get(bad,'/oauth2/authorization/authentik');unavailable(bad,'/api/auth/me');state['bad_nonce']=False
        print('OK nonce OIDC errato rifiutato')
        subprocess.run(['docker','restart',name],check=True,stdout=subprocess.DEVNULL);ready();unavailable(c,'/api/auth/me');state['sub']='oidc-user-1';c=client();get(c,'/oauth2/authorization/authentik');verify(c)
        print('OK storico OIDC e quattro download dopo riavvio Docker')
        print('TEST OIDC SUPERATI (provider locale; nessun Authentik reale configurato)')
    except Exception:
        subprocess.run(['docker','logs','--tail','500',name])
        raise
    finally:
        subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','volume','rm',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        server.shutdown()
