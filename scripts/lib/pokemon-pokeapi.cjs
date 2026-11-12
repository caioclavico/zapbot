'use strict';

/**
 * Cliente PokeAPI para caça JVM (M4.5).
 * Atk/def/spa/spd/spe: suavizados (legado) para seleção por poder.
 * HP oficial bruto vai em `hp-base` / `hpBase`; a JVM aplica species-hp-v1
 * como fonte canônica de HP de combate/coleção (não depender do HP suavizado).
 */

const MEDIA = 75;
const FATOR = 0.5;
const TOTAL = 1025;
const FATOR_CRESCIMENTO = 1.03;
const GOLPE_PADRAO = {
  slug: 'tackle',
  nomeExibicao: 'Investida',
  tipo: 'normal',
  poder: 40,
  classe: 'fisico'
};

/** Pedras de evolução — espelha zapbot.pokemon.aventuras/pedras (master). */
const PEDRAS = {
  'pedra-agua': {
    nome: 'Pedra da Água', emoji: '💧',
    evolucoes: {
      poliwhirl: 'poliwrath', shellder: 'cloyster', staryu: 'starmie',
      eevee: 'vaporeon', lombre: 'ludicolo', panpour: 'simipour'
    }
  },
  'pedra-trovao': {
    nome: 'Pedra do Trovão', emoji: '⚡',
    evolucoes: {
      pikachu: 'raichu', eevee: 'jolteon', eelektrik: 'eelektross',
      charjabug: 'vikavolt', tadbulb: 'bellibolt'
    }
  },
  'pedra-fogo': {
    nome: 'Pedra do Fogo', emoji: '🔥',
    evolucoes: {
      vulpix: 'ninetales', growlithe: 'arcanine', eevee: 'flareon',
      pansear: 'simisear', capsakid: 'scovillain'
    }
  },
  'pedra-folha': {
    nome: 'Pedra da Folha', emoji: '🍃',
    evolucoes: {
      gloom: 'vileplume', weepinbell: 'victreebel', exeggcute: 'exeggutor',
      nuzleaf: 'shiftry', pansage: 'simisage', eevee: 'leafeon'
    }
  },
  'pedra-solar': {
    nome: 'Pedra Solar', emoji: '☀️',
    evolucoes: {
      gloom: 'bellossom', sunkern: 'sunflora', cottonee: 'whimsicott',
      petilil: 'lilligant', helioptile: 'heliolisk'
    }
  },
  'pedra-lua': {
    nome: 'Pedra da Lua', emoji: '🌙',
    evolucoes: {
      nidorina: 'nidoqueen', nidorino: 'nidoking', clefairy: 'clefable',
      jigglypuff: 'wigglytuff', skitty: 'delcatty', munna: 'musharna'
    }
  }
};

function normalizarItem(raw) {
  return String(raw || '')
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\s+/g, '-');
}

function destinoPedra(itemId, speciesSlug) {
  const pedra = PEDRAS[normalizarItem(itemId)];
  if (!pedra) return null;
  const slug = String(speciesSlug || '').toLowerCase();
  return pedra.evolucoes[slug] || null;
}

function pedrasCompativeis(speciesSlug) {
  const slug = String(speciesSlug || '').toLowerCase();
  return Object.keys(PEDRAS).filter(id => PEDRAS[id].evolucoes[slug]);
}

function escalarNivel(pokemon, nivel) {
  const n = Math.max(1, Number(nivel) || 1);
  const fator = Math.pow(FATOR_CRESCIMENTO, n - 1);
  const scale = (v) => Math.round((Number(v) || 0) * fator);
  return {
    ...pokemon,
    nivel: n,
    hp: scale(pokemon.hp),
    ataque: scale(pokemon.ataque),
    defesa: scale(pokemon.defesa),
    atqEsp: scale(pokemon.atqEsp != null ? pokemon.atqEsp : pokemon['atq-esp']),
    defEsp: scale(pokemon.defEsp != null ? pokemon.defEsp : pokemon['def-esp']),
    veloc: scale(pokemon.veloc)
  };
}

