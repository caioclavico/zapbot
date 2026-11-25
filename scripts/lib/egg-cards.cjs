'use strict';

/**
 * Cards PNG de ovos/incubadoras (legado Adventure Sync → A6).
 * Visual original 640×820 via Sharp; DTOs canônicos da JVM (camelCase ou kebab).
 * Sem HTTP/Datomic — só render a partir do payload adventure.
 */

const sharp = require('sharp');
const { createHash } = require('node:crypto');

const CARD_WIDTH = 640;
const CARD_HEIGHT = 820;
const MAX_PNG_BYTES = 180 * 1024;

const RARITY_COLORS = {
  comum: '#159447',
  incomum: '#1684d8',
  raro: '#8b4bd8',
  epico: '#d94b3d',
  'muito-raro': '#d94b3d',
  especial: '#f0b429'
};

const GOALS = {
  comum: 100,
  incomum: 180,
  raro: 250,
  epico: 500,
  'muito-raro': 500,
  especial: 800
};

const ORANGE = '#f47b20';

function limiter(limit, maxWaiting = 16) {
  let active = 0;
  const waiting = [];
  const next = () => {
    while (active < limit && waiting.length) {
      const { fn, resolve, reject } = waiting.shift();
      active += 1;
      Promise.resolve()
        .then(fn)
        .then(resolve, reject)
        .finally(() => {
          active -= 1;
          next();
        });
    }
  };
  return (fn) => new Promise((resolve, reject) => {
    if (waiting.length >= maxWaiting) {
      reject(new Error('Fila de imagens cheia'));
      return;
    }
    waiting.push({ fn, resolve, reject });
    next();
  });
}

class ImageCache {
  constructor({ maxBytes = 8 * 1024 * 1024, maxEntries = 32, ttlMs = 600000 } = {}) {
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
    this.ttlMs = ttlMs;
    this.bytes = 0;
    this.entries = new Map();
    this.pending = new Map();
  }

  async get(key, produce) {
    const hit = this.entries.get(key);
    if (hit && hit.until > Date.now()) {
      this.entries.delete(key);
      this.entries.set(key, hit);
      return hit.value;
    }
    if (hit) this.remove(key);
    if (this.pending.has(key)) return this.pending.get(key);
    const promise = Promise.resolve()
      .then(produce)
      .then((value) => {
        const bytes = Buffer.isBuffer(value) ? value.length : 0;
        if (bytes > 0 && bytes <= this.maxBytes) {
          while (
            this.entries.size
            && (this.bytes + bytes > this.maxBytes || this.entries.size >= this.maxEntries)
          ) {
            this.remove(this.entries.keys().next().value);
          }
          this.entries.set(key, { value, bytes, until: Date.now() + this.ttlMs });
          this.bytes += bytes;
        }
        return value;
      })
      .finally(() => this.pending.delete(key));
    this.pending.set(key, promise);
    return promise;
  }

  remove(key) {
    const item = this.entries.get(key);
    if (item) this.bytes -= item.bytes;
    this.entries.delete(key);
  }
}

const cache = new ImageCache();
const queue = limiter(1, 16);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
}[c]));

function pick(obj, ...keys) {
  if (!obj || typeof obj !== 'object') return undefined;
  for (const k of keys) {
    if (obj[k] != null) return obj[k];
  }
  return undefined;
}

function rarityColor(rarity) {
  return RARITY_COLORS[String(rarity || 'comum').toLowerCase()] || RARITY_COLORS.comum;
}

function goalFor(category, explicit) {
  const n = Number(explicit);
  if (Number.isFinite(n) && n > 0) return n;
  return GOALS[String(category || 'comum').toLowerCase()] || 100;
}

function normalizeEgg(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const category = String(pick(raw, 'category', 'categoria') || 'comum').toLowerCase();
  const state = String(pick(raw, 'state', 'estado') || 'inventory').toLowerCase();
  const points = Number(pick(raw, 'points', 'pontos') || 0);
  const goal = goalFor(category, pick(raw, 'pointsGoal', 'points-goal', 'points_goal'));
  return {
    eggId: pick(raw, 'eggId', 'egg-id', 'id'),
    category,
    state,
    points: Number.isFinite(points) ? points : 0,
    pointsGoal: goal,
    progressPct: Number(pick(raw, 'progressPct', 'progress-pct') || 0),
    restanteMs: pick(raw, 'restanteMs', 'restante-ms'),
    incubatorId: pick(raw, 'incubatorId', 'incubator-id'),
    pronto: Boolean(pick(raw, 'pronto', 'ready'))
  };
}

