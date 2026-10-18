'use strict';

/**
 * Cliente PokeAPI para caça JVM (M4.5).
 * Stats suavizados e golpes espelham o legado; sem Cassandra.
 */

const MEDIA = 75;
const FATOR = 0.5;
const TOTAL = 1025;
const GOLPE_PADRAO = {
  slug: 'tackle',
  nomeExibicao: 'Investida',
  tipo: 'normal',
  poder: 40,
  classe: 'fisico'
};

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
      return {
        ...base,
        poder: d.power,
        classe: classe === 'physical' ? 'fisico' : 'especial'
      };
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
  return {
    nome: data.name ? data.name[0].toUpperCase() + data.name.slice(1) : '?',
    species: data.species && data.species.name,
    slugEspecie: data.species && data.species.name,
    imagemShiny: data.sprites?.other?.['official-artwork']?.front_shiny
      || data.sprites?.front_shiny || null,
    imagem: data.sprites?.other?.['official-artwork']?.front_default
      || data.sprites?.front_default || null,
    tipos,
    habilidade: (data.abilities || []).find(a => !a.is_hidden)?.ability?.name
      || data.abilities?.[0]?.ability?.name || null,
    movesBrutos: data.moves || [],
    hp: suavizarStat(statBase(data, 'hp')),
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
  return {
    ...rest,
    golpes,
    // chaves kebab para a bridge JVM
    'atq-esp': rest.atqEsp,
    'def-esp': rest.defEsp,
    'slug-especie': rest.slugEspecie,
    'imagem-shiny': rest.imagemShiny,
    'lendario-api?': rest.lendarioApi,
    'mitico-api?': rest.miticoApi,
    'paradox-api?': rest.paradoxApi,
    'taxa-captura': rest.taxaCaptura
  };
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

module.exports = {
  TOTAL,
  suavizarStat,
  fetchPokemonById,
  fetchPokemonByName,
  buildHuntCandidates,
  fetchImageBase64,
  pokemonDeDados,
  finalizeWild
};
