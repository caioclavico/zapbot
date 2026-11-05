'use strict';

/**
 * Pokédex de espécie (top-level !pokedex / !dex / !pdx).
 * Porta o comportamento de zapbot.pokemon.pokedex da master para o adaptador A6:
 * PokeAPI → cache em memória → legenda pt-BR. Sem estado de treinador/Datomic.
 */

const { PEDRAS } = (() => {
  try {
    const pokeapi = require('./pokemon-pokeapi.cjs');
    return { PEDRAS: pokeapi.PEDRAS || null };
  } catch (_) {
    return { PEDRAS: null };
  }
})();

const TOTAL_POKEMONS = 1025;
const CACHE_MAX = 256;
const cache = new Map();

const TIPOS_PT = {
  normal: 'Normal', fire: 'Fogo', water: 'Água', electric: 'Elétrico',
  grass: 'Planta', ice: 'Gelo', fighting: 'Lutador', poison: 'Venenoso',
  ground: 'Terra', flying: 'Voador', psychic: 'Psíquico', bug: 'Inseto',
  rock: 'Pedra', ghost: 'Fantasma', dragon: 'Dragão', dark: 'Sombrio',
  steel: 'Aço', fairy: 'Fada'
};

const PEDRAS_POKEAPI = {
  'water-stone': 'pedra-agua', 'thunder-stone': 'pedra-trovao',
  'fire-stone': 'pedra-fogo', 'leaf-stone': 'pedra-folha', 'moon-stone': 'pedra-lua',
  'sun-stone': 'pedra-solar'
};

const ITENS_TROCA_POKEAPI = {
  'metal-coat': 'Revestimento Metálico', 'dragon-scale': 'Escama de Dragão',
  'up-grade': 'Upgrade', protector: 'Protetor', 'kings-rock': 'Pedra do Rei',
  electirizer: 'Eletrizador', magmarizer: 'Magmarizador',
  'reaper-cloth': 'Tecido do Ceifador', 'prism-scale': 'Escama Prisma',
  'whipped-dream': 'Chicote Doce', sachet: 'Sachê Perfumado'
};

/** Espelha aventuras/pedras da master quando o require do pokeapi não exporta PEDRAS. */
const PEDRAS_FALLBACK = {
  'pedra-agua': {
    nome: 'Pedra da Água',
    evolucoes: {
      poliwhirl: 'poliwrath', shellder: 'cloyster', staryu: 'starmie',
      eevee: 'vaporeon', lombre: 'ludicolo', panpour: 'simipour'
    }
  },
  'pedra-trovao': {
    nome: 'Pedra do Trovão',
    evolucoes: {
      pikachu: 'raichu', eevee: 'jolteon', eelektrik: 'eelektross',
      charjabug: 'vikavolt', tadbulb: 'bellibolt'
    }
  },
  'pedra-fogo': {
    nome: 'Pedra do Fogo',
    evolucoes: {
      vulpix: 'ninetales', growlithe: 'arcanine', eevee: 'flareon',
      pansear: 'simisear', capsakid: 'scovillain'
    }
  },
  'pedra-folha': {
    nome: 'Pedra da Folha',
    evolucoes: {
      gloom: 'vileplume', weepinbell: 'victreebel', exeggcute: 'exeggutor',
      nuzleaf: 'shiftry', pansage: 'simisage', eevee: 'leafeon'
    }
  },
  'pedra-solar': {
    nome: 'Pedra Solar',
    evolucoes: {
      gloom: 'bellossom', sunkern: 'sunflora', cottonee: 'whimsicott',
      petilil: 'lilligant', helioptile: 'heliolisk'
    }
  },
  'pedra-lua': {
    nome: 'Pedra da Lua',
    evolucoes: {
      nidorina: 'nidoqueen', nidorino: 'nidoking', clefairy: 'clefable',
      jigglypuff: 'wigglytuff', skitty: 'delcatty', munna: 'musharna'
    }
  }
};

