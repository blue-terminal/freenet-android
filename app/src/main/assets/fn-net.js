// Rete Web 7.0: identità firmata, bacheche condivise (contratto hub) e siti su Freenet.
(function (root) {
  // Pagina aperta come sito Freenet: si parla col nodo che la serve, anche dentro l'app
  // (lì il bridge esiste ma risponde solo alle pagine interne dell'app).
  const INSIDE_FREENET = location.pathname.startsWith('/v1/contract/web/');
  const ANDROID = !INSIDE_FREENET && typeof Android !== 'undefined' && !!Android.net;
  // Dentro l'app Android anche le pagine del sito Freenet: per pubblicare si passa alla pagina interna.
  const IN_APP = INSIDE_FREENET && (typeof Android !== 'undefined' || /; wv\)/.test(navigator.userAgent));
  const UPLOAD_ROUTE = /^#\/(upload|live|new|new\/storia)$/;
  function openInApp() {
    const page = location.pathname.split('/').pop() || 'index.html';
    const route = UPLOAD_ROUTE.test(location.hash) ? location.hash : '';
    window.open('https://web70.invalid/open?page=' + encodeURIComponent(page) + '&route=' + encodeURIComponent(route), '_blank');
  }
  if (IN_APP) {
    const check = () => { if (UPLOAD_ROUTE.test(location.hash)) openInApp(); };
    addEventListener('hashchange', check);
    setTimeout(check, 0);
  }
  const NODE = INSIDE_FREENET ? location.protocol + '//' + location.host : 'http://127.0.0.1:7509';
  const PREFIX = 'fn7_';
  // Pezzi piccoli: dal telefono ognuno parte presto e si vede l'avanzamento.
  const PART_BYTES = 8 * 1024 * 1024;
  const MAX_PARTS = 40;
  const CHUNK_BYTES = 768 * 1024;
  const SAFE_ID = /^[A-Za-z0-9_-]{1,64}$/;
  const SAFE_KEY = /^[1-9A-HJ-NP-Za-km-z]{32,50}$/;
  const REPORTS_TO_HIDE = 3;
  const WORK_BITS = 14; // deve coincidere con il contratto web70-hub
  const MAX_PER_MINUTE = 20;
  const CATEGORIES = ['Scienza', 'Tecnologia', 'Matematica', 'Storia', 'Lingue', 'Salute', 'Filosofia', 'Arte', 'Documentari', 'Tutorial'];

  // Nella sandbox di Freenet localStorage non esiste: i dati della sessione passano da una
  // pagina all'altra in window.name, che resta finché la scheda è aperta.
  const NAME_PREFIX = 'web70:';
  const hasLocalStorage = (() => { try { localStorage.setItem(PREFIX + 't', '1'); localStorage.removeItem(PREFIX + 't'); return true; } catch { return false; } })();
  const session = (() => {
    try { return root.name.startsWith(NAME_PREFIX) ? JSON.parse(root.name.slice(NAME_PREFIX.length)) : {}; } catch { return {}; }
  })();
  const saveSession = () => { try { root.name = NAME_PREFIX + JSON.stringify(session); } catch {} };
  const store = hasLocalStorage ? {
    get(k, d) { try { const v = localStorage.getItem(PREFIX + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(PREFIX + k, JSON.stringify(v)); return true; } catch { return false; } },
    del(k) { try { localStorage.removeItem(PREFIX + k); } catch {} },
  } : {
    get(k, d) { return k in session ? JSON.parse(JSON.stringify(session[k])) : d; },
    set(k, v) { session[k] = v; saveSession(); return true; },
    del(k) { delete session[k]; saveSession(); },
  };

  const esc = s => String(s === undefined || s === null ? '' : s).replace(/[&<>"'`]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;' }[c]));
  const safeImage = s => (typeof s === 'string' && /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(s) ? s : '');
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64u = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  const newId = (prefix) => prefix + b64u(crypto.getRandomValues(new Uint8Array(12)));
  const sha = async text => b64u(await crypto.subtle.digest('SHA-256', enc.encode(text)));
  // Impronta di un file: va nel messaggio firmato, e chi scarica controlla che il contenuto sia quello.
  const hashBlob = async blob => b64u(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()));

  function timeAgo(t) {
    const s = Math.max(0, (Date.now() - t) / 1000);
    if (s < 60) return 'adesso';
    if (s < 3600) return Math.floor(s / 60) + ' min fa';
    if (s < 86400) return Math.floor(s / 3600) + ' ore fa';
    if (s < 2592000) return Math.floor(s / 86400) + ' giorni fa';
    return new Date(t).toLocaleDateString('it-IT');
  }

  // ── identità ───────────────────────────────────────────────────────────────
  const ECDSA = { name: 'ECDSA', namedCurve: 'P-256' };
  const SIGN = { name: 'ECDSA', hash: 'SHA-256' };
  let identityPromise = null;

  function logout() {
    for (const k of Object.keys(session)) delete session[k];
    saveSession();
    store.del('identity');
    identityPromise = null;
  }

  function identity() {
    if (identityPromise) return identityPromise;
    identityPromise = (async () => {
      // Nessuna password: se non c'è un'identità se ne crea una nuova, anonima.
      let saved = store.get('identity');
      if (!saved) {
        const pair = await crypto.subtle.generateKey(ECDSA, true, ['sign', 'verify']);
        const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
        saved = { jwk, pub: jwk.x + '.' + jwk.y };
        store.set('identity', saved);
      }
      const key = await crypto.subtle.importKey('jwk', saved.jwk, ECDSA, false, ['sign']);
      return { key, pub: saved.pub, tag: saved.pub.slice(0, 40) };
    })();
    return identityPromise;
  }

  function exportIdentity() { return JSON.stringify(store.get('identity')); }
  function importIdentity(text) {
    const parsed = JSON.parse(text);
    if (!parsed || !parsed.jwk || !parsed.jwk.d || parsed.pub !== parsed.jwk.x + '.' + parsed.jwk.y) throw new Error('File identità non valido');
    store.set('identity', parsed);
    identityPromise = null;
  }

  const profile = () => store.get('profile', { name: '', bio: '', avatar: '' });
  const setProfileLocal = p => store.set('profile', p);
  async function displayName() {
    const id = await identity();
    return profile().name || 'utente-' + id.tag.slice(0, 5);
  }

  // ── bridge Android ─────────────────────────────────────────────────────────
  const pending = {};
  let callSeq = 0;
  root.__fnReply = (id, json) => { const done = pending[id]; delete pending[id]; if (done) done(JSON.parse(json)); };
  const hasBridge = () => ANDROID || INSIDE_FREENET;

  function waitToken() {
    return new Promise(res => {
      if (root.__fnToken) return res();
      const t = setInterval(() => { if (root.__fnToken) { clearInterval(t); res(); } }, 40);
    });
  }

  async function call(method, args) {
    if (INSIDE_FREENET) {
      if (method === 'hubGet') return { ok: true, state: await FreenetWS.hubGet(args.topic) };
      if (method === 'hubPost') { await FreenetWS.hubPost(args.topic, args.message); return { ok: true }; }
      if (IN_APP) throw new Error("Per caricare foto e video tocca ＋ o Carica: si apre la pagina dell'app");
      throw new Error("Per caricare video e foto usa l'app Freenet o Web 7.0 per PC");
    }
    if (!hasBridge()) throw new Error("Questa funzione è disponibile nell'app Freenet");
    await waitToken();
    const reply = await new Promise(res => {
      const id = 'c' + (++callSeq);
      pending[id] = res;
      Android.net(root.__fnToken, method, JSON.stringify(args), id);
    });
    if (!reply.ok) throw new Error(reply.error || 'Errore del nodo');
    return reply;
  }

  // ── bacheche condivise ─────────────────────────────────────────────────────
  const verified = new Map();

  function leadingZeroBits(bytes) {
    let bits = 0;
    for (const b of bytes) {
      if (b === 0) { bits += 8; continue; }
      return bits + Math.clz32(b) - 24;
    }
    return bits;
  }

  // Lavoro richiesto dal contratto: costa poco a chi scrive, molto a chi vuole inondare la bacheca.
  async function proofOfWork(id) {
    for (let nonce = 0; ; nonce++) {
      const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(`${id}|${nonce}`)));
      if (leadingZeroBits(hash) >= WORK_BITS) return String(nonce);
    }
  }

  async function signedMessage(kind, data) {
    const id = await identity();
    const t = Date.now(), n = (await displayName()).slice(0, 40);
    const p = JSON.stringify({ k: kind, ...data });
    const text = `${t}|${id.pub}|${n}|${p}`;
    const s = b64u(await crypto.subtle.sign(SIGN, id.key, enc.encode(text)));
    const i = (await sha(text)).slice(0, 32);
    return { i, t, a: id.pub, n, p, s, w: await proofOfWork(i) };
  }

  async function checkMessage(m) {
    if (verified.has(m.i)) return verified.get(m.i);
    let out = null;
    try {
      const text = `${m.t}|${m.a}|${m.n}|${m.p}`;
      const [x, y] = String(m.a).split('.');
      if (m.t <= Date.now() + 600000 && (await sha(text)).slice(0, 32) === m.i) {
        const key = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', x, y, ext: true }, ECDSA, false, ['verify']);
        if (await crypto.subtle.verify(SIGN, key, unb64u(m.s), enc.encode(text))) {
          out = { ...JSON.parse(m.p), id: m.i, time: m.t, author: m.a, tag: m.a.slice(0, 40), name: String(m.n) };
        }
      }
    } catch {}
    verified.set(m.i, out);
    return out;
  }

  const outbox = () => store.get('outbox', []);

  async function flushOutbox() {
    const queued = outbox();
    if (!queued.length || !hasBridge()) return;
    const left = [];
    for (const q of queued) {
      try { await call('hubPost', { topic: q.topic, message: JSON.stringify(q.message) }); } catch { left.push(q); }
    }
    store.set('outbox', left);
  }

  // Non perde nulla: se il nodo non risponde il messaggio resta in coda e riparte da solo.
  async function post(topic, kind, data) {
    const message = await signedMessage(kind, data);
    if (JSON.stringify(message).length > 15000) throw new Error('Testo o immagine troppo grandi');
    try {
      await call('hubPost', { topic, message: JSON.stringify(message) });
      return { queued: false, id: message.i };
    } catch {
      const queued = outbox();
      queued.push({ topic, message });
      store.set('outbox', queued);
      return { queued: true, id: message.i };
    }
  }

  // ── messaggi privati: cifrati da un capo all'altro ───────────────────────────
  // La chiave nasce dalle due identità (ECDH P-256 + HKDF): la conoscono solo i due utenti.
  // Sulla rete passa solo testo cifrato AES-256-GCM: senza la chiave è impossibile leggerlo.
  const dmKeys = new Map();
  async function dmKey(theirPub) {
    if (dmKeys.has(theirPub)) return dmKeys.get(theirPub);
    const { d, x, y } = store.get('identity').jwk;
    const mine = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', d, x, y, ext: true }, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const [tx, ty] = String(theirPub).split('.');
    const theirs = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', x: tx, y: ty, ext: true }, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const secret = await crypto.subtle.deriveBits({ name: 'ECDH', public: theirs }, mine, 256);
    const base = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
    const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode('web70-dm-v1'), info: enc.encode([x + '.' + y, theirPub].sort().join('|')) },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    dmKeys.set(theirPub, key);
    return key;
  }
  const PUB = /^[A-Za-z0-9_-]{43}\.[A-Za-z0-9_-]{43}$/;
  async function dmTopic(theirPub) {
    const id = await identity();
    return 'dm:' + (await sha([id.pub, theirPub].sort().join('|'))).slice(0, 40);
  }
  async function sendDM(theirPub, text) {
    if (!PUB.test(theirPub)) throw new Error('Utente non valido');
    const id = await identity();
    if (theirPub === id.pub) throw new Error('Non puoi scrivere a te stesso');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const c = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await dmKey(theirPub), enc.encode(JSON.stringify({ text: String(text).slice(0, 4000) })));
    const r = await post(await dmTopic(theirPub), 'dm', { iv: b64u(iv), c: b64u(c) });
    // Nella casella del destinatario resta solo "ti ha scritto": nessun contenuto.
    await post('dmi:' + theirPub.slice(0, 40), 'dm-hi', {}).catch(() => {});
    return r;
  }
  async function readDM(theirPub) {
    if (!PUB.test(theirPub)) return [];
    const id = await identity();
    const key = await dmKey(theirPub);
    const out = [];
    for (const m of await read(await dmTopic(theirPub))) {
      if (m.k !== 'dm' || (m.author !== id.pub && m.author !== theirPub)) continue;
      try {
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64u(m.iv) }, key, unb64u(m.c));
        out.push({ text: String(JSON.parse(dec.decode(plain)).text || ''), mine: m.author === id.pub, time: m.time, name: m.name });
      } catch {}
    }
    return out.sort((a, b) => a.time - b.time);
  }
  async function dmInbox() {
    const id = await identity();
    const people = new Map();
    for (const m of await read('dmi:' + id.tag)) if (m.k === 'dm-hi' && PUB.test(m.author)) people.set(m.author, { pub: m.author, name: m.name, time: m.time });
    for (const p of store.get('dm_people', [])) if (!people.has(p.pub)) people.set(p.pub, p);
    return [...people.values()].sort((a, b) => b.time - a.time);
  }
  function rememberDM(pub, name) {
    const list = store.get('dm_people', []).filter(p => p.pub !== pub);
    list.unshift({ pub, name, time: Date.now() });
    store.set('dm_people', list.slice(0, 100));
  }

  // Protezione sul telefono: utenti bloccati fuori, e un autore non può riempire lo schermo.
  const blocked = () => store.get('blocked', {});
  function blockUser(tag, name) { const b = blocked(); b[tag] = name || tag.slice(0, 8); store.set('blocked', b); }
  function unblockUser(tag) { const b = blocked(); delete b[tag]; store.set('blocked', b); }
  let filteredCount = 0;

  function limitFlood(messages) {
    const block = blocked();
    const recent = new Map();
    return messages.filter(m => {
      if (block[m.tag]) { filteredCount++; return false; }
      const minute = Math.floor(m.time / 60000);
      const key = m.author + minute;
      const n = (recent.get(key) || 0) + 1;
      recent.set(key, n);
      if (n > MAX_PER_MINUTE) { filteredCount++; return false; }
      return true;
    });
  }

  // Copia delle bacheche per quando il nodo non risponde: solo le più recenti, così la memoria
  // del browser non si riempie (piena, ogni salvataggio fallisce e tutto rallenta).
  const BOARD_KEEP = hasLocalStorage ? 40 : 8;
  const BOARD_MESSAGES = hasLocalStorage ? 150 : 40;
  function cacheBoard(topic, raw) {
    const keys = store.get('board_keys', []).filter(k => k !== topic);
    keys.push(topic);
    while (keys.length > BOARD_KEEP) store.del('board_' + keys.shift());
    store.set('board_keys', keys);
    if (!store.set('board_' + topic, raw.slice(-BOARD_MESSAGES))) {
      // Spazio finito: si liberano le copie più vecchie e si riprova una volta.
      while (keys.length > 1) store.del('board_' + keys.shift());
      store.set('board_keys', keys);
      store.set('board_' + topic, raw.slice(-BOARD_MESSAGES));
    }
  }
  // Le copie salvate dalle versioni precedenti non avevano limite: si tolgono quelle fuori elenco.
  if (hasLocalStorage) {
    try {
      const keep = new Set(store.get('board_keys', []).map(k => PREFIX + 'board_' + k));
      for (let i = localStorage.length - 1; i >= 0; i--) {
        const k = localStorage.key(i);
        if (k && k.startsWith(PREFIX + 'board_') && k !== PREFIX + 'board_keys' && !keep.has(k)) localStorage.removeItem(k);
      }
    } catch {}
  } else {
    for (const k of Object.keys(session)) if (k.startsWith('board_') && k !== 'board_keys' && !(store.get('board_keys', []).includes(k.slice(6)))) delete session[k];
    saveSession();
  }

  // Video già scaricati in questa pagina: tornando indietro non si riscaricano, e i più vecchi
  // vengono liberati dalla memoria (un video può pesare decine di MB).
  const videoCache = new Map();
  const VIDEO_KEEP = 3;
  async function videoUrl(item, onProgress) {
    const key = (item.parts || []).map(p => p && p.sha).join(',');
    if (videoCache.has(key)) {
      const url = videoCache.get(key);
      videoCache.delete(key); videoCache.set(key, url);
      return url;
    }
    const url = URL.createObjectURL(await downloadVideo(item, onProgress));
    videoCache.set(key, url);
    while (videoCache.size > VIDEO_KEEP) {
      const [oldKey, oldUrl] = videoCache.entries().next().value;
      videoCache.delete(oldKey);
      URL.revokeObjectURL(oldUrl);
    }
    return url;
  }

  let lastReadError = '';
  async function read(topic) {
    let raw = [];
    let online = true;
    try {
      raw = JSON.parse((await call('hubGet', { topic })).state).m || [];
      cacheBoard(topic, raw);
    } catch (e) {
      lastReadError = String(e && e.message || e).slice(0, 160);
      online = false;
      raw = store.get('board_' + topic, []);
    }
    const local = outbox().filter(q => q.topic === topic).map(q => q.message);
    const seen = new Set(raw.map(m => m.i));
    const all = raw.concat(local.filter(m => !seen.has(m.i)));
    const checked = (await Promise.all(all.map(checkMessage))).filter(Boolean);
    checked.sort((a, b) => a.time - b.time || (a.id < b.id ? -1 : 1));
    const result = limitFlood(checked);
    result.online = online;
    return result;
  }

  // Elementi con modifiche ed eliminazioni: conta solo chi ha creato l'elemento.
  function foldItems(messages) {
    const items = new Map();
    const reports = new Map();
    for (const m of messages) {
      if (!SAFE_ID.test(String(m.item))) continue;
      const old = items.get(m.item);
      if (m.k === 'item' && m.data && typeof m.data === 'object') {
        if (!old || old.author === m.author) {
          items.set(m.item, { ...m.data, id: m.item, author: m.author, tag: m.tag, name: m.name, created: old ? old.created : m.time, updated: m.time });
        }
      } else if (m.k === 'del' && old && old.author === m.author) {
        old.deleted = true;
      } else if (m.k === 'report') {
        if (!reports.has(m.item)) reports.set(m.item, new Set());
        reports.get(m.item).add(m.author);
      }
    }
    const out = [];
    for (const it of items.values()) {
      if (it.deleted) continue;
      it.reports = reports.has(it.id) ? reports.get(it.id).size : 0;
      out.push(it);
    }
    return out.sort((a, b) => b.created - a.created);
  }

  const isVisible = it => it.vis !== 'unlisted' && it.reports < REPORTS_TO_HIDE;
  const readItems = async feed => { const m = await read(feed); const items = foldItems(m); items.online = m.online; return items; };
  const saveItem = (feed, item, data) => post(feed, 'item', { item, data });
  const deleteItem = (feed, item) => post(feed, 'del', { item });
  const reportItem = (feed, item) => post(feed, 'report', { item });

  // Commenti, voti e visualizzazioni: un voto e una visualizzazione per persona.
  function foldActivity(messages) {
    const comments = new Map(), votes = new Map(), views = new Set(), commentLikes = new Map();
    for (const m of messages) {
      if (m.k === 'comment' && typeof m.text === 'string') comments.set(m.id, { id: m.id, text: m.text.slice(0, 2000), name: m.name, tag: m.tag, author: m.author, time: m.time, reply: typeof m.reply === 'string' ? m.reply : null });
      else if (m.k === 'clike' && typeof m.ref === 'string') {
        if (!commentLikes.has(m.ref)) commentLikes.set(m.ref, new Map());
        commentLikes.get(m.ref).set(m.author, m.on === true);
      }
      else if (m.k === 'comment-del' && comments.has(m.ref) && comments.get(m.ref).author === m.author) comments.delete(m.ref);
      else if (m.k === 'vote') votes.set(m.author, m.value);
      else if (m.k === 'view') views.add(m.author);
    }
    const values = [...votes.values()];
    for (const c of comments.values()) {
      const likes = commentLikes.get(c.id) || new Map();
      c.likedBy = new Set([...likes].filter(([, on]) => on).map(([who]) => who));
    }
    return {
      comments: [...comments.values()].sort((a, b) => b.time - a.time),
      likes: values.filter(v => v === 1).length,
      dislikes: values.filter(v => v === -1).length,
      votes, views: views.size,
    };
  }

  // ── siti su Freenet ────────────────────────────────────────────────────────
  const blobToB64 = blob => new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(',')[1] || '');
    r.onerror = () => rej(new Error('Lettura del file non riuscita'));
    r.readAsDataURL(blob);
  });

  async function writeBlob(site, file, blob, onBytes) {
    if (blob.size === 0) { await call('siteWrite', { site, file, b64: '', append: false }); return; }
    for (let at = 0; at < blob.size; at += CHUNK_BYTES) {
      const b64 = await blobToB64(blob.slice(at, at + CHUNK_BYTES));
      await call('siteWrite', { site, file, b64, append: at > 0 });
      if (onBytes) onBytes(Math.min(blob.size, at + CHUNK_BYTES));
    }
  }

  const writeText = (site, file, text) => writeBlob(site, file, new Blob([text]));
  const publishSite = async site => (await call('sitePublish', { site })).key;
  const siteUrl = (key, file) => `${NODE}/v1/contract/web/${key}/${file || ''}`;

  const STUB_PAGE = '<!doctype html><meta charset="utf-8"><title>Web 7.0</title><p>Contenuto di Web 7.0.</p>';

  const CHANNEL_PAGE = `<!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Canale Web 7.0</title>
<style>body{margin:0;background:#14131c;color:#f3efe6;font:16px system-ui,sans-serif;padding:20px}main{max-width:760px;margin:auto}h1{margin:0 0 4px}p{color:#b5afa3}article{border-top:1px solid #322f3e;padding:14px 0}small{color:#b5afa3}</style></head>
<body><main><h1 id="n">Canale</h1><p id="b"></p><div id="l"></div></main><script>
fetch('feed.json').then(r=>r.json()).then(f=>{document.getElementById('n').textContent=f.name||'Canale';document.title=f.name||'Canale';document.getElementById('b').textContent=f.bio||'';
var l=document.getElementById('l');(f.items||[]).forEach(function(i){var a=document.createElement('article'),h=document.createElement('h3'),p=document.createElement('p'),s=document.createElement('small');h.textContent=i.title||i.kind;h.style.margin='0';p.textContent=i.desc||i.text||'';s.textContent=(i.cat?i.cat+' · ':'')+new Date(i.created).toLocaleDateString('it-IT');a.append(h,p,s);l.append(a)})});
<\/script></body></html>`;

  // Copia permanente: le bacheche tengono solo gli ultimi 1000 messaggi, il canale tiene tutto.
  async function updateChannel(change) {
    const site = 'w70-canale';
    let feed = { items: [] };
    try { const t = (await call('siteRead', { site, file: 'feed.json' })).text; if (t) feed = JSON.parse(t); } catch {}
    const p = profile();
    feed.name = await displayName();
    feed.bio = p.bio;
    feed.items = change ? change(feed.items || []) : feed.items || [];
    await writeText(site, 'index.html', CHANNEL_PAGE);
    await writeText(site, 'feed.json', JSON.stringify(feed));
    const key = await publishSite(site);
    store.set('channel_key', key);
    return key;
  }

  async function saveProfile(p) {
    setProfileLocal(p);
    const id = await identity();
    let key = store.get('channel_key', null);
    try { key = await updateChannel(); } catch {}
    return saveItem('feed:channels', id.tag.replace(/[^A-Za-z0-9_-]/g, ''), { bio: String(p.bio || '').slice(0, 500), avatar: safeImage(p.avatar), key });
  }

  async function ensureChannelListed() {
    if (store.get('channel_listed')) return;
    await saveProfile(profile());
    store.set('channel_listed', true);
  }

  async function publishMedia(kind, feed, item, data, permanent) {
    await saveItem(feed, item, data);
    // L'elemento è già in rete. Se il canale non si pubblica ora, la modifica resta salvata e parte con la prossima.
    try {
      await ensureChannelListed();
      await updateChannel(items => {
        const clean = items.filter(i => i.id !== item);
        clean.unshift({ id: item, kind, created: Date.now(), ...permanent });
        return clean;
      });
    } catch {}
  }

  async function removeFromChannel(item) {
    await updateChannel(items => items.filter(i => i.id !== item));
  }

  // Il video è diviso in più contratti perché uno solo arriva al massimo a 50 MiB.
  async function uploadVideoParts(file, id, onProgress) {
    const count = Math.ceil(file.size / PART_BYTES) || 1;
    if (count > MAX_PARTS) throw new Error(`Video troppo grande: massimo ${Math.floor(MAX_PARTS * PART_BYTES / 1048576)} MB`);
    const parts = [];
    for (let n = 0; n < count; n++) {
      const site = `w70v-${id.slice(1, 22).toLowerCase().replace(/[^a-z0-9]/g, 'x')}-${n}`;
      const piece = file.slice(n * PART_BYTES, (n + 1) * PART_BYTES);
      await writeText(site, 'index.html', STUB_PAGE);
      await writeBlob(site, 'data.bin', piece, bytes => onProgress && onProgress('Preparo', (n + bytes / piece.size) / count));
      const started = Date.now();
      const say = () => onProgress && onProgress(`Pubblico in rete: parte ${n + 1} di ${count} (${Math.round((Date.now() - started) / 1000)} s)`, n / count);
      say();
      const ticker = setInterval(say, 1000);
      try {
        parts.push({ key: await publishSite(site), site, sha: await hashBlob(piece) });
      } finally {
        clearInterval(ticker);
      }
      await call('siteDelete', { site });
    }
    return parts;
  }

  async function wipeVideoParts(parts) {
    for (const part of parts) {
      if (!part.site) continue;
      await writeText(part.site, 'index.html', STUB_PAGE);
      await publishSite(part.site);
      await call('siteDelete', { site: part.site });
    }
  }

  // Il primo recupero dalla rete può fallire finché il nodo non trova il contratto.
  // Con expectedSha il contenuto deve essere esattamente quello annunciato nel messaggio firmato.
  async function fetchPart(url, onBytes, expectedSha) {
    let lastError = null;
    for (let attempt = 0; attempt < 4; attempt++) {
      let blob;
      try {
        const r = await fetch(url);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const reader = r.body.getReader();
        const chunks = [];
        let got = 0;
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          chunks.push(value);
          got += value.length;
          if (onBytes) onBytes(got);
        }
        blob = new Blob(chunks);
      } catch (e) {
        lastError = e;
        await new Promise(res => setTimeout(res, 3000 * (attempt + 1)));
        continue;
      }
      if (expectedSha !== undefined && (await hashBlob(blob)) !== expectedSha) {
        throw new Error('Contenuto alterato: non corrisponde a quello pubblicato');
      }
      return blob;
    }
    throw new Error('Contenuto non trovato sulla rete: ' + lastError.message);
  }

  async function downloadVideo(item, onProgress) {
    const parts = (item.parts || []).filter(p => p && SAFE_KEY.test(p.key) && typeof p.sha === 'string');
    if (!parts.length || parts.length !== (item.parts || []).length) throw new Error('Video senza impronta: non verificabile');
    // Tre parti alla volta da peer diversi: molto più veloce che una dopo l'altra.
    const blobs = new Array(parts.length);
    const got = new Array(parts.length).fill(0);
    const report = () => onProgress && onProgress(got.reduce((a, b) => a + b, 0), item.size);
    let next = 0;
    const worker = async () => {
      while (next < parts.length) {
        const n = next++;
        blobs[n] = await fetchPart(siteUrl(parts[n].key, 'data.bin'), b => { got[n] = b; report(); }, parts[n].sha);
        got[n] = blobs[n].size;
        report();
      }
    };
    await Promise.all(Array.from({ length: Math.min(3, parts.length) }, worker));
    return new Blob(blobs, { type: /^video\/[a-z0-9.+-]+$/i.test(item.mime || '') ? item.mime : 'video/mp4' });
  }

  // Immagini in pagina: <img data-src="..." data-sha="..."> si mostrano solo se l'impronta coincide.
  async function loadVerifiedImages(root) {
    for (const img of (root || document).querySelectorAll('img[data-src]')) {
      const url = img.dataset.src;
      img.removeAttribute('data-src');
      try {
        const blob = await fetchPart(url, null, img.dataset.sha || '');
        img.src = URL.createObjectURL(new Blob([blob], { type: 'image/jpeg' }));
      } catch (e) {
        img.alt = e.message;
        img.classList.add('unverified');
        const note = document.createElement('div');
        note.className = 'bad-note';
        note.textContent = '⚠️ ' + e.message;
        img.after(note);
      }
    }
  }

  function imageToJpeg(file, maxSide, quality) {
    return new Promise((res, rej) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
        const c = document.createElement('canvas');
        c.width = Math.round(img.width * scale);
        c.height = Math.round(img.height * scale);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        c.toBlob(b => (b ? res(b) : rej(new Error('Immagine non valida'))), 'image/jpeg', quality);
      };
      img.onerror = () => { URL.revokeObjectURL(url); rej(new Error('Immagine non valida')); };
      img.src = url;
    });
  }
  const blobToDataUrl = blob => new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result));
    r.onerror = rej;
    r.readAsDataURL(blob);
  });
  async function smallImage(file, side, quality) { return blobToDataUrl(await imageToJpeg(file, side || 96, quality || 0.6)); }

  // Miniatura e durata, lette dal video stesso prima di pubblicarlo.
  function videoFacts(file) {
    return new Promise(res => {
      const v = document.createElement('video');
      const url = URL.createObjectURL(file);
      let settled = false;
      const finish = (thumb, dur) => { if (settled) return; settled = true; URL.revokeObjectURL(url); res({ thumb, dur }); };
      v.muted = true; v.preload = 'auto'; v.playsInline = true;
      v.onloadedmetadata = () => { v.currentTime = Math.min(1, (v.duration || 0) / 2); };
      v.onseeked = () => {
        const c = document.createElement('canvas');
        c.width = 256;
        c.height = Math.round(256 * (v.videoHeight / (v.videoWidth || 1))) || 144;
        try {
          c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
          finish(c.toDataURL('image/jpeg', 0.4), Math.round(v.duration || 0));
        } catch { finish('', Math.round(v.duration || 0)); }
      };
      v.onerror = () => finish('', 0);
      setTimeout(() => finish('', 0), 8000);
      v.src = url;
    });
  }

  // Tema: scuro, chiaro o automatico (segue il telefono o il PC).
  const systemLight = root.matchMedia ? root.matchMedia('(prefers-color-scheme: light)') : null;
  function applyTheme() {
    const t = store.get('theme', 'auto');
    document.documentElement.dataset.theme = t === 'auto' ? (systemLight && systemLight.matches ? 'light' : 'dark') : t;
    document.documentElement.style.colorScheme = document.documentElement.dataset.theme;
  }
  function setTheme(t) { store.set('theme', ['dark', 'light', 'auto'].includes(t) ? t : 'auto'); applyTheme(); }
  if (systemLight && systemLight.addEventListener) systemLight.addEventListener('change', applyTheme);
  applyTheme();
  if (hasBridge()) setTimeout(flushOutbox, 1500);

  root.FNet = {
    INSIDE_FREENET, IN_APP, logout, lastReadError: () => lastReadError,
    NODE, PART_BYTES, MAX_PARTS, CATEGORIES, SAFE_ID, SAFE_KEY,
    store, esc, safeImage, timeAgo, newId, hasBridge, applyTheme, setTheme,
    sendDM, readDM, dmInbox, rememberDM,
    identity, exportIdentity, importIdentity, profile, displayName, saveProfile,
    blocked, blockUser, unblockUser, filteredTotal: () => filteredCount,
    post, read, foldItems, foldActivity, readItems, isVisible, saveItem, deleteItem, reportItem, flushOutbox,
    call, writeBlob, writeText, publishSite, siteUrl, updateChannel, publishMedia, removeFromChannel,
    uploadVideoParts, wipeVideoParts, downloadVideo, videoUrl, videoFacts, hashBlob, loadVerifiedImages, fetchPart, imageToJpeg, smallImage, blobToDataUrl,
  };
})(window);
