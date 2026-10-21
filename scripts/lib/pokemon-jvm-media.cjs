'use strict';

/**
 * Composição visual da caça JVM (M4.6).
 * Espelha arena/captura do legado (core.cljs) sem gameplay nem Cassandra.
 * Falha de download/render → null (o chamador cai para texto; nunca re-TX).
 */

const sharp = require('sharp');

const SPRITE_CACADA = 320;
const TIMEOUT_MS = 6000;
/** Cache em memória de sprites baixados (URLs PokeAPI/CDN). Limite evita crescimento na VM. */
const SPRITE_CACHE_MAX = 48;
const spriteCache = new Map();

const TIPO_PT = {
  normal: 'Normal', fire: 'Fogo', water: 'Água', grass: 'Planta', electric: 'Elétrico',
  ice: 'Gelo', fighting: 'Lutador', poison: 'Veneno', ground: 'Terra', flying: 'Voador',
  psychic: 'Psíquico', bug: 'Inseto', rock: 'Pedra', ghost: 'Fantasma', dragon: 'Dragão',
  dark: 'Sombrio', steel: 'Aço', fairy: 'Fada'
};

const CORES_BOLA = {
  pokebola: '#dc2626',
  'grande-bola': '#2563eb',
  'ultra-bola': '#111827'
};

function urlCdn(url) {
  if (!url || typeof url !== 'string') return null;
  const m = url.match(
    /https:\/\/raw\.githubusercontent\.com\/PokeAPI\/sprites\/(?:master|refs\/heads\/master)\/(.+)/
  );
  return m ? `https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/${m[1]}` : null;
}

function candidatosUrl(url) {
  return [...new Set([urlCdn(url), url].filter(Boolean))];
}

function cacheGet(key) {
  const hit = spriteCache.get(key);
  if (!hit) return null;
  spriteCache.delete(key);
  spriteCache.set(key, hit);
  return hit;
}

function cacheSet(key, value) {
  if (spriteCache.has(key)) spriteCache.delete(key);
  spriteCache.set(key, value);
  while (spriteCache.size > SPRITE_CACHE_MAX) {
    const oldest = spriteCache.keys().next().value;
    spriteCache.delete(oldest);
  }
}

async function baixarBuffer(url) {
  if (!url) return null;
  const cached = cacheGet(url);
  if (cached) return cached;
  let lastErr = null;
  for (const u of candidatosUrl(url)) {
    try {
      const res = await fetch(u, { signal: AbortSignal.timeout(TIMEOUT_MS) });
      if (!res.ok) {
        lastErr = new Error(`HTTP ${res.status}`);
        continue;
      }
      const buf = Buffer.from(await res.arrayBuffer());
      cacheSet(url, buf);
      return buf;
    } catch (err) {
      lastErr = err;
    }
  }
  if (lastErr) {
    // eslint-disable-next-line no-console
    console.warn('[pokemon-jvm-media] sprite indisponível:', url, lastErr.message || lastErr);
  }
  return null;
}

function tiposPt(tipos) {
  return (tipos || [])
    .map((t) => TIPO_PT[String(t).toLowerCase()] || String(t))
    .filter(Boolean)
    .join('/');
}

