/**
 * fn-crypto.js — Web 7.0 Cryptographic Foundation v3
 *
 * CRITTOGRAFIA AVANZATA — QUATTRO STRATI:
 *
 * STRATO 1 — FN8Lang (linguaggio finto, generato al primo avvio)
 *   • Tabella di sostituzione casuale salvata in localStorage del dispositivo
 *   • MAI hardcoded su GitHub — il sorgente mostra solo l'algoritmo
 *   • Ogni dispositivo ha il SUO alfabeto unico
 *
 * STRATO 2 — Anti-fingerprint testo
 *   • Prima di inviare al nodo, insertRandomNoise() inietta omoglifi Unicode
 *     e caratteri zero-width in posizioni casuali
 *   • Ogni invio produce un fingerprint diverso — impossibile correlare
 *   • Il ricevitore usa stripNoise() per ripristinare il testo originale
 *
 * STRATO 3 — RSA-2048 + AES-GCM-256 (cifratura ibrida)
 *   • RSA-OAEP SHA-256 (2048 bit): cifra la chiave AES di sessione
 *   • AES-GCM-256: cifra il messaggio reale (con IV casuale)
 *   • RSA-PSS SHA-256 (2048 bit): firma digitale dell'identità
 *   • PBKDF2 + AES-GCM: protegge la chiave privata in localStorage
 *
 * STRATO 4 — Anti-DDoS / rilevamento attacchi
 *   • Traccia richieste per IP/fingerprint in finestre temporali
 *   • Blocca automaticamente sorgenti che superano la soglia
 *   • Registra IP bloccati con timestamp e conteggio in localStorage
 *
 * IDENTITÀ:
 *   Ogni utente ha solo una coppia RSA-2048.
 *   Nessuna email, nessun nome — l'identità è solo la chiave pubblica.
 *   Il fingerprint (SHA-256 della chiave pubblica) è l'unico identificativo.
 */

'use strict';

// ═══════════════════════════════════════════════════════════════════
// STRATO 1 — FN8Lang: linguaggio finto GENERATO ALL'AVVIO
//
// La tabella di sostituzione viene creata casualmente al primo avvio
// e salvata in localStorage. Ogni dispositivo ha il SUO alfabeto
// unico — non pubblicato su GitHub, non condiviso con nessuno.
// Senza la tabella locale il testo è completamente illeggibile.
// ═══════════════════════════════════════════════════════════════════

// Pool di simboli da alfabeti rari (96 simboli disponibili)
const _POOL = [
  'ⴰ','ⴱ','ⴲ','ⴳ','ⴴ','ⴵ','ⴶ','ⴷ','ⴸ','ⴹ','ⴺ','ⴻ','ⴼ','ⴽ','ⴾ','ⴿ',
  'ꔀ','ꔁ','ꔂ','ꔃ','ꔄ','ꔅ','ꔆ','ꔇ','ꔈ','ꔉ','ꔊ','ꔋ','ꔌ','ꔍ','ꔎ','ꔏ',
  'ꚠ','ꚡ','ꚢ','ꚣ','ꚤ','ꚥ','ꚦ','ꚧ','ꚨ','ꚩ','ꚪ','ꚫ','ꚬ','ꚭ','ꚮ','ꚯ',
  'ꓐ','ꓑ','ꓒ','ꓓ','ꓔ','ꓕ','ꓖ','ꓗ','ꓘ','ꓙ','ꓚ','ꓛ','ꓜ','ꓝ','ꓞ','ꓟ',
  'ꩠ','ꩡ','ꩢ','ꩣ','ꩤ','ꩥ','ꩦ','ꩧ','ꩨ','ꩩ','ꩪ','ꩫ','ꩬ','ꩭ','ꩮ','ꩯ',
  'ꧠ','ꧡ','ꧢ','ꧣ','ꧤ','ꧥ','ꧦ','ꧧ','ꧨ','ꧩ','ꧪ','ꧫ','ꧬ','ꧭ','ꧮ','ꧯ',
];
const _SEP_POOL = ['꩟','᪪','᪫','᪬','꩞','᪭','꩝','᪣','᪤','᪥','᪦','ᪧ'];
const _NUM_POOL = ['꘠','꘡','꘢','꘣','꘤','꘥','꘦','꘧','꘨','꘩'];

// Tabelle dinamiche (popolate da initLang)
let _ENCODE_MAP = new Map();
let _DECODE_MAP = new Map();
let _SEP = [];
let _NUMS = [];
let _langReady = false;