/** Dados para POST /v1/pokemon/evolve (HP final é canônico na JVM). */
function dadosEvolucao(evoluido) {
  const hpBase = evoluido.hpBase != null
    ? evoluido.hpBase
    : (evoluido['hp-base'] != null ? evoluido['hp-base'] : null);
  const out = {
    nomeNovo: evoluido.nome,
    imagem: evoluido.imagem || null,
    imagemShiny: evoluido.imagemShiny || evoluido['imagem-shiny'] || null,
    tipos: evoluido.tipos || [],
    habilidade: evoluido.habilidade || null,
    hp: evoluido.hp,
    ataque: evoluido.ataque,
    defesa: evoluido.defesa,
    atqEsp: evoluido.atqEsp != null ? evoluido.atqEsp : evoluido['atq-esp'],
    defEsp: evoluido.defEsp != null ? evoluido.defEsp : evoluido['def-esp'],
    veloc: evoluido.veloc
  };
  if (Number.isFinite(Number(hpBase)) && Number(hpBase) > 0) {
    out.hpBase = Number(hpBase);
    out['hp-base'] = Number(hpBase);
  }
  return out;
}

function suavizarStat(valor) {
  return Math.round(MEDIA + FATOR * ((Number(valor) || 0) - MEDIA));
}

function statBase(data, name) {
  const row = (data.stats || []).find(s => s.stat && s.stat.name === name);
  return row ? row.base_stat : 0;
}

function nivelAprendizado(movimento) {
  const levels = (movimento.version_group_details || [])
    .filter(d => d.move_learn_method && d.move_learn_method.name === 'level-up')
    .map(d => d.level_learned_at)
    .filter(n => n != null)
    .sort((a, b) => a - b);
  return levels.length ? levels[0] : null;
}

async function fetchJson(url, { timeoutMs = 15000 } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) {
      const err = new Error(`PokeAPI HTTP ${res.status}`);
      err.code = 'POKEAPI_HTTP';
      err.status = res.status;
      throw err;
    }
    return res.json();
  } finally {
    clearTimeout(t);
  }
}

async function carregarGolpe(nome) {
  try {
    const d = await fetchJson(`https://pokeapi.co/api/v2/move/${nome}`);
    const classe = d.damage_class && d.damage_class.name;
    const base = {
      slug: d.name,
      nomeExibicao: (d.names || []).find(n => n.language && n.language.name === 'pt-BR')?.name
        || d.name.split('-').map(p => p[0].toUpperCase() + p.slice(1)).join(' '),
      tipo: d.type && d.type.name,
      precisao: d.accuracy
    };
    if (d.name === 'transform') {
      return { ...base, poder: 0, classe: 'transform' };
    }
    if (d.power && (classe === 'physical' || classe === 'special')) {
      const meta = d.meta || {};
      const drain = Number(meta.drain || 0);
      const effectEntries = d.effect_entries || [];
      const effectText = effectEntries
        .filter(e => e.language && e.language.name === 'en')
        .map(e => (e.short_effect || e.effect || '').toLowerCase())
        .join(' ');
      const autodesmaio = /user faints/.test(effectText)
        || d.name === 'explosion'
        || d.name === 'self-destruct'
        || d.name === 'misty-explosion';
      const out = {
        ...base,
        poder: d.power,
        classe: classe === 'physical' ? 'fisico' : 'especial'
      };
      if (drain < 0) out.recuo = Math.abs(drain);
      if (drain > 0) out.dreno = drain;
      if (autodesmaio) out.autodesmaio = true;
      return out;
    }
    if (classe === 'status') {
      return { ...base, poder: 0, classe: 'status' };
    }
    return null;
  } catch (_) {
    return null;
  }
}