function escapeXml(s) {
  return String(s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function svgPlaceholder(tamanho) {
  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${tamanho}" height="${tamanho}">`
      + `<rect width="${tamanho}" height="${tamanho}" rx="28" fill="#0f172a"/>`
      + `<circle cx="${tamanho / 2}" cy="${tamanho / 2}" r="${Math.round(tamanho * 0.28)}" `
      + `fill="#f8fafc" stroke="#94a3b8" stroke-width="10"/>`
      + `<path d="M${tamanho * 0.22} ${tamanho / 2}h${tamanho * 0.56}" stroke="#111827" stroke-width="12"/>`
      + '</svg>'
  );
}

function tamanhoVisual(pokemon, maximo) {
  const altura = Math.max(0.1, Number((pokemon && pokemon.altura) || 1.0));
  const proporcao = Math.min(1, Math.log1p(altura) / Math.log(5));
  return Math.round(maximo * (0.5 + 0.5 * proporcao));
}

function isShiny(pokemon) {
  return Boolean(pokemon && (pokemon.shiny === true || pokemon.shiny === 1));
}

function spriteUrl(pokemon) {
  if (!pokemon) return null;
  if (isShiny(pokemon) && pokemon.imagemShiny) return pokemon.imagemShiny;
  if (isShiny(pokemon) && pokemon['imagem-shiny']) return pokemon['imagem-shiny'];
  return pokemon.imagem || pokemon.imagemShiny || pokemon['imagem-shiny'] || null;
}

async function spriteProporcional(pokemon, maximo) {
  const tamanho = tamanhoVisual(pokemon, maximo);
  const buf = await baixarBuffer(spriteUrl(pokemon));
  const entrada = buf || svgPlaceholder(tamanho);
  const out = await sharp(entrada)
    .resize(tamanho, tamanho, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
  return { buffer: out, tamanho };
}

function idCenario(area) {
  const raw = (area && (area.id || area.nome || area)) || '';
  const id = String(raw)
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .trim();
  for (const key of ['floresta', 'praia', 'caverna', 'cidade', 'lago', 'vulcao']) {
    if (id.includes(key)) return key;
  }
  return id;
}

function svgCenario(id) {
  switch (id) {
    case 'floresta':
      return "<g id='cenario-floresta'><g fill='#14532d' stroke='#052e16' stroke-width='5'>"
        + "<rect x='74' y='105' width='24' height='170' rx='10' fill='#78350f'/><circle cx='86' cy='92' r='63'/>"
        + "<circle cx='38' cy='126' r='43'/><circle cx='132' cy='128' r='48'/>"
        + "<rect x='657' y='112' width='24' height='163' rx='10' fill='#78350f'/><circle cx='669' cy='94' r='60'/>"
        + "<circle cx='625' cy='132' r='42'/><circle cx='713' cy='130' r='45'/></g>"
        + "<g fill='#4ade80' opacity='.8'><circle cx='214' cy='92' r='22'/><circle cx='543' cy='104' r='26'/></g></g>";
    case 'praia':
      return "<g id='cenario-praia'><circle cx='650' cy='75' r='45' fill='#fde047'/>"
        + "<path d='M0 176Q95 148 190 176T380 176T570 176T760 176V267H0Z' fill='#0284c7'/>"
        + "<path d='M0 202Q95 174 190 202T380 202T570 202T760 202' fill='none' stroke='#bae6fd' stroke-width='10'/>"
        + "<path d='M40 239q55-35 110 0M585 235q60-37 125 0' fill='none' stroke='#f8fafc' stroke-width='8' stroke-linecap='round'/></g>";
    case 'caverna':
      return "<g id='cenario-caverna' fill='#1e293b' stroke='#0f172a' stroke-width='5'>"
        + "<path d='M0 0h760v45L700 93l-35-50-52 86-46-95-58 74-55-108H0Z'/>"
        + "<path d='M0 400V112l58 66 55-105 44 112 54-78 51 125 42-58 54 99v127Z'/>"
        + "<path d='M760 400V92l-54 83-48-104-44 117-51-74-50 126-45-66-47 99v127Z'/></g>";
    case 'cidade':
      return "<g id='cenario-cidade' stroke='#334155' stroke-width='5'>"
        + "<path d='M18 238V82h132v156M155 238V118h120v120M498 238V105h108v133M611 238V65h132v173' fill='#64748b'/>"
        + "<g fill='#fde68a' stroke='none'><path d='M42 108h24v25H42zm55 0h24v25H97zm-55 50h24v25H42zm55 0h24v25H97z"
        + "M180 143h25v27h-25zm46 0h25v27h-25zM522 130h24v26h-24zm40 0h24v26h-24zm75-39h27v28h-27zm51 0h27v28h-27z"
        + "m-51 51h27v28h-27zm51 0h27v28h-27z'/></g>"
        + "<path d='M320 238V145h120v93M350 145v-37h60v37' fill='#475569'/></g>";
    case 'lago':
      return "<g id='cenario-lago'><path d='M0 190Q120 160 245 190T510 190T760 184V300H0Z' fill='#0891b2'/>"
        + "<g fill='none' stroke='#a5f3fc' stroke-width='7' stroke-linecap='round'><path d='M20 222h150m55 35h180m95-38h225'/></g>"
        + "<g fill='#65a30d' stroke='#365314' stroke-width='3'><ellipse cx='115' cy='263' rx='40' ry='14'/>"
        + "<ellipse cx='630' cy='255' rx='48' ry='16'/></g>"
        + "<g stroke='#166534' stroke-width='7'><path d='M46 260v-82m28 82v-105m640 105v-90m-31 90v-70'/></g></g>";
    case 'vulcao':
      return "<g id='cenario-vulcao'><path d='M166 249L355 53l52 58 42-26 154 164Z' fill='#3f3f46' stroke='#18181b' stroke-width='8'/>"
        + "<path d='M355 53l52 58 42-26 35 37-54 27-41-17-51 28-48-28Z' fill='#f97316'/>"
        + "<path d='M386 130l25 13-17 48 29 54' fill='none' stroke='#ef4444' stroke-width='15' stroke-linecap='round'/>"
        + "<g fill='#d6d3d1' opacity='.75'><circle cx='375' cy='45' r='26'/><circle cx='416' cy='29' r='34'/>"
        + "<circle cx='458' cy='49' r='25'/></g></g>";
    default:
      return '';
  }
}

function svgArenaCacada(area, fugiu) {
  const ceu = (area && area.ceu) || '#7dd3fc';
  const chao = (area && area.chao) || '#166534';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400">`
    + `<defs><linearGradient id="ceu-caca" x1="0" y1="0" x2="0" y2="1">`
    + `<stop stop-color="${ceu}"/><stop offset="1" stop-color="#e0f2fe"/></linearGradient>`
    + `<linearGradient id="grama" x1="0" y1="0" x2="0" y2="1">`
    + `<stop stop-color="${chao}"/><stop offset="1" stop-color="#111827"/></linearGradient></defs>`
    + `<rect width="760" height="400" rx="28" fill="url(#ceu-caca)"/>`
    + svgCenario(idCenario(area))
    + `<path d="M0 245 Q120 210 250 245 T510 245 T760 240 V400 H0Z" fill="url(#grama)"/>`
    + `<g stroke="#14532d" stroke-width="5" stroke-linecap="round">`
    + `<path d="M25 330l10-28m0 28l18-22M105 370l8-30m0 30l20-24M245 345l12-35m0 35l22-25`
    + `M390 372l10-31m0 31l19-25M540 340l12-32m0 32l22-25M690 365l10-30m0 30l20-24"/></g>`
    + (fugiu
      ? `<g fill="#f8fafc" fill-opacity=".92" stroke="#cbd5e1" stroke-width="3">`
        + `<circle cx="555" cy="205" r="62"/><circle cx="615" cy="180" r="52"/>`
        + `<circle cx="665" cy="220" r="64"/><circle cx="600" cy="245" r="70"/>`
        + `<circle cx="690" cy="165" r="31"/><circle cx="710" cy="125" r="20"/></g>`
        + `<path d="M510 290q55-40 110 0t110 0" fill="none" stroke="#e2e8f0" stroke-width="18" stroke-linecap="round"/>`
      : '')
    + '</svg>';
}

function svgBolaCaptura(bola, capturou, fugiu) {
  const cor = CORES_BOLA[bola] || '#dc2626';
  const corpo = capturou
    ? `<g filter="url(#sombra-bola)">`
      + `<circle cx="380" cy="225" r="105" fill="#f8fafc" stroke="#111827" stroke-width="12"/>`
      + `<path d="M275 225a105 105 0 0 1 210 0Z" fill="${cor}"/>`
      + `<path d="M278 225h204" stroke="#111827" stroke-width="16"/>`
      + `<circle cx="380" cy="225" r="31" fill="#f8fafc" stroke="#111827" stroke-width="12"/></g>`
      + `<g fill="#fde047" stroke="#f59e0b" stroke-width="3">`
      + `<path d="M300 72l10 24 26 2-20 17 6 25-22-14-22 14 6-25-20-17 26-2Z"/>`
      + `<path d="M380 42l8 19 21 2-16 13 5 21-18-11-18 11 5-21-16-13 21-2Z"/>`
      + `<path d="M460 72l10 24 26 2-20 17 6 25-22-14-22 14 6-25-20-17 26-2Z"/></g>`
    : `<g filter="url(#sombra-bola)" transform="translate(0 34)">`
      + `<ellipse cx="380" cy="150" rx="116" ry="82" fill="${cor}" stroke="#111827" stroke-width="9"/>`
      + `<ellipse cx="380" cy="160" rx="101" ry="66" fill="#1f2937" stroke="#111827" stroke-width="6"/>`
      + `<path d="M289 161C313 128 335 112 366 104L351 198C325 194 303 181 289 161Z" fill="#93c5fd" fill-opacity=".58" stroke="#111827" stroke-width="5"/>`
      + `<path d="M471 161C447 128 425 112 394 104L409 198C435 194 457 181 471 161Z" fill="#93c5fd" fill-opacity=".58" stroke="#111827" stroke-width="5"/>`
      + `<path d="M350 198L366 104H394L410 198Z" fill="#cbd5e1" fill-opacity=".72" stroke="#111827" stroke-width="5"/>`
      + `<circle cx="380" cy="118" r="35" fill="#0f172a" stroke="#111827" stroke-width="6"/>`
      + `<circle cx="380" cy="118" r="26" fill="#67e8f9" fill-opacity=".42" stroke="#334155" stroke-width="5"/>`
      + `<path d="M362 100L398 136M398 100L362 136" stroke="#111827" stroke-width="4" opacity=".75"/>`
      + `<circle cx="380" cy="118" r="13" fill="#f8fafc" stroke="#cbd5e1" stroke-width="3"/>`
      + `<path d="M270 248C285 205 475 205 490 248C490 311 438 347 380 347C322 347 270 311 270 248Z" fill="#f1f5f9" stroke="#111827" stroke-width="9"/>`
      + `<ellipse cx="380" cy="248" rx="108" ry="33" fill="#111827" stroke="#e5e7eb" stroke-width="5"/>`
      + `<path d="M270 248C305 275 455 275 490 248" fill="none" stroke="#111827" stroke-width="5" opacity=".5"/>`
      + `<path d="M350 247A30 24 0 0 0 410 247V268A30 24 0 0 1 350 268Z" fill="#0f172a" stroke="#111827" stroke-width="7"/></g>`;
  const nuvem = fugiu
    ? `<g fill="#f8fafc" fill-opacity=".92" stroke="#cbd5e1" stroke-width="3">`
      + `<circle cx="315" cy="78" r="22"/><circle cx="348" cy="56" r="26"/>`
      + `<circle cx="381" cy="50" r="30"/><circle cx="414" cy="56" r="26"/>`
      + `<circle cx="445" cy="78" r="22"/></g>`
      + `<g fill="none" stroke="#e2e8f0" stroke-width="8" stroke-linecap="round" opacity=".78">`
      + `<path d="M304 40C287 24 299 10 323 8"/><path d="M380 22C364 8 378 -5 404 0"/>`
      + `<path d="M456 40C474 23 464 9 440 7"/></g>`
    : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400">`
    + `<defs><linearGradient id="ceu-captura" x1="0" y1="0" x2="0" y2="1">`
    + `<stop stop-color="#7dd3fc"/><stop offset="1" stop-color="#e0f2fe"/></linearGradient>`
    + `<linearGradient id="grama-captura" x1="0" y1="0" x2="0" y2="1">`
    + `<stop stop-color="#65a30d"/><stop offset="1" stop-color="#166534"/></linearGradient>`
    + `<filter id="sombra-bola"><feDropShadow dx="0" dy="10" stdDeviation="9" flood-opacity=".35"/></filter>`
    + `</defs><rect width="760" height="400" rx="28" fill="url(#ceu-captura)"/>`
    + `<path d="M0 260 Q180 215 380 260 T760 255 V400 H0Z" fill="url(#grama-captura)"/>`
    + corpo + nuvem + '</svg>';
}

function formaGolpe(tipo) {
  if (tipo === 'fire') {
    return "<g><path d='M350 280q-55-70 5-120-5 45 28 58-8-72 42-112 55 80 10 174Z' fill='#f97316' stroke='#c2410c' stroke-width='8'/>"
      + "<path d='M377 268q-27-38 2-69 1 24 20 34-2-33 19-57 24 48-4 92Z' fill='#fde047'/></g>";
  }
  if (tipo === 'electric') {
    return "<path d='M405 75l-82 145h62l-34 112 101-157h-65Z' fill='#fde047' stroke='#eab308' stroke-width='8'/>";
  }
  if (tipo === 'water') {
    return "<path d='M380 80C330 155 300 195 300 245a80 80 0 0 0 160 0c0-50-30-90-80-165Z' fill='#38bdf8' stroke='#0369a1' stroke-width='8'/>";
  }
  if (tipo === 'grass') {
    return "<g fill='#4ade80' stroke='#15803d' stroke-width='6'>"
      + "<ellipse cx='345' cy='210' rx='38' ry='75' transform='rotate(-35 345 210)'/>"
      + "<ellipse cx='420' cy='205' rx='38' ry='75' transform='rotate(35 420 205)'/></g>";
  }
  return "<path d='M380 125l22 58 61-17-37 52 51 37-63-4-9 62-25-57-57 30 34-53-53-34 63 1Z' "
    + "fill='#ffffff' fill-opacity='.72' stroke='#facc15' stroke-width='9'/>";
}

function svgBarraHp({ x, y, width, atual, max, cor }) {
  const m = Math.max(1, Number(max) || 1);
  const a = Math.max(0, Math.min(m, Number(atual) || 0));
  const fillW = Math.round((a / m) * width);
  const pct = a / m;
  const fill = cor || (pct > 0.5 ? '#22c55e' : (pct > 0.2 ? '#eab308' : '#ef4444'));
  return `<g>`
    + `<rect x="${x}" y="${y}" width="${width}" height="10" rx="3" fill="#0f172a" fill-opacity=".55"/>`
    + `<rect x="${x}" y="${y}" width="${fillW}" height="10" rx="3" fill="${fill}"/>`
    + `<text x="${x + width / 2}" y="${y + 22}" text-anchor="middle" fill="#f8fafc" `
    + `font-family="sans-serif" font-size="12" font-weight="700">${Math.floor(a)}/${Math.floor(m)}</text>`
    + `</g>`;
}

function svgSobreposicao({ texto, shiny, efeito, mostrarEntrada, hpX, hpO, mostrarHpX = true, mostrarHpO = true }) {
  const t = texto || '';
  const lower = t.toLowerCase();
  const sono = lower.includes('dorm');
  const confuso = lower.includes('confus');
  const desmaio = /(desmaiou|caiu por causa|caiu com o recuo)/i.test(t);
  const entrada = mostrarEntrada !== false && /(envia \*|entrou na batalha)/i.test(t);
  const efeitos = efeito == null ? [] : (Array.isArray(efeito) ? efeito : [efeito]);
  const golpes = efeitos.map(e => {
    const escala = 0.29 + Math.min(0.19, Math.max(0, Number(e.dano) || 0) / 500);
    const centro = e.origem === 'o' || e.origem === ':o' ? 432 : 328;
    const esquerda = centro - 380 * escala;
    const topo = 205 - 210 * escala;
    return `<g transform="translate(${esquerda} ${topo}) scale(${escala})" filter="url(#golpe-glow)">`
      + `${formaGolpe(e.tipo)}</g>`;
  }).join('');
  const barras = [];
  if (mostrarHpX && hpX && Number.isFinite(Number(hpX.max)) && Number(hpX.max) > 0) {
    barras.push(svgBarraHp({ x: 70, y: 368, width: 190, atual: hpX.atual, max: hpX.max }));
  }
  if (mostrarHpO && hpO && Number.isFinite(Number(hpO.max)) && Number(hpO.max) > 0) {
    const ox = mostrarHpX ? 500 : 285;
    barras.push(svgBarraHp({ x: ox, y: 368, width: 190, atual: hpO.atual, max: hpO.max }));
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400">`
    + `<defs><filter id="golpe-glow"><feDropShadow dx="0" dy="0" stdDeviation="9" flood-color="#ffffff" flood-opacity=".75"/></filter></defs>`
    + golpes
    + barras.join('')
    + ((sono || confuso)
      ? `<circle cx="380" cy="215" r="72" fill="none" stroke="#ffffff" stroke-opacity=".8" stroke-width="9"/>`
      : '')
    + (sono
      ? `<g fill="#312e81" font-family="sans-serif" font-weight="bold"><text x="420" y="145" font-size="48">Z</text>`
        + `<text x="475" y="105" font-size="36">Z</text></g>`
      : '')
    + (confuso
      ? `<path d="M315 180q65-80 130 0t-130 0q65-55 130 0" fill="none" stroke="#f472b6" stroke-width="14"/>`
      : '')
    + (desmaio
      ? `<g stroke="#0f172a" stroke-width="13" stroke-linecap="round"><path d="M555 130l38 38m0-38l-38 38M635 130l38 38m0-38l-38 38"/></g>`
      : '')
    + (entrada
      ? `<g transform="translate(585 250)"><circle r="55" fill="#f8fafc" stroke="#111827" stroke-width="8"/>`
        + `<path d="M-55 0a55 55 0 0 1 110 0Z" fill="#dc2626"/><path d="M-52 0h104" stroke="#111827" stroke-width="10"/>`
        + `<circle r="15" fill="#fff" stroke="#111827" stroke-width="7"/></g>`
      : '')
    + (shiny
      ? `<g fill="#fde047" stroke="#f59e0b" stroke-width="2">`
        + `<path d="M555 60l9 22 24 2-18 15 5 24-20-13-20 13 5-24-18-15 24-2Z"/>`
        + `<path d="M685 125l7 17 19 2-15 12 5 19-16-10-16 10 5-19-15-12 19-2Z"/></g>`
      : '')
    + '</svg>';
}

function hpFromPokemon(p) {
  if (!p) return null;
  const max = Number(p.hp);
  const atual = Number(p.hpAtual != null ? p.hpAtual : p.hp);
  if (!Number.isFinite(max) || max <= 0 || !Number.isFinite(atual)) return null;
  return { atual: Math.max(0, atual), max };
}

async function aplicarSobreposicao(base, opts) {
  return sharp(base)
    .composite([{ input: Buffer.from(svgSobreposicao(opts || {})), left: 0, top: 0 }])
    .png()
    .toBuffer();
}

/**
 * Arena da caça (encontro / turno / fuga).
 * @returns {{ base64, mimeType, filename } | null}
 */
async function renderHuntArena({
  area,
  pokemonX,
  pokemonO,
  fugiu = false,
  aguardandoCaptura = false,
  caption = '',
  efeito = null,
  mostrarEntrada = true,
  hpX = null,
  hpO = null
} = {}) {
  try {
    const captura = aguardandoCaptura && !fugiu;
    const mostrarMeu = !captura;
    const centroMeu = 165;
    const centroSelvagem = captura ? 380 : 595;
    const [meu, selvagem] = await Promise.all([
      mostrarMeu && pokemonX ? spriteProporcional(pokemonX, SPRITE_CACADA) : null,
      !fugiu && pokemonO ? spriteProporcional(pokemonO, SPRITE_CACADA) : null
    ]);
    const layers = [];
    if (meu) {
      layers.push({
        input: meu.buffer,
        left: centroMeu - Math.floor(meu.tamanho / 2),
        top: 365 - meu.tamanho
      });
    }
    if (selvagem) {
      layers.push({
        input: selvagem.buffer,
        left: centroSelvagem - Math.floor(selvagem.tamanho / 2),
        top: 350 - selvagem.tamanho
      });
    }
    let base = await sharp(Buffer.from(svgArenaCacada(area || {}, fugiu)))
      .composite(layers)
      .png()
      .toBuffer();
    const shiny = isShiny(pokemonO);
    base = await aplicarSobreposicao(base, {
      texto: caption,
      shiny,
      efeito,
      mostrarEntrada,
      hpX: hpX || hpFromPokemon(pokemonX),
      hpO: hpO || hpFromPokemon(pokemonO),
      mostrarHpX: mostrarMeu,
      mostrarHpO: !fugiu
    });
    return {
      base64: base.toString('base64'),
      mimeType: 'image/png',
      filename: fugiu ? 'fuga-selvagem.png' : 'batalha-selvagem.png'
    };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-jvm-media] arena:', err.message || err);
    return null;
  }
}

/**
 * Cartão da Pokébola (sucesso / falha / fuga).
 */
async function renderCaptureBall({ bola = 'pokebola', capturou = false, fugiu = false } = {}) {
  try {
    const buf = await sharp(Buffer.from(svgBolaCaptura(bola, capturou, fugiu)))
      .png()
      .toBuffer();
    return {
      base64: buf.toString('base64'),
      mimeType: 'image/png',
      filename: capturou ? 'captura-concluida.png' : 'captura-falhou.png'
    };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-jvm-media] captura:', err.message || err);
    return null;
  }
}

/**
 * Sprite único (coleção/treinador leve). Falha → null.
 */
async function renderSpriteCard(pokemon, { filename = 'pokemon.png', size = 320 } = {}) {
  try {
    if (!pokemon) return null;
    const { buffer } = await spriteProporcional(pokemon, size);
    return {
      base64: buffer.toString('base64'),
      mimeType: 'image/png',
      filename
    };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-jvm-media] sprite:', err.message || err);
    return null;
  }
}

