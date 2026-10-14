# AvelSpace Docs — Archivio, conversione e unione PDF

Servizio Spring Boot con frontend HTML/CSS/JavaScript multipagina, design AvelSpace, SQLite, LibreOffice headless e Apache PDFBox. Ogni utente ha un archivio personale con cartelle e sottocartelle, nomi descrittivi e download di originali e PDF. Può anche caricare PDF e unire documenti provenienti dal dispositivo, dall’archivio o da entrambi, scegliendone l’ordine. `PPTX` è il formato PowerPoint (indicato come PTTX nella richiesta).

## Avvio Docker

```bash
cp .env.example .env
# Modifica LOCAL_PASSWORD in .env: almeno 12 caratteri.
docker compose up --build -d
```

Apri http://localhost:8080 e accedi con `LOCAL_USERNAME` / `LOCAL_PASSWORD`. Puoi creare altri utenti dalla schermata di accesso; imposta `ALLOW_REGISTRATION=false` per disabilitare questa funzione.

Le pagine sono distinte: `/accesso`, `/archivio`, `/converti` e `/unisci`, con navigazione normale e ricaricamento del documento HTML. Da **Converti e carica** scegli DOCX/PPTX (conversione in PDF) oppure PDF (salvataggio diretto), un nome descrittivo e la cartella. Alla fine viene scaricato il PDF e l’archivio mostra i pulsanti per riscaricare entrambi i formati. Dopo logout, nuovo login o riavvio del container, lo storico resta disponibile. L'accesso ai file è verificato dal backend per ogni richiesta.

Il volume Docker `converter-data` conserva:

```text
/data/converter.db             # utenti, token revocabili e metadati
/data/converter.db-wal         # SQLite WAL, quando presente
/data/converter.db-shm
/data/jwt.key                  # chiave JWT generata, se JWT_SECRET è assente
/data/files/<uuid>/source.docx # oppure source.pptx
/data/files/<uuid>/source.pdf
```

Per una cartella dell'host, configura `DATA_VOLUME=/percorso/assoluto` in `.env`. Il processo nel container usa UID/GID **10001**: prima dell'avvio prepara una cartella scrivibile da tale utente, ad esempio `sudo install -d -o 10001 -g 10001 /srv/converter`. Non usare `docker compose down -v` se vuoi conservare i dati. Per un backup consistente arresta il container e copia l'intero volume.

Anche senza Compose:

```bash
docker build -t forma-converter:local .
docker run -d --name forma -p 8080:8080 \
  -v converter-data:/data \
  -e PUBLIC_URL=http://localhost:8080 \
  -e LOCAL_USERNAME=admin \
  -e LOCAL_PASSWORD='scegli-una-password-lunga' \
  forma-converter:local
```

## Cartelle, documenti e unione PDF

Nell’**Archivio** puoi creare cartelle e sottocartelle, rinominarle e spostarle tramite la cartella superiore. Il pulsante di modifica di un documento cambia nome descrittivo e posizione. Il nome del file originale resta conservato. Sono impediti cicli, accessi alle cartelle di altri utenti e cancellazioni di cartelle non vuote; sposta prima i loro contenuti. Le cartelle sono organizzate in SQLite: i file fisici continuano a essere conservati in percorsi UUID stabili, anche dopo rinomina o spostamento.

In **Unisci PDF** aggiungi file dal dispositivo e/o selezioni i PDF già disponibili nel tuo archivio (incluse le conversioni DOCX/PPTX). Puoi riordinare la coda con trascinamento oppure frecce, rimuovere sorgenti e scegliere nome e cartella del risultato. L’ordine della coda determina quello delle pagine. Il risultato è salvato come documento PDF e può essere scaricato dopo nuovi accessi o riavvii.

Limiti: da 2 a 50 sorgenti, massimo 100 MB complessivi e 2500 pagine. I PDF protetti da password o danneggiati vengono rifiutati. Ogni singolo upload è soggetto a `MAX_FILE_SIZE`; per unioni con più upload il limite HTTP è `MAX_REQUEST_SIZE`, ora predefinito a `101MB`. Se nella tua `.env` era impostato esplicitamente il vecchio limite `26MB`, puoi aumentarlo a `101MB`.

Gli originali caricati direttamente nella coda di unione sono conservati in `/data/files/<merge-uuid>/inputs/<posizione>.pdf`; le sorgenti già presenti nell’archivio non vengono modificate. La tabella `merge_inputs` conserva provenienza, nome e ordine, e le API consentono di riscaricare anche le sorgenti dell’unione. Il PDF finale è in `source.pdf`, come i PDF caricati direttamente.

## Authentik OIDC

Crea in Authentik un'applicazione con provider OAuth2/OpenID Connect, client confidenziale, issuer specifico per applicazione (modalità predefinita), signing key RSA per token RS256 e redirect URI esatta ([documentazione Authentik](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)):

