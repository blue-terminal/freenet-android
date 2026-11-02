# Sicurezza

Questa è una versione di prova, non ufficiale, e non è prodotta da The Freenet Project.

## Versioni supportate

| Versione       | Correzioni di sicurezza |
| -------------- | ----------------------- |
| 0.5.4          | Sì                      |
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
- I file dei siti e le chiavi per aggiornarli sono cifrati sul telefono con AES-256 e una chiave del sistema Android. Durante la pubblicazione restano in chiaro per poco tempo, nella cartella privata dell'app. I siti pubblicati sono pubblici e non sono cifrati.
- Se la chiave del sistema Android si perde, i siti salvati non si possono più aprire.
- La versione di Freenet inclusa è fissa e non si aggiorna da sola: si aggiorna con una nuova
  versione dell'app.
- I programmi `freenet` e `fdev` sono quelli ufficiali già compilati: se ne verificano le
  impronte, ma non vengono ricompilati da qui.
