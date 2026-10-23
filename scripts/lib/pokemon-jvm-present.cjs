'use strict';

/**
 * Apresentação Node (M4.3): DTOs JVM → envelope {messages,effects}.
 * Formatação WhatsApp fica fora do domínio JVM.
 */

const PAGE_SIZE = 12;

function envelope(requestId, text, extras = {}) {
  const messages = [];
  if (extras.document && (extras.document.mediaId || extras.document.base64)) {
    messages.push({
      type: 'document',
      text: text || '',
      mediaId: extras.document.mediaId || null,
      base64: extras.document.base64 || null,
      mimeType: extras.document.mimeType || 'text/csv',
      filename: extras.document.filename || 'pokemon.csv'
    });
  } else if (extras.image && (extras.image.mediaId || extras.image.base64)) {
    messages.push({
      type: 'image',
      text: extras.caption || text,
      mediaId: extras.image.mediaId || null,
      base64: extras.image.base64 || null,
      mimeType: extras.image.mimeType || 'image/png',
      filename: extras.image.filename || 'pokemon-jvm.png'
    });
    if (text && extras.caption && text !== extras.caption && text.length > 900) {
      messages.push({ type: 'text', text });
    }
  } else if (text) {
    messages.push({ type: 'text', text });
  }
  return { requestId, messages, effects: extras.effects || [] };
}

function nomePokemon(p) {
  return (p && (p.nome || p.nickname || p.species)) || '?';
}

function shinyMark(p) {
  return p && p.shiny ? '✨ ' : '';
}

function barraHp(atual, max) {
  const a = Math.max(0, Number(atual) || 0);
  const m = Math.max(1, Number(max) || 1);
  const filled = Math.min(10, Math.round((a / m) * 10));
  return `[${'█'.repeat(filled)}${'░'.repeat(10 - filled)}] ${a}/${m}`;
}

/** HP atual/máx a partir do DTO — null se dados incompletos (não inventa). */
function hpPair(p, sideHp) {
  if (!p) return null;
  const max = Number(p.hp);
  const atual = Number(p.hpAtual != null ? p.hpAtual : sideHp);
  if (!Number.isFinite(max) || max <= 0) return null;
  if (!Number.isFinite(atual)) return null;
  return { atual: Math.max(0, Math.floor(atual)), max: Math.floor(max) };
}

const TIPOS_PT = {
  normal: 'Normal', fire: 'Fogo', water: 'Água', electric: 'Elétrico',
  grass: 'Planta', ice: 'Gelo', fighting: 'Lutador', poison: 'Venenoso',
  ground: 'Terra', flying: 'Voador', psychic: 'Psíquico', bug: 'Inseto',
  rock: 'Pedra', ghost: 'Fantasma', dragon: 'Dragão', dark: 'Sombrio',
  steel: 'Aço', fairy: 'Fada'
};

const TABELA_TIPOS = {
  normal: { rock: 0.5, steel: 0.5, ghost: 0 },
  fire: { grass: 2, ice: 2, bug: 2, steel: 2, fire: 0.5, water: 0.5, rock: 0.5, dragon: 0.5 },
  water: { fire: 2, ground: 2, rock: 2, water: 0.5, grass: 0.5, dragon: 0.5 },
  electric: { water: 2, flying: 2, electric: 0.5, grass: 0.5, dragon: 0.5, ground: 0 },
  grass: {
    water: 2, ground: 2, rock: 2, fire: 0.5, grass: 0.5, poison: 0.5,
    flying: 0.5, bug: 0.5, dragon: 0.5, steel: 0.5
  },
  ice: { grass: 2, ground: 2, flying: 2, dragon: 2, fire: 0.5, water: 0.5, ice: 0.5, steel: 0.5 },
  fighting: {
    normal: 2, ice: 2, rock: 2, dark: 2, steel: 2, poison: 0.5, flying: 0.5,
    psychic: 0.5, bug: 0.5, fairy: 0.5, ghost: 0
  },
  poison: { grass: 2, fairy: 2, poison: 0.5, ground: 0.5, rock: 0.5, ghost: 0.5, steel: 0 },
  ground: { fire: 2, electric: 2, poison: 2, rock: 2, steel: 2, grass: 0.5, bug: 0.5, flying: 0 },
  flying: { grass: 2, fighting: 2, bug: 2, electric: 0.5, rock: 0.5, steel: 0.5 },
  psychic: { fighting: 2, poison: 2, psychic: 0.5, steel: 0.5, dark: 0 },
  bug: {
    grass: 2, psychic: 2, dark: 2, fire: 0.5, fighting: 0.5, poison: 0.5,
    flying: 0.5, ghost: 0.5, steel: 0.5, fairy: 0.5
  },
  rock: { fire: 2, ice: 2, flying: 2, bug: 2, fighting: 0.5, ground: 0.5, steel: 0.5 },
  ghost: { psychic: 2, ghost: 2, dark: 0.5, normal: 0 },
  dragon: { dragon: 2, steel: 0.5, fairy: 0 },
  dark: { psychic: 2, ghost: 2, fighting: 0.5, dark: 0.5, fairy: 0.5 },
  steel: { ice: 2, rock: 2, fairy: 2, fire: 0.5, water: 0.5, electric: 0.5, steel: 0.5 },
  fairy: { fighting: 2, dragon: 2, dark: 2, fire: 0.5, poison: 0.5, steel: 0.5 }
};

function multiplicadorVsTipos(tipoAtaque, tiposDefesa, habilidadeDefensor) {
  if (tipoAtaque === 'ground' && habilidadeDefensor === 'levitate') return 0;
  const tipos = Array.isArray(tiposDefesa) ? tiposDefesa : [];
  if (!tipos.length) return 1;
  return tipos.reduce((acc, t) => {
    const row = TABELA_TIPOS[tipoAtaque] || {};
    return acc * (row[t] != null ? row[t] : 1);
  }, 1);
}

function emojiGolpe(golpe) {
  const c = golpe && (golpe.classe || golpe.class);
  if (c === 'fisico' || c === 'físico') return '💥';
  if (c === 'especial') return '🔮';
  if (c === 'status') return '📊';
  if (c === 'transform') return '🧬';
  return '❓';
}

function emojiStatus(status) {
  const s = status && (typeof status === 'string' ? status : status.kind || status.id);
  switch (s) {
    case 'queimado': return ' 🔥';
    case 'envenenado': return ' ☠️';
    case 'paralisado': return ' ⚡';
    case 'adormecido': return ' 💤';
    case 'congelado': return ' 🧊';
    case 'confuso': return ' 💫';
    default: return '';
  }
}

function resumoEfeitosGolpe(golpe) {
  const partes = [];
  const status = golpe.statusCausado || golpe['status-causado'];
  if (status) {
    const chance = golpe.chanceStatus != null ? golpe.chanceStatus : golpe['chance-status'];
    partes.push(`causa ${status}${chance != null && chance < 100 ? ` (${chance}%)` : ''}`);
  }
  const alt = golpe.alteracoes || golpe['alteracoes'] || [];
  if (alt.length) {
    partes.push(alt.map(a => {
      const atr = a.atributo || a.attribute || '?';
      const est = a.estagios != null ? a.estagios : a.stages;
      return `${atr} ${est > 0 ? '+' : ''}${est}`;
    }).join(', '));
  }
  const cura = Number(golpe.cura || 0);
  if (cura > 0) partes.push(`recupera ${cura}% do HP`);
  return partes.length ? partes.join(', ') : 'efeito especial';
}

function linhaGolpe(idx, golpe, tiposDefesa, habilidadeDefensor) {
  const nome = golpe.nomeExibicao || golpe['nome-exibicao'] || golpe.nome || golpe.slug || '?';
  const tipo = golpe.tipo || '?';
  const classe = golpe.classe || golpe.class || 'fisico';
  const especial = classe === 'status' || classe === 'transform';
  const mult = especial ? 1 : multiplicadorVsTipos(tipo, tiposDefesa, habilidadeDefensor);
  let corpo;
  if (classe === 'transform') {
    corpo = 'copia os golpes do rival';
  } else if (classe === 'status') {
    const alvo = golpe.alvo || golpe.target;
    corpo = `${alvo === 'proprio' || alvo === 'próprio' ? 'em si: ' : 'no rival: '}${resumoEfeitosGolpe(golpe)}`;
  } else {
    corpo = `poder ${golpe.poder != null ? golpe.poder : '?'}`;
  }
  const precisao = golpe.precisao != null ? golpe.precisao : golpe.accuracy;
  const prec = precisao != null ? `, precisão ${precisao}%` : '';
  const mark = mult === 0 ? ' 🚫' : (mult > 1 ? ' 🔥' : (mult < 1 ? ' 😕' : ''));
  return `${idx + 1}. ${emojiGolpe(golpe)} *${nome}* (${TIPOS_PT[tipo] || tipo}, ${corpo}${prec})${mark}`;
}

function menuGolpes(pokemon, tiposDefesa, habilidadeDefensor) {
  const gs = (pokemon && pokemon.golpes) || [];
  if (!gs.length) return null;
  return gs.map((g, i) => linhaGolpe(i, g, tiposDefesa || [], habilidadeDefensor)).join('\n');
}

function normalizar(s) {
  return String(s || '')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase();
}

function poder(p) {
  return ['ataque', 'defesa', 'atqEsp', 'defEsp', 'veloc', 'hp']
    .reduce((sum, k) => sum + (Number(p[k]) || 0), 0);
}

const RARIDADE_FILTRO = {
  comum: 'comum', common: 'comum',
  incomum: 'incomum', uncommon: 'incomum',
  raro: 'raro', rare: 'raro',
  epico: 'epico', épico: 'epico', epic: 'epico',
  lendario: 'lendario', lendário: 'lendario', legendary: 'lendario',
  mitico: 'mitico', mítico: 'mitico', mythical: 'mitico'
};

const RARIDADE_EMOJI = {
  comum: '⚪', incomum: '🟢', raro: '🔵', epico: '🟣', lendario: '🟡', mitico: '🔴'
};

function textoXp(p) {
  const atual = Number(p.xpDesdeNivel != null ? p.xpDesdeNivel : (p.xp || 0)) || 0;
  const nec = 9;
  return `XP ${atual}/${nec}`;
}

function interpretarFiltros(tokens) {
  const ordem = tokens.includes('>') ? '>' : tokens.includes('<') ? '<' : null;
  const rest = tokens.filter(t => t !== '>' && t !== '<');
  let nivel = null;
  let nome = '';
  const tipos = [];
  const raridades = [];
  let shiny = false;
  for (let i = 0; i < rest.length; i++) {
    const t = rest[i];
    if (t === 'shiny' || t === '✨') { shiny = true; continue; }
    if ((t === 'nivel' || t === 'nível' || t === 'nv') && rest[i + 1] && /^\d+$/.test(rest[i + 1])) {
      nivel = Number(rest[++i]);
      continue;
    }
    if (/^\d+$/.test(t) && rest[i - 1] && /^(nivel|nível|nv)$/.test(rest[i - 1])) continue;
    if (/^\d+$/.test(t)) continue; // página tratada à parte
    if ((t === 'raridade' || t === 'rar') && rest[i + 1] && RARIDADE_FILTRO[normalizar(rest[i + 1])]) {
      raridades.push(RARIDADE_FILTRO[normalizar(rest[++i])]);
      continue;
    }
    const rar = RARIDADE_FILTRO[normalizar(t)];
    if (rar) { raridades.push(rar); continue; }
    // tipos comuns pt/en
    const tipoAliases = {
      fogo: 'fire', fire: 'fire', agua: 'water', água: 'water', water: 'water',
      grama: 'grass', grass: 'grass', eletrico: 'electric', elétrico: 'electric',
      electric: 'electric', pedra: 'rock', rock: 'rock', terra: 'ground', ground: 'ground',
      voador: 'flying', flying: 'flying', psiquico: 'psychic', psíquico: 'psychic',
      psychic: 'psychic', gelo: 'ice', ice: 'ice', dragao: 'dragon', dragão: 'dragon',
      dragon: 'dragon', sombrio: 'dark', dark: 'dark', aco: 'steel', aço: 'steel',
      steel: 'steel', fada: 'fairy', fairy: 'fairy', lutador: 'fighting', fighting: 'fighting',
      veneno: 'poison', poison: 'poison', inseto: 'bug', bug: 'bug', fantasma: 'ghost',
      ghost: 'ghost', normal: 'normal'
    };
    if (tipoAliases[t]) { tipos.push(tipoAliases[t]); continue; }
    nome = nome ? `${nome} ${t}` : t;
  }
  return { ordem, nivel, nome: normalizar(nome), tipos, raridades, shiny };
}

function filtrarColecao(items, tokens) {
  const f = interpretarFiltros(tokens);
  let list = items.slice();
  list = list.filter(p => {
    if (f.shiny && !p.shiny) return false;
    if (f.nivel != null && Number(p.nivel) !== f.nivel) return false;
    if (f.nome && !normalizar(nomePokemon(p)).includes(f.nome)) return false;
    if (f.tipos.length) {
      const tipos = (p.tipos || []).map(normalizar);
      if (!f.tipos.every(t => tipos.includes(t) || tipos.includes(normalizar(t)))) return false;
    }
    if (f.raridades.length) {
      const r = normalizar(p.raridade || 'comum');
      if (!f.raridades.includes(r)) return false;
    }
    return true;
  });
  if (f.ordem === '>') list.sort((a, b) => poder(b) - poder(a));
  if (f.ordem === '<') list.sort((a, b) => poder(a) - poder(b));
  return { list, filtros: f };
}