function pedrasTable() {
  return PEDRAS || PEDRAS_FALLBACK;
}

function removerAcentos(s) {
  return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

function normalizar(entrada) {
  return removerAcentos(String(entrada || '').trim())
    .toLowerCase()
    .replace(/\s+/g, '-');
}

function numeroFormatado(n) {
  const s = String(n);
  return `${'0'.repeat(Math.max(0, 3 - s.length))}${s}`;
}

function nomeFormatado(slug) {
  return String(slug || '')
    .split('-')
    .map((p) => (p ? p[0].toUpperCase() + p.slice(1) : ''))
    .join(' ');
}

function statBase(dados, nomeStat) {
  const row = (dados.stats || []).find((s) => s.stat && s.stat.name === nomeStat);
  return row ? row.base_stat : null;
}

function descricaoEmIngles(especie) {
  const entrada = (especie.flavor_text_entries || [])
    .find((e) => e.language && e.language.name === 'en');
  if (!entrada) return null;
  return String(entrada.flavor_text || '').replace(/\s+/g, ' ').trim();
}

function nomeHabilidade(a) {
  const nome = String((a.ability && a.ability.name) || '').replace(/-/g, ' ');
  return a.is_hidden ? `${nome} (hidden)` : nome;
}

function formatarTipos(tipos) {
  return (tipos || [])
    .map((t) => TIPOS_PT[t] || (t ? t[0].toUpperCase() + t.slice(1) : t))
    .join('/');
}

async function buscarJson(url) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 15000);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    // 404 (e outros 4xx) = espécie inexistente → null (não é falha de rede).
    if (res.status === 404) return null;
    if (!res.ok) {
      const err = new Error(`PokeAPI HTTP ${res.status}`);
      err.status = res.status;
      throw err;
    }
    return res.json();
  } finally {
    clearTimeout(t);
  }
}

function nomeRecurso(detalhe, chave) {
  const nome = detalhe && detalhe[chave] && detalhe[chave].name;
  return nome ? nomeFormatado(nome) : null;
}

function condicaoEspecial(d) {
  return Boolean(
    d.known_move || d.known_move_type || d.location || d.party_species
    || d.party_type || d.relative_physical_stats || d.min_beauty
    || d.near_special_rock || d.needs_overworld_rain || d.needs_multiplayer
    || d.turn_upside_down || d.used_move || d.min_steps || d.min_damage_taken
    || d.gender
  );
}

function descreverDetalhe(d) {
  const gatilho = d.trigger && d.trigger.name;
  const horario = d.time_of_day === 'day' ? ' de dia'
    : d.time_of_day === 'night' ? ' à noite' : '';
  if (gatilho === 'trade') {
    const parceiro = nomeRecurso(d, 'trade_species');
    if (parceiro) return `trocar por ${parceiro}`;
    const itemId = d.held_item && d.held_item.name;
    if (itemId) {
      return `troca segurando ${ITENS_TROCA_POKEAPI[itemId] || nomeFormatado(itemId)}`;
    }
    return 'troca entre jogadores';
  }
  if (gatilho === 'level-up' && !condicaoEspecial(d)) {
    let s = '';
    if (d.min_level != null) s += `nível ${d.min_level}`;
    const amizade = d.min_happiness != null ? d.min_happiness : d.min_affection;
    if (amizade != null) {
      s += `${d.min_level != null ? ' + ' : ''}amizade ${amizade}`;
    }
    return `${s}${horario}`;
  }
  if (gatilho === 'use-item') {
    const id = PEDRAS_POKEAPI[d.item && d.item.name];
    if (id) {
      const pedra = pedrasTable()[id];
      return `${(pedra && pedra.nome) || id} (${id})`;
    }
    const item = nomeRecurso(d, 'item');
    return `Catalisador Evolutivo${item ? ` (adapta ${item})` : ''}`;
  }
  return 'Catalisador Evolutivo';
}