```text
https://convert.example.com/login/oauth2/code/authentik
```

Configura `.env`:

```dotenv
PUBLIC_URL=https://convert.example.com
COOKIE_SECURE=true
OIDC_ISSUER_URI=https://auth.example.com/application/o/converter/
OIDC_CLIENT_ID=il-client-id
OIDC_CLIENT_SECRET=il-client-secret
OIDC_SCOPES=openid,profile,email
OIDC_AUTH_METHOD=client_secret_basic
```

L'issuer deve corrispondere a quello pubblicato dal documento di discovery del provider e deve essere raggiungibile dal container. Sono supportati `client_secret_basic` e `client_secret_post`; configura lo stesso metodo sul provider. Spring Security usa discovery e Authorization Code con verifica di stato, nonce e firma del token ([documentazione ufficiale](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)).

Con tutti e tre i parametri issuer, ID e secret presenti, il login locale e la registrazione vengono disabilitati. Una configurazione parziale interrompe l'avvio per evitare fallback involontari. Gli utenti OIDC sono identificati dalla coppia issuer/sub, così un cambio di nome o email non perde lo storico. La sessione OIDC è nel container: dopo un riavvio occorre accedere nuovamente, mentre file e storico restano persistenti. `Esci` chiude la sessione dell'app; la sessione SSO di Authentik può restare attiva.

In produzione esponi il container attraverso un reverse proxy HTTPS e configura `PUBLIC_URL` con l'origine pubblica, senza sottopercorso. `COOKIE_SECURE=true` abilita il cookie sicuro della sessione OIDC; il cookie JWT è automaticamente sicuro quando `PUBLIC_URL` usa HTTPS. La callback e il ritorno dopo login usano l'URL configurato.

## Parametri

| Variabile | Default / funzione |
| --- | --- |
| `DATA_VOLUME` | `converter-data`, riferimento al volume dichiarato o percorso host (Compose) |
| `DATA_VOLUME_NAME` | `converter-data`, nome del volume Docker gestito da Compose |
| `DATA_DIR` | `/data` nel container; percorso interno di DB e file |
| `PUBLIC_URL` | `http://localhost:8080`, origine pubblica |
| `HTTP_PORT` | `8080`, porta host (Compose) |
| `PORT` | `8080`, porta interna Spring Boot; lascia 8080 con Compose |
| `LOCAL_USERNAME` | `admin`, creato solo al primo avvio locale |
| `LOCAL_PASSWORD` | obbligatoria al primo avvio locale, minimo 12 caratteri, massimo 72 byte UTF-8 |
| `ALLOW_REGISTRATION` | `true`, registrazione locale |
| `JWT_SECRET` | opzionale, almeno 32 byte; se assente chiave generata nel volume |
| `JWT_HOURS` | `12`, scadenza token locale |
| `OIDC_ISSUER_URI` | issuer del provider, assente = autenticazione locale |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | credenziali client OIDC |
| `OIDC_SCOPES` | `openid,profile,email` |
| `OIDC_AUTH_METHOD` | `client_secret_basic` |
| `COOKIE_SECURE` | `false`; usare `true` con HTTPS per OIDC |
| `MAX_FILE_SIZE` | `25MB` |
| `MAX_REQUEST_SIZE` | `101MB`, include tutti gli upload della richiesta multipart |
| `CONVERSION_TIMEOUT_SECONDS` | `120` |
| `LIBREOFFICE_BIN` | `libreoffice`, percorso eseguibile |

`LOCAL_PASSWORD` inizializza l'utente nel DB; modificarla in seguito non cambia la password già salvata. Le password sono hash BCrypt (minimo 12 caratteri, massimo 72 byte UTF-8). Il logout revoca i token locali nel DB. Mantieni invariati `PUBLIC_URL` e la chiave JWT se desideri conservare la validità dei token dopo un riavvio.

## API

| Metodo | Endpoint | Risultato |
| --- | --- | --- |
| GET | `/health` | Stato del servizio |
| GET | `/api/auth/config` | Modalità login e disponibilità registrazione |
| GET | `/api/auth/csrf` | Token CSRF e nome header |
| POST | `/api/auth/register` | JSON `{username,password}`: crea utente locale |
| POST | `/api/auth/login` | JSON `{username,password}`: cookie HttpOnly e JWT per client API |
| GET | `/api/auth/me` | Identità corrente |
| POST | `/api/auth/logout` | Disconnessione / revoca JWT |
| POST | `/api/conversions` | Multipart `file`: converte, restituisce metadati (201) |
| GET | `/api/conversions` | Storico personale |
| GET | `/api/conversions/{id}/original` | Download originale |
| GET | `/api/conversions/{id}/pdf` | Download PDF |
| PATCH | `/api/conversions/{id}` | JSON `{displayName,folderId}`: rinomina/sposta documento |
| GET/POST | `/api/folders` | Elenco cartelle / creazione con JSON `{name,parentId}` |
| PUT/DELETE | `/api/folders/{id}` | Aggiorna nome/parent / elimina cartella vuota |
| POST | `/api/documents` | Multipart `file` PDF, `displayName` e `folderId` opzionali |
| POST | `/api/merges` | Multipart `request` JSON e `files` opzionali: unione ordinata |
| GET | `/api/conversions/{id}/inputs` | Sorgenti e ordine di un PDF unito |
| GET | `/api/conversions/{id}/inputs/{position}` | Download della sorgente dell’unione |