/** Página da coleção com índices estáveis (collectionIndex) — A2/A4.2. */
function collectionPageSlice(data, tokens) {
  const items = data.collection || [];
  const { list, filtros } = filtrarColecao(items, tokens);
  const { page, pages, valida } = paginaDe(tokens, list.length);
  const slice = list.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  return { items, list, slice, filtros, page, pages, valida };
}

function paginaDe(tokens, total) {
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  let page = 1;
  const explicita = tokens.findIndex(t => t === 'pagina' || t === 'página' || t === 'pag');
  if (explicita >= 0 && tokens[explicita + 1] && /^\d+$/.test(tokens[explicita + 1])) {
    page = Number(tokens[explicita + 1]);
  } else {
    const num = tokens.find(t => /^\d+$/.test(t));
    if (num) page = Number(num);
  }
  const valida = Number.isSafeInteger(page) && page >= 1 && page <= pages;
  return { page: valida ? page : page, pages, valida };
}

function formatTrainer(data, playerName) {
  const nome = playerName || 'Treinador';
  const perfil = data.profile || {};
  const ativo = data.ativo;
  const nivel = perfil.nivel || perfil.trainerLevel || 1;
  const xp = perfil.xp != null ? perfil.xp : (perfil.trainerXp || 0);
  const xpAtual = perfil.xpAtual != null ? perfil.xpAtual : 0;
  const xpNec = perfil.xpNecessario != null ? perfil.xpNecessario : 5;
  return [
    `🧢 *Treinador: ${nome}*`,
    `⭐ Nível ${nivel} • ✨ PE ${xp} • próximo ${xpAtual}/${xpNec}`,
    `🎖️ PE ginásios +${perfil.peGinasios || 0} • raides +${perfil.peRaids || 0}`,
    `💰 Moedas: ${perfil.coins || 0} • Save: ${data.activeSaveId || '?'}`,
    `🔥 Capturas: sequência ${perfil.sequenciaCapturas || 0} • recorde ${perfil.maiorSequenciaCapturas || 0}`,
    `⚡ Ativo: ${ativo
      ? `#${(data.ativoIndex || 0) + 1} *${shinyMark(ativo)}${nomePokemon(ativo)}* • Nv.${ativo.nivel || 1}`
      : 'nenhum'}`,
    `📦 Coleção: ${data.collectionCount || 0} Pokémon`,
    '',
    '_Perfil JVM (dev). Cartão visual legado ainda não portado._'
  ].join('\n');
}

function formatCollection(data, tokens, prefix = '!pk') {
  const { items, list, slice, filtros, page, pages, valida } = collectionPageSlice(data, tokens);
  if (!valida && list.length) {
    return `❓ Página inválida. Escolha de 1 a ${pages}: ${prefix} time 1`;
  }
  const ativoIdx = data.ativoIndex;
  const favId = (data.favorito && data.favorito.id)
    || data.favoritoIdPokemon
    || null;
  const linhas = slice.map(p => {
    const idx = p.collectionIndex;
    const marker = idx === ativoIdx ? '👉 ' : '';
    const star = (favId && p.idPokemon === favId) ? '⭐ ' : '';
    const rar = p.raridade || 'comum';
    return `${marker}${star}${(idx != null ? idx + 1 : '?')}. ${shinyMark(p)}*${nomePokemon(p)}* Nv.${p.nivel || 1}`
      + ` • ${(RARIDADE_EMOJI[rar] || '')} ${rar}`
      + `\n   ${barraHp(p.hpAtual, p.hp)}`
      + `\n   ✨ ${textoXp(p)}`;
  });
  const filtroDesc = [];
  if (filtros.shiny) filtroDesc.push('✨ shiny');
  if (filtros.ordem) filtroDesc.push(filtros.ordem === '>' ? 'força ↓' : 'força ↑');
  if (filtros.nivel != null) filtroDesc.push(`nível ${filtros.nivel}`);
  if (filtros.tipos.length) filtroDesc.push(`tipo ${filtros.tipos.join('/')}`);
  if (filtros.raridades && filtros.raridades.length) {
    filtroDesc.push(`raridade ${filtros.raridades.join('/')}`);
  }
  if (filtros.nome) filtroDesc.push(`nome “${filtros.nome}”`);

  return [
    `🎒 *Seu time:* • Página ${Math.min(page, pages)}/${pages}`,
    `🧑‍🎓 Nível de treinador: ${data.trainerLevel || 1} • Save: ${data.saveId || '?'}`,
    filtroDesc.length ? `🔎 Filtros: ${filtroDesc.join(' + ')} — ${list.length} Pokémon` : `Total: ${data.count || items.length}`,
    '',
    linhas.length ? linhas.join('\n') : (items.length ? 'Nenhum Pokémon do time corresponde a esse filtro.' : 'Coleção vazia. Use !pk iniciar.'),
    '',
    page > 1 ? `⬅️ Anterior: ${prefix} time ${page - 1}` : null,
    page < pages ? `➡️ Próxima: ${prefix} time ${page + 1}` : null,
    `Use ${prefix} escolher <número> pra trocar o ativo (👉), ou ${prefix} joy para enviar os feridos à Enfermeira Joy.`,
    `Veja a ficha do ativo com ${prefix} time ativo.`,
    `Favorito: ${prefix} favorito <número>.`,
    `Combine filtros com ${prefix} time [tipo] [raridade] [nome] [nivel N].`,
    `Ordene por força: ${prefix} time > ou <.`,
    `Modo texto: ${prefix} time txt.`
  ].filter(Boolean).join('\n');
}

function formatPokedex(data, tokens, prefix = '!pk') {
  const entries = data.entries || [];
  const f = interpretarFiltros(tokens || []);
  const filtradas = entries.filter(e => {
    if (f.shiny && !(e.shinyCapturados || e.shinyCaught)) return false;
    if (f.nivel != null) {
      const idxs = e.indices || [];
      if (!idxs.some(i => Number(i.nivel) === f.nivel)) return false;
    }
    if (f.nome && !normalizar(e.nome || e.species).includes(f.nome)) return false;
    if (f.tipos.length) {
      const tipos = (e.tipos || []).map(normalizar);
      if (!f.tipos.every(t => tipos.includes(t))) return false;
    }
    if (f.raridades && f.raridades.length) {
      if (!f.raridades.includes(normalizar(e.raridade || 'comum'))) return false;
    }
    return true;
  });
  const total = data.totalPokemons || 1025;
  const reg = entries.length;
  const pct = total ? ((reg / total) * 100).toFixed(1) : '0.0';
  const capturas = filtradas.reduce((s, e) => s + (Number(e.capturas || e.caughtCount) || 0), 0);
  const porRar = {};
  filtradas.forEach(e => {
    const r = e.raridade || 'comum';
    porRar[r] = (porRar[r] || 0) + 1;
  });
  const rarLinha = ['comum', 'incomum', 'raro', 'epico', 'lendario', 'mitico']
    .filter(r => porRar[r])
    .map(r => `${RARIDADE_EMOJI[r] || ''} ${porRar[r]}`)
    .join(' • ');
  const filtroDesc = [];
  if (f.shiny) filtroDesc.push('✨ shiny');
  if (f.tipos.length) filtroDesc.push(`tipo ${f.tipos.join('/')}`);
  if (f.raridades && f.raridades.length) filtroDesc.push(`raridade ${f.raridades.join('/')}`);
  if (f.nome) filtroDesc.push(`nome “${f.nome}”`);
  if (f.nivel != null) filtroDesc.push(`nível ${f.nivel}`);
  const ativo = data.ativo;
  const linhas = filtradas.slice(0, 60).map(e => {
    const idxs = e.indices || [];
    const nums = idxs.map(i => `#${i.numero}`).join(', ');
    const rar = e.raridade || 'comum';
    const cap = e.capturas != null ? e.capturas : e.caughtCount;
    const maxNv = e.maiorNivel != null ? e.maiorNivel : e.maxLevel;
    const shinyN = e.shinyCapturados != null ? e.shinyCapturados : e.shinyCaught;
    return `${nums ? `${nums} ` : ''}${RARIDADE_EMOJI[rar] || ''} *${e.nome || e.species}* — ${cap || 0}x`
      + (maxNv ? ` • maior Nv.${maxNv}` : '')
      + (shinyN ? ` • ✨ ${shinyN}` : '')
      + (!nums ? ' (fora da coleção disponível)' : '');
  });
  return [
    `📚 *Sua Pokédex*`,
    `Espécies registradas: ${reg}/${total} (${pct}%)`,
    filtroDesc.length ? `🔎 Filtros: ${filtroDesc.join(' + ')} — ${filtradas.length} espécie(s)` : null,
    `${filtroDesc.length ? 'Capturas neste filtro' : 'Total de capturas'}: ${capturas}`,
    `🔥 Sequência atual: ${data.sequenciaCapturas || 0}`,
    ativo
      ? `👉 Pokémon ativo: #${(data.ativoIndex || 0) + 1} *${shinyMark(ativo)}${nomePokemon(ativo)}*`
      : null,
    rarLinha ? `Raridades: ${rarLinha}` : null,
    '',
    linhas.length ? `*Registrados:*\n${linhas.join('\n')}` : (filtroDesc.length
      ? `Nenhum Pokémon corresponde aos filtros.`
      : `Nenhuma espécie registrada. Use ${prefix} cacar.`),
    filtradas.length > 60 ? `… e mais ${filtradas.length - 60} espécies.` : null,
    '',
    `Os números são os mesmos de ${prefix} time.`,
    `Abra a ficha com ${prefix} pokedex <número>.`,
    `Veja estatísticas em ${prefix} pokedex descobertas.`
  ].filter(v => v != null && v !== '').join('\n');
}

function formatStorage(data, tokens, prefix = '!pk') {
  return formatCollection(data, tokens, prefix)
    .replace('🎒 *Seu time:*', '💻 *PC Pokémon:*')
    .replace('Nenhum Pokémon do time corresponde', 'Nenhum Pokémon armazenado corresponde');
}

function formatSpace(data, prefix = '!pk') {
  const ocupacao = Number(data && (data.ocupacaoPokemon ?? data.ocupacao ?? data.collectionCount ?? 0));
  const capacidade = Number(data && (data.capacidadePokemon ?? data.capacidade ?? 0));
  const livres = Math.max(0, capacidade - ocupacao);
  return [
    '📦 *Espaço Pokémon*',
    `Ocupação: ${ocupacao}/${capacidade} (${livres} vagas livres)`,
    `Expansões: ${Number(data && (data.expansoesPokemon50 ?? data.expansoes ?? 0))}`,
    `Use ${prefix} espaco comprar para adquirir +50 vagas.`
  ].join('\n');
}

function formatTeams(data, prefix = '!pk') {
  const teams = (data && (data.teams || data.namedTeams)) || [];
  if (!teams.length) return `👥 Nenhuma escalação salva. Use ${prefix} times salvar <nome> <1,2,3>.`;
  const lines = teams.map((team) => {
    const ids = Array.isArray(team.pokemonIds) ? team.pokemonIds : (team.pokemon_ids || []);
    const indices = Array.isArray(team.indices) ? team.indices.map((i) => i == null ? '?' : Number(i) + 1) : [];
    return `• *${team.name || '?'}*: ${indices.length ? indices.join(', ') : ids.join(', ')}`;
  });
  return `👥 *Escalações salvas*\n${lines.join('\n')}\n\nUse ${prefix} times usar <nome> [liga|ginasio].`;
}

function formatDiscoveries(data, prefix = '!pk') {
  const total = data.totalPokemons || 1025;
  const vistos = data.vistos || 0;
  const desconhecidos = data.desconhecidos != null
    ? data.desconhecidos
    : Math.max(0, total - vistos);
  const silhuetas = '⬛'.repeat(Math.min(12, desconhecidos))
    + (desconhecidos > 12 ? ' …' : '');
  return [
    `🔎 *Descobertas da Pokédex*`,
    '',
    `👀 Espécies vistas: ${vistos}/${total} • ${data.avistamentos || 0} encontros`,
    `📚 Espécies capturadas: ${data.capturados || 0} • ${data.capturas || 0} capturas`,
    `✨ Shiny capturados: ${data.shiny || 0}`,
    `⬛ Silhuetas ainda desconhecidas: ${desconhecidos}`,
    silhuetas || null,
    '',
    `${prefix} pokedex · ${prefix} time`
  ].filter(v => v != null && v !== '').join('\n');
}