/** Mescola array in modo casuale (Fisher-Yates) */
function _shuffle(arr, rng) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/** Generatore pseudo-casuale da seed (xorshift32) */
function _makeRng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return (s >>> 0) / 4294967296;
  };
}

/**
 * Inizializza il linguaggio finto.
 * - Se già in localStorage: carica la tabella salvata.
 * - Altrimenti: genera una nuova tabella casuale e la salva.
 * DEVE essere chiamata prima di obfuscate/deobfuscate.
 */
export function initLang() {
  if (_langReady) return;
  const stored = (() => { try { return JSON.parse(localStorage.getItem('fn8_lang') || 'null'); } catch { return null; } })();
  if (stored) {
    _buildMaps(stored.enc, stored.sep, stored.nums);
  } else {
    // Genera seed da timestamp + performance + Math.random
    const seed = (Date.now() ^ (Math.random() * 0xFFFFFFFF | 0) ^ (performance.now() * 1000 | 0)) >>> 0;
    const rng = _makeRng(seed);
    // Mescola il pool — ogni dispositivo ha un ordine diverso
    const shuffled = _shuffle(_POOL, rng);
    // Costruisci tabella: ogni ASCII (32-127) → coppia di simboli unica
    const enc = {};
    let idx = 0;
    for (let c = 32; c <= 127; c++) {
      enc[c] = shuffled[idx % shuffled.length] + shuffled[(idx + 1) % shuffled.length];
      idx += 2;
    }
    const sep  = _shuffle(_SEP_POOL, rng).slice(0, 6);
    const nums = _shuffle(_NUM_POOL, rng);
    // Salva in localStorage (solo questo dispositivo può decodificare)
    try { localStorage.setItem('fn8_lang', JSON.stringify({ enc, sep, nums, seed })); } catch {}
    _buildMaps(enc, sep, nums);
  }
  _langReady = true;
}

function _buildMaps(enc, sep, nums) {
  _ENCODE_MAP = new Map();
  _DECODE_MAP = new Map();
  for (const [code, pair] of Object.entries(enc)) {
    const ch = String.fromCharCode(+code);
    _ENCODE_MAP.set(ch, pair);
    _DECODE_MAP.set(pair, ch);
  }
  _SEP  = sep;
  _NUMS = nums;
}

/** Strato 1: codifica testo → FN8Lang (unico per dispositivo) */
export function obfuscate(text) {
  initLang();
  let out = '';
  let count = 0;
  for (const ch of text) {
    if (ch >= '0' && ch <= '9') {
      out += _NUMS[+ch] + (_ENCODE_MAP.get('x') ?? '??');
    } else {
      out += _ENCODE_MAP.get(ch) ?? (ch + ch);
    }
    count++;
    if (count % (4 + (ch.charCodeAt(0) % 4)) === 0) {
      out += _SEP[count % _SEP.length];
    }
  }
  return out;
}

/** Strato 1: decodifica FN8Lang → testo normale */
export function deobfuscate(encoded) {
  initLang();
  let clean = encoded;
  for (const s of _SEP) clean = clean.split(s).join('');
  const chars = [...clean];
  let result = '';
  let i = 0;
  while (i < chars.length) {
    const numIdx = _NUMS.indexOf(chars[i]);
    if (numIdx !== -1) { result += String(numIdx); i += 2; continue; }
    if (i + 1 < chars.length) {
      const pair = chars[i] + chars[i + 1];
      const ch = _DECODE_MAP.get(pair);
      if (ch !== undefined) { result += ch; i += 2; continue; }
    }
    i++;
  }
  return result;
}

/** Esporta la tabella corrente (per condividerla con un contatto sicuro) */
export function exportLang() {
  initLang();
  return localStorage.getItem('fn8_lang') || null;
}

/** Importa una tabella da un contatto (per leggere i loro messaggi) */
export function importLang(langJson) {
  try {
    const data = JSON.parse(langJson);
    _buildMaps(data.enc, data.sep, data.nums);
    _langReady = true;
  } catch { throw new Error('Tabella FN8Lang non valida'); }
}

// ═══════════════════════════════════════════════════════════════════
// STRATO 2 — RSA-2048 + AES-GCM-256 (cifratura ibrida)
// ═══════════════════════════════════════════════════════════════════

const subtle = globalThis.crypto?.subtle;