/**
 * Cartão único dos 3 iniciais (M9.6.1) — horizontal, labels 1–3 + tipo PT.
 * Sem gameplay: só download/render. Sprite ausente → placeholder. Falha → null.
 */
async function renderStartersCard(starters, {
  filename = 'iniciais.png',
  width = 960,
  height = 540
} = {}) {
  try {
    const list = (Array.isArray(starters) ? starters : []).slice(0, 3);
    if (!list.length) return null;

    const colW = Math.floor(width / list.length);
    const panelTop = 56;
    const panelH = height - panelTop - 16;
    const spriteMax = 220;
    const spriteTop = panelTop + 56;
    const labelY = panelTop + panelH - 78;
    const typeY = panelTop + panelH - 40;
    const numY = panelTop + 32;

    const rendered = await Promise.all(list.map(async (s, i) => {
      const poke = {
        nome: s.nome || s.species || `Opção ${i + 1}`,
        imagem: s.imagem,
        tipos: s.tipos,
        altura: 0.7
      };
      const { buffer, tamanho } = await spriteProporcional(poke, spriteMax);
      return { buffer, tamanho, i };
    }));

    const panels = list.map((s, i) => {
      const idx = s.index != null ? s.index : i + 1;
      const nome = escapeXml(s.nome || s.species || `Opção ${idx}`);
      const tipos = escapeXml(tiposPt(s.tipos));
      const cx = i * colW + colW / 2;
      return `<g>`
        + `<rect x="${i * colW + 14}" y="${panelTop}" width="${colW - 28}" height="${panelH}" `
        + `rx="28" fill="#1e293b" stroke="#334155" stroke-width="3"/>`
        + `<circle cx="${cx}" cy="${numY}" r="22" fill="#fbbf24" stroke="#92400e" stroke-width="3"/>`
        + `<text x="${cx}" y="${numY + 8}" text-anchor="middle" font-family="sans-serif" `
        + `font-size="26" font-weight="700" fill="#111827">${idx}</text>`
        + `<text x="${cx}" y="${labelY}" text-anchor="middle" font-family="sans-serif" `
        + `font-size="28" font-weight="700" fill="#f8fafc">${nome}</text>`
        + `<text x="${cx}" y="${typeY}" text-anchor="middle" font-family="sans-serif" `
        + `font-size="20" fill="#94a3b8">${tipos}</text>`
        + `</g>`;
    }).join('');

    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">`
      + `<rect width="${width}" height="${height}" fill="#0f172a"/>`
      + `<text x="${width / 2}" y="36" text-anchor="middle" font-family="sans-serif" `
      + `font-size="22" font-weight="700" fill="#e2e8f0">Escolha seu Pokémon inicial</text>`
      + panels
      + `</svg>`;

    const layers = rendered.map(({ buffer, tamanho, i }) => ({
      input: buffer,
      left: i * colW + Math.floor((colW - tamanho) / 2),
      top: spriteTop + Math.floor((spriteMax - tamanho) / 2)
    }));

    const buf = await sharp(Buffer.from(svg))
      .composite(layers)
      .png()
      .toBuffer();

    return {
      base64: buf.toString('base64'),
      mimeType: 'image/png',
      filename
    };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-jvm-media] starters:', err.message || err);
    return null;
  }
}