function formatFicha(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  const idx = data.idx != null ? data.idx : p.collectionIndex;
  const n = idx != null ? Number(idx) + 1 : '?';
  const ativo = Boolean(data.isAtivo);
  const fav = Boolean(data.isFavorito);
  const tipos = (p.tipos || []).map(t => TIPOS_PT[t] || t).join('/') || '?';
  const golpes = menuGolpes(p, [], null) || 'Nenhum golpe registrado.';
  return [
    `${ativo ? '👉 ' : ''}${fav ? '⭐ ' : ''}${n}. ${shinyMark(p)}*${nomePokemon(p)}* Nv.${p.nivel || 1}`,
    `🏷️ ${tipos} · ${p.raridade || 'comum'}`,
    `❤️ ${barraHp(p.hpAtual, p.hp)}`,
    `✨ ${textoXp(p)}`,
    '',
    `⚔️ Ataque: ${p.ataque || 0} | 🛡️ Defesa: ${p.defesa || 0}`,
    `🔮 Atq. Especial: ${p.atqEsp || 0} | 🌀 Def. Especial: ${p.defEsp || 0} | 💨 Velocidade: ${p.veloc || 0}`,
    '',
    `🎯 *Golpes:*`,
    golpes,
    '',
    ativo
      ? '👉 Esse é o seu Pokémon ativo.'
      : `Use ${prefix} escolher ${n} pra deixar ele ativo.`
  ].join('\n');
}

function formatFavorite(data, prefix = '!pk') {
  if (data && data.clear) {
    return '💔 Pokémon favorito removido.';
  }
  if (data && data.favorito) {
    const nome = data.favorito.nome || (data.pokemon && data.pokemon.nome) || '?';
    const extra = data.ativado ? ' e também ficou ativo' : '';
    return `⭐ *${nome}* agora é seu favorito${extra}.`;
  }
  if (data && data.atual) {
    return `⭐ Seu Pokémon favorito é *${data.atual.nome}*. Quando voltar saudável da Joy ou do ginásio, ficará ativo automaticamente.`;
  }
  return `⭐ Escolha com ${prefix} favorito <número> (ou ${prefix} favorito remover).`;
}

function formatSetActive(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  const n = (data && data.ativoIndex != null) ? Number(data.ativoIndex) + 1 : '?';
  return `✅ *${p.nome || '?'}* agora é seu Pokémon ativo! (#${n})\n${prefix} time · ${prefix} cacar`;
}

function formatEvolve(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  const item = data && data.itemId;
  return [
    `✨ Evoluiu para *${p.nome || '?'}*!`,
    item ? `Uma pedra (\`${item}\`) foi consumida.` : null,
    'Nível, XP e item equipado foram preservados.',
    `${prefix} time · ${prefix} escolher`
  ].filter(Boolean).join('\n');
}

function formatLearnMove(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  const golpes = p.golpes || [];
  const lista = golpes.map((g, i) => {
    const nome = g.nomeExibicao || g['nome-exibicao'] || g.slug || '?';
    return `${i + 1}. ${nome}`;
  }).join('\n');
  return [
    `✅ *${p.nome || 'Pokémon'}* atualizou os golpes!`,
    lista || '(sem golpes)',
    `${prefix} aprender · ${prefix} reaprender`
  ].join('\n');
}

function formatForgetMove(data, prefix = '!pk') {
  const rem = (data && data.removido) || {};
  const nome = rem.nomeExibicao || rem['nome-exibicao'] || rem.slug || 'golpe';
  const p = (data && data.pokemon) || {};
  return `🗑️ *${nome}* removido de *${p.nome || '?'}*.\n${prefix} aprender · ${prefix} reaprender`;
}

function formatOfferMove(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  if ((data && data.resultado) === 'aprendido') {
    return `📘 *${p.nome || '?'}* aprendeu o golpe automaticamente (havia vaga).\n${prefix} time`;
  }
  const ofertas = p.ofertasGolpes || p['ofertas-golpes'] || p.golpesOferecidos || [];
  const oferta = ofertas[0] || {};
  const nome = oferta.nomeExibicao || oferta['nome-exibicao'] || oferta.slug || 'golpe';
  return [
    `📘 *${p.nome || '?'}* pode aprender *${nome}*!`,
    menuGolpes(p, [], null),
    `Use ${prefix} aprender <1-4> para substituir, ou ${prefix} aprender aceitar (vaga livre).`,
    `Recusar: ${prefix} aprender recusar.`
  ].join('\n');
}

function formatRefuseOffer(data, prefix = '!pk') {
  return `📘 Oferta recusada. Golpes inalterados.\nReaprender: ${prefix} reaprender (50 moedas).`;
}

function formatRelearnMove(data, prefix = '!pk') {
  const p = (data && data.pokemon) || {};
  const preco = (data && data.preco) || 50;
  return `✅ *${p.nome || '?'}* reaprendeu o golpe! −${preco} moedas.\n${prefix} time`;
}

function formatMoveOfferMenu(pokemon, prefix = '!pk') {
  const p = pokemon || {};
  const ofertas = p.ofertasGolpes || p['ofertas-golpes'] || p.golpesOferecidos || [];
  const oferta = ofertas[0];
  if (!oferta) {
    return [
      `📘 *${p.nome || '?'}* — sem oferta pendente.`,
      menuGolpes(p, [], null),
      `Use ${prefix} aprender após subir de nível, ou ${prefix} reaprender.`
    ].join('\n');
  }
  const nome = oferta.nomeExibicao || oferta['nome-exibicao'] || oferta.slug || 'golpe';
  const vagas = (p.golpes || []).length < 4;
  return [
    `📘 *${p.nome || '?'}* pode aprender *${nome}*!`,
    menuGolpes(p, [], null),
    `Use ${prefix} aprender <número do golpe a substituir>`
      + (vagas ? ` ou ${prefix} aprender aceitar` : '')
      + '.',
    `Recusar: ${prefix} aprender recusar.`
  ].join('\n');
}

function formatRelearnMenu(pokemon, opcoes, prefix = '!pk') {
  const p = pokemon || {};
  const lista = (opcoes || []).map((slug, i) => `${i + 1}. ${slug}`).join('\n');
  return [
    `📚 *Reaprender — ${p.nome || '?'}*`,
    '💰 50 moedas por golpe.',
    '',
    lista || 'Nenhum golpe esquecido ou recusado disponível.',
    lista
      ? `\nUse ${prefix} reaprender <número> [golpe a substituir 1-4].`
      : null
  ].filter(Boolean).join('\n');
}

function formatActiveSave(data) {
  const save = data.save || {};
  const party = data.party || [];
  return [
    '💾 *Save ativo*',
    `Slot: *${data.activeSaveId || save.slotId || '?'}* · fence ${data.fenceGeneration || 0}`,
    `💰 Moedas: ${save.coins || 0} · Nv.treinador ${save.nivel || save.trainerLevel || 1}`,
    `⭐ PE ${save.xp || save.trainerXp || 0} · revisão ${save.revision || 0}`,
    `👥 Party: ${party.length} · Inventário: ${(data.inventory || []).length} itens`,
    party.length
      ? party.map(p => `• ${shinyMark(p)}${nomePokemon(p)} Nv.${p.nivel || 1}`).join('\n')
      : '(party vazia)'
  ].join('\n');
}

function formatSavesList(data) {
  const linhas = (data.saves || []).map(s => {
    const ativo = s.saveId === data.activeSaveId ? ' 👉' : '';
    return `• *${s.saveId}*${ativo}: ${s.coins || 0} moedas · ${s.collectionCount || 0} na coleção · party ${s.partyCount || 0}`;
  });
  return [
    `💾 *Saves A/B* · ativo=${data.activeSaveId || '?'} · fence=${data.fenceGeneration || 0}`,
    ...linhas,
    '',
    'Alternar: !pk ativar a|b'
  ].join('\n');
}

function formatEnsure(data) {
  return [
    data.created ? '✅ *Treinador criado*' : 'ℹ️ *Treinador já existia*',
    `Save ativo: *${data.activeSaveId}*`
  ].join('\n');
}

const MENU_CMDS = [
  '`treinador` — perfil e Pokémon ativo',
  '`meus` / `time` — coleção',
  '`cacar` — caçada selvagem',
  '`mochila` / `loja` — itens e compras',
  '`joy` — Enfermeira Joy',
  '`ginasio` — ginásios',
  '`raide` — raides cooperativas',
  '`ovos` — Adventure Sync',
  '`saves` / `ativar a|b` — saves A/B'
];

function formatMainMenu(onboarding, prefix = '!pk') {
  const st = (onboarding && onboarding.status) || 'not_started';
  if (!onboarding || !onboarding.hasTrainer || st === 'not_started') {
    return [
      '🌟 *Pokémon RPG*',
      'Bem-vindo, treinador! Sua jornada ainda não começou.',
      '',
      `Para criar seu perfil e receber o kit inicial:`,
      `👉 ${prefix} iniciar`
    ].join('\n');
  }
  if (st === 'awaiting_starter') {
    return [
      '🌟 *Pokémon RPG*',
      'Você já tem o kit inicial. Falta escolher o primeiro Pokémon!',
      '',
      `Veja as opções: ${prefix} iniciar`,
      `Escolha: ${prefix} inicial <1|2|3>`
    ].join('\n');
  }
  return [
    '🧢 *Pokémon RPG* — menu',
    ...MENU_CMDS.map((l) => `• ${l}`),
    '',
    `Ex.: ${prefix} treinador · ${prefix} cacar · ${prefix} mochila`
  ].join('\n');
}

const TIPO_PT_LABEL = {
  grass: 'Planta', poison: 'Veneno', fire: 'Fogo', water: 'Água'
};

function tiposStarterPt(tipos) {
  return (tipos || [])
    .map((t) => TIPO_PT_LABEL[String(t).toLowerCase()] || String(t))
    .filter(Boolean)
    .join('/');
}

function formatStartersMenu(onboarding, prefix = '!pk') {
  const starters = (onboarding && onboarding.starters) || [];
  const linhas = starters.map((s) => {
    const tipos = tiposStarterPt(s.tipos) || (s.tipos || []).join('/');
    return `${s.index}. ${s.emoji || ''} *${s.nome}* — ${tipos}`;
  });
  return [
    '🌟 *Escolha seu Pokémon inicial:*',
    '',
    ...linhas,
    '',
    `Use ${prefix} inicial <número> ou ${prefix} inicial <nome>.`,
    'Ex.: `' + prefix + ' inicial 1` · `' + prefix + ' inicial charmander`'
  ].join('\n');
}

function formatStartJourney(data, prefix = '!pk') {
  const onboard = (data && data.onboarding) || {};
  const kit = data && data.kit;
  if (data && data.alreadyComplete) {
    return [
      '✅ *Sua jornada já começou!*',
      'O kit e o Pokémon inicial não são concedidos de novo.',
      '',
      `Veja o perfil: ${prefix} treinador`,
      `Coleção: ${prefix} meus`,
      `Menu: ${prefix}`
    ].join('\n');
  }
  const partes = [];
  if (data && data.created) {
    partes.push('✅ *Treinador criado!* Bem-vindo à jornada.');
  } else {
    partes.push('👋 Continuando sua jornada…');
  }
  if (kit && !kit.kitAlreadyClaimed && (kit.qty || 0) > 0) {
    partes.push(`🎁 Você recebeu *${kit.qty} Pokébolas* (kit inicial).`);
  } else if (kit && kit.kitAlreadyClaimed) {
    partes.push('🎁 Kit inicial já resgatado anteriormente.');
  } else if (onboard.kitClaimed) {
    partes.push('🎁 Kit inicial ok.');
  }
  if (onboard.status === 'awaiting_starter' || onboard.starterAvailable) {
    partes.push('', formatStartersMenu(onboard, prefix));
  } else if (onboard.status === 'completed') {
    partes.push('', `Tudo certo! ${prefix} treinador · ${prefix} cacar`);
  }
  return partes.filter((p) => p !== null && p !== undefined).join('\n');
}

function formatStarterChosen(data, prefix = '!pk') {
  if (data && data.starterAlreadyChosen) {
    return [
      `🧢 Você já escolheu seu inicial${data.nome ? ` (*${data.nome}*)` : ''}.`,
      `${prefix} treinador · ${prefix} meus`
    ].join('\n');
  }
  const nome = (data && (data.nome || data.species)) || '?';
  const emoji = (data && data.emoji) || '🎉';
  const kitExtra = data && data.kitGrantedWithStarter
    ? `\n🎁 Também recebeu *${data.kitQty || 10} Pokébolas* do kit.`
    : '';
  return [
    `${emoji} Você escolheu *${nome}* como seu inicial!${kitExtra}`,
    '',
    'A aventura começou. Próximos passos:',
    `• ${prefix} treinador — ver perfil`,
    `• ${prefix} meus — coleção`,
    `• ${prefix} cacar — caçar selvagens`,
    `• ${prefix} mochila — itens`
  ].join('\n');
}

function formatActivate(data) {
  const save = data.save || {};
  return [
    `✅ Save *${data.activeSaveId}* ativo`,
    `Fence: ${data.fenceGeneration} · moedas: ${save.coins || 0}`
  ].join('\n');
}

function formatReceipt(data) {
  return [
    '🧾 *Recibo de operação* (persistido no Datomic)',
    `id: ${data.operationId}`,
    `canal: ${data.channel || '?'} · save: ${data.saveId || '?'}`,
    data.persisted ? 'estado: *persistido*' : 'estado: desconhecido',
    data.payloadHash ? `hash: ${String(data.payloadHash).slice(0, 12)}…` : null
  ].filter(Boolean).join('\n');
}