function normalizeIncubator(raw, eggsById) {
  if (!raw || typeof raw !== 'object') return null;
  const activeEggId = pick(raw, 'activeEggId', 'active-egg-id') || null;
  const egg = activeEggId && eggsById ? eggsById.get(String(activeEggId)) : null;
  const usesRaw = pick(raw, 'uses', 'usos');
  return {
    incubatorId: pick(raw, 'incubatorId', 'incubator-id', 'id') || 'permanente',
    kind: String(pick(raw, 'kind', 'tipo') || 'permanente').toLowerCase(),
    uses: usesRaw == null ? null : Number(usesRaw),
    bonusPoints: Number(pick(raw, 'bonusPoints', 'bonus-points') || 0),
    activeEggId,
    livre: pick(raw, 'livre') != null ? Boolean(raw.livre) : !activeEggId,
    rarity: egg ? egg.category : (pick(raw, 'rarity', 'category') || 'comum')
  };
}

function normalizeAdventure(data) {
  const src = data && typeof data === 'object' ? data : {};
  const eggs = (Array.isArray(src.eggs) ? src.eggs : [])
    .map(normalizeEgg)
    .filter(Boolean)
    .filter((e) => e.state !== 'hatched');
  const eggsById = new Map();
  for (const e of eggs) {
    if (e.eggId) eggsById.set(String(e.eggId), e);
  }
  const incubators = (Array.isArray(src.incubators) ? src.incubators : [])
    .map((i) => normalizeIncubator(i, eggsById))
    .filter(Boolean);
  const pending = Number(pick(src, 'pendingEggGrants', 'pending-egg-grants') || 0);
  const pi = pick(src, 'piDaily', 'pi-daily') || {};
  return {
    eggs,
    incubators,
    pendingEggGrants: Number.isFinite(pending) ? pending : 0,
    inventarioCount: Number(pick(src, 'inventarioCount', 'inventario-count') || eggs.length),
    limiteInventario: Number(pick(src, 'limiteInventario', 'limite-inventario') || 9),
    piDaily: {
      points: Number(pick(pi, 'points', 'pontos') || 0),
      cap: Number(pick(pi, 'cap') || 150),
      restante: Number(pick(pi, 'restante') || 0)
    }
  };
}

/** Ovo sólido/opaco com manchas de raridade (sem espécie). */
function egg(cx, cy, radius, rarity, scale = 1) {
  const color = rarityColor(rarity);
  const r = radius;
  return `<g transform="translate(${cx} ${cy}) scale(${scale})">
    <ellipse cx="0" cy="${r * 0.85}" rx="${r * 0.58}" ry="${r * 0.13}" fill="#020817" opacity=".4"/>
    <path d="M0 ${-r} C ${r * 0.48} ${-r * 0.9},${r * 0.72} ${-r * 0.18},${r * 0.66} ${r * 0.3} C ${r * 0.6} ${r * 0.75},${r * 0.3} ${r},0 ${r * 1.04} C ${-r * 0.3} ${r},${-r * 0.6} ${r * 0.75},${-r * 0.66} ${r * 0.3} C ${-r * 0.72} ${-r * 0.18},${-r * 0.48} ${-r * 0.9},0 ${-r}Z" fill="#f7fafb" stroke="#d5dde4" stroke-width="3"/>
    <ellipse cx="${-r * 0.24}" cy="${r * 0.08}" rx="${r * 0.16}" ry="${r * 0.23}" fill="${color}"/>
    <ellipse cx="${r * 0.28}" cy="${r * 0.34}" rx="${r * 0.2}" ry="${r * 0.27}" fill="${color}"/>
    <path d="M${-r * 0.5} ${r * 0.57} Q0 ${r * 0.35} ${r * 0.43} ${r * 0.72} Q0 ${r * 0.94} ${-r * 0.5} ${r * 0.57}Z" fill="${color}"/>
    <ellipse cx="${r * 0.18}" cy="${-r * 0.48}" rx="${r * 0.1}" ry="${r * 0.14}" fill="${color}"/>
  </g>`;
}