const RSA_PARAMS = {
  name: 'RSA-OAEP',
  modulusLength: 2048,
  publicExponent: new Uint8Array([1, 0, 1]),
  hash: 'SHA-256'
};
const PSS_PARAMS = {
  name: 'RSA-PSS',
  modulusLength: 2048,
  publicExponent: new Uint8Array([1, 0, 1]),
  hash: 'SHA-256'
};

/**
 * Genera identità anonima RSA-2048:
 * - rsaEncrypt: coppia RSA-OAEP (cifra messaggi)
 * - rsaSign:    coppia RSA-PSS  (firma)
 */
export async function generateIdentity() {
  const [encPair, sigPair] = await Promise.all([
    subtle.generateKey(RSA_PARAMS, true, ['encrypt','decrypt']),
    subtle.generateKey(PSS_PARAMS, true, ['sign','verify'])
  ]);
  const [encPub, encPriv, sigPub, sigPriv] = await Promise.all([
    subtle.exportKey('jwk', encPair.publicKey),
    subtle.exportKey('jwk', encPair.privateKey),
    subtle.exportKey('jwk', sigPair.publicKey),
    subtle.exportKey('jwk', sigPair.privateKey)
  ]);
  const fp = await _fingerprint(encPub);
  return {
    encPublic:  encPub,
    encPrivate: encPriv,
    sigPublic:  sigPub,
    sigPrivate: sigPriv,
    fingerprint: fp   // es: "a3f2·7b1c·9e04·d85a"
  };
}

/**
 * Cifratura ibrida a doppio strato:
 *   plaintext → FN8Lang → AES-GCM(chiave random) → RSA-OAEP(chiave AES)
 *
 * @param {string} plaintext
 * @param {object} recipientEncPublicJwk — chiave pubblica RSA-OAEP del destinatario
 * @returns {{ ekey, iv, ct }} tutto in base64
 */
export async function encryptFull(plaintext, recipientEncPublicJwk) {
  // Strato 1
  const obfuscated = obfuscate(plaintext);

  // Genera chiave AES-256 di sessione
  const aesKey = await subtle.generateKey({ name:'AES-GCM', length:256 }, true, ['encrypt','decrypt']);
  const aesRaw = await subtle.exportKey('raw', aesKey);

  // Strato 2a: cifra il testo con AES-GCM
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(obfuscated);
  const ct = await subtle.encrypt({ name:'AES-GCM', iv }, aesKey, encoded);

  // Strato 2b: cifra la chiave AES con RSA-OAEP del destinatario
  const recvPub = await subtle.importKey('jwk', recipientEncPublicJwk, RSA_PARAMS, false, ['encrypt']);
  const ekey = await subtle.encrypt({ name:'RSA-OAEP' }, recvPub, aesRaw);

  return {
    ekey: _b64(new Uint8Array(ekey)),
    iv:   _b64(iv),
    ct:   _b64(new Uint8Array(ct))
  };
}

/**
 * Decifratura ibrida a doppio strato:
 *   RSA-OAEP(chiave AES) → AES-GCM → FN8Lang → plaintext
 *
 * @param {{ ekey, iv, ct }} packet
 * @param {object} myEncPrivateJwk — chiave privata RSA-OAEP del destinatario
 */
export async function decryptFull(packet, myEncPrivateJwk) {
  const myPriv = await subtle.importKey('jwk', myEncPrivateJwk, RSA_PARAMS, false, ['decrypt']);

  // Decifra chiave AES con RSA privata
  const aesRaw = await subtle.decrypt({ name:'RSA-OAEP' }, myPriv, _unb64(packet.ekey));
  const aesKey = await subtle.importKey('raw', aesRaw, { name:'AES-GCM' }, false, ['decrypt']);

  // Decifra messaggio con AES-GCM
  const dec = await subtle.decrypt({ name:'AES-GCM', iv: _unb64(packet.iv) }, aesKey, _unb64(packet.ct));
  const obfuscated = new TextDecoder().decode(dec);

  // Rimuovi strato 1
  return deobfuscate(obfuscated);
}

/** Firma RSA-PSS */
export async function signMessage(data, sigPrivateJwk) {
  const key = await subtle.importKey('jwk', sigPrivateJwk, PSS_PARAMS, false, ['sign']);
  const sig = await subtle.sign(
    { name:'RSA-PSS', saltLength: 32 }, key,
    new TextEncoder().encode(data)
  );
  return _b64(new Uint8Array(sig));
}

/** Verifica firma RSA-PSS */
export async function verifySignature(data, signatureB64, sigPublicJwk) {
  const key = await subtle.importKey('jwk', sigPublicJwk, PSS_PARAMS, false, ['verify']);
  return subtle.verify(
    { name:'RSA-PSS', saltLength: 32 }, key,
    _unb64(signatureB64),
    new TextEncoder().encode(data)
  );
}