function efeitoDeTurno(data) {
  const la = (data && data.battle && data.battle.lastAction) || (data && data.lastAction);
  if (!la || !la.dano) return null;
  const tipos = (data.battle && data.battle.pokemonX && data.battle.pokemonX.tipos) || [];
  return {
    tipo: (Array.isArray(tipos) && tipos[0]) || 'normal',
    origem: 'x',
    dano: Number(la.dano) || 0
  };
}

const CORES_TIPO_TIME = {
  fire: '#ef5350', water: '#42a5f5', grass: '#66bb6a', electric: '#fbc02d',
  psychic: '#ec407a', ice: '#4dd0e1', dragon: '#7e57c2', dark: '#5c6b73',
  fairy: '#f48fb1', fighting: '#e53935', poison: '#ab47bc', ground: '#a1887f',
  rock: '#bcae66', bug: '#9ccc65', ghost: '#7e57c2', steel: '#90a4ae',
  flying: '#7986cb', normal: '#b0bec5'
};

/**
 * Grade do time (até 12) — espelha criar-cartao-time da master.
 * Índices exibidos = collectionIndex+1 (mesmos de !pk escolher).
 */
async function renderTeamGrid(pokemons, {
  filename = 'meu-time-pokemon.png',
  trainerLevel = 1,
  ativoIndex = 0,
  title = 'Seu time Pokémon'
} = {}) {
  try {
    const list = Array.isArray(pokemons) ? pokemons.slice(0, 12) : [];
    if (!list.length) return null;
    const largura = 1000;
    const altura = 120 + (Math.ceil(list.length / 2) * 205) + 20;
    const cards = list.map((p, idx) => {
      const coluna = idx % 2;
      const linha = Math.floor(idx / 2);
      const x = 20 + coluna * 480;
      const y = 120 + linha * 205;
      const tipos = p.tipos || [];
      const cor = CORES_TIPO_TIME[tipos[0]] || '#78909c';
      const hpMax = Math.max(1, Number(p.hp) || 1);
      const hpAtual = Math.max(0, Number(p.hpAtual != null ? p.hpAtual : hpMax) || 0);
      const hpLargura = Math.round(270 * Math.min(1, hpAtual / hpMax));
      const numero = (p.collectionIndex != null ? p.collectionIndex : idx) + 1;
      const ativo = Number(p.collectionIndex) === Number(ativoIndex);
      const nome = escapeXml(String(p.nome || p.species || '?').slice(0, 20));
      const tiposTxt = escapeXml(tiposPt(tipos));
      const rar = escapeXml(p.raridade || 'comum');
      return `<g>`
        + `<rect x="${x}" y="${y}" width="460" height="185" rx="18" fill="#172033" `
        + `stroke="${ativo ? '#facc15' : '#2d3b55'}" stroke-width="${ativo ? 5 : 2}"/>`
        + `<rect x="${x}" y="${y}" width="12" height="185" rx="6" fill="${cor}"/>`
        + (ativo
          ? `<text x="${x + 28}" y="${y + 28}" font-size="16" font-family="Arial,sans-serif" `
            + `font-weight="bold" fill="#facc15">ATIVO</text>`
          : '')
        + `<text x="${x + 430}" y="${y + 38}" font-size="24" font-family="Arial,sans-serif" `
        + `font-weight="bold" fill="#94a3b8" text-anchor="end">#${numero}</text>`
        + `<text x="${x + 165}" y="${y + 58}" font-size="27" font-family="Arial,sans-serif" `
        + `font-weight="bold" fill="#f8fafc">${nome}</text>`
        + `<text x="${x + 165}" y="${y + 88}" font-size="18" font-family="Arial,sans-serif" `
        + `fill="#cbd5e1">Nv. ${p.nivel || 1} • ${tiposTxt}</text>`
        + `<text x="${x + 165}" y="${y + 118}" font-size="17" font-family="Arial,sans-serif" `
        + `fill="#e2e8f0">HP ${hpAtual}/${hpMax}</text>`
        + `<rect x="${x + 165}" y="${y + 133}" width="270" height="18" rx="9" fill="#334155"/>`
        + `<rect x="${x + 165}" y="${y + 133}" width="${hpLargura}" height="18" rx="9" `
        + `fill="${(hpAtual / hpMax) > 0.3 ? '#4ade80' : '#f87171'}"/>`
        + `<text x="${x + 165}" y="${y + 174}" font-size="15" font-family="Arial,sans-serif" `
        + `fill="#cbd5e1">${rar}</text>`
        + `</g>`;
    }).join('');

    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${largura}" height="${altura}">`
      + `<rect width="100%" height="100%" fill="#0f172a"/>`
      + `<rect width="100%" height="100" fill="#1e293b"/>`
      + `<text x="35" y="45" font-size="32" font-family="Arial,sans-serif" font-weight="bold" `
      + `fill="#f8fafc">${escapeXml(title)}</text>`
      + `<text x="35" y="76" font-size="18" font-family="Arial,sans-serif" fill="#cbd5e1">`
      + `Nível de treinador: ${trainerLevel} • ${list.length} Pokémon</text>`
      + cards
      + `</svg>`;

    const sprites = await Promise.all(list.map(async (p) => {
      try {
        const { buffer } = await spriteProporcional(p, 145);
        return buffer;
      } catch (_) {
        return null;
      }
    }));

    const layers = sprites
      .map((buf, idx) => {
        if (!buf) return null;
        return {
          input: buf,
          left: 28 + ((idx % 2) * 480),
          top: 140 + (Math.floor(idx / 2) * 205)
        };
      })
      .filter(Boolean);

    const out = await sharp(Buffer.from(svg))
      .composite(layers)
      .png()
      .toBuffer();

    return {
      base64: out.toString('base64'),
      mimeType: 'image/png',
      filename
    };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-jvm-media] team-grid:', err.message || err);
    return null;
  }
}

module.exports = {
  renderHuntArena,
  renderCaptureBall,
  renderSpriteCard,
  renderStartersCard,
  renderTeamGrid,
  efeitoDeTurno,
  tamanhoVisual,
  idCenario,
  svgArenaCacada,
  svgBolaCaptura,
  candidatosUrl,
  baixarBuffer,
  tiposPt
};