function proximasEvolucoes(cadeia, slugAtual) {
  if (!cadeia || !cadeia.chain) return [];
  function achar(no) {
    if ((no.species && no.species.name) === slugAtual) return no;
    for (const filho of (no.evolves_to || [])) {
      const hit = achar(filho);
      if (hit) return hit;
    }
    return null;
  }
  const atual = achar(cadeia.chain);
  if (!atual) return [];
  return (atual.evolves_to || []).map((proximo) => ({
    nome: nomeFormatado(proximo.species && proximo.species.name),
    como: [...new Set((proximo.evolution_details || []).map(descreverDetalhe))].join(' ou ')
  }));
}

function formatarEvolucoes(pokemon) {
  const slug = normalizar(pokemon.nome);
  const daCadeia = (pokemon.evolucoes || []).map((e) => `${e.nome} — ${e.como}`);
  const porPedra = [];
  const table = pedrasTable();
  for (const id of Object.keys(table).sort()) {
    const pedra = table[id];
    const destino = pedra.evolucoes && pedra.evolucoes[slug];
    if (destino) {
      porPedra.push(`${nomeFormatado(destino)} — ${pedra.nome} (${id})`);
    }
  }
  const evolucoes = [...new Set([...daCadeia, ...porPedra])];
  return evolucoes.length ? evolucoes.join(' | ') : 'forma final — não evolui';
}

async function traduzir(texto, origem = 'en', destino = 'pt') {
  if (!texto) return '';
  const url = `https://translate.googleapis.com/translate_a/single`
    + `?client=gtx&dt=t&sl=${origem}&tl=${destino}`
    + `&q=${encodeURIComponent(texto)}`;
  try {
    const res = await fetch(url);
    if (!res.ok) return texto;
    const data = await res.json();
    return (data[0] || []).map((row) => row[0]).join('') || texto;
  } catch (_) {
    return texto;
  }
}

async function buscarDados(entrada) {
  const slug = normalizar(entrada);
  const dados = await buscarJson(`https://pokeapi.co/api/v2/pokemon/${slug}`);
  if (!dados) return null;
  const especie = await buscarJson(`https://pokeapi.co/api/v2/pokemon-species/${slug}`);
  let cadeia = null;
  if (especie && especie.evolution_chain && especie.evolution_chain.url) {
    cadeia = await buscarJson(especie.evolution_chain.url);
  }
  const artwork = dados.sprites
    && dados.sprites.other
    && dados.sprites.other['official-artwork']
    && dados.sprites.other['official-artwork'].front_default;
  return {
    numero: dados.id,
    nome: nomeFormatado(dados.name),
    imagem: artwork || (dados.sprites && dados.sprites.front_default) || null,
    tipos: (dados.types || []).map((t) => t.type && t.type.name).filter(Boolean),
    altura: (dados.height || 0) / 10.0,
    peso: (dados.weight || 0) / 10.0,
    'habilidades-en': (dados.abilities || []).map(nomeHabilidade).join(', '),
    hp: statBase(dados, 'hp'),
    ataque: statBase(dados, 'attack'),
    defesa: statBase(dados, 'defense'),
    'atq-esp': statBase(dados, 'special-attack'),
    'def-esp': statBase(dados, 'special-defense'),
    veloc: statBase(dados, 'speed'),
    evolucoes: proximasEvolucoes(cadeia, dados.name),
    'versao-evolucoes': 2,
    'descricao-en': descricaoEmIngles(especie || {})
  };
}

function cachePut(chave, pokemon) {
  if (cache.size >= CACHE_MAX) {
    const first = cache.keys().next().value;
    cache.delete(first);
  }
  cache.set(chave, pokemon);
  if (pokemon && pokemon.nome) {
    cache.set(normalizar(pokemon.nome), pokemon);
  }
  return pokemon;
}

/**
 * Resolve espécie enriquecida (textos pt). null = não encontrado.
 * Rejeita só em falha de rede/API inesperada (quem chama decide a mensagem).
 */