function base(title, subtitle, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${CARD_WIDTH}" height="${CARD_HEIGHT}" viewBox="0 0 ${CARD_WIDTH} ${CARD_HEIGHT}">
  <defs>
    <linearGradient id="bg" x2="0" y2="1"><stop stop-color="#101d42"/><stop offset="1" stop-color="#071027"/></linearGradient>
    <filter id="shadow"><feGaussianBlur stdDeviation="8"/></filter>
  </defs>
  <rect width="640" height="${CARD_HEIGHT}" rx="28" fill="url(#bg)"/>
  <text x="42" y="55" fill="#8edbff" font-family="sans-serif" font-size="18" font-weight="700" letter-spacing="2">ODISSEU • POKÉMON</text>
  <text x="42" y="105" fill="#fff" font-family="sans-serif" font-size="38" font-weight="700">${esc(title)}</text>
  <text x="42" y="137" fill="#93a8d5" font-family="sans-serif" font-size="18">${esc(subtitle)}</text>
  ${body}
</svg>`;
}

/**
 * Incubadora cilíndrica reta: vidro plano translúcido (sem riscos de reflexo),
 * tampas pouco arredondadas e centralizadas.
 */
function incubator(x, y, width, height, occupied, rarity, slot, kindLabel) {
  const cx = x + width / 2;
  const baseY = y + height - 18;
  const bodyTop = y + 52;
  const bodyH = height - 78;
  const rxBody = 8;
  return `<g>
    <ellipse cx="${cx}" cy="${baseY + 28}" rx="${width * 0.48}" ry="18" fill="#000" opacity=".35" filter="url(#shadow)"/>
    <rect x="${x + 14}" y="${bodyTop}" width="${width - 28}" height="${bodyH}" rx="${rxBody}"
          fill="#9fd7ff" fill-opacity="0.14" stroke="${ORANGE}" stroke-width="3"/>
    <rect x="${x + 18}" y="${bodyTop + 4}" width="${width - 36}" height="${bodyH - 8}" rx="4"
          fill="#cfefff" fill-opacity="0.06"/>
    <path d="M${x + 18} ${y + 40} L${x + width - 18} ${y + 40} L${x + width - 18} ${y + 56} L${x + 18} ${y + 56}Z"
          fill="#f1f4f7" stroke="#aeb9c8" stroke-width="2"/>
    <ellipse cx="${cx}" cy="${y + 40}" rx="${width * 0.36}" ry="10" fill="#fff" opacity=".75"/>
    <rect x="${x + 18}" y="${bodyTop}" width="${width - 36}" height="12" rx="3" fill="${ORANGE}"/>
    <rect x="${x + width - 36}" y="${y + 38}" width="16" height="10" rx="2" fill="#536177"/>
    <ellipse cx="${cx}" cy="${baseY}" rx="${width * 0.48}" ry="24" fill="#eef2f6" stroke="#aeb9c8" stroke-width="2"/>
    <ellipse cx="${cx}" cy="${baseY - 8}" rx="${width * 0.4}" ry="16" fill="#cbd4df" stroke="${ORANGE}" stroke-width="5"/>
    <ellipse cx="${cx}" cy="${baseY - 12}" rx="${width * 0.3}" ry="10" fill="${ORANGE}" opacity=".45"/>
    <rect x="${cx - 28}" y="${baseY + 4}" width="56" height="14" rx="4" fill="#202c42"/>
    <circle cx="${cx - 17}" cy="${baseY + 11}" r="3" fill="#63e6a1"/>
    <circle cx="${cx}" cy="${baseY + 11}" r="3" fill="#70c9ff"/>
    <circle cx="${cx + 17}" cy="${baseY + 11}" r="3" fill="#d08cff"/>
    ${occupied ? egg(cx, y + height / 2, 48, rarity, 0.82) : ''}
    <text x="${cx}" y="${y + 28}" text-anchor="middle" fill="#8edbff" font-family="sans-serif" font-size="28" font-weight="700">${slot}</text>
    <text x="${cx}" y="${y + height + 28}" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="15" font-weight="700">${esc(kindLabel)}</text>
  </g>`;
}

function stateLabel(egg) {
  if (egg.state === 'incubating') return 'incubando';
  if (egg.state === 'ready-to-hatch') return 'pronto';
  if (egg.state === 'delivery-pending') return 'nascimento pendente';
  return 'disponível';
}

function eggsSvg(data) {
  const adv = normalizeAdventure(data);
  // Spec: !pk ovos mostra só inventário (incubando fica em !pk incubadoras).
  const list = adv.eggs.filter((e) => e.state === 'inventory').slice(0, 9);
  const invTotal = Math.min(adv.inventarioCount, adv.limiteInventario);
  let body = `<text x="560" y="105" text-anchor="end" fill="#8edbff" font-family="sans-serif" font-size="22">${invTotal}/${adv.limiteInventario}</text>`;
  body += `<text x="42" y="158" fill="#93a8d5" font-family="sans-serif" font-size="15">${list.length} disponível(is) para incubar</text>`;
  if (adv.pendingEggGrants > 0) {
    body += `<text x="320" y="158" text-anchor="middle" fill="#fbbf24" font-family="sans-serif" font-size="14" font-weight="700">⏳ ${adv.pendingEggGrants} aguardando espaço</text>`;
  }
  if (adv.inventarioCount >= adv.limiteInventario) {
    body += `<text x="560" y="158" text-anchor="end" fill="#f87171" font-family="sans-serif" font-size="13">cheio</text>`;
  }
  Array.from({ length: 9 }, (_, i) => list[i]).forEach((item, index) => {
    const x = 42 + (index % 3) * 188;
    const y = 175 + Math.floor(index / 3) * 175;
    const slot = index + 1;
    body += `<rect x="${x}" y="${y}" width="164" height="154" rx="18" fill="#132653" stroke="#273d70"/>`;
    body += `<text x="${x + 148}" y="${y + 24}" text-anchor="end" fill="#8edbff" font-family="sans-serif" font-size="18" font-weight="700">${slot}</text>`;
    if (!item) {
      body += `<text x="${x + 82}" y="${y + 88}" text-anchor="middle" fill="#52658e" font-family="sans-serif" font-size="15">vazio</text>`;
      return;
    }
    const rarity = item.category;
    body += egg(x + 82, y + 58, 42, rarity, 0.85);
    body += `<text x="${x + 82}" y="${y + 128}" text-anchor="middle" fill="${rarityColor(rarity)}" font-family="sans-serif" font-size="15" font-weight="700">${esc(String(rarity).toUpperCase())}</text>`;
    body += `<text x="${x + 82}" y="${y + 146}" text-anchor="middle" fill="#93a8d5" font-family="sans-serif" font-size="12">!pk incubar ${slot}</text>`;
  });
  return base('Meus Ovos', 'Disponíveis para incubar', body);
}

function kindTitle(kind) {
  const k = String(kind || 'permanente').toLowerCase();
  if (k === 'especial') return 'ESPECIAL';
  if (k === 'extra' || k === 'temporaria' || k === 'temporária') return 'EXTRA';
  return 'PERMANENTE';
}

function incubatorsSvg(data) {
  const adv = normalizeAdventure(data);
  const eggsById = new Map();
  for (const e of adv.eggs) {
    if (e.eggId) eggsById.set(String(e.eggId), e);
  }
  let items = adv.incubators.slice(0, 3);
  if (!items.length) {
    items = [{ incubatorId: 'permanente', kind: 'permanente', uses: null, bonusPoints: 0, activeEggId: null, livre: true, rarity: 'comum' }];
  }
  const count = items.length;
  const cardW = count === 1 ? 220 : (count === 2 ? 200 : 180);
  const gap = count === 1 ? 0 : (count === 2 ? 40 : 24);
  const totalW = count * cardW + (count - 1) * gap;
  const startX = Math.max(24, Math.floor((CARD_WIDTH - totalW) / 2));
  const y = 168;
  const chamberH = 250;
  let body = `<text x="42" y="158" fill="#93a8d5" font-family="sans-serif" font-size="15">${count} incubadora(s) · use !pk incubar &lt;ovo&gt; &lt;nº&gt;</text>`;
  items.forEach((item, index) => {
    const eggObj = item.activeEggId ? eggsById.get(String(item.activeEggId)) : null;
    const slot = index + 1;
    const x = startX + index * (cardW + gap);
    const usesLabel = item.uses == null
      ? 'Usos: ∞'
      : Number(item.uses) <= 0
        ? 'Usos: 0'
        : `Usos restantes: ${item.uses}`;
    const estado = eggObj || item.activeEggId ? 'OCUPADA' : 'LIVRE';
    const kindLabel = kindTitle(item.kind || item.incubatorId);
    body += incubator(
      x,
      y,
      cardW,
      chamberH,
      Boolean(item.activeEggId),
      (eggObj && eggObj.category) || item.rarity || 'comum',
      slot,
      kindLabel
    );
    // Linhas de estado abaixo da câmara — sem sobrepor o desenho (y+height+38 era o conflito).
    const infoY = y + chamberH + 48;
    body += `<text x="${x + cardW / 2}" y="${infoY}" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="14" font-weight="700">Estado: ${estado}</text>`;
    body += `<text x="${x + cardW / 2}" y="${infoY + 22}" text-anchor="middle" fill="#c0cee8" font-family="sans-serif" font-size="13">${esc(usesLabel)}</text>`;
    if (Number(item.bonusPoints || 0) > 0) {
      body += `<text x="${x + cardW / 2}" y="${infoY + 44}" text-anchor="middle" fill="#fbbf24" font-family="sans-serif" font-size="13">Bônus: +${item.bonusPoints}% PI</text>`;
    }
    if (eggObj) {
      const goal = eggObj.pointsGoal || goalFor(eggObj.category);
      const points = eggObj.points || 0;
      const ratio = Math.max(0, Math.min(1, goal > 0 ? points / goal : (eggObj.progressPct || 0) / 100));
      const ready = eggObj.state === 'ready-to-hatch' || eggObj.state === 'delivery-pending' || eggObj.pronto;
      const barY = infoY + (Number(item.bonusPoints || 0) > 0 ? 58 : 48);
      body += `<rect x="${x + 24}" y="${barY}" width="${cardW - 48}" height="8" rx="4" fill="#263b68"/>`;
      body += `<rect x="${x + 24}" y="${barY}" width="${(cardW - 48) * ratio}" height="8" rx="4" fill="${rarityColor(eggObj.category)}"/>`;
      body += `<text x="${x + cardW / 2}" y="${barY + 24}" text-anchor="middle" fill="#c6d5ef" font-family="sans-serif" font-size="12">Ovo ${esc(String(eggObj.category || ''))}: ${points}/${goal} PI</text>`;
      if (ready) {
        body += `<text x="${x + cardW / 2}" y="${barY + 42}" text-anchor="middle" fill="#86efac" font-family="sans-serif" font-size="13" font-weight="700">pronto · !pk chocar</text>`;
      }
    } else {
      const cmdY = infoY + (Number(item.bonusPoints || 0) > 0 ? 66 : 48);
      body += `<text x="${x + cardW / 2}" y="${cmdY}" text-anchor="middle" fill="#8edbff" font-family="sans-serif" font-size="12">!pk incubar &lt;ovo&gt; ${slot}</text>`;
    }
  });
  return base('Incubadoras', 'Números iguais ao texto e ao comando', body);
}

async function render(kind, data) {
  if (kind !== 'eggs' && kind !== 'incubators') {
    throw new Error('card_type_invalid');
  }
  const adv = normalizeAdventure(data);
  const key = createHash('sha256')
    .update(JSON.stringify([kind, adv]))
    .digest('hex');
  return cache.get(key, () => queue(async () => {
    const svg = kind === 'eggs' ? eggsSvg(adv) : incubatorsSvg(adv);
    const buf = await sharp(Buffer.from(svg)).png({ compressionLevel: 6 }).toBuffer();
    if (buf.length > MAX_PNG_BYTES) {
      return sharp(Buffer.from(svg)).png({ compressionLevel: 9, palette: true }).toBuffer();
    }
    return buf;
  }));
}


/** Fundo e interior da casca, atrás do sprite. */
function hatchRevealSvg({ speciesName = 'Pokémon', shiny = false } = {}) {
  const title = shiny ? 'SEU OVO CHOCOU! ✨' : 'SEU OVO CHOCOU!';
  const name = esc(speciesName);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${CARD_WIDTH}" height="${CARD_HEIGHT}" viewBox="0 0 ${CARD_WIDTH} ${CARD_HEIGHT}">
  <defs>
    <linearGradient id="hbg" x2="0" y2="1"><stop stop-color="#1a2a55"/><stop offset="1" stop-color="#0a142c"/></linearGradient>
    <radialGradient id="glow" cx="50%" cy="42%" r="45%"><stop stop-color="#fff7c2" stop-opacity=".55"/><stop offset="1" stop-color="#fff7c2" stop-opacity="0"/></radialGradient>
  </defs>
  <rect width="640" height="820" rx="28" fill="url(#hbg)"/>
  <circle cx="320" cy="340" r="210" fill="url(#glow)"/>
  <text x="42" y="55" fill="#8edbff" font-family="sans-serif" font-size="18" font-weight="700" letter-spacing="2">ODISSEU • POKÉMON</text>
  <text x="320" y="120" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="36" font-weight="700">${esc(title)}</text>
  <text x="320" y="158" text-anchor="middle" fill="#fbbf24" font-family="sans-serif" font-size="22" font-weight="700">${name}</text>
  <ellipse cx="320" cy="537" rx="182" ry="49" fill="#c4d1df" stroke="#f7fafb" stroke-width="5"/>
  <!-- fragmentos -->
  <path d="M150 430 L190 400 L175 460 Z" fill="#f7fafb" stroke="#c5ced6"/>
  <path d="M470 420 L520 450 L480 480 Z" fill="#f7fafb" stroke="#c5ced6"/>
  <path d="M260 390 L300 370 L290 420 Z" fill="#f7fafb" stroke="#c5ced6"/>
  <path d="M380 385 L430 395 L400 430 Z" fill="#f7fafb" stroke="#c5ced6"/>
  <text x="320" y="760" text-anchor="middle" fill="#93a8d5" font-family="sans-serif" font-size="18">Nível 1 · adicionado à coleção</text>
</svg>`;
}