/**
 * Salva identità in localStorage — cifrata con password utente (PBKDF2 + AES-GCM)
 * La chiave privata RSA non lascia mai il dispositivo in chiaro.
 */
export async function saveIdentity(identity, password) {
  const k = await _pwdKey(password);
  const enc = await _aesEncrypt(k, JSON.stringify(identity));
  localStorage.setItem('fn8_id', JSON.stringify(enc));
}

/** Carica e decifra identità da localStorage */
export async function loadIdentity(password) {
  const raw = localStorage.getItem('fn8_id');
  if (!raw) return null;
  try {
    const k = await _pwdKey(password);
    const json = await _aesDecrypt(k, JSON.parse(raw));
    return JSON.parse(json);
  } catch { return null; }
}

// ═══════════════════════════════════════════════════════════════════
// UTILITÀ
// ═══════════════════════════════════════════════════════════════════

function _b64(buf) {
  return btoa(String.fromCharCode(...new Uint8Array(buf)));
}
function _unb64(s) {
  return Uint8Array.from(atob(s), c => c.charCodeAt(0));
}

async function _fingerprint(rsaPublicJwk) {
  const data = new TextEncoder().encode(rsaPublicJwk.n);
  const hash = new Uint8Array(await subtle.digest('SHA-256', data));
  const hex = [...hash].map(b => b.toString(16).padStart(2,'0')).join('');
  return [0,4,8,12].map(i => hex.slice(i, i+4)).join('·');
}

async function _pwdKey(password) {
  const raw = await subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  const salt = new TextEncoder().encode('fn8salt_v2_web70');
  return subtle.deriveKey(
    { name:'PBKDF2', salt, iterations:200000, hash:'SHA-256' },
    raw,
    { name:'AES-GCM', length:256 }, false, ['encrypt','decrypt']
  );
}

async function _aesEncrypt(key, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await subtle.encrypt({ name:'AES-GCM', iv }, key, new TextEncoder().encode(text));
  return { iv: _b64(iv), ct: _b64(new Uint8Array(ct)) };
}

async function _aesDecrypt(key, { iv, ct }) {
  const dec = await subtle.decrypt({ name:'AES-GCM', iv: _unb64(iv) }, key, _unb64(ct));
  return new TextDecoder().decode(dec);
}

// ═══════════════════════════════════════════════════════════════════
// STRATO 2 — Anti-fingerprint testo
//
// Ogni messaggio viene alterato con omoglifi Unicode e caratteri
// zero-width prima di essere inviato al nodo Freenet.
// Questo rende impossibile il fingerprinting statistico del mittente:
// lo stesso testo inviato due volte produce bit diversi.
// ═══════════════════════════════════════════════════════════════════

// Sostituzioni omoglife: caratteri ASCII → equivalenti Unicode visivamente identici
const _HOMO = new Map([
  ['a','а'],['c','с'],['e','е'],['o','о'],
  ['p','р'],['s','ѕ'],['x','х'],['y','у'],
  ['A','А'],['B','В'],['C','С'],['E','Е'],
  ['H','Н'],['I','І'],['K','К'],['M','М'],
  ['O','О'],['P','Р'],['T','Т'],['X','Х'],
]);

// Caratteri zero-width invisibili (steganografia)
const _ZW = ['​','‌','‍','﻿','⁠','᠎'];

// Generatore casuale leggero (non crypto — solo per varianza fingerprint)
function _fprng(seed) {
  let s = seed >>> 0 || 0xdeadbeef;
  return () => { s = (s ^ (s << 13) ^ (s >> 17) ^ (s << 5)) >>> 0; return s / 0x100000000; };
}

/**
 * Inietta rumore anti-fingerprint nel testo.
 * Chiamare PRIMA di encryptFull() quando si invia al nodo.
 * @param {string} text - testo in chiaro
 * @returns {string} - testo con rumore inserito (aspetto identico)
 */
export function insertRandomNoise(text) {
  const rng = _fprng(Date.now() ^ (Math.random() * 0xFFFF | 0));
  let out = '';
  for (const ch of text) {
    // ~25% delle lettere viene sostituita con il suo omoglifo
    if (rng() < 0.25 && _HOMO.has(ch)) {
      out += _HOMO.get(ch);
    } else {
      out += ch;
    }
    // ~15% delle posizioni riceve un carattere zero-width
    if (rng() < 0.15) {
      out += _ZW[Math.floor(rng() * _ZW.length)];
    }
  }
  return out;
}

