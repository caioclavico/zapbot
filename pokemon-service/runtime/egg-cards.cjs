'use strict';
const sharp = require('sharp');
const { createHash } = require('node:crypto');
const { limiter, ImageCache } = require('./images.cjs');

const CARD_WIDTH = 640;
const CARD_HEIGHT = 820;
const RARITY_COLORS = {
  comum: '#159447',
  incomum: '#1684d8',
  raro: '#8b4bd8',
  epico: '#d94b3d',
  'muito-raro': '#d94b3d'
};
const GOALS = { comum: 100, incomum: 180, raro: 250, epico: 500, 'muito-raro': 500 };
const ORANGE = '#f47b20';
const cache = new ImageCache({ maxBytes: 8 * 1024 * 1024, maxEntries: 32, ttlMs: 600000 });
const queue = limiter(1, 16);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));

function rarityColor(rarity) {
  return RARITY_COLORS[String(rarity || 'comum').toLowerCase()] || RARITY_COLORS.comum;
}

function egg(cx, cy, radius, rarity, scale = 1) {
  const color = rarityColor(rarity);
  const r = radius;
  return `<g transform="translate(${cx} ${cy}) scale(${scale})">
    <ellipse cx="0" cy="${r * .85}" rx="${r * .58}" ry="${r * .13}" fill="#020817" opacity=".4"/>
    <path d="M0 ${-r} C ${r * .48} ${-r * .9},${r * .72} ${-r * .18},${r * .66} ${r * .3} C ${r * .6} ${r * .75},${r * .3} ${r},0 ${r * 1.04} C ${-r * .3} ${r},${-r * .6} ${r * .75},${-r * .66} ${r * .3} C ${-r * .72} ${-r * .18},${-r * .48} ${-r * .9},0 ${-r}Z" fill="#f7fafb" stroke="#d5dde4" stroke-width="3"/>
    <ellipse cx="${-r * .24}" cy="${r * .08}" rx="${r * .16}" ry="${r * .23}" fill="${color}"/>
    <ellipse cx="${r * .28}" cy="${r * .34}" rx="${r * .2}" ry="${r * .27}" fill="${color}"/>
    <path d="M${-r * .5} ${r * .57} Q0 ${r * .35} ${r * .43} ${r * .72} Q0 ${r * .94} ${-r * .5} ${r * .57}Z" fill="${color}"/>
    <ellipse cx="${r * .18}" cy="${-r * .48}" rx="${r * .1}" ry="${r * .14}" fill="${color}"/>
  </g>`;
}

