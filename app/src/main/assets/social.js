// Parti comuni di Gram e Posts: avatar, messaggi a comparsa, commenti e voti su bacheca.
(function (root) {
  const { esc, timeAgo } = FNet;

  function toast(text) {
    const t = document.createElement('div');
    t.className = 'toast';
    t.textContent = text;
    document.body.append(t);
    setTimeout(() => t.remove(), 3500);
  }

  function avatar(name, image, big) {
    const safe = FNet.safeImage(image);
    const cls = 'avatar' + (big ? ' big' : '');
    return safe ? `<img class="${cls}" src="${safe}" alt="">` : `<span class="${cls}">${esc(String(name || '?')[0].toUpperCase())}</span>`;
  }

  function offlineNotice(online) {
    if (online) return '';
    return FNet.hasBridge()
      ? `<div class="notice">Il nodo non risponde: stai vedendo gli ultimi dati salvati.${FNet.lastReadError() ? ' Motivo: ' + esc(FNet.lastReadError()) : ''}</div>`
      : '<div class="notice">Da questo browser non si può accedere alla rete condivisa: apri la pagina nell\'app Freenet.</div>';
  }

  // Commenti di un elemento, disegnati dentro il contenitore indicato.
  async function drawComments(box, itemId) {
    const me = await FNet.identity();
    const a = FNet.foldActivity(await FNet.read('c:' + itemId));
    box.innerHTML = `<form class="row" style="margin:10px 0" data-comment="${esc(itemId)}">
        <input class="grow" name="text" maxlength="2000" required placeholder="Scrivi un commento" style="padding:9px 12px;border-radius:10px;background:var(--panel2);border:1px solid var(--line);min-width:0">
        <button class="btn main">Invia</button></form>`
      + a.comments.map(c => `<div class="row" style="align-items:flex-start;padding:7px 0">${avatar(c.name)}<div class="grow"><div class="sub"><b>${esc(c.name)}</b> · ${esc(timeAgo(c.time))}
        ${c.author === me.pub ? `· <button class="sub" data-del-comment="${esc(c.id)}" data-item="${esc(itemId)}">Elimina</button>` : ''}</div><div style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(c.text)}</div></div></div>`).join('');
    return a;
  }

  document.addEventListener('submit', async e => {
    const id = e.target.dataset && e.target.dataset.comment;
    if (!id) return;
    e.preventDefault();
    const text = e.target.text.value.trim();
    if (!text) return;
    e.target.text.value = '';
    const r = await FNet.post('c:' + id, 'comment', { text });
    if (r.queued) toast('Salvato: partirà quando il nodo risponde');
    drawComments(e.target.parentElement, id);
  });
  document.addEventListener('click', async e => {
    const ref = e.target.dataset && e.target.dataset.delComment;
    if (!ref) return;
    const box = e.target.closest('[data-comments]');
    await FNet.post('c:' + e.target.dataset.item, 'comment-del', { ref });
    if (box) drawComments(box, e.target.dataset.item);
  });

  async function toggleVote(itemId) {
    const me = await FNet.identity();
    const a = FNet.foldActivity(await FNet.read('c:' + itemId));
    const liked = a.votes.get(me.pub) === 1;
    await FNet.post('c:' + itemId, 'vote', { value: liked ? 0 : 1 });
    return { liked: !liked, likes: a.likes + (liked ? -1 : 1) };
  }

  // Foto nel sito indicato, con impronta; un video in contratti suoi come su FreenetTV.
  async function uploadMedia(files, id, site, say, maxImages) {
    // Foto e un video possono stare nello stesso post.
    const video = files.find(f => f.type.startsWith('video/'));
    let clip = null;
    if (video) {
      say('Analizzo il video');
      const facts = await FNet.videoFacts(video);
      const parts = await FNet.uploadVideoParts(video, id, text => say(text));
      clip = { type: 'video', parts, mime: video.type, size: video.size, thumb: facts.thumb, dur: facts.dur };
    }
    const images = files.filter(f => f.type.startsWith('image/')).slice(0, maxImages || 4);
    if (!images.length) return clip ? [clip] : [];
    const existing = await FNet.call('siteRead', { site, file: 'index.html' });
    if (!existing.text) await FNet.writeText(site, 'index.html', '<!doctype html><meta charset="utf-8"><title>Web 7.0</title><p>Immagini di Web 7.0.</p>');
    const written = [];
    for (let i = 0; i < images.length; i++) {
      say(`Preparo la foto ${i + 1} di ${images.length}`);
      const file = `${id}-${i}.jpg`;
      const jpeg = await FNet.imageToJpeg(images[i], 1600, 0.82);
      await FNet.writeBlob(site, file, jpeg);
      written.push({ file, sha: await FNet.hashBlob(jpeg) });
    }
    say('Pubblico in rete');
    const ch = await FNet.publishSite(site);
    const photos = written.map(w => ({ type: 'image', ch, ...w }));
    return clip ? [...photos, clip] : photos;
  }

  const imageUrl = m => (m && FNet.SAFE_KEY.test(m.ch || '') && /^[A-Za-z0-9_-]+\.jpg$/.test(m.file || '') ? FNet.siteUrl(m.ch, m.file) : '');

  root.Social = { toast, avatar, offlineNotice, drawComments, toggleVote, uploadMedia, imageUrl };
})(window);