async function escolherGolpes(movesBrutos, tipos, nivel = 1) {
  const aprendidos = (movesBrutos || []).filter(m => {
    const n = nivelAprendizado(m);
    return n != null && n <= nivel;
  });
  const nomes = [...new Set(aprendidos.map(m => m.move && m.move.name).filter(Boolean))];
  const loaded = (await Promise.all(nomes.map(carregarGolpe))).filter(Boolean);
  const tiposSet = new Set(tipos || []);
  const byPower = (a, b) => (b.poder || 0) - (a.poder || 0);
  const proprio = loaded.filter(g => tiposSet.has(g.tipo)).sort(byPower);
  const normal = loaded.filter(g => g.tipo === 'normal').sort(byPower);
  const cobertura = loaded
    .filter(g => !tiposSet.has(g.tipo) && g.tipo !== 'normal')
    .sort(byPower);
  const ordenados = [...proprio, ...normal, ...cobertura];
  const ofensivos = ordenados.filter(g => g.classe === 'fisico' || g.classe === 'especial');
  const status = ordenados.filter(g => g.classe === 'status');
  const base = [...ofensivos.slice(0, 3), ...status.slice(0, 1)];
  const escolhidos = [];
  const seen = new Set();
  for (const g of [...base, ...ordenados]) {
    if (!g || seen.has(g.slug)) continue;
    seen.add(g.slug);
    escolhidos.push(g);
    if (escolhidos.length >= 4) break;
  }
  return escolhidos.length ? escolhidos : [GOLPE_PADRAO];
}

function pokemonDeDados(data) {
  const tipos = (data.types || []).map(t => t.type.name);
  const hpOfficial = statBase(data, 'hp');
  return {
    nome: data.name ? data.name[0].toUpperCase() + data.name.slice(1) : '?',
    species: data.species && data.species.name,
    slugEspecie: data.species && data.species.name,
    imagemShiny: data.sprites?.other?.['official-artwork']?.front_shiny
      || data.sprites?.front_shiny || null,
    imagem: data.sprites?.other?.['official-artwork']?.front_default
      || data.sprites?.front_default || null,
    // PokeAPI height em decímetros → metros (legado core/altura)
    altura: data.height != null ? data.height / 10.0 : 1.0,
    tipos,
    habilidade: (data.abilities || []).find(a => !a.is_hidden)?.ability?.name
      || data.abilities?.[0]?.ability?.name || null,
    movesBrutos: data.moves || [],
    // HP suavizado só para poder-total/seleção; JVM sobrescreve via hp-base.
    hp: suavizarStat(hpOfficial),
    hpBase: hpOfficial,
    'hp-base': hpOfficial,
    ataque: suavizarStat(statBase(data, 'attack')),
    defesa: suavizarStat(statBase(data, 'defense')),
    atqEsp: suavizarStat(statBase(data, 'special-attack')),
    defEsp: suavizarStat(statBase(data, 'special-defense')),
    veloc: suavizarStat(statBase(data, 'speed')),
    nivel: 1
  };
}

async function enrichSpecies(pokemon) {
  const slug = pokemon.slugEspecie || pokemon.species || String(pokemon.nome || '').toLowerCase();
  try {
    const sp = await fetchJson(`https://pokeapi.co/api/v2/pokemon-species/${slug}`);
    const paradox = (sp.genera || []).some(g =>
      g.language && g.language.name === 'en'
      && String(g.genus || '').toLowerCase().includes('paradox')
    );
    return {
      ...pokemon,
      lendarioApi: Boolean(sp.is_legendary),
      miticoApi: Boolean(sp.is_mythical),
      paradoxApi: Boolean(paradox),
      taxaCaptura: sp.capture_rate
    };
  } catch (_) {
    return pokemon;
  }
}