Le API protette accettano cookie JWT locale, header `Authorization: Bearer <jwt>` locale oppure sessione OIDC. Le richieste POST richiedono sempre il token CSRF: prima chiama `/api/auth/csrf` conservando i cookie, poi aggiungi l'header indicato dalla risposta. File di altri utenti restituiscono 404. Le conversioni sono sincrone, con massimo due processi contemporanei e timeout; se occupati restituiscono 503. File non Office, estensioni non supportate e archivi eccessivi vengono rifiutati.

Le conversioni accettano anche `displayName` e `folderId` come campi multipart opzionali. Il JSON della parte `request` di `/api/merges` ha questa struttura; invialo con Content-Type `application/json`:

```json
{
  "name": "Dossier progetto",
  "folderId": null,
  "items": [
    {"type": "stored", "id": "uuid-del-documento"},
    {"type": "upload", "index": 0},
    {"type": "stored", "id": "uuid-di-un-altro-documento"}
  ]
}
```

Gli indici upload sono zero-based e si riferiscono alle parti multipart `files`, nell’ordine in cui sono inviate; `items` stabilisce l’ordine finale. Ogni upload deve essere incluso nell’unione. I documenti salvati vengono controllati rispetto all’utente autenticato prima della lettura.

## Versione, bump e controllo al push

Come in Loom, `VERSION` è la fonte della versione SemVer (`major.minor.patch`). Lo script di bump mantiene sincronizzati `VERSION`, la versione del progetto Maven in `pom.xml`, `package.json` e la versione del pacchetto radice in `package-lock.json`. La versione iniziale è `1.0.0`.

```bash
./scripts/install-hooks.sh                 # da eseguire dopo ogni clone
./scripts/bump.sh                          # scelta interattiva, come in Loom
./scripts/bump.sh patch --no-commit         # aggiorna soltanto i quattro file
./scripts/bump.sh minor --commit            # aggiorna e crea il commit release
./scripts/bump.sh major --no-commit
```

L'hook `.githooks/pre-push` è già attivato in questo checkout tramite `core.hooksPath=.githooks`. Il controllo legge i file dei commit effettivamente inviati: un bump rimasto nel working tree non basta. Prima del push, includi nel commit tutti e quattro i file della versione.

Richiedono un incremento le modifiche a `src/main/**` (backend, frontend statico, configurazione e migrazioni), `pom.xml`, `Dockerfile`, `compose.yaml`, `.dockerignore` e `.env.example`. Modifiche a test, script, documentazione e workflow non richiedono bump, ma le versioni dei manifest devono sempre essere coerenti. Anche una diminuzione della versione viene rifiutata. Il primo push di un repository nuovo richiede una versione valida e sincronizzata; sui nuovi branch il confronto usa la base condivisa con i branch remoti.

`.github/workflows/version-check.yml` applica lo stesso controllo su push e pull request, anche quando l'hook locale è aggirato. Per impedire il merge di una PR con controllo fallito, configura `Verify VERSION bumped with code` come controllo obbligatorio nelle regole del branch su GitHub.

```bash
./scripts/check-version-bump.sh BASE..HEAD  # confronto esplicito
./scripts/check-version-bump.sh            # dall'ultimo tag precedente, o commit iniziale
python3 scripts/version.py check           # coerenza dei file nel working tree
python3 scripts/test_versioning.py         # test con repository e push locali temporanei
```

Gli script richiedono Bash, Git e Python 3. Il JAR Maven ha nome stabile `target/converter.jar`, quindi un bump non richiede modifiche al Dockerfile. Il bump non crea tag e non pubblica immagini automaticamente.

## Migrazioni del database

Lo schema SQLite è gestito da Flyway all'avvio, prima dell'inizializzazione dell'autenticazione. Le migrazioni SQL sono in `src/main/resources/db/migration`, con nomi come `V1__initial_schema.sql`, `V2__add_document_status.sql`, ecc. La versione di Flyway è gestita dal BOM Spring Boot.