function base(title, subtitle, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${CARD_WIDTH}" height="${CARD_HEIGHT}" viewBox="0 0 ${CARD_WIDTH} ${CARD_HEIGHT}">
  <defs><linearGradient id="bg" x2="0" y2="1"><stop stop-color="#101d42"/><stop offset="1" stop-color="#071027"/></linearGradient><linearGradient id="glass" x2="1"><stop stop-color="#fff" stop-opacity=".12"/><stop offset=".5" stop-color="#fff" stop-opacity=".02"/><stop offset="1" stop-color="#8de7ff" stop-opacity=".08"/></linearGradient><filter id="shadow"><feGaussianBlur stdDeviation="8"/></filter></defs>
  <rect width="640" height="${CARD_HEIGHT}" rx="32" fill="url(#bg)"/><text x="42" y="55" fill="#8edbff" font-family="sans-serif" font-size="18" font-weight="700" letter-spacing="2">ODISSEU • POKÉMON</text><text x="42" y="105" fill="#fff" font-family="sans-serif" font-size="38" font-weight="700">${esc(title)}</text><text x="42" y="137" fill="#93a8d5" font-family="sans-serif" font-size="18">${esc(subtitle)}</text>${body}</svg>`;
}

function incubator(x, y, width, height, occupied, rarity, label, uses, bonus) {
  const cx = x + width / 2;
  const baseY = y + height - 18;
  return `<g><ellipse cx="${cx}" cy="${baseY + 28}" rx="${width * .48}" ry="18" fill="#000" opacity=".35" filter="url(#shadow)"/>
    <rect x="${x + 10}" y="${y + 58}" width="${width - 20}" height="${height - 80}" rx="18" fill="url(#glass)" stroke="${ORANGE}" stroke-width="3" opacity=".72"/>
    <path d="M${x + 16} ${y + 42} Q${cx} ${y + 12} ${x + width - 16} ${y + 42} L${x + width - 16} ${y + 62} Q${cx} ${y + 78} ${x + 16} ${y + 62}Z" fill="#f1f4f7" stroke="#aeb9c8" stroke-width="2"/>
    <ellipse cx="${cx}" cy="${y + 42}" rx="${width * .37}" ry="13" fill="#fff" opacity=".7"/><rect x="${x + 16}" y="${y + 58}" width="${width - 32}" height="14" rx="6" fill="${ORANGE}"/><rect x="${x + width - 38}" y="${y + 39}" width="20" height="11" rx="3" fill="#536177"/>
    <ellipse cx="${cx}" cy="${baseY}" rx="${width * .49}" ry="27" fill="#eef2f6" stroke="#aeb9c8" stroke-width="2"/><ellipse cx="${cx}" cy="${baseY - 8}" rx="${width * .42}" ry="18" fill="#cbd4df" stroke="${ORANGE}" stroke-width="5"/><ellipse cx="${cx}" cy="${baseY - 12}" rx="${width * .31}" ry="12" fill="${ORANGE}" opacity=".5"/><rect x="${cx - 28}" y="${baseY + 5}" width="56" height="15" rx="5" fill="#202c42"/><circle cx="${cx - 17}" cy="${baseY + 12}" r="3" fill="#63e6a1"/><circle cx="${cx}" cy="${baseY + 12}" r="3" fill="#70c9ff"/><circle cx="${cx + 17}" cy="${baseY + 12}" r="3" fill="#d08cff"/>
    ${occupied ? `<ellipse cx="${cx}" cy="${y + height / 2}" rx="${width * .34}" ry="${height * .17}" fill="#b8dfff" opacity=".13"/>${egg(cx, y + height / 2, 48, rarity, .82)}` : ''}
    <text x="${cx}" y="${y + height + 38}" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="18" font-weight="700">${esc(label)}</text><text x="${cx}" y="${y + height + 62}" text-anchor="middle" fill="#c0cee8" font-family="sans-serif" font-size="15">${esc(uses)}${bonus ? ' • +25%' : ''}</text></g>`;
}

function eggs(data) {
  const list = Array.isArray(data.eggs) ? data.eggs : [];
  let body = `<text x="560" y="105" text-anchor="end" fill="#8edbff" font-family="sans-serif" font-size="22">${list.filter(x => x.state !== 'hatched').length}/9</text>`;
  Array.from({ length: 9 }, (_, i) => list[i]).forEach((item, index) => {
    const x = 42 + (index % 3) * 188, y = 165 + Math.floor(index / 3) * 175;
    body += `<rect x="${x}" y="${y}" width="164" height="154" rx="22" fill="#132653" stroke="#273d70"/>`;
    if (!item) { body += `<text x="${x + 82}" y="${y + 88}" text-anchor="middle" fill="#52658e" font-family="sans-serif" font-size="15">vazio</text>`; return; }
    const rarity = item.category || 'comum';
    const active = item.state === 'incubating' || item.state === 'ready-to-hatch';
    const goal = Number(item['points-goal'] || GOALS[rarity] || 100);
    const points = Number(item.points || 0);
    const ratio = active ? Math.max(0, Math.min(1, points / goal)) : 0;
    body += egg(x + 82, y + 48, 36, rarity, .72);
    body += `<text x="${x + 82}" y="${y + 103}" text-anchor="middle" fill="${rarityColor(rarity)}" font-family="sans-serif" font-size="14" font-weight="700">${esc(String(rarity).toUpperCase())}</text><text x="${x + 82}" y="${y + 122}" text-anchor="middle" fill="#d9e7ff" font-family="sans-serif" font-size="12">${active ? (item.state === 'incubating' ? 'incubando' : 'pronto') : 'disponível'}</text><rect x="${x + 18}" y="${y + 132}" width="128" height="6" rx="3" fill="#263b68"/><rect x="${x + 18}" y="${y + 132}" width="${128 * ratio}" height="6" rx="3" fill="${rarityColor(rarity)}"/><text x="${x + 82}" y="${y + 149}" text-anchor="middle" fill="#c6d5ef" font-family="sans-serif" font-size="10">${active ? `${points}/${goal} pts` : `meta ${goal} pts`}</text>`;
  });
  return base('Meus Ovos', 'Inventário e progresso', body);
}

function incubators(data) {
  const items = Array.isArray(data.incubators) ? data.incubators : [];
  let body = '';
  items.slice(0, 4).forEach((item, index) => {
    const uses = item.uses == null ? 'ilimitados' : Number(item.uses) <= 0 ? 'bloqueada' : `${item.uses} usos`;
    body += incubator(18 + index * 154, 155, 130, 290, Boolean(item.activeEggId || item['active-egg-id']), item.rarity || ['comum', 'incomum', 'raro', 'epico'][index], item.kind || 'permanente', uses, Number(item.bonusPoints || item['bonus-points'] || 0) > 0);
  });
  return base('Incubadoras', 'Disponibilidade e usos restantes', body);
}

async function render(kind, data) {
  const key = createHash('sha256').update(JSON.stringify([kind, data])).digest('hex');
  if (kind !== 'eggs' && kind !== 'incubators') throw new Error('card_type_invalid');
  return cache.get(key, () => queue(() => sharp(Buffer.from(kind === 'eggs' ? eggs(data) : incubators(data))).png({ compressionLevel: 6 }).toBuffer()));
}

module.exports = { render, rarityColor, CARD_WIDTH, CARD_HEIGHT };
