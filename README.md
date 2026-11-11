# Freenet mobile (non ufficiale)

App Android che fa girare sul telefono la nuova [Freenet](https://freenet.org) e apre le sue
app, come River. Nasce da [freenet-mobile/app](https://github.com/freenet-mobile/app), che usava
la vecchia rete (oggi Hyphanet). Non è un'app di The Freenet Project.

<img src="docs/schermata-principale.png" width="240" alt="Schermata principale"> <img src="docs/schermata-crea-sito.png" width="240" alt="Editor per creare un sito">

## Versione attuale: v1.4.4

Scarica l'APK dalla pagina [Releases](https://github.com/blue-terminal/freenet-android/releases/latest).

## Cosa cambia rispetto all'originale

- Il motore `fred` è sostituito dal programma ufficiale `freenet` (vedi `prepara-freenet.sh`).
- Telemetria e aggiornamento automatico sono spenti.
- L'accesso da PC senza password è stato tolto.
- Niente permessi per l'archivio, niente backup dei dati, niente immagini esterne.

- C'è un editor HTML: scrivi le pagine, guardi l'anteprima e pubblichi con `fdev website`.

- Ha uno stile scuro nuovo.
- Può cercare e scaricare da sola le nuove versioni da GitHub. L'installazione la confermi sempre tu.
- **Temi stagionali** con grafica canvas: fiocchi di neve, pesci, pipistrelli, coriandoli e altro — oppure scegli il tema manualmente in Impostazioni → Aspetto → Tema.
- **Videochiamata** integrata (meet.ffmuc.net, nessun account richiesto).
- **Modalità daltonico** che ha sempre la precedenza sui temi stagionali.

## Compilare

Servono JDK 21 e Android SDK (piattaforma 35, build-tools 35.0.0).

    ./prepara-freenet.sh
    ./gradlew assembleRelease

L'APK per telefoni finisce in `app/build/outputs/apk/release/`. I programmi `freenet` e `fdev`
non sono nel repository: li scarica `prepara-freenet.sh`, che ne controlla le impronte.

## Limiti noti

- Non è un'app ufficiale di The Freenet Project. Non è stata verificata fino in fondo su un telefono vero: avvio del nodo, chat River e pubblicazione dei siti possono ancora dare problemi.
- Gli aggiornamenti funzionano solo con APK firmati dalla stessa chiave, che resta fuori dal repository. Senza quella chiave la build usa la chiave di debug.
- La chat River è stata segnalata come non funzionante. Dalla 0.4.1 c'è una correzione, ma non è stata verificata.
- Sull'emulatore x86_64 il nodo non parte: Android blocca la chiamata di sistema `poll`.
- Le altre app sullo stesso telefono possono raggiungere il nodo su `127.0.0.1:7509`.
- La versione di Freenet è fissa: per aggiornarla va cambiata in `prepara-freenet.sh` e
  `app/build.gradle`.

## Autori

- [desyncr](https://github.com/desyncr) — progetto originale [freenet-mobile/app](https://github.com/freenet-mobile/app)
- [blue-terminal](https://github.com/blue-terminal) — questa versione

## Licenza

GPL v3 (`LICENSE`). Autori originali, Freenet (AGPL) e altri componenti sono in `NOTICE.md`.


## ⚠️ Avviso / Disclaimer

**Italiano** — Questo progetto è realizzato esclusivamente a scopo educativo e di studio. Il software è fornito "così com'è", senza garanzie di alcun tipo. L'autore non si assume alcuna responsabilità per l'uso che ne viene fatto, né per i contenuti pubblicati dagli utenti sulla rete, né per eventuali danni diretti o indiretti. Chi lo usa se ne assume la piena responsabilità e deve rispettare le leggi del proprio paese.

**English** — This project is made for educational and study purposes only. The software is provided "as is", without warranty of any kind. The author takes no responsibility for how it is used, for content published by users on the network, or for any direct or indirect damage. Anyone who uses it does so at their own risk and must follow the laws of their own country.