async function finalizeWild(pokemon) {
  const withSp = await enrichSpecies(pokemon);
  const golpes = await escolherGolpes(withSp.movesBrutos, withSp.tipos, withSp.nivel || 1);
  const { movesBrutos, ...rest } = withSp;
  // Não serializar null: JVM (long/double nil) → INTERNAL_ERROR.
  // taxa-captura ausente é opcional (raridade cai no fallback por poder).
  const out = {
    ...rest,
    golpes,
    'atq-esp': rest.atqEsp,
    'def-esp': rest.defEsp,
    'slug-especie': rest.slugEspecie,
    'imagem-shiny': rest.imagemShiny
  };
  if (rest.lendarioApi != null) out['lendario-api?'] = Boolean(rest.lendarioApi);
  if (rest.miticoApi != null) out['mitico-api?'] = Boolean(rest.miticoApi);
  if (rest.paradoxApi != null) out['paradox-api?'] = Boolean(rest.paradoxApi);
  if (Number.isFinite(Number(rest.taxaCaptura))) {
    out['taxa-captura'] = Number(rest.taxaCaptura);
  }
  delete out.taxaCaptura;
  delete out.lendarioApi;
  delete out.miticoApi;
  delete out.paradoxApi;
  return out;
}

async function fetchPokemonById(id) {
  const data = await fetchJson(`https://pokeapi.co/api/v2/pokemon/${id}`);
  return finalizeWild(pokemonDeDados(data));
}

async function fetchPokemonByName(name) {
  const data = await fetchJson(`https://pokeapi.co/api/v2/pokemon/${String(name).toLowerCase()}`);
  return finalizeWild(pokemonDeDados(data));
}

/**
 * Monta candidatos para o plano JVM.
 * Surto: uma espécie do evento. Senão: até `tentativasMax` ids aleatórios.
 */
async function buildHuntCandidates(plan, { random = Math.random } = {}) {
  if (plan.surto && plan.evento && Array.isArray(plan.evento.especies)) {
    const list = plan.evento.especies;
    const nome = list[Math.floor(random() * list.length)];
    return [await fetchPokemonByName(nome)];
  }
  const max = Math.min(Number(plan.tentativasMax) || 25, 25);
  const out = [];
  const seen = new Set();
  for (let i = 0; i < max; i++) {
    let id;
    do {
      id = 1 + Math.floor(random() * TOTAL);
    } while (seen.has(id));
    seen.add(id);
    try {
      out.push(await fetchPokemonById(id));
    } catch (err) {
      if (err.code === 'POKEAPI_HTTP' && err.status === 404) continue;
      throw err;
    }
  }
  if (!out.length) {
    const err = new Error('PokeAPI não retornou candidatos.');
    err.code = 'POKEAPI_EMPTY';
    throw err;
  }
  return out;
}

async function fetchImageBase64(url) {
  if (!url || typeof url !== 'string') return null;
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (!res.ok) return null;
    const buf = Buffer.from(await res.arrayBuffer());
    const mime = res.headers.get('content-type') || 'image/png';
    return { base64: buf.toString('base64'), mimeType: mime.split(';')[0] };
  } catch (_) {
    return null;
  }
}

/**
 * Time NPC do ginásio (slugs do catálogo). Escalação de nível no JVM.
 */
async function buildGymNpcTeam(speciesNames) {
  const names = Array.isArray(speciesNames) ? speciesNames : [];
  if (names.length !== 3) {
    const err = new Error('Time NPC de ginásio precisa de 3 espécies.');
    err.code = 'INVALID_GYM_NPC_TEAM';
    throw err;
  }
  return Promise.all(names.map((n) => fetchPokemonByName(n)));
}

function slugGolpe(g) {
  if (!g) return '';
  return String(g.slug || g['slug'] || g.nomeExibicao || g['nome-exibicao'] || g.nome || '')
    .trim()
    .toLowerCase();
}

function ataqueDoTipo(golpe, tipos) {
  const tipo = golpe && golpe.tipo;
  const classe = golpe && golpe.classe;
  const poder = Number(golpe && golpe.poder) || 0;
  return Boolean(tipo)
    && (tipos || []).includes(tipo)
    && (classe === 'fisico' || classe === 'especial')
    && poder > 0;
}

/** Catálogo versionado compartilhado com a JVM (sem HTTP em runtime). */
let _mtCatalog;
function mtLearnsetCatalog() {
  if (_mtCatalog) return _mtCatalog;
  // eslint-disable-next-line global-require
  _mtCatalog = require('./mt-learnset-v1.json');
  return _mtCatalog;
}

