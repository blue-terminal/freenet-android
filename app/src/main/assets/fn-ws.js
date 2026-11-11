// Web 7.0 dentro Freenet: parla col nodo attraverso il suo WebSocket, con i messaggi
// costruiti dal modulo web70_client.wasm (gli stessi tipi Rust che usa il nodo).
(function (root) {
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const TIMEOUT_MS = 90000;
  let wasm = null;
  let socket = null;
  let queue = Promise.resolve();
  let waiting = null;
  const ids = new Map();
  let hubCode = null;

  async function loadWasm() {
    if (wasm) return wasm;
    const bytes = await (await fetch('web70_client.wasm')).arrayBuffer();
    wasm = (await WebAssembly.instantiate(bytes, {})).instance.exports;
    return wasm;
  }

  // Ogni funzione del modulo riceve byte e restituisce [lunghezza u32][byte].
  function invoke(name, ...args) {
    const reserved = [];
    const flat = [];
    for (const a of args) {
      if (typeof a === 'number') { flat.push(a); continue; }
      const bytes = typeof a === 'string' ? enc.encode(a) : a;
      const p = wasm.reserve(bytes.length);
      new Uint8Array(wasm.memory.buffer, p, bytes.length).set(bytes);
      reserved.push([p, Math.max(bytes.length, 1)]);
      flat.push(p, bytes.length);
    }
    const out = wasm[name](...flat);
    const len = new DataView(wasm.memory.buffer).getUint32(out, true);
    const result = new Uint8Array(wasm.memory.buffer, out + 4, len).slice();
    wasm.release(out, len + 4);
    for (const [p, c] of reserved) wasm.release(p, c);
    return result;
  }

  function asError(bytes) {
    if (bytes[0] !== 123) return null; // '{': i messaggi bincode non iniziano così
    try { const j = JSON.parse(dec.decode(bytes)); return j.type === 'error' ? new Error(j.message) : null; } catch { return null; }
  }

  function connect() {
    if (socket && socket.readyState <= 1) return socket;
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    socket = new WebSocket(`${scheme}://${location.host}/v1/contract/command?encodingProtocol=native`);
    socket.binaryType = 'arraybuffer';
    socket.onmessage = async e => {
      const data = e.data instanceof ArrayBuffer ? new Uint8Array(e.data) : new Uint8Array(await e.data.arrayBuffer());
      const reply = JSON.parse(dec.decode(invoke('decode', data)));
      // Le notifiche delle iscrizioni non sono risposte a una richiesta.
      if (reply.type === 'pending' || reply.type === 'notification') return;
      if (waiting) { const w = waiting; waiting = null; w(reply); }
    };
    socket.onclose = () => { if (waiting) { const w = waiting; waiting = null; w({ type: 'error', message: 'Connessione al nodo chiusa' }); } };
    return socket;
  }

  // Il nodo risponde in ordine: una richiesta alla volta per connessione.
  function request(bytes) {
    const run = queue.then(() => new Promise((resolve, reject) => {
      const err = asError(bytes);
      if (err) { reject(err); return; }
      const ws = connect();
      const timer = setTimeout(() => { waiting = null; reject(new Error('Il nodo non risponde')); }, TIMEOUT_MS);
      waiting = reply => { clearTimeout(timer); reply.type === 'error' ? reject(new Error(reply.message)) : resolve(reply); };
      const send = () => ws.send(bytes);
      if (ws.readyState === 1) send();
      else ws.addEventListener('open', send, { once: true });
    }));
    queue = run.catch(() => {});
    return run;
  }

  async function code() {
    if (!hubCode) hubCode = new Uint8Array(await (await fetch('web70_hub.contract')).arrayBuffer());
    return hubCode;
  }

  async function topicId(topic) {
    if (ids.has(topic)) return ids.get(topic);
    await loadWasm();
    const out = invoke('contract_id', await code(), topic);
    const err = asError(out);
    if (err) throw err;
    const id = dec.decode(out);
    ids.set(topic, id);
    return id;
  }

  async function hubGet(topic) {
    await loadWasm();
    const id = await topicId(topic);
    const reply = await request(invoke('encode_get', id, 0)).catch(e => {
      // Bacheca mai creata: per chi legge è vuota, non un guasto.
      if (/missing contract|not found|no contract|GET exhausted/i.test(e.message)) return { type: 'notfound' };
      throw e;
    });
    return reply.type === 'get' ? reply.state : '{"m":[]}';
  }

  // Il primo messaggio di un argomento crea il suo contratto; un PUT su uno esistente si unisce.
  async function hubPost(topic, message) {
    await loadWasm();
    const id = await topicId(topic);
    const delta = enc.encode('{"m":[' + message + ']}');
    try {
      await request(invoke('encode_update', id, delta));
    } catch {
      await request(invoke('encode_put', await code(), topic, delta));
    }
  }

  root.FreenetWS = { hubGet, hubPost, topicId };
})(window);