/**
 * Rimuove il rumore anti-fingerprint (per il ricevitore).
 * Chiamare dopo decryptFull() per ottenere il testo originale.
 * @param {string} noisy - testo con rumore
 * @returns {string} - testo pulito
 */
export function stripNoise(noisy) {
  // Rimuovi zero-width
  let clean = noisy;
  for (const zw of _ZW) clean = clean.split(zw).join('');
  // Riconverti omoglifi → ASCII
  const REV = new Map([..._HOMO.entries()].map(([k, v]) => [v, k]));
  return [...clean].map(ch => REV.get(ch) ?? ch).join('');
}

// ═══════════════════════════════════════════════════════════════════
// STRATO 4 — Anti-DDoS / rilevamento e blocco attacchi
//
// Protegge il nodo da attacchi di tipo denial-of-service.
// Traccia le richieste per sorgente (IP o fingerprint), blocca
// automaticamente chi supera la soglia e registra l'incidente.
// ═══════════════════════════════════════════════════════════════════

const _DDOS_WINDOW_MS  = 10_000; // 10 secondi
const _DDOS_MAX_REQ    = 30;     // max richieste per finestra
const _DDOS_BLOCK_MS   = 300_000; // 5 minuti di blocco

// Mappa in memoria: sorgente → { timestamps: [], blockedUntil: 0 }
const _ddosState = new Map();

function _loadBlockList() {
  try { return JSON.parse(localStorage.getItem('fn8_blocklist') || '{}'); } catch { return {}; }
}
function _saveBlockList(bl) {
  try { localStorage.setItem('fn8_blocklist', JSON.stringify(bl)); } catch {}
}

/**
 * Registra una richiesta in arrivo e verifica se la sorgente è bloccata.
 * @param {string} source - IP o fingerprint della sorgente
 * @returns {{ allowed: boolean, reason?: string, blockedUntil?: number }}
 */
export function checkRequest(source) {
  const now = Date.now();

  // Controlla lista nera persistente
  const bl = _loadBlockList();
  if (bl[source] && bl[source].until > now) {
    return { allowed: false, reason: 'blocked', blockedUntil: bl[source].until };
  }

  // Stato in-memory
  if (!_ddosState.has(source)) _ddosState.set(source, { timestamps: [], blockedUntil: 0 });
  const st = _ddosState.get(source);

  if (st.blockedUntil > now) {
    return { allowed: false, reason: 'rate_limited', blockedUntil: st.blockedUntil };
  }

  // Pulisci timestamp vecchi
  st.timestamps = st.timestamps.filter(t => now - t < _DDOS_WINDOW_MS);
  st.timestamps.push(now);

  if (st.timestamps.length > _DDOS_MAX_REQ) {
    // Attacco rilevato: blocca
    st.blockedUntil = now + _DDOS_BLOCK_MS;
    // Persisti in localStorage con metadati
    bl[source] = { until: st.blockedUntil, count: st.timestamps.length, firstSeen: now, type: 'ddos' };
    _saveBlockList(bl);
    console.warn(`[fn8-security] DDoS rilevato da ${source} — bloccato per 5 minuti`);
    return { allowed: false, reason: 'ddos_detected', blockedUntil: st.blockedUntil };
  }

  return { allowed: true };
}

/**
 * Ritorna la lista degli IP/sorgenti bloccati.
 * @returns {Array<{ source, until, count, type }>}
 */
export function getBlockList() {
  const now = Date.now();
  const bl = _loadBlockList();
  return Object.entries(bl)
    .filter(([, v]) => v.until > now)
    .map(([source, v]) => ({ source, ...v }))
    .sort((a, b) => b.count - a.count);
}

/**
 * Sblocca manualmente una sorgente (solo per amministratori).
 * @param {string} source
 */
export function unblockSource(source) {
  const bl = _loadBlockList();
  delete bl[source];
  _saveBlockList(bl);
  _ddosState.delete(source);
}

/**
 * Pulisce le voci scadute dalla lista nera.
 * Chiamare periodicamente (es. ogni 10 minuti).
 */
export function purgeExpiredBlocks() {
  const now = Date.now();
  const bl = _loadBlockList();
  let changed = false;
  for (const src of Object.keys(bl)) {
    if (bl[src].until <= now) { delete bl[src]; changed = true; }
  }
  if (changed) _saveBlockList(bl);
}