function formatError(err) {
  const code = (err && err.code) || 'ERROR';
  const map = {
    PLAYER_NOT_FOUND: '⚠️ Treinador não encontrado. Use !pk iniciar.',
    NO_ACTIVE_SAVE: '⚠️ Nenhum save ativo. Use !pk iniciar ou !pk ativar a|b.',
    SAVE_NOT_FOUND: '⚠️ Save não encontrado.',
    STALE_WRITER_FENCE:
      '⚠️ Save/fence obsoleto (provavelmente houve !pk ativar). Consulte !pk saves e tente de novo.',
    WRITER_FENCE_CONFLICT: '⚠️ Conflito de fence. Tente novamente com o mesmo operationId.',
    ACTIVATE_CONFLICT: '⚠️ Não foi possível alternar o save (conflito). Tente de novo.',
    RECEIPT_PAYLOAD_MISMATCH:
      '⚠️ Mesmo operationId com payload diferente — operação rejeitada.',
    OPERATION_PENDING:
      '⏳ Operação ainda em andamento *neste processo JVM* (não é recibo persistido). Aguarde e reconcilie com !pk recibo <id>.',
    OPERATION_NOT_FOUND:
      '⚠️ Recibo não encontrado (operação não persistida ou id inválido).',
    OPERATION_FORBIDDEN: '⚠️ Recibo de outro jogador — consulta negada.',
    ENCOUNTER_NOT_FOUND: '❓ Não há caçada/encontro em andamento.',
    ENCOUNTER_NOT_READY: '⚔️ Derrote o Pokémon selvagem antes de capturar.',
    ENCOUNTER_OWNER_MISMATCH: '🚫 Essa caçada pertence a outro treinador.',
    UNKNOWN_BALL: '❓ Bola desconhecida. Use !pk capturar para ver o menu.',
    INSUFFICIENT_ITEM: '🎒 Você não tem o item necessário. Cartões de presente custam 20 moedas: !loja comprar cartao-presente.',
    INVALID_GIFT_TARGET: '🎁 Marque um amigo válido: !presente @amigo.',
    GIFT_TARGET_CHANGED: '🎁 O save do destinatário mudou durante o envio. Confira e tente novamente.',
    MISSION_PERIOD_CONFLICT: '📋 O período de missões mudou. Consulte !missoes e tente novamente.',
    COLLECTION_FULL: '🎒 Bolsa cheia: libere espaço antes de capturar.',
    BATTLE_PENDING: '⚠️ Há batalha/encontro em andamento neste save — não dá para alternar A↔B agora.',
    BATTLE_SAVE_MISMATCH: '⚠️ Encontro pertence a outro save — callback antigo rejeitado.',
    BATTLE_ALREADY_ACTIVE: '⚔️ Já existe uma batalha/encontro seu em andamento.',
    BATTLE_BUSY: '🚫 Termine a batalha/caça antes de trocar o ativo, evoluir ou alterar golpes.',
    INVALID_COLLECTION_INDEX: '❓ Número de Pokémon inválido. Veja com !pk time.',
    INVALID_MOVE_SLOT: '❓ Informe um golpe válido (1-4) para substituir ou liberar vaga.',
    MOVE_ALREADY_KNOWN: '❓ Esse golpe já é conhecido (ou já foi oferecido/removido).',
    MUST_KEEP_STAB_MOVE: '🚫 Mantenha pelo menos um ataque ofensivo do próprio tipo.',
    NO_MOVE_OFFER: '❓ Não há oferta de golpe pendente. Use !pk aprender após subir de nível.',
    MOVE_NOT_IN_HISTORY: '❓ Esse golpe não está no histórico para reaprender.',
    CANNOT_FORGET_LAST_MOVE: '🚫 Remover deixaria o Pokémon sem golpes suficientes.',
    PVP_CHAT_BUSY: '⚔️ Já existe um desafio ou batalha PvP neste chat.',
    PVP_SELF_ACCEPT: '❓ Você não pode aceitar o próprio desafio.',
    PVP_NOT_WAITING: '❓ Não há desafio aguardando adversário neste chat.',
    PVP_CHALLENGE_EXPIRED: '⏳ Este desafio expirou. Abra outro com !pk batalhar.',
    PVP_LEVEL_INCOMPATIBLE: '❌ Seu Pokémon ativo está fora da faixa de níveis do desafio. Use !pk escolher.',
    PVP_NO_CHALLENGE: '❓ Não há desafio PvP neste chat. Use !pk batalhar.',
    PVP_OUT_OF_TURN: '⏳ Não é a sua vez.',
    PVP_NOT_PARTICIPANT: '🚫 Você não participa desta batalha.',
    PVP_CANCEL_FORBIDDEN: '🚫 Só quem abriu o desafio pode cancelá-lo com !pk sair.',
    NOT_PVP_BATTLE: '❓ Esta batalha não é PvP.',
    AREA_UNAVAILABLE: '🗺️ Essa área não está disponível hoje. Veja a rotação com !pk clima.',
    HUNT_COOLDOWN: '⏳ Ainda em cooldown de caça. Aguarde e tente de novo.',
    CHAT_HUNT_BUSY: '⚔️ Já existe uma caçada em andamento neste chat.',
    ACTIVE_POKEMON_FAINTED: '😵 Seu Pokémon ativo está desmaiado. Cure-o antes de caçar.',
    NO_ACTIVE_POKEMON: '❓ Você precisa de um Pokémon na equipe para caçar.',
    HUNT_NO_CANDIDATES: '❌ Não consegui buscar um pokémon selvagem agora. Tente de novo.',
    POKEAPI_HTTP: '❌ A PokeAPI falhou ao buscar espécies. Tente de novo.',
    POKEAPI_EMPTY: '❌ Não consegui buscar um pokémon selvagem agora. Tente de novo.',
    GYM_LOCKED: '🔒 Vença os ginásios anteriores primeiro.',
    GYM_DAILY_LIMIT: '⏳ Você já venceu este ginásio hoje. A revanche abre à meia-noite de São Paulo.',
    CANNOT_CHALLENGE_OWN_GYM: '🏛️ Você já lidera este ginásio.',
    INVALID_GYM_TEAM: '❓ Escale três Pokémon saudáveis: !pk gin time auto ou !pk gin time 1,2,3.',
    GYM_TEAM_UNHEALTHY: '❓ Escale três Pokémon saudáveis: !pk gin time auto ou !pk gin time 1,2,3.',
    INVALID_GYM_NPC_TEAM: '❌ Não consegui montar o time do líder. Tente de novo.',
    UNKNOWN_GYM: '❓ Ginásio desconhecido. Use pedra, agua, eletrico, planta ou fogo.',
    RAID_NOT_FOUND: '❓ Nenhuma raide ativa neste chat.',
    RAID_NOT_OPEN: '❓ As inscrições desta raide não estão abertas.',
    ALREADY_IN_RAID: '⚔️ Você já está inscrito nesta raide.',
    RAID_FULL: '🚫 A raide já está cheia (máx. 6).',
    RAID_CANNOT_LEAVE: '🚫 Só é possível sair durante as inscrições.',
    RAID_NEED_PLAYERS: '❓ Precisa de pelo menos 2 inscritos para iniciar.',
    RAID_NOT_IN_COMBAT: '❓ A raide não está em combate.',
    RAID_NOT_YOUR_TURN: '⏳ Não é a sua vez de atacar.',
    RAID_SAVE_MISMATCH: '⚠️ Você mudou de save após entrar — reentre na próxima raide.',
    RAID_LIGA_MISMATCH: '❓ Esse Pokémon não está na faixa de nível da liga da raide.',
    INVALID_RAID_POKEMON: '❓ Escolha um Pokémon saudável com golpe ofensivo: !pk raide time.',
    INVALID_RAID_MOVE: '❓ Use !pk raide atacar <1-4> com um golpe ofensivo.',
    RAID_CAPTURE_UNAVAILABLE: '❓ Captura indisponível (sem dano, janela expirada ou já capturou).',
    RAID_NOT_CREATOR: '🚫 Só quem abriu a raide pode iniciar (quando há criador).',
    NOT_IN_RAID: '❓ Você não está nesta raide.',
    INVALID_JOY_INDEX: '❓ Índice inválido. Use !pk joy para ver os feridos.',
    POKEMON_HEALTHY: '✨ Esse Pokémon já está saudável.',
    JOY_NOTHING_READY: '⏳ Ainda não há Pokémon prontos na enfermaria.',
    EGG_NOT_FOUND: '❓ Ovo não encontrado.',
    EGG_INVENTORY_FULL: '🥚 Inventário de ovos cheio (máx. 9).',
    INVALID_INCUBATION: '❓ Esse ovo não pode ser incubado agora.',
    INCUBATOR_BUSY: '⏳ Essa incubadora já está em uso.',
    INCUBATOR_NOT_FOUND: '❓ Incubadora não encontrada.',
    EGG_NOT_READY: '⏳ O ovo ainda não está pronto para nascer.',
    EGG_SAVE_MISMATCH: '⚠️ Esse ovo foi incubado em outro save — volte ao save de origem.',
    ADVENTURE_ACCOUNT_MISSING: '❓ Conta Adventure Sync ainda não criada. Use !pk ovos.',
    INSUFFICIENT_COINS: '❌ Moedas insuficientes para essa compra.',
    BAG_FULL: '🎒 Mochila cheia! Use itens ou compre expansão: !loja comprar mochila.',
    UNKNOWN_SHOP_ITEM: '❓ Item não encontrado. Use !loja para ver o catálogo.',
    ITEM_NOT_FOR_SALE: '🎁 Esse item não está à venda (missões, diário ou ginásio).',
    ITEM_USE_UNSUPPORTED: '🚧 Uso desse item ainda não está no adaptador JVM.',
    WRONG_STATUS_ITEM: '❓ Essa cura não corresponde ao status atual do Pokémon.',
    POKEMON_FULL_HP: '✨ Esse Pokémon já está com HP cheio.',
    NO_STATUS_TO_CURE: '✨ Não há status para curar no Pokémon alvo.',
    MISSING_ITEM_ID: '❓ Informe o item: !loja comprar <item> ou !pk pocao.',
    TIMEOUT: '⏳ Demorou um pouco. Se foi escrita, confira com !pk recibo <id> antes de repetir.',
    CONNECT_TIMEOUT: '⚠️ Não consegui falar com o servidor do jogo. Tente de novo em instantes.',
    NETWORK: '⚠️ Servidor do jogo indisponível. Tente de novo em instantes.',
    ECONNREFUSED: '⚠️ Servidor do jogo indisponível. Tente de novo em instantes.',
    INVALID_STARTER: '❓ Escolha inválida. Use !pk inicial 1, 2 ou 3 (Bulbasaur, Charmander ou Squirtle).',
    STARTER_UNAVAILABLE: '🧢 Inicial já escolhido ou jornada já em andamento. Use !pk treinador.'
  };
  let base = map[code] || `⚠️ ${err.message || 'Falha no jogo'} (${code}).`;
  if (code === 'AREA_UNAVAILABLE') {
    const d = (err && err.details) || {};
    const resumo = d.resumoTexto || d['resumo-texto'];
    if (resumo) base = `${base}\n${resumo}\nUse !pk cacar <área>.`;
  }
  return base;
}

function formatUnavailable(commandHint) {
  return [
    '❓ Esse comando ainda não está disponível por aqui.',
    commandHint ? `Você pediu: \`${commandHint}\`` : null,
    'Tente: `treinador`, `meus`, `escolher`, `evoluir`, `aprender`, `cacar`, `capturar`, `ginasio`, `raide`, `joy`, `ovos`, `loja`, `mochila`, `saves`.'
  ].filter(Boolean).join('\n');
}

const RARIDADE = {
  comum: { nome: 'Comum', emoji: '⚪' },
  incomum: { nome: 'Incomum', emoji: '🟢' },
  raro: { nome: 'Raro', emoji: '🔵' },
  epico: { nome: 'Épico', emoji: '🟣' },
  lendario: { nome: 'Lendário', emoji: '🟡' },
  mitico: { nome: 'Mítico', emoji: '🔴' }
};

function textoRaridade(p) {
  const r = RARIDADE[(p && p.raridade) || 'comum'] || RARIDADE.comum;
  const shiny = p && (p.shiny || p.shiny === true) ? ' ✨ Shiny' : '';
  return `${r.emoji} ${r.nome}${shiny}`;
}

function formatHuntEligibility(plan) {
  const e = (plan && plan.eligibilidade) || {};
  if (!e.temTreinador) return '⚠️ Treinador não encontrado. Use !pk iniciar.';
  if (!e.temPokemon) return '❓ Você precisa de um Pokémon na equipe para caçar. Use !pk iniciar.';
  if (!e.ativoVivo) return '😵 Seu Pokémon ativo está desmaiado. Cure-o antes de caçar.';
  if (!e.cooldownOk) {
    return `⏳ Calma aí! Você pode caçar de novo em ${e.cooldownRestanteS || '?'}s.`;
  }
  if (!e.semBatalhaPropria) return '⚔️ Já existe uma batalha/encontro seu em andamento.';
  if (!e.chatLivre) return '⚔️ Já existe uma caçada em andamento neste chat.';
  return null;
}

/**
 * Estado de batalha estilo legado (HP barras + menu de golpes numerados).
 * Retorna texto ou mensagem de erro controlado se HP/golpes faltarem.
 */
