# Sicurezza

Questa è una versione di prova, non ufficiale, e non è prodotta da The Freenet Project.

## Versioni supportate

| Versione       | Correzioni di sicurezza |
| -------------- | ----------------------- |
| 0.4.3          | Sì                      |
| Precedenti     | No                      |

Conviene usare sempre l'ultima versione della pagina
[Releases](https://github.com/blue-terminal/freenet-android/releases). L'app la cerca da sola, e
l'installazione la confermi sempre tu.

## Segnalare una vulnerabilità

Se la vulnerabilità è nell'app, usa **Security → Report a vulnerability** in questo repository, se
è disponibile. Altrimenti apri una Issue chiedendo un contatto privato, **senza scrivere i dettagli
tecnici in pubblico**.

Scrivi la versione dell'app, il modello del telefono, la versione di Android e i passi per
riprodurre il problema.

Questo è un progetto seguito da una persona sola, nel tempo libero: non ci sono tempi di risposta
garantiti. Le segnalazioni confermate vengono corrette nella versione successiva, e quelle
respinte ricevono una spiegazione.

Se la vulnerabilità è in Freenet stesso, segnalala al progetto
[freenet-core](https://github.com/freenet/freenet-core/security).

## Rischi noti

- Le altre app sullo stesso telefono possono raggiungere il nodo su `127.0.0.1:7509`, e da lì
  arrivare a dati e chiavi di Freenet.
- Gli APK sono firmati con una chiave di prova, non adatta alla distribuzione.
- La versione di Freenet inclusa è fissa e non si aggiorna da sola: si aggiorna con una nuova
  versione dell'app.
- I programmi `freenet` e `fdev` sono quelli ufficiali già compilati: se ne verificano le
  impronte, ma non vengono ricompilati da qui.