/** Borda aberta em cima, fundo arredondado embaixo. Sobrepõe a base do sprite. */
function hatchShellForegroundSvg() {
  return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${CARD_WIDTH}" height="${CARD_HEIGHT}">
    <path d="M138 525 L178 548 L214 525 L252 549 L290 529 L327 552 L369 526 L407 550 L451 523 L502 535
      C482 655 412 704 320 704 C228 704 158 655 138 525 Z"
      fill="#f7fafb" stroke="#d5dde4" stroke-width="4" stroke-linejoin="round"/>
    <ellipse cx="224" cy="599" rx="25" ry="31" fill="#8b4bd8" opacity=".85"/>
    <ellipse cx="408" cy="604" rx="29" ry="36" fill="#1684d8" opacity=".85"/>
  </svg>`);
}

async function renderHatchReveal(data = {}) {
  const key = createHash('sha256')
    .update(JSON.stringify(['hatch', data.speciesName, data.shiny]))
    .digest('hex');
  return cache.get(key, () => queue(async () => {
    const svg = hatchRevealSvg(data);
    const buf = await sharp(Buffer.from(svg)).png({ compressionLevel: 6 }).toBuffer();
    if (buf.length > MAX_PNG_BYTES) {
      return sharp(Buffer.from(svg)).png({ compressionLevel: 9, palette: true }).toBuffer();
    }
    return buf;
  }));
}

async function renderEggGrant(data = {}) {
  const category = String((data && (data.category || data.eggCategory)) || 'comum');
  const key = createHash('sha256').update(JSON.stringify(['egg-grant', category])).digest('hex');
  return cache.get(key, () => queue(async () => {
    const label = category.charAt(0).toUpperCase() + category.slice(1);
    const body = `${egg(320, 420, 150, category, 1)}
    <text x="320" y="690" text-anchor="middle" fill="#f8fafc" font-family="sans-serif" font-size="22" font-weight="700">Raridade: ${esc(label)}</text>
    <text x="320" y="732" text-anchor="middle" fill="#93a8d5" font-family="sans-serif" font-size="18">A espécie fica surpresa até chocar</text>
    <text x="320" y="774" text-anchor="middle" fill="#8edbff" font-family="sans-serif" font-size="16">Incube com !pk incubar &lt;n&gt;</text>`;
    const svg = base('Você encontrou um ovo!', 'Sem revelar a espécie', body);
    const buf = await sharp(Buffer.from(svg)).png({ compressionLevel: 6 }).toBuffer();
    if (buf.length > MAX_PNG_BYTES) {
      return sharp(Buffer.from(svg)).png({ compressionLevel: 9, palette: true }).toBuffer();
    }
    return buf;
  }));
}

module.exports = {
  render,
  renderEggGrant,
  renderHatchReveal,
  hatchShellForegroundSvg,
  hatchRevealSvg,
  rarityColor,
  normalizeAdventure,
  normalizeEgg,
  kindTitle,
  CARD_WIDTH,
  CARD_HEIGHT,
  MAX_PNG_BYTES,
  GOALS,
  RARITY_COLORS
};