function formatBattleState(battle, prefix = '!pk', opts = {}) {
  const b = battle || {};
  const x = b.pokemonX || (b.pokemons && b.pokemons.x) || {};
  const o = b.pokemonO || (b.pokemons && b.pokemons.o) || {};
  const hp = b.hp || {};
  const status = b.status || {};
  const area = b.area || opts.area || {};
  const clima = b.clima || opts.clima || {};
  const gym = b.kind === 'gym' || Boolean(b.gymId);
  const hpX = hpPair(x, hp.x);
  const hpO = hpPair(o, hp.o);
  if (!hpX || !hpO) {
    return '❌ Dados de HP incompletos nesta batalha. Reinicie com '
      + `${prefix} caçar ou ${prefix} gin desafiar.`;
  }
  const golpes = menuGolpes(x, o.tipos || [], o.habilidade);
  if (!golpes) {
    return `❌ Golpes ausentes para *${nomePokemon(x)}*. Reinicie a batalha.`;
  }
  const nGolpes = ((x.golpes) || []).length;
  const cabecalho = gym
    ? `🏛️ *${b.gymNome || b.gymId || 'Ginásio'}* — vs ${b.liderNome || 'líder'}`
    : [
      area.nome
        ? `${area.emoji || '🗺️'} *${area.nome}*${clima.nome ? ` • ${clima.emoji || '☁️'} ${clima.nome}` : ''}`
        : null
    ].filter(Boolean).join('\n');
  const reservas = gym && (b.reservasX != null || b.reservasO != null)
    ? `Reservas: você ${b.reservasX != null ? b.reservasX : '?'} · líder ${b.reservasO != null ? b.reservasO : '?'}`
    : null;
  return [
    cabecalho || null,
    '',
    `🐾 *${nomePokemon(x)}*${emojiStatus(status.x)} —`,
    barraHp(hpX.atual, hpX.max),
    `👾 *${nomePokemon(o)}*${emojiStatus(status.o)}${o.raridade ? ` — ${textoRaridade(o)}` : ''}`,
    barraHp(hpO.atual, hpO.max),
    reservas,
    '',
    `Escolha: ${prefix} atacar <1-${nGolpes}> · ${prefix} <1-${nGolpes}> · ${prefix} defender · ${prefix} pocao`,
    golpes,
    '',
    `📖 Ações: ${prefix} atacar ajuda · ${prefix} defender · ${prefix} pocao`
  ].filter(v => v !== null).join('\n');
}

/** Clima / áreas do dia — espelha master mundo/resumo (+ dica de caça). */
function formatWorldInfo(plan, prefix = '!pk') {
  const texto = (plan && (plan.resumoTexto || plan['resumo-texto'])) || null;
  const mundo = (plan && plan.mundo) || {};
  const padrao = mundo.areaPadrao || mundo['area-padrao'] || (plan && plan.area) || {};
  if (texto) {
    return `${texto}\n\nCaçar: ${prefix} cacar [área] · Ex.: ${prefix} cacar ${padrao.id || 'praia'}`;
  }
  const clima = (plan && plan.clima) || mundo.clima || {};
  const disponiveis = mundo.areasDisponiveis || mundo['areas-disponiveis'] || [];
  const indisponiveis = mundo.areasIndisponiveis || mundo['areas-indisponiveis'] || [];
  const tiposClima = (clima.tipos || []).slice().sort();
  const rotacao = disponiveis.map(a => `${a.emoji || '🗺️'} ${a.id || a.nome}`).join(' • ') || '?';
  const semAcesso = indisponiveis.map(a => `${a.emoji || '·'} ${a.id || a.nome}`).join(' • ') || '—';
  return [
    `${clima.emoji || '☁️'} *Clima de hoje: ${clima.nome || '?'}* — favorece ${tiposClima.join(', ') || '?'}.`,
    `📍 *Bioma deste horário:* ${padrao.emoji || '🗺️'} ${padrao.id || padrao.nome || '?'}`,
    `🕒 *Rotação de hoje:* ${rotacao}`,
    `🚫 *Sem acesso por horário hoje:* ${semAcesso}`,
    '',
    `Caçar: ${prefix} cacar [área] · Ex.: ${prefix} cacar ${padrao.id || 'praia'}`
  ].join('\n');
}

/** Surto rotativo 6h — espelha master ver-evento (sem recomeço/loja). */
function formatEventInfo(plan, prefix = '!pk') {
  const ev = (plan && (plan.eventoAtual || plan['evento-atual'] || plan.evento)) || {};
  const especies = Array.isArray(ev.especies) ? ev.especies : [];
  const fim = Number(ev.fim);
  const agora = Date.now();
  const minutos = Number.isFinite(fim)
    ? Math.max(0, Math.ceil((fim - agora) / 60000))
    : '?';
  return [
    `🎉 *${ev.nome || 'Evento'}*`,
    `Espécies em destaque: ${especies.join(', ') || '?'}`,
    '50% das caçadas encontram uma dessas espécies; os demais encontros seguem o bioma.',
    `Troca de evento em ${minutos} minutos. Os eventos mudam a cada 6 horas.`,
    `Use ${prefix} cacar. As três tentativas e a chance de fuga continuam valendo.`
  ].join('\n');
}

function formatHuntStart(plan, result, prefix = '!pk') {
  const area = (result && result.area) || (plan && plan.area) || {};
  const clima = (plan && plan.clima) || {};
  const selvagem = (result && result.selvagem) || {};
  const battle = (result && result.battle) || {};
  const somente = result && result.somenteBatalha;
  const surto = result && result.surto;
  const intro = [
    `${area.emoji || '🗺️'} Você entrou em *${area.nome || area.id || '?'}*!`,
    `${clima.emoji || '☁️'} Clima *${clima.nome || '?'}*: tipos ${(clima.tipos || []).join(', ') || '?'} aparecem mais.`,
    surto && plan && plan.evento ? `🎉 ${plan.evento.nome} — encontro do evento!` : null,
    `Um ${textoRaridade(selvagem)} *${selvagem.nome || '?'}* apareceu.`,
    somente
      ? 'Bolsa cheia: esta caçada vale batalha e XP, sem captura.'
      : 'Derrote-o antes de tentar capturar!'
  ].filter(Boolean).join('\n');
  const estadoBattle = {
    ...battle,
    area: battle.area || area,
    clima: battle.clima || clima,
    pokemonX: battle.pokemonX || {},
    pokemonO: battle.pokemonO || selvagem,
    hp: battle.hp || {
      x: battle.pokemonX && battle.pokemonX.hpAtual,
      o: selvagem.hpAtual != null ? selvagem.hpAtual : selvagem.hp
    }
  };
  const temPokemonX = estadoBattle.pokemonX && (estadoBattle.pokemonX.nome || estadoBattle.pokemonX.species);
  const temGolpes = ((estadoBattle.pokemonX && estadoBattle.pokemonX.golpes) || []).length > 0;
  if (temPokemonX && temGolpes) {
    return `${intro}\n\n${formatBattleState(estadoBattle, prefix, { area, clima })}`;
  }
  return [
    intro,
    '',
    `Batalha: ${prefix} atacar <1-4> ou ${prefix} <1-4>`,
    `Captura: ${prefix} capturar <bola> após a vitória.`
  ].join('\n');
}

function golpesLinha(p) {
  const gs = (p && p.golpes) || [];
  if (!gs.length) return null;
  return gs.map((g, i) => {
    const nome = g.nomeExibicao || g['nome-exibicao'] || g.nome || g.slug || '?';
    const poder = g.poder != null ? g.poder : '?';
    const tipo = g.tipo || '?';
    return `${i + 1}. ${nome} (${tipo}/${poder})`;
  }).join(' · ');
}

function formatGymResolve(gymResolve, battle) {
  if (!gymResolve) return null;
  const nome = (battle && battle.gymNome) || 'ginásio';
  if (gymResolve.venceu) {
    const rew = gymResolve.reward || {};
    return [
      `🏅 Você venceu o ginásio *${nome}*!`,
      gymResolve.premio === 'primeira'
        ? 'Nova insígnia conquistada e próximo ginásio liberado!'
        : (gymResolve.premio === 'revanche'
          ? 'Revanche diária concluída.'
          : 'Você já recebeu a recompensa diária; o XP de combate continua valendo.'),
      rew.moedas != null ? `💰 +${rew.moedas} moedas.` : null,
      gymResolve.peDelta != null ? `⭐ +${gymResolve.peDelta} PE.` : null,
      gymResolve.occupied ? '🏛️ Você é o novo líder — time reservado no ginásio.' : null
    ].filter(Boolean).join('\n');
  }
  if ((battle && battle.outcome) === 'draw') {
    return `⚖️ Empate no ginásio *${nome}*. Sem ocupação nem recompensa diária.`;
  }
  return [
    `💀 O líder venceu no ginásio *${nome}*.`,
    'Recupere seu time e tente novamente! Suas insígnias foram mantidas.',
    gymResolve.peDelta != null ? `⭐ +${gymResolve.peDelta} PE.` : null
  ].filter(Boolean).join('\n');
}

function formatGymChallengeStart(data, prefix = '!pk') {
  const b = (data && data.battle) || {};
  const intro = `🏛️ Desafio contra *${b.liderNome || 'líder'}*!`;
  if (b.pokemonX && ((b.pokemonX.golpes) || []).length && b.pokemonO) {
    return `${intro}\n\n${formatBattleState(b, prefix)}`;
  }
  const x = b.pokemonX || {};
  const o = b.pokemonO || {};
  return [
    intro,
    `*${x.nome || 'Você'}* vs *${o.nome || 'defensor'}*`,
    `HP: ${b.hp && b.hp.x != null ? b.hp.x : '?'} · ${b.hp && b.hp.o != null ? b.hp.o : '?'}`,
    `Reservas: você ${b.reservasX != null ? b.reservasX : 2} · líder ${b.reservasO != null ? b.reservasO : 2}`,
    golpesLinha(x) ? `Seus golpes: ${golpesLinha(x)}` : null,
    `Ataque: ${prefix} atacar <1-4> ou ${prefix} <1-4>`
  ].filter(Boolean).join('\n');
}

function formatBattleTurn(data, prefix = '!pk') {
  const b = (data && data.battle) || {};
  const o = b.pokemonO || (b.pokemons && b.pokemons.o) || {};
  const x = b.pokemonX || (b.pokemons && b.pokemons.x) || {};
  const nome = o.nome || o.species || 'adversário';
  const area = b.area || {};
  const gym = b.kind === 'gym' || Boolean(b.gymId);
  if (data.gymResolve || (!b.active && gym && b.outcome)) {
    return formatGymResolve(data.gymResolve || { venceu: b.outcome === 'win' }, b);
  }
  if (data.aguardandoCaptura || b.aguardandoCaptura) {
    const hpX = hpPair(x, (b.hp || {}).x);
    return [
      `🏁 *${nome}* foi derrotado!`,
      area.nome ? `${area.emoji || '🗺️'} ${area.nome}` : null,
      hpX ? `🐾 *${nomePokemon(x)}* — ${barraHp(hpX.atual, hpX.max)}` : null,
      'Agora você pode capturar:',
      `${prefix} capturar`,
      data.chanceBaseCaptura != null
        ? `Chance-base carimbada: ${data.chanceBaseCaptura}%`
        : null
    ].filter(Boolean).join('\n');
  }
  if (b.outcome === 'lose' || data.outcome === 'lose') {
    return gym
      ? formatGymResolve({ venceu: false, peDelta: 1 }, b)
      : '💀 Seu Pokémon desmaiou. A caçada acabou.';
  }
  if (b.outcome === 'flee' || data.outcome === 'flee') {
    return `💨 *${nome}* fugiu durante a batalha!`;
  }
  const la = b.lastAction || {};
  const switchLine = b.lastSwitch && b.lastSwitch.nome
    ? `🔁 Entra *${b.lastSwitch.nome}*!`
    : null;
  let acaoLinha = null;
  if (la.action === 'defend' || la.action === ':defend') {
    acaoLinha = la.impedido
      ? '🛡️ Tentou defender, mas ficou impedido pelo status.'
      : '🛡️ Entrou em posição defensiva (esquiva / metade do dano).';
  } else if (la.action === 'item' || la.action === ':item') {
    if (la.hpDepois != null) {
      acaoLinha = `🧪 Usou *${la.itemId || 'poção'}* e recuperou HP (${la.hpAntes}→${la.hpDepois}/${la.hpMax}).`;
    } else if (la.statusCleared) {
      acaoLinha = `💊 Curou status *${la.statusCleared}* com \`${la.itemId}\`.`;
    } else if (la.impedido) {
      acaoLinha = '🧪 Tentou usar item, mas ficou impedido pelo status.';
    } else {
      acaoLinha = `🧪 Usou item \`${la.itemId || '?'}\`.`;
    }
  } else if (la.dano != null) {
    acaoLinha = `Último golpe: ${la.dano} de dano.`;
  }
  const cabeçalhoTurno = [
    switchLine,
    `⚔️ Turno ${b.turnIndex != null ? b.turnIndex : '?'}`,
    acaoLinha
  ].filter(Boolean).join('\n');
  const estado = formatBattleState(b, prefix);
  if (estado.startsWith('❌')) return estado;
  return [cabeçalhoTurno, '', estado].filter(Boolean).join('\n');
}

const BOLA_LABEL = {
  pokebola: { nome: 'Pokébola', emoji: '🔴' },
  'grande-bola': { nome: 'Grande Bola', emoji: '🔵' },
  'ultra-bola': { nome: 'Ultra Bola', emoji: '🟡' }
};