Un database nuovo esegue V1 e V2. V2 aggiunge cartelle, nomi descrittivi, tipologia di documento e provenienza delle unioni, mantenendo i documenti già presenti nella radice dell’archivio con il loro nome originale. Un database creato dalle versioni precedenti, senza tabella di storico Flyway, viene automaticamente registrato alla versione 0 e poi esegue V1: la prima migrazione usa `IF NOT EXISTS` per conservare utenti, password, token, metadati e indice esistenti. I file e la chiave JWT sul volume non vengono modificati. Dopo V1, le versioni successive vengono applicate automaticamente. Le versioni applicate e i checksum sono conservati in `flyway_schema_history` nello stesso database. Il comportamento di adozione è documentato da [Flyway baseline-on-migrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting).

Per modificare lo schema in futuro:

1. Aggiungi un nuovo file con versione progressiva, per esempio `V3__add_document_status.sql`.
2. Scrivi SQL compatibile con SQLite; per nuove colonne obbligatorie prevedi un valore di default o un aggiornamento dei record già presenti.
3. Verifica la migrazione su un database nuovo e su un database della versione precedente, includendo i dati preesistenti.
4. Ricostruisci l'immagine e riavvia il servizio mantenendo lo stesso volume.

Non modificare né eliminare migrazioni già distribuite: il controllo dei checksum blocca l'avvio se vengono alterate. Un errore di migrazione interrompe l'avvio; il test verifica il rollback delle modifiche DDL e DML transazionali. `clean` è disabilitato. Per aggiornare un'istanza, esegui prima un backup consistente dell'intero volume a servizio arrestato e avvia un solo container per database durante le migrazioni: SQLite non supporta migrazioni concorrenti ([documentazione Flyway SQLite](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite)).

## Test

Richiede Java 21, Maven e LibreOffice sul sistema per i test d'integrazione:

```bash
mvn test
```

La suite include test di cartelle, spostamenti, isolamento utenti, PDF caricati, unione da soli upload/soli documenti salvati/sorgenti miste, ordine effettivo delle pagine e pulizia delle unioni fallite. Il test produce DOCX/PPTX reali con Apache POI, verifica conversione, download, logout/login, isolamento utenti, revoca JWT, CSRF e input non valido. Le fixture vengono salvate in `target/fixtures`. I test delle migrazioni coprono database nuovo e precedente, login e download dopo aggiornamento, applicazione di una migrazione successiva una sola volta, conservazione dei dati, checksum e rollback in caso di errore.

Con il container attivo, esegui il test HTTP di accettazione (Python 3, libreria standard):

```bash
python3 scripts/acceptance.py --password 'la-tua-password' \
  --container "$(docker compose ps -q converter)"
```

Verifica le due conversioni, quattro download dopo un nuovo login, identità binaria degli originali e dei PDF anche dopo riavvio Docker, e persistenza del JWT. Se disponibile, `pdftotext` controlla anche il testo nei PDF. Il container deve essere dedicato al test o riavviabile. I file di test restano nello storico dell'utente.

Per provare anche l'aggiornamento tra due immagini Docker (la prima deve essere la versione precedente senza Flyway):

```bash
python3 scripts/test_upgrade.py --old-image immagine-precedente:tag
```

Il test crea un volume temporaneo, converte DOCX/PPTX con la vecchia immagine, avvia quella nuova sullo stesso volume senza password di bootstrap e verifica JWT preesistente, storico, quattro download identici, nuove conversioni e registrazione di baseline/V1/V2.

Test OIDC completo con un provider locale RSA (Docker Linux, Python 3 e OpenSSL):

```bash
python3 scripts/test_oidc.py
```

Verifica discovery, Authorization Code, firma e nonce, callback con URL pubblico, disattivazione login locale, logout/login, isolamento di due utenti, stabilità dello storico al cambio nome e dopo riavvio. Il provider è simulato: per la verifica contro il tuo Authentik servono le credenziali del provider reale.

Test browser opzionale (Node.js 20+):

```bash
npm ci
npx playwright install chromium
TEST_URL=http://localhost:8080 TEST_PASSWORD='la-tua-password' npm run test:ui
```

Copre navigazione multipagina, cartelle e sottocartelle, conversioni, rinomina e spostamento dei documenti, logout/login, quattro download dall’archivio, merge misto riordinato con frecce e trascinamento e layout mobile. Richiede `pdftotext` per verificare il testo del risultato; usa `CHECK_PDF_TEXT=false` se non disponibile. Le schermate sono in `target/screenshots`.

Per sviluppo: imposta `LOCAL_PASSWORD` e avvia `mvn spring-boot:run`. La conversione conserva il layout usando LibreOffice; font diversi da quelli originali possono modificare l'impaginazione. Il container include font Liberation, DejaVu, Carlito e Caladea. Non sono necessari servizi cloud o storage esterni.
