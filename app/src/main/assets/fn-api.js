/**
 * fn-api.js — Freenet Web 7.0
 * Basato sul codice sorgente reale: github.com/freenet/freenet-core
 *
 * Compatibilità:
 *   - Nuovo Freenet (freenet-core, Rust) → http://127.0.0.1:7509
 *       GET /v1/version                           → {"version":"..."}
 *       GET /v1/contract/web/{key}/               → serve webapp
 *       WS  /v1/contract/command?encodingProtocol=native → ContractRequest
 *   - Vecchio Freenet (Fred/Hyphanet, Java) → http://127.0.0.1:8888
 *       /USK@<key>/0/   /CHK@<key>/   /SSK@<key>/
 *
 * Shell postMessage (da navigation_interceptor.js di freenet-core):
 *   { __freenet_shell__: true, type: 'navigate', href: '...' }
 */

'use strict';

const FN = (() => {

  // ── Configurazione nodi ───────────────────────────────────────────────────
  // Legge IP nodo remoto salvato (es. PC Linux sulla stessa LAN)
  function _loadRemoteNode() {
    try {
      const ip = localStorage.getItem('fn_remote_node');
      if (ip) return { name: 'Nodo remoto (' + ip + ')', base: 'http://' + ip + ':7509', ws: 'ws://' + ip + ':7509', type: 'new', apiVersion: 'v1' };
    } catch {}
    return null;
  }

  const NODES_BASE = [
    { name: 'Freenet (freenet-core, Rust)', base: 'http://127.0.0.1:7509', ws: 'ws://127.0.0.1:7509', type: 'new', apiVersion: 'v1' },
    { name: 'Fred/Hyphanet (Java)',         base: 'http://127.0.0.1:8888', ws: null,                  type: 'old', apiVersion: null },
    { name: 'Fred/Hyphanet alt',            base: 'http://127.0.0.1:8080', ws: null,                  type: 'old', apiVersion: null },
  ];

  function getNodes() {
    const remote = _loadRemoteNode();
    return remote ? [remote, ...NODES_BASE] : NODES_BASE;
  }

  const NODES = NODES_BASE; // retrocompatibilità

  let activeNode  = null;
  let nodeVersion = null;

  // ── Chiavi contratto reali (da freenet-core/home.html) ───────────────────
  // null = app su GitHub ma chiave non ancora pubblicata in rete
  const FN_APPS = {
    atlas:      '771DvtPMwt2PumPyrFvsz7fpvU1gogcmb5qtS1yYEEH9',
    river:      'raAqMhMG7KUpXBU2SxgCQ3Vh4PYjttxdSWd9ftV7RLv',
    mail:       '122f6AR7PyF8d8mhczuNQM4xrLtBf5t8g2iB7PEVT7KC',
    delta:      'EqJ5YpEEV3XLqEvKWLQHFhGAac2qXzSUoE6k2zbdnXBr',
    ghostkey:   'DLog47hEsrtuGT4N5XCeMBG45m4n1aWM89tBZXue2E1N',
    freenetorg: 'E4m5WbaC4cdbpDjL82WfYUnrM9iMnfaaN8Tsn7UHPMjZ',
    wiki:       null,
    raven:      null,
    blog:       null,
    git:        null,
    portal:     null,
    telemetry:  null,
    gitea:      null, // Gitea locale — porta 3000 — non è un contratto Freenet
  };

  // ── Servizi locali (non Freenet — girano sul PC/server locale) ────────────
  const LOCAL_SERVICES = {
    gitea: { label:'Gitea', desc:'Git locale', icon:'🐙', port:3000, path:'/' },
  };

  const FN_APP_INFO = {
    atlas:      { label:'Atlas',            desc:'Discovery layer decentralizzato',         icon:'🗺️', gh:'freenet/atlas',                       active:true  },
    river:      { label:'River Chat',       desc:'Chat di gruppo senza server',             icon:'💬', gh:'freenet/river',                       active:true  },
    mail:       { label:'Freenet Mail',     desc:'Email decentralizzata',                   icon:'✉️', gh:'freenet/mail',                        active:true  },
    delta:      { label:'Delta',            desc:'Costruttore siti decentralizzato',        icon:'🌐', gh:'freenet/delta',                       active:true  },
    ghostkey:   { label:'Ghostkey Vault',   desc:'Gestione identità Ghostkey',              icon:'🔑', gh:'freenet/ghostkeys',                   active:true  },
    freenetorg: { label:'freenet.org',      desc:'Specchio di freenet.org sulla rete',      icon:'🏠', gh:'freenet/web',                         active:true  },
    wiki:       { label:'Freenet Wiki',     desc:'Wiki decentralizzato',                    icon:'📖', gh:'freenet/freenet-wiki',                active:false },
    raven:      { label:'Raven',            desc:'Messaggistica su Freenet',                icon:'🐦', gh:'freenet/raven',                       active:false },
    blog:       { label:'Blog ufficiale',   desc:'Blog Freenet pubblicato sulla rete',      icon:'📝', gh:'freenet/blog',                        active:false },
    git:        { label:'Freenet Git',      desc:'Hosting git decentralizzato',             icon:'🔀', gh:'freenet/freenet-git',                 active:false },
    portal:     { label:'Portal',           desc:'Portale di ingresso alla rete',           icon:'🚀', gh:'freenet/portal',                      active:false },
    telemetry:  { label:'Telemetry',        desc:'Dashboard telemetria rete in tempo reale',icon:'📡', gh:'freenet/freenet-telemetry-dashboard', active:false },
  };

  // ── Costruzione URL per tipo nodo ─────────────────────────────────────────
  function contractUrl(node, key) {
    if (!key) return null;
    if (node.type === 'new') return node.base + '/v1/contract/web/' + key + '/';
    return node.base + '/USK@' + key + ',AAAA,AQACAAE,AAACAAA/0/';
  }

  // ── Rilevamento nodo — tutti in parallelo, vince il più veloce ──────────
  async function detectNode() {
    const nodes = getNodes();
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 1500);

    async function tryNode(node) {
      try {
        const r = await fetch(node.base + '/v1/version', { signal: ctrl.signal });
        if (r.ok) {
          const j = await r.json().catch(() => ({}));
          return { node, version: j.version || null };
        }
      } catch {
        // CORS bloccato (file:// origin): prova no-cors
        try {
          await fetch(node.base + '/v1/version', { signal: ctrl.signal, mode: 'no-cors' });
          return { node, version: null };
        } catch { /* non raggiungibile */ }
      }
      throw new Error('unreachable');
    }

    // Polyfill Promise.any compatibile Android 8+
    function anyOf(promises) {
      return new Promise((resolve, reject) => {
        let left = promises.length;
        if (!left) { reject(new Error('no nodes')); return; }
        promises.forEach(p => p.then(resolve).catch(() => { if (!--left) reject(); }));
      });
    }

    try {
      const { node, version } = await anyOf(nodes.map(tryNode));
      clearTimeout(timer);
      ctrl.abort();
      activeNode = node;
      nodeVersion = version;
      return node;
    } catch {
      clearTimeout(timer);
      activeNode = null;
      nodeVersion = null;
      return null;
    }
  }

  function getActiveNode()  { return activeNode; }
  function getNodeVersion() { return nodeVersion; }

  // ── WebSocket per ContractRequest (solo nuovo Freenet) ───────────────────
  // Endpoint reale da freenet-core/crates/fdev/src/commands/v1.rs:
  //   ws://127.0.0.1:7509/v1/contract/command?encodingProtocol=native
  function openContractWs(onmessage, onerror) {
    const node = activeNode;
    if (!node || node.type !== 'new') return null;
    const ws = new WebSocket(node.ws + '/v1/contract/command?encodingProtocol=native');
    ws.binaryType = 'arraybuffer';
    ws.onmessage = onmessage || null;
    ws.onerror   = onerror   || null;
    return ws;
  }

  // ── Risoluzione URL freenet:// ────────────────────────────────────────────
  function resolveUrl(fnUrl) {
    const node = activeNode || NODES[0];
    if (!fnUrl || fnUrl === 'freenet://home') return null;
    if (fnUrl === 'freenet://video') return 'video.html';

    const ROUTE_MAP = {
      'freenet://atlas':      FN_APPS.atlas,
      'freenet://river':      FN_APPS.river,
      'freenet://mail':       FN_APPS.mail,
      'freenet://editor':     FN_APPS.delta,
      'freenet://delta':      FN_APPS.delta,
      'freenet://ghostkey':   FN_APPS.ghostkey,
      'freenet://freenetorg': FN_APPS.freenetorg,
      'freenet://impostazioni': null,
    };

    if (fnUrl in ROUTE_MAP) {
      const key = ROUTE_MAP[fnUrl];
      if (!key) return node.base + '/';
      return contractUrl(node, key);
    }

    if (fnUrl.startsWith('http')) return fnUrl;
    if (/^(USK|CHK|SSK)@/.test(fnUrl)) return node.base + '/' + fnUrl;
    return node.base + '/v1/contract/web/' + fnUrl.replace('freenet://', '') + '/';
  }

  // ── Archivio siti visitati ────────────────────────────────────────────────
  function getSiteArchive()  { try { return JSON.parse(localStorage.getItem('fn_site_archive')||'[]'); } catch { return []; } }
  function saveSiteArchive(a){ try { localStorage.setItem('fn_site_archive', JSON.stringify(a.slice(-500))); } catch {} }
  function recordVisit(url, title) {
    if (!url || url === 'freenet://home') return;
    const a = getSiteArchive();
    if (a.length && a[a.length-1].url === url) return;
    a.push({ url, title: title||url, time: new Date().toISOString() });
    saveSiteArchive(a);
  }

  // ── Archivio video caricati ───────────────────────────────────────────────
  function getVideoArchive()  { try { return JSON.parse(localStorage.getItem('fn_my_videos')||'[]'); } catch { return []; } }
  function saveVideoArchive(v){ try { localStorage.setItem('fn_my_videos', JSON.stringify(v)); } catch {} }
  function addVideo(video)    { const v=getVideoArchive(); v.push(video); saveVideoArchive(v); return v; }

  // ── Dati video (like / commenti) ──────────────────────────────────────────
  function getVD(id) {
    try {
      const d = JSON.parse(localStorage.getItem('fn_vd')||'{}');
      if (!d[id]) d[id] = { likes:0, liked:false, disliked:false, cmts:[] };
      return d[id];
    } catch { return { likes:0, liked:false, disliked:false, cmts:[] }; }
  }
  function saveVD(id, vd) {
    try { const d=JSON.parse(localStorage.getItem('fn_vd')||'{}'); d[id]=vd; localStorage.setItem('fn_vd',JSON.stringify(d)); } catch {}
  }

  // ── Account locali ────────────────────────────────────────────────────────
  function getAccounts()    { try { return JSON.parse(localStorage.getItem('fn_acc')||'{}'); } catch { return {}; } }
  function saveAccounts(a)  { try { localStorage.setItem('fn_acc', JSON.stringify(a)); } catch {} }
  function getCurrentUser() { try { return localStorage.getItem('fn_user')||null; } catch { return null; } }
  function setCurrentUser(u){ try { localStorage.setItem('fn_user', u||''); } catch {} }

  function login(name, pass) {
    const acc = getAccounts();
    if (!name || !acc[name]) return 'not_found';
    if (acc[name] !== btoa(pass)) return 'wrong_pass';
    setCurrentUser(name);
    return 'ok';
  }
  function register(name, pass, pass2) {
    if (!name) return 'name_empty';
    const acc = getAccounts();
    if (acc[name]) return 'name_taken';
    if (pass.length < 6) return 'pass_short';
    if (pass !== pass2)  return 'pass_mismatch';
    acc[name] = btoa(pass);
    saveAccounts(acc);
    setCurrentUser(name);
    return 'ok';
  }
  function logout() { setCurrentUser(null); }

  // ── Chiave contratto preview (per pubblicare il proprio FreenetTV) ────────
  function makeContractPreview(username) {
    const seed = [...username].reduce((a,c)=>a^c.charCodeAt(0),0x1337);
    const pseudo = btoa(username+seed+'fresnettv').replace(/[=+/]/g,'').substring(0,44).padEnd(44,'A');
    const node = activeNode || NODES[0];
    return node.base + '/v1/contract/web/' + pseudo + '/';
  }

  // ── Guida pubblicazione (fdev website init/publish) ──────────────────────
  function publishGuide(username) {
    const name = username.toLowerCase().replace(/[^a-z0-9-]/g,'-');
    return [
      '# Pubblica il tuo FreenetTV su Freenet',
      '',
      '# 1. Genera la chiave di firma (ed25519)',
      'fdev website init ' + name + '-tv',
      '',
      '# 2. Copia i file',
      'mkdir ~/my-fresnettv',
      'cp video.html ~/my-fresnettv/index.html',
      'cp fn-theme.css fn-api.js ~/my-fresnettv/',
      '',
      '# 3. Pubblica sulla rete Freenet',
      'fdev website publish ~/my-fresnettv --key ' + name + '-tv',
      '',
      '# → Freenet restituisce la chiave contratto.',
      '# → Tutti i nodi accedono a:',
      '# → http://127.0.0.1:7509/v1/contract/web/<chiave>/',
    ].join('\n');
  }

  return {
    NODES, FN_APPS, FN_APP_INFO,
    detectNode, getActiveNode, getNodeVersion,
    contractUrl, resolveUrl,
    openContractWs,
    getSiteArchive, recordVisit,
    getVideoArchive, saveVideoArchive, addVideo,
    getVD, saveVD,
    getAccounts, getCurrentUser, setCurrentUser,
    login, register, logout,
    makeContractPreview, publishGuide,
  };
})();