function qtyBall(inv, id) {
  const row = (inv || []).find(i => i.itemId === id);
  return row ? Number(row.qty) || 0 : 0;
}

function chanceComBola(chanceBase, bolaId) {
  const limits = { pokebola: [1, 75], 'grande-bola': [1.5, 88], 'ultra-bola': [2, 95] };
  const [mult, lim] = limits[bolaId] || [1, 75];
  return Math.min(lim, Math.round(Number(chanceBase || 0) * mult));
}

function formatCaptureMenu(encounterData, prefix = '!pk') {
  const enc = encounterData && encounterData.encounter;
  if (!enc) return '❓ Não há caçada/encontro em andamento.';
  if (!enc.aguardandoCaptura) {
    return '⚔️ Derrote o Pokémon selvagem antes de capturar.';
  }
  const nome = (enc.pokemonO && (enc.pokemonO.nome || enc.pokemonO.species)) || 'selvagem';
  const inv = enc.inventory || [];
  const base = enc.chanceBaseCaptura;
  const tent = Number(enc.tentativasCaptura) || 0;
  const linhas = ['pokebola', 'grande-bola', 'ultra-bola'].map((id, i) => {
    const meta = BOLA_LABEL[id];
    return `${prefix} capturar ${i + 1} — ${meta.emoji} ${meta.nome} — ${qtyBall(inv, id)} disponível(is) — ${chanceComBola(base, id)}% de chance`;
  });
  return [
    `🎯 *Escolha a bola para capturar ${nome}!*`,
    ...linhas,
    '',
    `Tentativas restantes: ${3 - tent} de 3.`,
    `📖 Como capturar: ${prefix} capturar <bola>`
  ].join('\n');
}

function formatCaptureResult(data, prefix = '!pk') {
  if (data && data.reconciled && data.summary && typeof data.capturou === 'undefined') {
    const s = data.summary;
    data = {
      ...data,
      capturou: s.capturou,
      fugiu: s.fugiu,
      chance: s.chance,
      bola: s.bola,
      tentativa: s.tentativa
    };
  }
  const bolaId = data.bola || 'pokebola';
  const meta = BOLA_LABEL[bolaId] || { nome: bolaId, emoji: '⚪' };
  const chance = data.chance != null ? data.chance : '?';
  const selvagem = (data.encounter && data.encounter.pokemonO
    && (data.encounter.pokemonO.nome || data.encounter.pokemonO.species))
    || (data.registro && (data.registro.nome || data.registro.species))
    || 'Pokémon';

  if (data.capturou) {
    const rew = data.recompensa || {};
    const idx = data.indice != null ? data.indice + 1 : '?';
    const linhas = [
      `✅ ${meta.nome} lançada: captura concluída! (${chance}% de chance)`,
      `📚 Registrado na Pokédex e adicionado à coleção como nº ${idx}.`,
      `🔥 Sequência de capturas: ${rew.sequencia != null ? rew.sequencia : data.sequenciaCapturas || 0}`,
      `✨ +${rew.xp || 0} XP • +${rew.moedas || 0} moedas`,
      data.subida
        ? `🌟 *${data.subida.nome}* subiu para o nível ${data.subida.nivel}!`
        : null,
      data.duplicate ? '_idempotente: mesma operação já persistida_' : null
    ];
    return linhas.filter(Boolean).join('\n');
  }

  if (data.fugiu) {
    return [
      `💨 A ${meta.nome} falhou e *${selvagem}* fugiu! (${chance}% de chance)`,
      (data.tentativa || 0) >= 3 ? 'As três tentativas de captura acabaram.' : null,
      '💔 A sequência de capturas foi encerrada.',
      '✨ +1 XP pela batalha.'
    ].filter(Boolean).join('\n');
  }

  return [
    `💥 A ${meta.nome} falhou, mas *${selvagem}* continua aqui! (${chance}% de chance)`,
    formatCaptureMenu({
      encounter: {
        aguardandoCaptura: true,
        pokemonO: data.encounter && data.encounter.pokemonO,
        chanceBaseCaptura: data.chanceBase,
        tentativasCaptura: data.tentativa || 0,
        inventory: data.inventory || []
      }
    }, prefix)
  ].join('\n');
}

const GYM_SUB = {
  des: 'desafiar', dsf: 'desafiar', desafiar: 'desafiar',
  tm: 'time', time: 'time',
  pot: 'pocao', pocao: 'pocao', 'poção': 'pocao',
  fru: 'fruta', fruta: 'fruta', 'fruta-dourada': 'fruta-dourada', dourada: 'fruta-dourada',
  ran: 'ranking', ranking: 'ranking',
  hist: 'historico', historico: 'historico', 'histórico': 'historico'
};

function resolveGymSubcommand(token) {
  const raw = String(token || '').toLowerCase();
  return GYM_SUB[raw] || raw || null;
}

function formatGymsOverview(data, prefix = '!pk') {
  const lines = [
    '🏛️ *Ginásios do Odisseu*',
    data.desafioHabilitado === false
      ? `🚧 Desafio 3×3 ainda bloqueado no adaptador JVM. ${data.desafioBloqueio || ''}`.trim()
      : `Desafiar: ${prefix} gin desafiar <id>`,
    ''
  ];
  for (const g of data.gyms || []) {
    const lock = g.desbloqueado ? '✅' : '🔒';
    const lider = g.ocupacao
      ? `líder *${g.ocupacao.nome || g.ocupacao.playerId}*`
      : `NPC *${g.liderNpc}*`;
    const badge = g.insignia ? ` · insígnia ${g.insignia}` : '';
    lines.push(`${lock} ${g.nome} (nv.${g.nivel}) — ${lider}${badge}`);
  }
  lines.push('');
  lines.push(`Detalhe: ${prefix} gin <pedra|agua|eletrico|planta|fogo>`);
  lines.push(`Time: ${prefix} gin time · Recuperar: ${prefix} gin pocao <id> <1-3>`);
  return lines.filter(v => v !== null).join('\n');
}

function formatGymDetail(data, prefix = '!pk') {
  const g = data.gym || {};
  if (!g.id) return '❓ Ginásio desconhecido. Use pedra, agua, eletrico, planta ou fogo.';
  const ocup = g.ocupacao;
  const lines = [
    `${g.nome} — Nv.${g.nivel}`,
    g.desbloqueado ? null : '🔒 Vença os ginásios anteriores primeiro.',
    ocup
      ? `Líder: *${ocup.nome || ocup.playerId}* (save ${ocup.saveId})`
      : `Líder NPC: *${g.liderNpc}* · equipe ${((g.timeNpc) || []).join(', ')}`,
    `Recompensa: ${g.item}`,
    data.desafioHabilitado === false
      ? `🚧 Desafiar bloqueado: ${data.desafioBloqueio || 'combate 3×3 pendente no JVM.'}`
      : `Desafiar: ${prefix} gin desafiar ${g.id}`
  ];
  if (ocup && Array.isArray(ocup.time) && ocup.time.length) {
    lines.push('');
    lines.push('*Defensores*');
    ocup.time.forEach((p, i) => {
      const mot = (ocup.motivacao && ocup.motivacao[i] != null)
        ? ocup.motivacao[i]
        : (p.motivacaoGinasio != null ? p.motivacaoGinasio : '?');
      lines.push(`${i + 1}. ${p.nome || '?'} · motivação ${mot}%`);
    });
  }
  return lines.filter(Boolean).join('\n');
}

function formatGymTeam(data, prefix = '!pk') {
  if (!data.completo) {
    return [
      '❓ Time de ginásio incompleto.',
      `Use ${prefix} gin time auto ou ${prefix} gin time 1,2,3 (números da coleção).`
    ].join('\n');
  }
  const nomes = (data.membros || []).map(p => `*${p.nome || '?'}*`).join(', ');
  return [
    `✅ Time de ginásio: ${nomes}`,
    `Desafiar: ${prefix} gin desafiar <pedra|agua|eletrico|planta|fogo>`
  ].join('\n');
}

function formatGymTeamSaved(data, prefix = '!pk') {
  const idxs = (data.indices || data.timeGinasio || []).map(i => Number(i) + 1).join(', ');
  return [
    `✅ Time de ginásio salvo (índices coleção: ${idxs || '?'}).`,
    `Consulte: ${prefix} gin time`,
    `Desafiar: ${prefix} gin desafiar <id>`
  ].join('\n');
}

function formatGymHeal(data) {
  return [
    `🧪 Defensor ${Number(data.indice) + 1} recuperou motivação no ginásio *${data.gymId}*:`,
    `${data.antes}% → ${data.depois}%`,
    `${data.label || data.item} consumida.`,
    data.duplicate ? '_idempotente: mesma operação já persistida_' : null
  ].filter(Boolean).join('\n');
}

function formatGymBlocked(action) {
  const map = {
    ranking: '🚧 Ranking de ginásio ainda não persistido no Datomic.',
    historico: '🚧 Histórico de ginásio ainda não persistido no Datomic.',
    cancelar: '🚧 Cancelar raide ainda não está no núcleo JVM.',
    abrir: '🚧 A aparição automática de raides foi removida; abertura admin não está no WhatsApp.'
  };
  return map[action] || formatUnavailable(`ginasio ${action || ''}`.trim());
}

const RAID_SUB = {
  ent: 'entrar', entrar: 'entrar',
  sai: 'sair', sair: 'sair',
  ini: 'iniciar', iniciar: 'iniciar',
  atk: 'atacar', atacar: 'atacar',
  cap: 'capturar', capturar: 'capturar',
  tm: 'time', time: 'time',
  abr: 'abrir', abrir: 'abrir',
  can: 'cancelar', cancelar: 'cancelar'
};

function resolveRaidSubcommand(token) {
  const raw = String(token || '').toLowerCase();
  return RAID_SUB[raw] || raw || null;
}

function formatRaidStatus(data, prefix = '!pk') {
  const r = data && data.raid;
  if (!r) {
    return [
      '🏛️ Nenhuma raide ativa neste chat.',
      `Ajuda: ${prefix} raide ajuda`
    ].join('\n');
  }
  const chefe = r.chefe || {};
  const lines = [
    `⚔️ *Raide — ${chefe.nome || 'Chefe'}* / ${r.liga || '?'}`,
    `🏛️ ${r.nomeGinasio || r.ginasio || 'Ginásio'} · fase *${r.fase}*`,
    r.fase === 'combate' || r.fase === 'vitoria'
      ? `❤️ Chefe: ${r.hpChefe != null ? r.hpChefe : '?'} / ${r.hpMax != null ? r.hpMax : '?'}`
      : `Inscritos: ${r.nParticipantes || 0}/6`,
    r.vez ? `Vez: ${r.suaVez ? '*você*' : r.vez}` : null,
    r.capturaPendente
      ? `🎯 Captura aberta: ${prefix} raide capturar <bola>`
      : null,
    '',
    r.fase === 'inscricoes'
      ? `${prefix} raide entrar auto · ${prefix} raide iniciar · ${prefix} raide sair`
      : (r.fase === 'combate'
        ? `${prefix} raide atacar <1-4>`
        : null)
  ];
  if (Array.isArray(r.participantes) && r.participantes.length) {
    lines.push('', '*Participantes*');
    for (const p of r.participantes) {
      lines.push(
        `• ${p.voce ? '👉 ' : ''}${p.nome || p.playerId}`
        + (p.hp != null ? ` · HP ${p.hp}` : '')
        + (p.dano ? ` · dano ${p.dano}` : '')
      );
    }
  }
  return lines.filter(v => v !== null).join('\n');
}

function formatRaidJoin(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  return [
    `✅ Você entrou na raide de *${r.nomeGinasio || r.ginasio || 'ginásio'}*!`,
    `Inscritos: ${r.nParticipantes || '?'}/6 · liga ${r.liga || '?'}`,
    data.collectionIndex != null
      ? `Pokémon nº ${Number(data.collectionIndex) + 1} da coleção.`
      : null,
    `Quando houver ≥2: ${prefix} raide iniciar`
  ].filter(Boolean).join('\n');
}

function formatRaidLeave() {
  return '👋 Você saiu das inscrições da raide.';
}

function formatRaidStart(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  return [
    `🔥 Combate iniciado! Chefe com ${r.hpChefe || r.hpMax || '?'} HP.`,
    `Vez: ${r.vez || '?'}`,
    `Ataque: ${prefix} raide atacar <1-4>`
  ].join('\n');
}

function formatRaidAttack(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  if (data.venceu) {
    return [
      `🏅 *Vitória!* O chefe caiu (${data.dano || 0} de dano no golpe final).`,
      'Recompensas creditadas a quem causou dano (>0).',
      `Captura: ${prefix} raide capturar <bola>`
    ].join('\n');
  }
  if (data.derrota) {
    return '💀 A raide foi derrotada. Sem recompensas.';
  }
  return [
    `💥 Dano ${data.dano != null ? data.dano : '?'} · revide ${data.revide != null ? data.revide : 0}`,
    `❤️ Chefe: ${r.hpChefe != null ? r.hpChefe : '?'} / ${r.hpMax != null ? r.hpMax : '?'}`,
    r.vez ? `Próxima vez: ${r.vez}` : null,
    data.duplicate ? '_idempotente: mesmo operationId_' : null,
    `Continue: ${prefix} raide atacar <1-4>`
  ].filter(Boolean).join('\n');
}

