# Freenet mobile (non ufficiale)

App Android che fa girare sul telefono la nuova [Freenet](https://freenet.org) e apre le sue
app, come River. Nasce da [freenet-mobile/app](https://github.com/freenet-mobile/app), che usava
la vecchia rete (oggi Hyphanet). Non è un'app di The Freenet Project.

## Cosa cambia rispetto all'originale

- Il motore `fred` è sostituito dal programma ufficiale `freenet` (vedi `prepara-freenet.sh`).
- Telemetria e aggiornamento automatico sono spenti.
- L'accesso da PC senza password è stato tolto.
- Niente permessi per l'archivio, niente backup dei dati, niente immagini esterne.

- C'è un editor HTML: scrivi le pagine, guardi l'anteprima e pubblichi con `fdev website`.

## Compilare

Servono JDK 21 e Android SDK (piattaforma 35, build-tools 35.0.0).

    ./prepara-freenet.sh
    ./gradlew assembleRelease

L'APK per telefoni finisce in `app/build/outputs/apk/release/`. I programmi `freenet` e `fdev`
non sono nel repository: li scarica `prepara-freenet.sh`, che ne controlla le impronte.

## Limiti noti

- Non è un'app ufficiale di The Freenet Project e non è stata provata a lungo sui telefoni.
- Le build usano la chiave di debug: per distribuirla serve una chiave di firma propria.
- Sull'emulatore x86_64 il nodo non parte: Android blocca la chiamata di sistema `poll`.
- Le altre app sullo stesso telefono possono raggiungere il nodo su `127.0.0.1:7509`.
- La versione di Freenet è fissa: per aggiornarla va cambiata in `prepara-freenet.sh` e
  `app/build.gradle`.

Licenze: app GPL v2 (`LICENSE`); Freenet AGPL, codice su github.com/freenet/freenet-core.