async function dadosEspecie(entrada) {
  const chave = normalizar(entrada);
  const cached = cache.get(chave);
  if (cached && cached['versao-evolucoes'] === 2
      && cached['descricao-pt'] != null
      && cached['habilidades-pt'] != null) {
    return cached;
  }
  const pokemon = await buscarDados(entrada);
  if (!pokemon) return null;
  const descricaoPt = pokemon['descricao-en']
    ? await traduzir(pokemon['descricao-en'], 'en', 'pt')
    : '';
  const habilidadesPt = await traduzir(pokemon['habilidades-en'], 'en', 'pt');
  return cachePut(chave, {
    ...pokemon,
    'descricao-pt': descricaoPt,
    'habilidades-pt': habilidadesPt
  });
}

function cabecalho(botName) {
  return `📖 *Pokédex do tio ${botName || 'Zapbot'}*\n\n`;
}

function montarLegenda(pokemon, botName) {
  const descricaoPt = pokemon['descricao-pt'] || '';
  const habilidadesPt = pokemon['habilidades-pt'] || pokemon['habilidades-en'] || '';
  const altura = Number(pokemon.altura || 0).toFixed(1);
  const peso = Number(pokemon.peso || 0).toFixed(1);
  let texto = `${cabecalho(botName)}`
    + `#${numeroFormatado(pokemon.numero)} *${pokemon.nome}*\n`
    + `🏷️ Tipo: ${formatarTipos(pokemon.tipos)}\n`
    + `📏 Altura: ${altura} m | ⚖️ Peso: ${peso} kg\n`
    + `✨ Habilidades: ${habilidadesPt}\n\n`
    + `❤️ HP: ${pokemon.hp} | ⚔️ Ataque: ${pokemon.ataque} | 🛡️ Defesa: ${pokemon.defesa}\n`
    + `🔮 Atq. Especial: ${pokemon['atq-esp']} | 🌀 Def. Especial: ${pokemon['def-esp']}`
    + ` | 💨 Velocidade: ${pokemon.veloc}`
    + `\n🔺 Evolução: ${formatarEvolucoes(pokemon)}`;
  if (descricaoPt && String(descricaoPt).trim()) {
    texto += `\n\n📜 _${descricaoPt}_`;
  }
  return texto;
}

function mensagemNaoEncontrado(entrada, botName) {
  return `${cabecalho(botName)}❓ Não encontrei nenhum Pokémon com "${entrada}"`
    + '. Tente pelo nome (ex.: pikachu) ou número da Pokédex (ex.: 25).';
}

function mensagemErroApi(botName) {
  return `${cabecalho(botName)}❌ Não consegui buscar esse Pokémon agora (PokeAPI fora do ar?). Tente de novo.`;
}

/**
 * Busca por nome/número (ou sorteia se entrada vazia).
 * Retorna { text, pokemon? } — pokemon ausente em erro/não encontrado.
 */
async function lookup(entrada, botName) {
  const raw = String(entrada || '').trim();
  const query = raw || String(1 + Math.floor(Math.random() * TOTAL_POKEMONS));
  try {
    const pokemon = await dadosEspecie(query);
    if (!pokemon) {
      return { text: mensagemNaoEncontrado(query, botName), pokemon: null };
    }
    return { text: montarLegenda(pokemon, botName), pokemon };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[pokemon-pokedex-species] lookup:', err && err.message ? err.message : err);
    return { text: mensagemErroApi(botName), pokemon: null };
  }
}

/** Só para testes. */
function _resetCacheForTests() {
  cache.clear();
}

module.exports = {
  TOTAL_POKEMONS,
  normalizar,
  numeroFormatado,
  formatarTipos,
  formatarEvolucoes,
  montarLegenda,
  cabecalho,
  mensagemNaoEncontrado,
  mensagemErroApi,
  dadosEspecie,
  buscarDados,
  lookup,
  _resetCacheForTests,
  TIPOS_PT
};