function formatRaidCapture(data, prefix = '!pk') {
  const bola = data.bola || 'pokebola';
  if (data.capturou) {
    return [
      `✅ Captura concluída com ${bola}! (${data.chance != null ? data.chance : '?'}%)`,
      'O chefe entrou na sua coleção (nv.1).'
    ].join('\n');
  }
  return [
    `💥 A ${bola} falhou (${data.chance != null ? data.chance : '?'}%).`,
    `Tentativas: ${data.tentativas || '?'}/3`,
    `Tente de novo: ${prefix} raide capturar <bola>`
  ].join('\n');
}

function formatJoyStatus(data, prefix = '!pk') {
  const beds = (data && data.enfermaria) || [];
  const feridos = (data && data.feridos) || [];
  const mins = (data && data.tempoTratamentoMinutos) || 30;
  const lines = ['🏥 *Centro Pokémon — Enfermeira Joy*'];
  if (beds.length) {
    lines.push(`Leitos: ${beds.length} (${data.prontos || 0} pronto(s))`);
    for (const b of beds) {
      const nome = (b.pokemon && (b.pokemon.nome || b.pokemon.species)) || '?';
      const rest = b.pronto
        ? 'pronto — use o comando de novo para recolher'
        : `~${Math.ceil((b.restanteMs || 0) / 60000)} min`;
      lines.push(`• ${nome} — ${rest}`);
    }
  } else {
    lines.push('Nenhum Pokémon na enfermaria.');
  }
  if (feridos.length) {
    lines.push('', '*Feridos na coleção* (índice 1-based):');
    for (const p of feridos) {
      const idx = (p.collectionIndex != null ? p.collectionIndex : 0) + 1;
      const hp = `${p.hpAtual != null ? p.hpAtual : '?'}/${p.hp != null ? p.hp : '?'}`;
      const st = p.status ? ` [${p.status}]` : '';
      lines.push(`${idx}. ${p.nome || p.species || '?'} HP ${hp}${st}`);
    }
    lines.push(`Envie: ${prefix} joy 1,2`);
  } else if (!beds.length) {
    lines.push('✨ Seu time/coleção já está saudável.');
  }
  lines.push(`Cura: ${mins} min · ${prefix} joy <números>`);
  return lines.join('\n');
}

function formatJoySend(data, prefix = '!pk') {
  const nome = (data && data.pokemon && (data.pokemon.nome || data.pokemon.species)) || 'Pokémon';
  return [
    `🏥 A Enfermeira Joy recebeu *${nome}*.`,
    'Eles voltarão com HP cheio em 30 minutos.',
    `Acompanhe: ${prefix} joy · ${prefix} time`
  ].join('\n');
}

function formatJoyCollect(data, prefix = '!pk') {
  const n = (data && data.n) || 0;
  const nomes = ((data && data.curados) || [])
    .map((p) => p.nome || p.species || '?')
    .join(', ');
  if (n <= 0) {
    return `⏳ Ainda não há Pokémon prontos. ${prefix} joy`;
  }
  return [
    `✨ A Joy devolveu ${n} Pokémon curado(s)!`,
    nomes ? `• ${nomes}` : null,
    `${prefix} time`
  ].filter(Boolean).join('\n');
}

function formatAdventureStatus(data, prefix = '!pk') {
  const eggs = (data && data.eggs) || [];
  const incu = (data && data.incubators) || [];
  const inv = eggs.filter((e) => e.state !== 'hatched');
  const lines = [
    '🥚 *Adventure Sync — Ovos*',
    `Inventário: ${data.inventarioCount || inv.length}/${data.limiteInventario || 9}`
  ];
  if (!inv.length) {
    lines.push('Nenhum ovo no inventário.');
  } else {
    inv.forEach((e, i) => {
      const n = i + 1;
      const cat = e.category || '?';
      if (e.state === 'inventory') {
        lines.push(`${n}. ${cat} (${e.species || '?'}) — disponível · ${prefix} incubar ${n}`);
      } else if (e.state === 'incubating') {
        const min = Math.ceil((e.restanteMs || 0) / 60000);
        lines.push(`${n}. ${cat} — incubando (~${min} min) save ${e.saveId || '?'}`);
      } else if (e.state === 'ready-to-hatch' || e.state === 'delivery-pending') {
        lines.push(`${n}. ${cat} — *pronto!* ${prefix} ovo nascer ${n}`);
      } else {
        lines.push(`${n}. ${cat} — ${e.state}`);
      }
    });
  }
  lines.push('', '*Incubadoras*');
  for (const i of incu) {
    const livre = i.livre ? 'livre' : `ovo ${i.activeEggId || '?'}`;
    lines.push(`• ${i.incubatorId} (${i.kind}) — ${livre}`);
  }
  lines.push(
    '',
    `Comandos: ${prefix} incubar <n> · ${prefix} ovo · ${prefix} incubadoras`,
    '⚠️ Nascimento sob demanda (sem notificação automática neste adaptador).'
  );
  return lines.join('\n');
}

function formatEggIncubate(data, prefix = '!pk') {
  const e = (data && data.egg) || {};
  return [
    `🥚 Incubando *${e.category || 'ovo'}* (${e.species || '?'}) em ${e.incubatorId || 'permanente'}.`,
    `Save carimbado: ${e.saveId || '?'} · ${prefix} ovos`
  ].join('\n');
}

function formatEggHatch(data, prefix = '!pk') {
  const reg = (data && data.registro) || {};
  const nome = reg.nome || reg.species || 'Pokémon';
  return [
    `🎉 O ovo eclodiu! *${nome}* nv.${reg.nivel || 1} entrou na coleção.`,
    `${prefix} time · ${prefix} ovos`
  ].join('\n');
}

function resolveEggIndex(data, oneBased) {
  const eggs = ((data && data.eggs) || []).filter((e) => e.state !== 'hatched');
  const n = Number(oneBased);
  if (!Number.isFinite(n) || n < 1 || n > eggs.length) return null;
  return eggs[n - 1];
}

function formatShop(data, prefix = '!loja') {
  const itens = (data && data.itens) || [];
  const lines = [
    '🏪 *Loja Pokémon*',
    `💰 Saldo: ${data.coins != null ? data.coins : 0} moedas`,
    `🎒 Mochila: ${data.ocupacaoMochila || 0}/${data.capacidadeMochila || '?'} (${data.livres != null ? data.livres : '?'} livres)`,
    ''
  ];
  for (const it of itens) {
    lines.push(`${it.emoji || '•'} *${it.nome}* — ${it.preco} moedas (\`${it.itemId}\`)`);
  }
  lines.push('', `Comprar: ${prefix} comprar <item>`, `Detalhes: ${prefix} detalhes <item>`, 'Inventário: !mochila');
  return lines.join('\n');
}

function formatShopBuy(data) {
  const nome = (data && (data.nome || data.itemId)) || 'item';
  const emoji = (data && data.emoji) || '✅';
  if (data && data.expansao) {
    return `${emoji} Comprou expansão de mochila! Expansões: ${data.expansoesMochila50 || '?'}. Saldo: ${data.coins} moedas.`;
  }
  return `${emoji} Comprou *${nome}*! Saldo: ${data.coins} moedas.`;
}

function formatShopDetails(item, prefix = '!loja') {
  if (!item) return `❓ Item não encontrado. Use ${prefix}.`;
  return [
    `${item.emoji || '•'} *${item.nome}* (\`${item.itemId}\`)`,
    item.preco != null ? `💰 Preço: ${item.preco} moedas` : '🎁 Não está à venda',
    item.descricao || ''
  ].filter(Boolean).join('\n');
}

function formatInventory(data, prefix = '!mochila') {
  const itens = (data && data.itens) || [];
  const lines = [
    '🎒 *Mochila*',
    `💰 ${data.coins != null ? data.coins : 0} moedas · ${data.ocupacaoMochila || 0}/${data.capacidadeMochila || '?'} slots`,
    ''
  ];
  if (!itens.length) {
    lines.push('Vazia. Compre em !loja.');
  } else {
    for (const it of itens) {
      lines.push(`${it.emoji || '•'} ${it.nome || it.itemId} ×${it.qty}`);
    }
  }
  const pendentes = (data && data.pendingRewards) || [];
  lines.push('');
  if (pendentes.length) {
    lines.push(`🎁 Recompensas pendentes: ${formatMissionItems(pendentes)}.`,
      `Use ${prefix} resgatar para recolher o que couber.`);
  } else {
    lines.push(`🎁 Recompensas pendentes: nenhuma.`, `Expansão: !loja comprar mochila.`);
  }
  lines.push(`${prefix}`);
  return lines.join('\n');
}

const MISSION_ITEM_NAMES = {
  pokebola: 'Pokébola', 'grande-bola': 'Grande Bola', 'ultra-bola': 'Ultra Bola',
  reviver: 'Reviver', 'catalisador-evolutivo': 'Catalisador Evolutivo',
  'revestimento-metalico': 'Revestimento Metálico', 'escama-dragao': 'Escama de Dragão',
  upgrade: 'Upgrade', protetor: 'Protetor', 'pedra-rei': 'Pedra do Rei',
  eletrizador: 'Eletrizador', magmarizador: 'Magmarizador',
  'tecido-ceifador': 'Tecido do Ceifador', 'escama-prisma': 'Escama Prisma',
  'chicote-doce': 'Chicote Doce', 'sache-perfumado': 'Sachê Perfumado'
};

function formatMissionItems(items) {
  const values = Array.isArray(items) ? items : [];
  if (!values.length) return 'nenhum item';
  return values.map(item => {
    const id = item.itemId || item['item-id'] || '?';
    const qty = Number(item.qty != null ? item.qty : 0);
    return `${qty}× ${MISSION_ITEM_NAMES[id] || id}`;
  }).join(', ');
}

function formatMissionReward(mission) {
  const parts = [];
  if (mission.xp) parts.push(`+${mission.xp} PE do treinador`);
  if (mission.moedas) parts.push(`+${mission.moedas} moedas`);
  for (const [key, label] of [['pokebolas', 'Pokébolas'], ['grandes', 'Grandes Bolas'],
    ['ultras', 'Ultra Bolas'], ['catalisadores', 'Catalisador Evolutivo']]) {
    if (mission[key]) parts.push(`${mission[key]} ${label}`);
  }
  return parts.length ? parts.join(' • ') : 'recompensa histórica';
}

function formatMissionList(kind, group, prefix) {
  const daily = kind === 'daily';
  const title = daily ? '📋 *Missões diárias*' : '📅 *Missões semanais*';
  const period = group && group.period ? group.period : '?';
  const lines = [
    `${title} — ${daily ? period : `semana de ${period}`}`,
    ...(daily && group && group.level != null ? [`Nível de referência: ${group.level}`] : []),
    ''
  ];
  for (const mission of (group && group.missions) || []) {
    const state = mission.estado;
    const icon = state === 'rewarded' ? '🎁' : state === 'completed' ? '✅' : '⬜';
    lines.push(`${icon} *${mission.nome || mission.objetivo || mission.id}*: ${mission.objetivo || ''} — ${mission.progresso || 0}/${mission.meta || 0}`,
      `   ${formatMissionReward(mission)}${state === 'rewarded' ? ' (resgatada)' : ''}`);
  }
  const ready = ((group && group.missions) || []).some(m => m.estado === 'completed');
  lines.push('', ready
    ? `🎁 Há recompensa disponível: ${prefix} missoes ${daily ? 'diarias' : 'semanais'} resgatar.`
    : 'Continue jogando para concluir os objetivos.');
  return lines.join('\n');
}

function missionScope(scope) {
  const normalized = String(scope || 'all').normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '').toLowerCase();
  if (['daily', 'diaria', 'diarias'].includes(normalized)) return 'daily';
  if (['weekly', 'semanal', 'semanais'].includes(normalized)) return 'weekly';
  return 'all';
}

function formatMissions(data, scope = 'all', prefix = '!pk') {
  const selectedScope = missionScope(scope);
  const sections = [];
  if (selectedScope !== 'weekly') sections.push(formatMissionList('daily', data && data.daily, prefix));
  if (selectedScope !== 'daily') sections.push(formatMissionList('weekly', data && data.weekly, prefix));
  const event = data && Array.isArray(data.events) ? data.events[0] : null;
  if (selectedScope === 'all' && event && event.status === 'ended') {
    sections.push(`🏁 *${event.nome}* — evento encerrado\nHistórico preservado; não há resgate ativo.`);
  }
  return sections.join('\n\n');
}

function missionResult(data) {
  return data && data.reconciled && data.summary ? (data.summary.result || data.summary) : (data || {});
}

function formatMissionClaim(data, kind, prefix = '!pk') {
  const result = missionResult(data);
  if (result.empty) return `📋 Nenhuma missão ${kind === 'daily' ? 'diária' : 'semanal'} concluída disponível para resgatar.`;
  const lines = [`✅ ${result.ids && result.ids.length || 0} missão(ões) resgatada(s)!`];
  if (result.xp) lines.push(`✨ +${result.xp} PE do treinador`);
  if (result.coins) lines.push(`💰 +${result.coins} moedas`);
  if (result.items && result.items.length) lines.push(`🎁 ${formatMissionItems(result.items)}`);
  if (result.pending && result.pending.length) lines.push(`🎒 Sem espaço: ${formatMissionItems(result.pending)}. Use !mochila resgatar.`);
  if (result.duplicate || data && data.reconciled) lines.push('_Mesma operação já confirmada._');
  lines.push(`${prefix} missoes`);
  return lines.join('\n');
}

