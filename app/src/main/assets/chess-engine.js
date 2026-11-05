// Regole degli scacchi e bot. Case: 0 = a8 ... 63 = h1.
(function (root) {
  const START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  const KNIGHT = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
  const DIAG = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
  const ORTHO = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  const KING = DIAG.concat(ORTHO);
  const VALUE = { p: 100, n: 320, b: 330, r: 500, q: 900, k: 0 };

  const isWhite = p => p >= 'A' && p <= 'Z';
  const colorOf = p => (p === '.' ? null : isWhite(p) ? 'w' : 'b');
  const fileOf = s => s % 8;
  const rankOf = s => s >> 3;
  const at = (f, r) => (f < 0 || f > 7 || r < 0 || r > 7 ? -1 : r * 8 + f);
  const sqName = s => 'abcdefgh'[fileOf(s)] + (8 - rankOf(s));
  const sqIndex = n => at(n.charCodeAt(0) - 97, 8 - Number(n[1]));

  function parseFen(fen) {
    const [rows, turn, castle, ep, half, full] = fen.trim().split(/\s+/);
    const b = [];
    for (const ch of rows.replace(/\//g, '')) {
      if (ch >= '1' && ch <= '8') for (let i = 0; i < Number(ch); i++) b.push('.');
      else b.push(ch);
    }
    return {
      b, turn: turn || 'w',
      castle: castle && castle !== '-' ? castle : '',
      ep: ep && ep !== '-' ? sqIndex(ep) : -1,
      half: Number(half) || 0, full: Number(full) || 1,
    };
  }

  function toFen(pos) {
    let rows = '';
    for (let r = 0; r < 8; r++) {
      let gap = 0;
      for (let f = 0; f < 8; f++) {
        const p = pos.b[r * 8 + f];
        if (p === '.') gap++;
        else { if (gap) rows += gap; gap = 0; rows += p; }
      }
      if (gap) rows += gap;
      if (r < 7) rows += '/';
    }
    return `${rows} ${pos.turn} ${pos.castle || '-'} ${pos.ep >= 0 ? sqName(pos.ep) : '-'} ${pos.half} ${pos.full}`;
  }

  function attacked(pos, sq, by) {
    const f = fileOf(sq), r = rankOf(sq), b = pos.b;
    const pawn = by === 'w' ? 'P' : 'p', dir = by === 'w' ? 1 : -1;
    for (const df of [-1, 1]) {
      const s = at(f + df, r + dir);
      if (s >= 0 && b[s] === pawn) return true;
    }
    const knight = by === 'w' ? 'N' : 'n', king = by === 'w' ? 'K' : 'k';
    for (const [df, dr] of KNIGHT) { const s = at(f + df, r + dr); if (s >= 0 && b[s] === knight) return true; }
    for (const [df, dr] of KING) { const s = at(f + df, r + dr); if (s >= 0 && b[s] === king) return true; }
    const rays = (dirs, a, c) => {
      for (const [df, dr] of dirs) {
        for (let i = 1; i < 8; i++) {
          const s = at(f + df * i, r + dr * i);
          if (s < 0) break;
          if (b[s] !== '.') { if (b[s] === a || b[s] === c) return true; break; }
        }
      }
      return false;
    };
    return by === 'w'
      ? rays(DIAG, 'B', 'Q') || rays(ORTHO, 'R', 'Q')
      : rays(DIAG, 'b', 'q') || rays(ORTHO, 'r', 'q');
  }

  const kingSquare = (pos, color) => pos.b.indexOf(color === 'w' ? 'K' : 'k');
  const inCheck = (pos, color) => attacked(pos, kingSquare(pos, color), color === 'w' ? 'b' : 'w');

  function pseudoMoves(pos) {
    const out = [], b = pos.b, me = pos.turn, foe = me === 'w' ? 'b' : 'w';
    for (let s = 0; s < 64; s++) {
      const p = b[s];
      if (p === '.' || colorOf(p) !== me) continue;
      const f = fileOf(s), r = rankOf(s), kind = p.toLowerCase();
      if (kind === 'p') {
        const dir = me === 'w' ? -1 : 1, last = me === 'w' ? 0 : 7, home = me === 'w' ? 6 : 1;
        const push = (to) => {
          if (rankOf(to) === last) for (const promo of 'qrbn') out.push({ from: s, to, promo });
          else out.push({ from: s, to });
        };
        const one = at(f, r + dir);
        if (one >= 0 && b[one] === '.') {
          push(one);
          const two = at(f, r + dir * 2);
          if (r === home && b[two] === '.') out.push({ from: s, to: two });
        }
        for (const df of [-1, 1]) {
          const t = at(f + df, r + dir);
          if (t < 0) continue;
          if (b[t] !== '.' && colorOf(b[t]) === foe) push(t);
          else if (t === pos.ep) out.push({ from: s, to: t, ep: true });
        }
      } else if (kind === 'n' || kind === 'k') {
        for (const [df, dr] of kind === 'n' ? KNIGHT : KING) {
          const t = at(f + df, r + dr);
          if (t >= 0 && colorOf(b[t]) !== me) out.push({ from: s, to: t });
        }
      } else {
        const dirs = kind === 'b' ? DIAG : kind === 'r' ? ORTHO : KING;
        for (const [df, dr] of dirs) {
          for (let i = 1; i < 8; i++) {
            const t = at(f + df * i, r + dr * i);
            if (t < 0) break;
            if (b[t] === '.') { out.push({ from: s, to: t }); continue; }
            if (colorOf(b[t]) === foe) out.push({ from: s, to: t });
            break;
          }
        }
      }
    }
    const rank = me === 'w' ? 7 : 0, kingAt = rank * 8 + 4, king = me === 'w' ? 'K' : 'k';
    if (b[kingAt] === king && !attacked(pos, kingAt, foe)) {
      const short = me === 'w' ? 'K' : 'k', long = me === 'w' ? 'Q' : 'q';
      if (pos.castle.includes(short) && b[kingAt + 1] === '.' && b[kingAt + 2] === '.'
        && !attacked(pos, kingAt + 1, foe) && !attacked(pos, kingAt + 2, foe)) {
        out.push({ from: kingAt, to: kingAt + 2, castle: true });
      }
      if (pos.castle.includes(long) && b[kingAt - 1] === '.' && b[kingAt - 2] === '.' && b[kingAt - 3] === '.'
        && !attacked(pos, kingAt - 1, foe) && !attacked(pos, kingAt - 2, foe)) {
        out.push({ from: kingAt, to: kingAt - 2, castle: true });
      }
    }
    return out;
  }

  const CASTLE_LOSS = { 0: 'q', 4: 'kq', 7: 'k', 56: 'Q', 60: 'KQ', 63: 'K' };

  function makeMove(pos, m) {
    const b = pos.b.slice();
    const p = b[m.from], me = pos.turn;
    const capture = b[m.to] !== '.' || m.ep;
    b[m.to] = m.promo ? (me === 'w' ? m.promo.toUpperCase() : m.promo) : p;
    b[m.from] = '.';
    if (m.ep) b[m.to + (me === 'w' ? 8 : -8)] = '.';
    if (m.castle) {
      const shortSide = m.to > m.from;
      const rookFrom = shortSide ? m.to + 1 : m.to - 2, rookTo = shortSide ? m.to - 1 : m.to + 1;
      b[rookTo] = b[rookFrom];
      b[rookFrom] = '.';
    }
    let castle = pos.castle;
    for (const s of [m.from, m.to]) {
      for (const c of CASTLE_LOSS[s] || '') castle = castle.replace(c, '');
    }
    const isPawn = p === 'P' || p === 'p';
    return {
      b, turn: me === 'w' ? 'b' : 'w', castle,
      ep: isPawn && Math.abs(m.to - m.from) === 16 ? (m.to + m.from) / 2 : -1,
      half: isPawn || capture ? 0 : pos.half + 1,
      full: pos.full + (me === 'b' ? 1 : 0),
    };
  }

  function legalMoves(pos) {
    return pseudoMoves(pos).filter(m => !inCheck(makeMove(pos, m), pos.turn));
  }

  function perft(pos, depth) {
    if (depth === 0) return 1;
    let n = 0;
    for (const m of legalMoves(pos)) n += perft(makeMove(pos, m), depth - 1);
    return n;
  }

  const toUci = m => sqName(m.from) + sqName(m.to) + (m.promo || '');
  const findUci = (pos, uci) => legalMoves(pos).find(m => toUci(m) === uci) || null;

  function insufficient(pos) {
    const rest = pos.b.filter(p => p !== '.' && p !== 'K' && p !== 'k');
    return rest.length === 0 || (rest.length === 1 && 'nNbB'.includes(rest[0]));
  }

  const repetitionKey = fen => fen.split(' ').slice(0, 4).join(' ');

  // history: posizioni FEN già giocate, serve per la tripla ripetizione
  function status(pos, history) {
    if (!legalMoves(pos).length) {
      return inCheck(pos, pos.turn)
        ? { over: true, reason: 'scacco matto', winner: pos.turn === 'w' ? 'b' : 'w' }
        : { over: true, reason: 'stallo', winner: null };
    }
    if (pos.half >= 100) return { over: true, reason: 'regola delle 50 mosse', winner: null };
    if (insufficient(pos)) return { over: true, reason: 'materiale insufficiente', winner: null };
    if (history) {
      const key = repetitionKey(toFen(pos));
      if (history.filter(f => repetitionKey(f) === key).length >= 3) {
        return { over: true, reason: 'tripla ripetizione', winner: null };
      }
    }
    return { over: false, check: inCheck(pos, pos.turn) };
  }

  function evaluate(pos) {
    let score = 0;
    for (let s = 0; s < 64; s++) {
      const p = pos.b[s];
      if (p === '.') continue;
      const kind = p.toLowerCase(), f = fileOf(s), r = rankOf(s);
      const center = 7 - (Math.abs(2 * f - 7) + Math.abs(2 * r - 7));
      let v = VALUE[kind];
      if (kind === 'n' || kind === 'b') v += center * 3;
      else if (kind === 'p') v += (isWhite(p) ? 6 - r : r - 1) * 6 + center;
      else if (kind === 'k') v -= center * 2;
      score += isWhite(p) ? v : -v;
    }
    return pos.turn === 'w' ? score : -score;
  }

  function ordered(pos) {
    const gain = m => (pos.b[m.to] === '.' ? 0 : 10 * VALUE[pos.b[m.to].toLowerCase()] - VALUE[pos.b[m.from].toLowerCase()]) + (m.promo === 'q' ? 800 : 0);
    return legalMoves(pos).sort((a, b) => gain(b) - gain(a));
  }

  function search(pos, depth, alpha, beta) {
    if (depth === 0) return evaluate(pos);
    const moves = ordered(pos);
    if (!moves.length) return inCheck(pos, pos.turn) ? -100000 - depth : 0;
    for (const m of moves) {
      const v = -search(makeMove(pos, m), depth - 1, -beta, -alpha);
      if (v >= beta) return beta;
      if (v > alpha) alpha = v;
    }
    return alpha;
  }

  // level 1 sbaglia di proposito, 2 e 3 cercano più a fondo
  function botMove(pos, level, rand) {
    const random = rand || Math.random;
    const moves = ordered(pos);
    if (!moves.length) return null;
    const depth = level >= 3 ? 3 : level === 2 ? 2 : 1;
    let best = -Infinity, picks = [];
    for (const m of moves) {
      const noise = level === 1 ? random() * 120 : random() * 6;
      const v = -search(makeMove(pos, m), depth - 1, -Infinity, Infinity) + noise;
      if (v > best) { best = v; picks = [m]; }
    }
    return picks[0];
  }

  const api = {
    START, parseFen, toFen, legalMoves, makeMove, status, perft, botMove, inCheck,
    toUci, findUci, sqName, sqIndex, colorOf,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Chess = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
