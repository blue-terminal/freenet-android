// Crea messaggi firmati con il vero fn-net.js (WebCrypto), per provare che il contratto li accetta.
// Uso: node tests/make_js_vectors.js <percorso di fn-net.js> <identità> <messaggi per identità> > out.json
const fs = require('fs');
const [, , fnNetPath, identities = '5', perIdentity = '8'] = process.argv;

const memory = new Map();
globalThis.window = globalThis;
globalThis.localStorage = {
  getItem: k => (memory.has(k) ? memory.get(k) : null),
  setItem: (k, v) => memory.set(k, String(v)),
  removeItem: k => memory.delete(k),
};
globalThis.document = { documentElement: { dataset: {} } };
const captured = [];
globalThis.Android = {
  net(token, method, args, id) {
    if (method === 'hubPost') captured.push(JSON.parse(JSON.parse(args).message));
    setTimeout(() => window.__fnReply(id, JSON.stringify({ ok: true })), 0);
  },
};
globalThis.__fnToken = 't';
eval(fs.readFileSync(fnNetPath, 'utf8'));

const samples = ['ciao', 'caffè ☕ e "virgolette"', 'riga\nnuova', '<b>non html</b>', 'emoji 🎓📚', 'barra \\ rovescia'];
(async () => {
  for (let who = 0; who < Number(identities); who++) {
    localStorage.removeItem('fn7_identity');
    FNet.store.set('profile', { name: `Prova ${who} àè`, bio: '', avatar: '' });
    await FNet.importIdentity(JSON.stringify(await (async () => {
      const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
      const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
      return { jwk, pub: jwk.x + '.' + jwk.y };
    })()));
    for (let n = 0; n < Number(perIdentity); n++) {
      await FNet.post('test:vettori', 'comment', { text: samples[n % samples.length] + ' ' + n });
    }
  }
  process.stdout.write(JSON.stringify({ m: captured }));
})();