function formatGift(data, prefix = '!presente') {
  const result = missionResult(data);
  const target = String(result.targetPlayerId || '?').split('@')[0];
  const lines = [`🎁 Presente enviado para @${target}!`, `🎁 ${formatMissionItems(result.items || [])}`];
  if (result.pending && result.pending.length) lines.push(`🎒 Itens sem espaço ficaram pendentes: ${formatMissionItems(result.pending)}.`);
  lines.push('📋 Envio registrado nas missões diárias e semanais.');
  if (result.duplicate || data && data.reconciled) lines.push('_Mesma operação já confirmada._');
  lines.push(`${prefix} @amigo`);
  return lines.join('\n');
}

function formatMissionPending(data, prefix = '!mochila') {
  const result = missionResult(data);
  if (result.empty) return '🎒 Nenhuma recompensa pendente coube na mochila agora.';
  const lines = [`🎁 Recompensas recolhidas: ${formatMissionItems(result.added || result.items || [])}.`];
  if (result.pending && result.pending.length) lines.push(`🎒 Ainda pendentes: ${formatMissionItems(result.pending)}.`);
  if (result.duplicate || data && data.reconciled) lines.push('_Mesma operação já confirmada._');
  lines.push(`${prefix}`);
  return lines.join('\n');
}

function formatItemUse(data, prefix = '!pk') {
  const poke = (data && data.pokemon) || {};
  const nome = poke.nome || poke.species || 'Pokémon';
  if (data && data.kind === 'status') {
    return `✨ Status removido de *${nome}* com \`${data.itemId}\`. ${prefix} time`;
  }
  if (data && data.kind === 'pocao') {
    return `🧪 *${nome}* HP ${data.hpAntes}→${data.hpDepois}/${data.hpMax}. ${prefix} time`;
  }
  if (data && data.kind === 'reviver') {
    return `💎 *${nome}* foi revivido com HP completo. 1 Reviver consumido. ${prefix} time`;
  }
  if (data && data.kind === 'equip') {
    return `🎒 *${nome}* agora segura \`${data.itemId}\`.`;
  }
  return `✅ Item usado em *${nome}*.`;
}

function findShopItem(data, rawId) {
  const id = String(rawId || '').toLowerCase();
  return ((data && data.itens) || []).find((it) => it.itemId === id) || null;
}

function formatRaidEligible(data, prefix = '!pk') {
  const aptos = (data && data.aptos) || [];
  if (!aptos.length) {
    return `❓ Nenhum Pokémon apto para a liga *${data.liga || '?'}*. Cure e confira o nível.`;
  }
  const linhas = aptos.slice(0, 12).map((p, i) => {
    const n = (p.collectionIndex != null ? p.collectionIndex : i) + 1;
    return `${n}. ${p.nome || '?'} Nv.${p.nivel || '?'}`;
  });
  return [
    `⚔️ *Aptos para a raide* (liga ${data.liga || '?'})`,
    ...linhas,
    `${prefix} raide entrar <n> · ${prefix} raide entrar auto`
  ].join('\n');
}

/** Aliases alinhados ao legado (ajuda.cljs / core). */
const ALIASES = {
  professor: 'professor', prof: 'professor',
  titulo: 'titulo', título: 'titulo', titulos: 'titulo', 'títulos': 'titulo',
  amizade: 'amizade', happy: 'amizade',
  treinador: 'treinador', perfil: 'treinador', tre: 'treinador',
  meus: 'time', pokemons: 'time',
  time: 'time', equipe: 'time', tm: 'time',
  shiny: 'shiny',
  pc: 'pc', computador: 'pc', centro: 'pc',
  espaco: 'espaco', espaço: 'espaco',
  times: 'times',
  liga: 'liga', ligas: 'liga', lig: 'liga',
  pokedex: 'pokedex', dex: 'pokedex', colecao: 'pokedex', 'coleção': 'pokedex',
  favorito: 'favorito', fav: 'favorito',
  save: 'save',
  saves: 'saves', 'saves-list': 'saves',
  iniciar: 'iniciar', criar: 'iniciar',
  inicial: 'inicial', iniciais: 'inicial', ini: 'inicial',
  menu: 'menu',
  ativar: 'ativar',
  capturar: 'capturar', cap: 'capturar',
  cacar: 'cacar', caçar: 'cacar', cac: 'cacar', hunt: 'cacar',
  atacar: 'atacar', ataque: 'atacar', atk: 'atacar',
  defender: 'defender', defesa: 'defender', def: 'defender',
  esquivar: 'defender', evasiva: 'defender',
  ginasio: 'ginasio', ginásio: 'ginasio', ginasios: 'ginasio', ginásios: 'ginasio',
  gin: 'ginasio',
  raid: 'raid', raide: 'raid', raides: 'raid', raids: 'raid',
  joy: 'joy', enfermeira: 'joy', enfermaria: 'joy', hospital: 'joy',
  ovos: 'ovos', ovo: 'ovos',
  incubar: 'incubar',
  incubadoras: 'incubadoras',
  loja: 'loja', shop: 'loja',
  mochila: 'mochila', mch: 'mochila', inventario: 'mochila', inventário: 'mochila',
  missoes: 'missoes', 'missões': 'missoes', mis: 'missoes',
  presente: 'presente', presentes: 'presente', pre: 'presente',
  curar: 'curar', cura: 'curar', cur: 'curar',
  pocao: 'pocao', 'poção': 'pocao', pot: 'pocao', vida: 'pocao',
  'pocao-maxima': 'pocao-maxima', maxima: 'pocao-maxima', pmax: 'pocao-maxima',
  clima: 'clima', areas: 'clima', áreas: 'clima', mapa: 'clima',
  eventos: 'eventos', evento: 'eventos', evt: 'eventos',
  recibo: 'recibo', op: 'recibo', operation: 'recibo',
  escolher: 'escolher', esc: 'escolher', trocar: 'escolher', troca: 'escolher',
  evoluir: 'evoluir', evo: 'evoluir', evolucao: 'evoluir', evolução: 'evoluir',
  aprender: 'aprender', apr: 'aprender',
  reaprender: 'reaprender', reap: 'reaprender',
  removergolpe: 'removergolpe', rmg: 'removergolpe',
  cancelar: 'cancelar', can: 'cancelar',
  batalhar: 'batalhar', abrir: 'batalhar', entrar: 'batalhar',
  sair: 'sair', sai: 'sair'
};

function resolveCommand(tokens) {
  if (!tokens || !tokens.length) return 'menu';
  const raw = (tokens[0] || '').toLowerCase();
  // Alias legado: !pk 1 … !pk 4 escolhe o golpe pelo índice.
  if (/^[1-4]$/.test(raw)) return 'atacar';
  return ALIASES[raw] || null;
}

module.exports = {
  PAGE_SIZE,
  envelope,
  formatTrainer,
  formatCollection,
  formatStorage,
  formatTeams,
  formatSpace,
  collectionPageSlice,
  formatPokedex,
  formatDiscoveries,
  formatFicha,
  formatFavorite,
  formatActiveSave,
  formatSavesList,
  formatEnsure,
  formatMainMenu,
  formatStartersMenu,
  formatStartJourney,
  formatStarterChosen,
  formatActivate,
  formatReceipt,
  formatError,
  formatUnavailable,
  formatFriendship: d => `💛 ${d && d.species ? d.species : 'Pokémon'}: ${d && d.value != null ? d.value : 70}/255 — ${d && d.label ? d.label : 'ainda se conhecendo'}`,
  formatTitles: d => {
    const titles = Array.isArray(d && d.titles) ? d.titles : [];
    return `🏅 Títulos${d && d.selected ? ` (atual: ${d.selected})` : ''}:\n` +
      (titles.length ? titles.map((t, i) => `${i + 1}. ${t.label || t.id}`).join('\n') : 'Nenhum título desbloqueado.');
  },
  formatProfessor: d => `🎓 Professor: ${d && d.token ? `confirme com ${d.token}` : (d && d.used ? 'cartão utilizado.' : 'operação concluída.')}`,
  formatHuntEligibility,
  formatHuntStart,
  formatBattleState,
  formatBattleTurn,
  formatWorldInfo,
  formatEventInfo,
  barraHp,
  menuGolpes,
  textoRaridade,
  formatCaptureMenu,
  formatCaptureResult,
  resolveCommand,
  resolveGymSubcommand,
  formatGymsOverview,
  formatGymDetail,
  formatGymTeam,
  formatGymTeamSaved,
  formatGymHeal,
  formatGymBlocked,
  formatGymChallengeStart,
  formatGymResolve,
  resolveRaidSubcommand,
  formatRaidStatus,
  formatRaidJoin,
  formatRaidLeave,
  formatRaidStart,
  formatRaidAttack,
  formatRaidCapture,
  formatRaidEligible,
  formatJoyStatus,
  formatJoySend,
  formatJoyCollect,
  formatAdventureStatus,
  formatEggIncubate,
  formatEggHatch,
  resolveEggIndex,
  formatShop,
  formatShopBuy,
  formatShopDetails,
  formatInventory,
  formatMissions,
  formatMissionClaim,
  formatGift,
  formatMissionPending,
  formatMissionItems,
  missionScope,
  formatItemUse,
  findShopItem,
  filtrarColecao,
  interpretarFiltros,
  golpesLinha,
  formatSetActive,
  formatEvolve,
  formatLearnMove,
  formatForgetMove,
  formatOfferMove,
  formatRefuseOffer,
  formatRelearnMove,
  formatMoveOfferMenu,
  formatRelearnMenu,
  formatPvpChallenge,
  formatPvpBattle,
  formatPvpIncompatible
};

function formatPvpChallenge(battle, playerName, prefix = '!pk') {
  const b = battle || {};
  const px = b.pokemonX || {};
  const faixa = b.faixaNiveis || b.faixa || {};
  const min = faixa.min != null ? faixa.min : '?';
  const max = faixa.max != null ? faixa.max : '?';
  const nome = playerName || (b.nomes && b.nomes.x) || 'Treinador';
  return [
    '⚔️ *Desafio Pokémon!*',
    '',
    `🎮 *${nome}* está procurando um adversário!`,
    `🐉 Pokémon: *${px.nome || '?'}*`,
    `⭐ Nível: ${px.nivel || 1}`,
    '',
    `📊 Níveis aceitos: ${min} a ${max}`,
    `Digite ${prefix} batalhar para aceitar com seu Pokémon ativo!`,
    '⏳ O desafio expira em 5 minutos.'
  ].join('\n');
}

function formatPvpIncompatible(battle, pokemon, prefix = '!pk') {
  const faixa = (battle && (battle.faixaNiveis || battle.faixa)) || {};
  const p = pokemon || {};
  return [
    '❌ *Pokémon incompatível!*',
    '',
    `🐲 Seu Pokémon: *${p.nome || '?'}*`,
    `⭐ Nível atual: ${p.nivel || 1}`,
    '',
    `⚔️ O desafio aceita níveis ${faixa.min || '?'} a ${faixa.max || '?'}.`,
    `🔄 Selecione outro ativo: ${prefix} escolher <número> e envie ${prefix} batalhar.`
  ].join('\n');
}

function formatPvpBattle(battle, prefix = '!pk', opts = {}) {
  const b = battle || {};
  if (b.aguardando || b.phase === 'waiting') {
    return formatPvpChallenge(b, opts.playerName, prefix);
  }
  const px = b.pokemonX || {};
  const po = b.pokemonO || {};
  const nomes = b.nomes || {};
  const vez = b.vez;
  const jog = b.jogadores || {};
  const vezNome = vez === 'x' ? (nomes.x || 'X') : (nomes.o || 'O');
  const lines = [
    '⚔️ *Batalha PvP*',
    `${nomes.x || 'X'}: *${px.nome || '?'}* ${barraHp(px.hpAtual != null ? px.hpAtual : b.hp && b.hp.x, px.hp)}`,
    `${nomes.o || 'O'}: *${po.nome || '?'}* ${barraHp(po.hpAtual != null ? po.hpAtual : b.hp && b.hp.o, po.hp)}`,
    ''
  ];
  if (b.outcome) {
    if (b.outcome === 'draw' || b.outcome === 'cancelled') {
      lines.push(b.outcome === 'draw' ? '🤝 Empate!' : '🚪 Desafio cancelado.');
    } else if (String(b.outcome).startsWith('forfeit')) {
      lines.push('🏳️ Desistência — sem prêmio de vitória.');
    } else {
      const winName = b.outcome === 'x' ? (nomes.x || 'X') : (nomes.o || 'O');
      lines.push(`🏆 *${winName}* venceu! (+10 moedas)`);
    }
    return lines.join('\n');
  }
  lines.push(`👉 Vez de *${vezNome}*`);
  if (opts.mySide && opts.mySide === vez) {
    lines.push(menuGolpes(opts.mySide === 'x' ? px : po, [], null));
    lines.push(`${prefix} atacar <1-4> · ${prefix} defender · ${prefix} pocao · ${prefix} sair`);
  } else {
    lines.push('Aguarde o adversário…');
  }
  return lines.join('\n');
}