function mtCatalogVersion() {
  return String((mtLearnsetCatalog() || {}).version || 'mt-learnset-v1-2026-11');
}

/**
 * Candidatos de MT genérica a partir do catálogo local (mesmo da JVM).
 * Learnset level-up ofensivo; sem PokeAPI; sem regras de Pokémon GO.
 */
function listarCandidatosMt(speciesSlug, {
  nivel = 1,
  tipos = [],
  golpesAtuais = [],
  slot = null
} = {}) {
  const slug = String(speciesSlug || '').toLowerCase().trim();
  if (!slug) return [];
  const catalog = mtLearnsetCatalog();
  const entries = (catalog.species && catalog.species[slug]) || [];
  if (!entries.length) {
    const err = new Error(`Learnset MT indisponível para ${slug}`);
    err.code = 'MT_LEARNSET_UNAVAILABLE';
    err.species = slug;
    throw err;
  }
  const nivelMax = Math.max(1, Number(nivel) || 1);
  const moves = catalog.moves || {};
  const conhecidos = new Set((golpesAtuais || []).map(slugGolpe).filter(Boolean));
  const tiposArr = Array.isArray(tipos) ? tipos.map(String) : [];
  const slotN = slot == null ? null : Number(slot);
  const loaded = [];
  for (const e of entries) {
    const n = Number(e['nivel-min'] != null ? e['nivel-min'] : e.nivelMin);
    if (!(n <= nivelMax)) continue;
    const moveSlug = e.slug;
    if (!moveSlug || conhecidos.has(String(moveSlug).toLowerCase())) continue;
    const m = moves[moveSlug];
    if (!m) continue;
    loaded.push({
      slug: m.slug || moveSlug,
      nomeExibicao: m.nomeExibicao || m['nome-exibicao'] || moveSlug,
      'nome-exibicao': m['nome-exibicao'] || m.nomeExibicao || moveSlug,
      tipo: m.tipo,
      poder: m.poder,
      classe: m.classe
    });
  }
  return loaded.filter((g) => {
    if (!(g.classe === 'fisico' || g.classe === 'especial')) return false;
    if (!(Number(g.poder) > 0)) return false;
    if (slotN == null || !Number.isInteger(slotN)) return true;
    const atuais = (golpesAtuais || []).map((x) => ({
      slug: slugGolpe(x),
      tipo: x.tipo || x['tipo'],
      classe: x.classe || x['classe'],
      poder: Number(x.poder != null ? x.poder : x['poder']) || 0
    }));
    if (slotN < 0 || slotN >= atuais.length) return false;
    const next = atuais.slice();
    next[slotN] = g;
    return next.some((gg) => ataqueDoTipo(gg, tiposArr));
  });
}

/**
 * RNG canônico (SHA-256 do seed) — mesmo seed ⇒ mesmo índice.
 */
function escolherGolpeMtAleatorio(candidatos, seed) {
  const list = Array.isArray(candidatos) ? candidatos.filter(Boolean) : [];
  if (!list.length) return null;
  const crypto = require('node:crypto');
  const digest = crypto.createHash('sha256').update(String(seed || ''), 'utf8').digest();
  const n = digest.readUInt32BE(0);
  return list[n % list.length];
}

module.exports = {
  TOTAL,
  PEDRAS,
  FATOR_CRESCIMENTO,
  suavizarStat,
  normalizarItem,
  destinoPedra,
  pedrasCompativeis,
  escalarNivel,
  dadosEvolucao,
  fetchPokemonById,
  fetchPokemonByName,
  buildHuntCandidates,
  buildGymNpcTeam,
  fetchImageBase64,
  pokemonDeDados,
  finalizeWild,
  carregarGolpe,
  nivelAprendizado,
  listarCandidatosMt,
  escolherGolpeMtAleatorio,
  mtLearnsetCatalog,
  mtCatalogVersion,
  slugGolpe,
  ataqueDoTipo
};
