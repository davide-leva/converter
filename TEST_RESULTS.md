# Verifica — 14 ottobre 2026

Verifica iniziale, precedente all’introduzione di Flyway: immagine `forma-converter:local`.

ID dell'immagine testata:
`sha256:dc1fc583bd818710f59674e9472ae96f179be233c942824039007fd4327c9cae`.

| Requisito | Evidenza | Esito |
| --- | --- | --- |
| Backend Spring Boot e API | `mvn test` con Java 21; un test d’integrazione composto da più verifiche HTTP | PASS |
| Avvio Docker e collegamento | Docker Compose; richieste HTTP e Chromium al servizio pubblicato sulla porta 18080 | PASS |
| DOCX → PDF | DOCX generato da Apache POI, upload HTTP, header PDF e testo estratto con pdftotext | PASS |
| PPTX → PDF | PPTX generato da Apache POI, upload HTTP, header PDF e testo estratto con pdftotext | PASS |
| Originali e PDF scaricabili dopo nuovo login | Logout/login, quattro download; confronto binario originali e SHA-256 dei PDF | PASS |
| Storico personale e isolamento utenti | Utente locale e secondo utente; archivio separato e download altrui 404. Ripetuto con due identità OIDC | PASS |
| SQLite e file su filesystem | Database SQLite e coppie originali/PDF in `/data/files/<uuid>/`, ispezionati nel container | PASS |
| Volume configurabile e persistenza | Volume nominato `converter-acceptance-data` montato su `/data`; ricreazione e riavvio container; storico e file identici. Configurazione Compose bind mount validata | PASS |
| URL pubblico configurabile | URL locale porta 18080 con porta interna 8080; callback OIDC porta 18081 verificata dal provider | PASS |
| Login locale JWT in assenza OIDC | Login, cookie HttpOnly, token Bearer, revoca dopo logout e persistenza chiave/token dopo riavvio | PASS |
| OIDC configurabile | Discovery e Authorization Code reali con provider di test RSA; callback, firma, nonce, logout/login e riavvio Docker | PASS |
| Configurazione OIDC parziale | Avvio container rifiutato con messaggio esplicito; nessun fallback locale | PASS |
| Frontend semplice | Browser: caricamento dei due formati, download automatici, logout/login, quattro download; screenshot desktop/mobile, nessun errore JavaScript e nessun overflow mobile | PASS |
| Input e sicurezza | CSRF richiesto, password errata, formato non supportato, DOCX non valido e username duplicato | PASS |

Output sintetici delle esecuzioni in `target/verification/`, report JUnit in `target/surefire-reports/`, screenshot in `target/screenshots/`.

I test OIDC usano un provider locale che implementa discovery, authorization endpoint, token endpoint, JWKS e userinfo con firma RSA. Non è stato collegato un Authentik reale: servono issuer e credenziali del provider dell’utente. Il README indica configurazione Authentik con issuer per applicazione e signing key RS256.

L’infrastruttura di test Docker è temporanea e viene rimossa dopo la verifica. I comandi per ripetere tutti i test sono nel README; l’immagine Docker resta disponibile localmente.

## Aggiornamento: migrazioni Flyway

Immagine aggiornata costruita e verificata: `forma-converter:local`, ID `sha256:cd2238094e7f8c20cf9b3218046dbced5834ec8eb21fe9acf728f5a8114b3407`.

- `mvn test` con Java 21: **6 test passati**, nessun errore o test saltato.
- Database nuovo: V1 crea lo schema; un secondo avvio non ripete la migrazione.
- Database precedente: baseline 0 e V1 conservano tutte le righe di utenti, token e conversioni.
- Istanza Spring Boot precedente: avvio senza nuove credenziali di bootstrap, login con password originale, storico e download dei file già archiviati; chiave JWT invariata.
- Migrazione successiva V2 di test: aggiunta colonna e aggiornamento dati; esecuzione una sola volta.
- Modifica del checksum di una migrazione applicata: aggiornamento rifiutato.
- Migrazione SQL fallita: rollback di ALTER TABLE e DELETE, dati preservati e versione non avanzata.
- Upgrade Docker reale dalla vecchia immagine sullo stesso volume: JWT già emesso valido, storico e quattro download identici; nuova conversione riuscita; baseline 0 e V1 confermate nella tabella Flyway.

Output del test Docker in `target/verification/migration-upgrade.txt`; report JUnit in `target/surefire-reports/`. Il test Docker è ripetibile con `scripts/test_upgrade.py` e rimuove i propri container e volume temporanei.

## Aggiornamento: bump e controllo versione al push

- Quattro test automatici in repository Git temporanei: push iniziale, blocco senza bump, rifiuto del bump non committato, bump patch/minor/major, nuovi branch, cancellazione branch, modifiche a documentazione/test, manifest incoerenti e regressione della versione. Tutti passati.
- Hook locale installato e verificato: `core.hooksPath=.githooks`. Nessun commit o push eseguito sul repository del progetto; i push dei test usano soltanto remoti locali temporanei.
- Packaging Maven con Java 21 riuscito: `target/converter.jar`, nome indipendente dalla versione e corrispondente al percorso usato dal Dockerfile.
- Bash e Python: verifiche di sintassi passate. Versioni iniziali sincronizzate a `1.0.0`.
- Workflow GitHub Actions aggiunto in `.github/workflows/version-check.yml`; non eseguito su GitHub durante questa verifica.

Ripetere con `python3 scripts/test_versioning.py`.
