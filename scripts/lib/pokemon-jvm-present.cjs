'use strict';

/**
 * Apresentação Node (M4.3): DTOs JVM → envelope {messages,effects}.
 * Formatação WhatsApp fica fora do domínio JVM.
 */

const PAGE_SIZE = 12;

function envelope(requestId, text, extras = {}) {
  const mentions = Array.isArray(extras.mentions)
    ? extras.mentions.filter(m => typeof m === 'string' && m)
    : null;
  const withMentions = (msg) => (mentions && mentions.length
    ? Object.assign({}, msg, { mentions })
    : msg);
  const messages = [];
  if (extras.document && (extras.document.mediaId || extras.document.base64)) {
    messages.push(withMentions({
      type: 'document',
      text: text || '',
      mediaId: extras.document.mediaId || null,
      base64: extras.document.base64 || null,
      mimeType: extras.document.mimeType || 'text/csv',
      filename: extras.document.filename || 'pokemon.csv'
    }));
  } else if (extras.image && (extras.image.mediaId || extras.image.base64)) {
    messages.push(withMentions({
      type: 'image',
      text: extras.caption || text,
      mediaId: extras.image.mediaId || null,
      base64: extras.image.base64 || null,
      mimeType: extras.image.mimeType || 'image/png',
      filename: extras.image.filename || 'pokemon-jvm.png'
    }));
    if (text && extras.caption && text !== extras.caption && text.length > 900) {
      messages.push(withMentions({ type: 'text', text }));
    }
  } else if (text) {
    messages.push(withMentions({ type: 'text', text }));
  }
  return { requestId, messages, effects: extras.effects || [] };
}

function nomePokemon(p) {
  return (p && (p.nome || p.nickname || p.species)) || '?';
}

function shinyMark(p) {
  return p && p.shiny ? '✨ ' : '';
}

/** Formata duração em pt-BR a partir de segundos (ex.: 1800 → "30 minutos"). */
function formatDurationPt(seconds) {
  const s = Math.max(0, Math.ceil(Number(seconds) || 0));
  if (s <= 0) return 'agora';
  if (s < 60) return `${s}s`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rem = s % 60;
  const parts = [];
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(h > 0 || rem > 0 ? `${m}m` : (m === 1 ? '1 minuto' : `${m} minutos`));
  if (rem > 0 && h === 0) parts.push(`${rem}s`);
  if (parts.length === 1 && m > 0 && h === 0 && rem === 0) {
    return m === 1 ? '1 minuto' : `${m} minutos`;
  }
  return parts.join(' ') || `${s}s`;
}

function barraHp(atual, max) {
  const a = Math.max(0, Number(atual) || 0);
  const m = Math.max(1, Number(max) || 1);
  const filled = Math.min(10, Math.round((a / m) * 10));
  return `[${'█'.repeat(filled)}${'░'.repeat(10 - filled)}] ${a}/${m}`;
}

/** Barra de progresso XP do treinador (mesma escala visual do HP). */
function barraXp(atual, necessario) {
  const a = Math.max(0, Number(atual) || 0);
  const m = Math.max(1, Number(necessario) || 1);
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

/** Aliases pt/en → id canônico (inglês). Lookup sempre via normalizar(). */
const TIPO_ALIASES = {
  fogo: 'fire', fire: 'fire',
  agua: 'water', water: 'water',
  planta: 'grass', grama: 'grass', grass: 'grass',
  eletrico: 'electric', electric: 'electric',
  pedra: 'rock', rock: 'rock',
  terra: 'ground', ground: 'ground',
  voador: 'flying', flying: 'flying',
  psiquico: 'psychic', psychic: 'psychic',
  gelo: 'ice', ice: 'ice',
  dragao: 'dragon', dragon: 'dragon',
  sombrio: 'dark', dark: 'dark',
  aco: 'steel', steel: 'steel',
  fada: 'fairy', fairy: 'fairy',
  lutador: 'fighting', luta: 'fighting', fighting: 'fighting',
  veneno: 'poison', poison: 'poison',
  inseto: 'bug', bug: 'bug',
  fantasma: 'ghost', ghost: 'ghost',
  normal: 'normal'
};

function canonicalTipo(t) {
  const n = normalizar(t);
  return TIPO_ALIASES[n] || n;
}

function especieChave(p) {
  return normalizar(p && (p.species || p.nome || p.nickname) || '');
}

function interpretarFiltros(tokens) {
  const ordem = tokens.includes('>') ? '>' : tokens.includes('<') ? '<' : null;
  const rest = tokens.filter(t => t !== '>' && t !== '<');
  let nivel = null;
  let nome = '';
  const tipos = [];
  const raridades = [];
  let shiny = false;
  let repetidos = false;
  for (let i = 0; i < rest.length; i++) {
    const t = rest[i];
    const tn = normalizar(t);
    if (tn === 'shiny' || t === '✨') { shiny = true; continue; }
    if (tn === 'repetidos' || tn === 'repetido' || tn === 'duplicados'
        || tn === 'duplicado' || tn === 'dup' || tn === 'repeats') {
      repetidos = true;
      continue;
    }
    if ((tn === 'nivel' || tn === 'nv') && rest[i + 1] && /^\d+$/.test(rest[i + 1])) {
      nivel = Number(rest[++i]);
      continue;
    }
    if (/^\d+$/.test(t) && rest[i - 1] && /^(nivel|nível|nv)$/i.test(rest[i - 1])) continue;
    if (/^\d+$/.test(t)) continue; // página tratada à parte
    if ((tn === 'raridade' || tn === 'rar') && rest[i + 1] && RARIDADE_FILTRO[normalizar(rest[i + 1])]) {
      raridades.push(RARIDADE_FILTRO[normalizar(rest[++i])]);
      continue;
    }
    const rar = RARIDADE_FILTRO[tn];
    if (rar) { raridades.push(rar); continue; }
    if (TIPO_ALIASES[tn]) { tipos.push(TIPO_ALIASES[tn]); continue; }
    nome = nome ? `${nome} ${t}` : t;
  }
  return { ordem, nivel, nome: normalizar(nome), tipos, raridades, shiny, repetidos };
}

function filtrarColecao(items, tokens) {
  const f = interpretarFiltros(tokens);
  let list = items.slice();
  let especiesRepetidas = null;
  if (f.repetidos) {
    const contagem = {};
    for (const p of list) {
      const k = especieChave(p);
      if (!k) continue;
      contagem[k] = (contagem[k] || 0) + 1;
    }
    especiesRepetidas = new Set(
      Object.keys(contagem).filter(k => contagem[k] > 1)
    );
  }
  list = list.filter(p => {
    if (f.shiny && !p.shiny) return false;
    if (f.nivel != null && Number(p.nivel) !== f.nivel) return false;
    if (f.nome && !normalizar(nomePokemon(p)).includes(f.nome)) return false;
    if (f.tipos.length) {
      const tipos = (p.tipos || []).map(canonicalTipo);
      if (!f.tipos.every(t => tipos.includes(t))) return false;
    }
    if (f.raridades.length) {
      const r = normalizar(p.raridade || 'comum');
      if (!f.raridades.includes(r)) return false;
    }
    if (especiesRepetidas) {
      const k = especieChave(p);
      if (!k || !especiesRepetidas.has(k)) return false;
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

function pokemonStorageFields(data) {
  const d = data || {};
  const ocupacao = Number(d.ocupacaoPokemon ?? d.ocupacao ?? d.collectionCount ?? d.count ?? 0);
  const capacidade = Number(d.capacidadePokemon ?? d.capacidade ?? 0);
  const excesso = Number(d.excessoPokemon ?? Math.max(0, ocupacao - (capacidade || 0)));
  const cheio = d.colecaoCheia === true
    || d.cheio === true
    || (capacidade > 0 && ocupacao >= capacidade);
  return { ocupacao, capacidade, excesso, cheio };
}

/** Linha única: `📦 Armazenamento Pokémon: 119/100 — CHEIO (19 acima do limite)` */
function formatPokemonStorageLine(data) {
  const { ocupacao, capacidade, excesso, cheio } = pokemonStorageFields(data);
  if (!capacidade) {
    return `📦 Coleção: ${ocupacao} Pokémon`;
  }
  if (cheio && excesso > 0) {
    return `📦 Armazenamento Pokémon: ${ocupacao}/${capacidade} — CHEIO (${excesso} acima do limite)`;
  }
  if (cheio) {
    return `📦 Armazenamento Pokémon: ${ocupacao}/${capacidade} — CHEIO`;
  }
  return `📦 Armazenamento Pokémon: ${ocupacao}/${capacidade}`;
}

function formatTrainer(data, playerName, prefix = '!pk') {
  const d = data || {};
  const nome = playerName || 'Treinador';
  const perfil = d.profile || {};
  const ativo = d.ativo;
  const joy = d.joy || {};
  const hunt = d.hunt || {};
  const nivel = perfil.nivel || perfil.trainerLevel || 1;
  const xp = perfil.xp != null ? perfil.xp : (perfil.trainerXp || 0);
  const xpAtual = perfil.xpAtual != null ? perfil.xpAtual : 0;
  const xpNec = perfil.xpNecessario != null ? perfil.xpNecessario : 5;
  const vitorias = perfil.vitoriasTreinador;
  const peGin = perfil.peGinasios || 0;
  const peRaid = perfil.peRaids || 0;
  const xpIns = Number(perfil.xpInsignias || 0);
  const xpMis = Number(perfil.xpMissoes || 0);
  const seq = perfil.sequenciaCapturas;
  const recorde = perfil.maiorSequenciaCapturas;
  const economiaOk = d.economiaConfiavel === true;
  const saveAtivo = d.activeSaveName || d.activeSaveId || '?';
  const saves = Array.isArray(d.saves) ? d.saves : [];
  const partyDefined = d.partyDefined === true;
  const party = d.partyCount;
  const shiny = Number(d.shinyCount != null ? d.shinyCount : 0);
  const bagCap = d.capacidadeMochila;
  const bagOcc = d.ocupacaoMochila;
  const gymDef = Number(d.gymDefenseCount || 0);
  const titulo = d.titulo || d.titleLabel || null;
  const ginasiosNomes = d.ginasiosNomes || 'nenhuma';
  const insignias = Array.isArray(d.insignias) ? d.insignias : [];

  const lines = [
    `🧢 *Treinador: ${nome}*`
  ];
  if (titulo) lines.push(`🏷️ Título: *${titulo}*`);
  lines.push(`⭐ Nível ${nivel} • ✨ PE ${xp} • próximo ${xpAtual}/${xpNec}`);
  lines.push(barraXp(xpAtual, xpNec));
  lines.push(
    `🎖️ PE extra: insígnias +${xpIns} • missões +${xpMis}`
    + ` • ginásios +${peGin} • raides +${peRaid}`
  );
  lines.push(`🏛️ Ginásios: ${ginasiosNomes}`);
  if (vitorias != null && Number(vitorias) > 0) {
    lines.push(`🏅 Vitórias: ${vitorias}`);
  }
  lines.push(`🔥 Capturas: sequência ${seq || 0} • recorde ${recorde || 0}`);

  lines.push('');
  lines.push(formatPokemonStorageLine(d));
  if (partyDefined) {
    lines.push(`👥 Equipe: ${Number(party) || 0}`);
  } else {
    lines.push('👥 Equipe: não definida');
  }
  if (shiny > 0) {
    lines.push(`✨ Shiny: ${shiny}`);
  }
  lines.push(`⚡ Ativo: ${ativo
    ? `#${(d.ativoIndex || 0) + 1} *${shinyMark(ativo)}${nomePokemon(ativo)}* • Nv.${ativo.nivel || 1}`
    : 'nenhum'}`);

  lines.push('');
  const ativoLabel = d.activeSaveName || saveAtivo;
  lines.push(`💾 Save ativo: *${ativoLabel}*`);
  if (saves.length) {
    for (const s of saves) {
      const id = s.saveId || s.slotId || '?';
      const label = publicSaveLabel(s);
      const mark = (s.active === true || id === d.activeSaveId) ? ' ✅' : '';
      const n = s.collectionCount != null ? s.collectionCount : 0;
      const extra = label !== id ? ` _(id ${id})_` : '';
      lines.push(`• *${label}*${mark}${extra}: ${n} Pokémon`);
    }
    lines.push(`_Gerenciar: ${prefix} saves_`);
  } else if (d.savesCount != null) {
    lines.push(`Saves: ${d.savesCount} · _${prefix} saves_`);
  }

  lines.push('');
  if (economiaOk && (d.coins != null || perfil.coins != null)) {
    lines.push(`💰 Moedas: ${d.coins != null ? d.coins : perfil.coins}`);
  } else if (d.economiaConfiavel === false || d.coinsStatus === 'unknown-pending') {
    lines.push('💰 Moedas: saldo histórico em reconciliação');
  }
  if (bagCap != null) {
    lines.push(`🎒 Mochila de itens: ${bagOcc != null ? bagOcc : 0}/${bagCap}`);
  }

  const joyN = Number(joy.emTratamento || 0);
  if (joyN > 0) {
    const restMs = Number(joy.proximoRestanteMs || 0);
    const when = restMs > 0 ? ` (próximo em ~${formatDurationPt(restMs / 1000)})` : '';
    lines.push(`🏥 Joy: ${joyN} em tratamento${when}`);
  }
  if (hunt.cooldownOk === false && Number(hunt.cooldownRestanteS || 0) > 0) {
    lines.push(`🎯 Caça: cooldown ~${formatDurationPt(hunt.cooldownRestanteS)}`);
  } else if (hunt.cooldownOk === true) {
    lines.push('🎯 Caça: disponível');
  }
  if (gymDef > 0) {
    lines.push(`🏛️ Em ginásios: ${gymDef} Pokémon`);
  }

  if (insignias.length) {
    const done = insignias.filter((i) => i.conquistada === true).length;
    lines.push('');
    lines.push(`🏅 *Conquistas: ${done}/${insignias.length}*`);
    for (const i of insignias) {
      const mark = i.conquistada ? '🏅' : '🔒';
      const pe = i.xpRecompensa != null ? i.xpRecompensa : 0;
      lines.push(`${mark} ${i.nome} — ${i.requisito} (+${pe} PE)`);
    }
    lines.push(`_Título: ${prefix} titulo_`);
  }

  return lines.join('\n');
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
  if (filtros.repetidos) filtroDesc.push('repetidos');
  if (filtros.ordem) filtroDesc.push(filtros.ordem === '>' ? 'força ↓' : 'força ↑');
  if (filtros.nivel != null) filtroDesc.push(`nível ${filtros.nivel}`);
  if (filtros.tipos.length) {
    filtroDesc.push(`tipo ${filtros.tipos.map(t => TIPOS_PT[t] || t).join('/')}`);
  }
  if (filtros.raridades && filtros.raridades.length) {
    filtroDesc.push(`raridade ${filtros.raridades.join('/')}`);
  }
  if (filtros.nome) filtroDesc.push(`nome “${filtros.nome}”`);

  return [
    `🎒 *Seu time:* • Página ${Math.min(page, pages)}/${pages}`,
    `🧑‍🎓 Nível de treinador: ${data.trainerLevel || 1} • Save: ${publicSaveLabel({ displayName: data.displayName || data.saveDisplayName, saveId: data.saveId }) || '?'}`,
    formatPokemonStorageLine(data),
    filtroDesc.length ? `🔎 Filtros: ${filtroDesc.join(' + ')} — ${list.length} Pokémon` : `Total: ${data.count || items.length}`,
    '',
    linhas.length ? linhas.join('\n') : (items.length ? 'Nenhum Pokémon do time corresponde a esse filtro.' : 'Coleção vazia. Use !pk iniciar.'),
    '',
    page > 1 ? `⬅️ Anterior: ${prefix} time ${page - 1}` : null,
    page < pages ? `➡️ Próxima: ${prefix} time ${page + 1}` : null,
    `Use ${prefix} escolher <número> pra trocar o ativo (👉), ou ${prefix} joy para enviar os feridos à Enfermeira Joy.`,
    `Veja a ficha do ativo com ${prefix} time ativo.`,
    `Favorito: ${prefix} favorito <número>.`,
    `Combine filtros com ${prefix} time [tipo] [raridade] [nome] [nivel N] [repetidos].`,
    `Ordene por força: ${prefix} time > ou <.`,
    `Modo texto: ${prefix} time txt.`,
    `+50 vagas: ${prefix} espaco comprar`
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
  const { ocupacao, capacidade, excesso, cheio } = pokemonStorageFields(data);
  const livres = Math.max(0, capacidade - ocupacao);
  const status = cheio
    ? (excesso > 0
      ? `CHEIO (${excesso} acima do limite) — Pokémon históricos preservados`
      : 'CHEIO')
    : `${livres} vagas livres`;
  return [
    '📦 *Espaço Pokémon*',
    formatPokemonStorageLine(data),
    `Status: ${status}`,
    `Expansões compradas: ${Number(data && (data.expansoesPokemon50 ?? data.expansoes ?? 0))} (+${50 * Number(data && (data.expansoesPokemon50 ?? data.expansoes ?? 0))} vagas)`,
    (data && data.economiaConfiavel === false)
      ? '💰 Compra de expansão bloqueada: saldo de moedas deste save ainda não é confiável.'
      : `Use ${prefix} espaco comprar para adquirir +50 vagas (200 moedas).`,
    'Mochila de itens é separada: !mochila / !loja comprar mochila.'
  ].filter(Boolean).join('\n');
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
  return [
    `✅ *${p.nome || '?'}* agora é seu Pokémon ativo! (#${n})`,
    `${prefix} time · ${prefix} cacar`,
    `⚔️ Raid: o combatente é o da *entrada*. No lobby use \`${prefix} raid entrar\` de novo (ou \`${prefix} raid entrar <n>\`) para atualizar. Em combate a troca fica travada.`
  ].join('\n');
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

function publicSaveLabel(save) {
  if (!save) return '?';
  const shown = String(save.displayName || '').trim();
  return shown || String(save.saveId || save.slotId || '?');
}

function nextSaveSlotId(saves) {
  const used = new Set((saves || []).map((s) => String(s.saveId || s.slotId || '')));
  const letters = 'abcdefghijklmnopqrstuvwxyz';
  for (const ch of letters) {
    if (!used.has(ch)) return ch;
  }
  for (let i = 1; i <= 99; i += 1) {
    const id = `s${i}`;
    if (!used.has(id)) return id;
  }
  return null;
}

/** Resolve por índice 1-based, slot-id interno ou nome público (displayName). */
function resolveSaveSelection(saves, target) {
  const list = Array.isArray(saves) ? saves : [];
  const raw = String(target == null ? '' : target).trim();
  if (!raw) return null;
  if (/^[0-9]+$/.test(raw)) {
    const idx = Number.parseInt(raw, 10) - 1;
    return idx >= 0 && idx < list.length ? list[idx] : null;
  }
  const needle = raw.toLowerCase();
  const bySlot = list.find((s) => String(s.saveId || s.slotId || '').toLowerCase() === needle);
  if (bySlot) return bySlot;
  const byName = list.filter((s) => String(s.displayName || '').trim().toLowerCase() === needle);
  return byName.length === 1 ? byName[0] : null;
}

function formatActiveSave(data) {
  const save = data.save || {};
  const party = data.party || [];
  const label = publicSaveLabel({
    displayName: save.displayName,
    saveId: data.activeSaveId || save.slotId || save.saveId
  });
  const internal = data.activeSaveId || save.slotId || save.saveId || '?';
  return [
    '💾 *Save ativo*',
    `Save: *${label}* · fence ${data.fenceGeneration || 0}`,
    internal !== label ? `_id interno: ${internal}_` : null,
    `💰 Moedas: ${save.coins || 0} · Nv.treinador ${save.nivel || save.trainerLevel || 1}`,
    `⭐ PE ${save.xp || save.trainerXp || 0} · revisão ${save.revision || 0}`,
    `👥 Party: ${party.length} · Inventário: ${(data.inventory || []).length} itens`,
    party.length
      ? party.map(p => `• ${shinyMark(p)}${nomePokemon(p)} Nv.${p.nivel || 1}`).join('\n')
      : '(party vazia)'
  ].filter(Boolean).join('\n');
}

function formatSavesList(data, prefix = '!pk') {
  const active = data.activeSaveId;
  const linhas = (data.saves || []).map((s) => {
    const label = publicSaveLabel(s);
    const internal = String(s.saveId || s.slotId || '');
    const ativo = internal === active;
    const recovered = String(s.origin || '').toLowerCase().includes('recovery')
      || /^recovery-v\d+$/i.test(internal);
    const mark = ativo ? '✅' : (recovered ? '📦' : '•');
    const tag = ativo ? ' — ativo' : (recovered ? ' — recuperado' : '');
    const extra = label !== internal ? ` _(id ${internal})_` : '';
    return `${mark} \`${label}\`${tag}${extra}`;
  });
  return [
    '📁 *SEUS SAVES*',
    ...linhas,
    '',
    `Selecionar: ${prefix} save <nome|número|id>`,
    `Criar: ${prefix} novo save [nome]`,
    `Renomear: ${prefix} renomear save <nome|número|id> <novo-nome>`
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
  '`raid` — raids cooperativas (alias: raide)',
  '`sair` — abandonar batalha/raid/PvP',
  '`ovos` — Adventure Sync',
  '`saves` / `save <nome>` — listar e selecionar saves',
  '`novo save [nome]` / `renomear save <nome> <novo>` — criar e renomear'
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
  const label = publicSaveLabel({
    displayName: save.displayName,
    saveId: data.activeSaveId || save.slotId || save.saveId
  });
  return [
    `✅ Save *${label}* ativo`,
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
    INVALID_SLOT_ID: '❓ Identificador de save inválido. Use !pk saves e escolha um nome ou id listado.',
    INVALID_SAVE_NAME: '❓ Nome de save inválido. Evite vazio, palavras reservadas ou quebras de linha.',
    SAVE_NAME_TAKEN: '⚠️ Esse nome já está em uso em outro save seu. Escolha outro.',
    SAVE_LIMIT_REACHED: '⚠️ Limite de saves atingido para este treinador.',
    SAVE_ALREADY_EXISTS: '⚠️ Esse save já existe.',
    SAVE_PAYLOAD_MISMATCH: '⚠️ Dados do save não batem com a operação (retry divergente).',
    STALE_WRITER_FENCE:
      '⚠️ Save/fence obsoleto (provavelmente houve !pk ativar). Consulte !pk saves e tente de novo.',
    WRITER_FENCE_CONFLICT:
      '⚠️ Conflito ao gravar (outra ação no mesmo save). Espere 1s e tente de novo com !pk cacar / o mesmo comando.',
    DATOMIC_VALUE_TOO_LARGE:
      '⚠️ Não consegui gravar este passo (dados do Pokémon grandes demais no snapshot). '
      + 'Seu save não foi sobrescrito por uma versão antiga. Tente de novo; se repetir, avise um admin.',
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
    ECONOMY_UNRELIABLE:
      '💰 Moedas deste save de recuperação ainda não são confiáveis (saldo histórico pendente). '
      + 'A capacidade Pokémon é só informativa — compra de expansão bloqueada até a economia ser reconciliada.',
    COLLECTION_FULL: (() => {
      const det = (err && err.details) || {};
      const ocup = det.count != null ? det.count : det.ocupacao;
      const cap = det.capacidade;
      const exc = det.excesso;
      if (ocup != null && cap != null) {
        const over = exc != null ? exc : Math.max(0, Number(ocup) - Number(cap));
        return over > 0
          ? `📦 Armazenamento Pokémon cheio: ${ocup}/${cap} (${over} acima do limite). Libere ou compre vagas (!pk espaco) antes de capturar.`
          : `📦 Armazenamento Pokémon cheio: ${ocup}/${cap}. Libere ou compre vagas (!pk espaco) antes de capturar.`;
      }
      return '📦 Armazenamento Pokémon cheio: libere ou compre vagas (!pk espaco) antes de capturar.';
    })(),
    BATTLE_PENDING: '⚠️ Há batalha/encontro em andamento neste save — não dá para alternar A↔B agora.',
    BATTLE_SAVE_MISMATCH: '⚠️ Encontro pertence a outro save — callback antigo rejeitado.',
    BATTLE_ALREADY_ACTIVE: '⚔️ Já existe uma batalha/encontro seu em andamento.',
    BATTLE_BUSY: '🚫 Termine a batalha/caça antes de trocar o ativo, evoluir, alterar golpes ou usar o professor.',
    INVALID_COLLECTION_INDEX: '❓ Número inválido. Consulte !pk tm.',
    PROFESSOR_BUSY: '⚠️ Já existe um envio pendente. Confirme o código ou use !pk professor cancelar.',
    PROFESSOR_INVALID_TOKEN: '❓ Confirmação inválida, expirada ou Pokémon alterado/protegido. Faça um novo pedido de envio.',
    PROFESSOR_EXPIRED: '❓ Confirmação expirada (5 minutos). Faça um novo !pk professor enviar <número>.',
    PROFESSOR_NO_CARDS: '🍬 Você não tem Doces Pokémon dessa família.',
    POKEMON_FAVORITE: '⭐ Remova a marca de favorito antes de enviar este Pokémon ao professor.',
    POKEMON_IN_TREATMENT: '🏥 Este Pokémon está na enfermaria. Espere o retorno antes de enviar ao professor.',
    POKEMON_MAX_LEVEL: '🏅 Este Pokémon já está no nível máximo. Nenhum Doce foi gasto.',
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
    RAID_NOT_FOUND: '❓ Nenhuma raid ativa neste chat.',
    RAID_NOT_OPEN: '❓ As inscrições desta raid não estão abertas.',
    RAID_ALREADY_STARTED: '⚔️ Esta raid já está em andamento.',
    RAID_START_NOT_JOINED: '⚠️ Entre na raid antes de iniciar a batalha.',
    ALREADY_IN_RAID: '⚔️ Você já está inscrito nesta raid.',
    RAID_FULL: '🚫 A raid já está cheia (máx. 5).',
    RAID_CANNOT_LEAVE: '🚫 Não é possível sair desta raid neste momento.',
    FEATURE_DISABLED: '⚠️ Este comando está temporariamente desabilitado. Tente mais tarde.',
    NOT_FOUND: '❓ Rota inexistente.',
    RAID_NEED_PLAYERS: '❓ Precisa de pelo menos 1 inscrito para iniciar.',
    RAID_NOT_IN_COMBAT: '❓ A raid não está em combate.',
    RAID_NOT_YOUR_TURN: '⏳ Não é a sua vez de atacar.',
    RAID_TURN_INVALID: '⚠️ A vez da raid está inválida (participante ausente). Avise um admin — sem correção automática.',
    RAID_NO_ELIGIBLE_TURN: '⚠️ Nenhum participante elegível para iniciar o combate.',
    RAID_SAVE_MISMATCH: '⚠️ Você mudou de save após entrar — reentre na próxima raid.',
    RAID_LIGA_MISMATCH: '❓ Esse Pokémon não está apto para esta raid.',
    INVALID_RAID_POKEMON: '❓ Seu Pokémon ativo não está apto para esta raid (HP ou golpe ofensivo). Use !pk raid entrar auto, !pk raid entrar <n> ou !pk escolher + !pk raid entrar.',
    INVALID_RAID_MOVE: '❓ Use !pk raid atacar <1-4> com um golpe ofensivo.',
    RAID_CAPTURE_UNAVAILABLE: '❓ Captura indisponível (sem dano, janela expirada ou já capturou).',
    FRAMBO_ALREADY_PREPARED: '🍓 Frambo já está preparada para a próxima tentativa.',
    RAID_COMBATANT_LOCKED: '🚫 Não dá para trocar de Pokémon no meio do combate da raid. O combatente foi fixado na entrada.',
    RAID_NOT_CREATOR: '🚫 Só quem abriu a raid pode iniciar (quando há criador).',
    NOT_IN_RAID: '❓ Você não está nesta raid.',
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
    BAG_FULL: '🎒 Sem espaço na mochila (kit precisa de 10 vagas). Libere itens ou compre expansão: !loja comprar mochila.',
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
  if (code === 'HUNT_COOLDOWN') {
    const d = (err && err.details) || {};
    const ms = d.restanteMs != null ? d.restanteMs : d['restante-ms'];
    if (ms != null) {
      base = `⏳ Calma aí! Você pode caçar de novo em ${formatDurationPt(Number(ms) / 1000)}.`;
    }
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
    const secs = e.cooldownRestanteS != null ? e.cooldownRestanteS : e['cooldown-restante-s'];
    return `⏳ Calma aí! Você pode caçar de novo em ${formatDurationPt(secs)}.`;
  }
  if (!e.semBatalhaPropria) return '⚔️ Já existe uma batalha/encontro seu em andamento.';
  if (!e.chatLivre) return '⚔️ Já existe uma caçada em andamento neste chat.';
  return null;
}

function formatHuntStorageHint(plan) {
  const e = (plan && plan.eligibilidade) || {};
  if (!(e.somenteBatalha || e.colecaoCheia)) return null;
  return formatPokemonStorageLine({
    ocupacaoPokemon: e.ocupacaoPokemon,
    capacidadePokemon: e.capacidadePokemon,
    excessoPokemon: e.excessoPokemon,
    colecaoCheia: true
  }) + ' — captura bloqueada; batalha ainda permitida.';
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
    `Escolha: ${prefix} atacar <1-${nGolpes}> · ${prefix} <1-${nGolpes}> · ${prefix} defender · ${prefix} pocao · ${prefix} sair`,
    golpes,
    '',
    `📖 Ações: ${prefix} atacar ajuda · ${prefix} defender · ${prefix} pocao · ${prefix} sair`
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
  const tiposClima = (clima.tipos || []).slice().sort().map(tipoLabelPt);
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
      ? ((formatHuntStorageHint(plan) || formatHuntStorageHint({ eligibilidade: result }))
        || '📦 Armazenamento Pokémon cheio: esta caçada vale batalha e XP, sem captura.')
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
  const bolsaCheiaLiberou = Boolean(
    data.selvagemLiberadoBolsaCheia || b.selvagemLiberadoBolsaCheia
    || ((data.somenteBatalha || b.somenteBatalha)
        && (data.aguardandoCaptura || b.aguardandoCaptura
            || b.outcome === 'win' || data.outcome === 'win'))
  );
  if (bolsaCheiaLiberou) {
    const hpX = hpPair(x, (b.hp || {}).x);
    const header = [
      `🏁 *${nome}* foi derrotado!`,
      area.nome ? `${area.emoji || '🗺️'} ${area.nome}` : null,
      hpX ? `🐾 *${nomePokemon(x)}* — ${barraHp(hpX.atual, hpX.max)}` : null
    ].filter(Boolean).join('\n');
    return [
      header,
      '🎒 Bolsa cheia: o selvagem foi liberado. Vitória registrada; sem captura nesta caçada.'
    ].join('\n');
  }
  if (data.aguardandoCaptura || b.aguardandoCaptura) {
    const hpX = hpPair(x, (b.hp || {}).x);
    const header = [
      `🏁 *${nome}* foi derrotado!`,
      area.nome ? `${area.emoji || '🗺️'} ${area.nome}` : null,
      hpX ? `🐾 *${nomePokemon(x)}* — ${barraHp(hpX.atual, hpX.max)}` : null
    ].filter(Boolean).join('\n');
    // Paridade master: após o KO, menu de bolas direto — sem etapa "carimbada".
    const menu = formatCaptureMenu({
      encounter: {
        aguardandoCaptura: true,
        pokemonO: o,
        chanceBaseCaptura: data.chanceBaseCaptura != null
          ? data.chanceBaseCaptura
          : b.chanceBaseCaptura,
        tentativasCaptura: data.tentativasCaptura != null
          ? data.tentativasCaptura
          : (b.tentativasCaptura || 0),
        inventory: data.inventory || b.inventory || []
      }
    }, prefix);
    return `${header}\n\n${menu}`;
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
    ? `🔁 Envia *${b.lastSwitch.nome}*!`
    : null;
  let acaoLinha = null;
  if (la.mensagem) {
    acaoLinha = la.mensagem;
  } else if (la.action === 'defend' || la.action === ':defend') {
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
  const rivalLinha = la.mensagemRival || null;
  const cabeçalhoTurno = [
    switchLine,
    acaoLinha,
    rivalLinha,
    `⚔️ Turno ${b.turnIndex != null ? b.turnIndex : '?'}`
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

function chanceComBola(chanceBase, bolaId, frambo) {
  const limits = { pokebola: [1, 95], 'grande-bola': [1.5, 95], 'ultra-bola': [2, 95] };
  const [mult, lim] = limits[bolaId] || [1, 95];
  let n = Math.min(lim, Math.round(Number(chanceBase || 0) * mult));
  if (frambo) n = Math.min(lim, Math.round(n * 1.5));
  return n;
}

function formatCaptureMenu(encounterData, prefix = '!pk') {
  const enc = encounterData && encounterData.encounter;
  if (!enc) return '❓ Não há caçada/encontro em andamento.';
  if (!enc.aguardandoCaptura) {
    return '⚔️ Derrote o Pokémon selvagem antes de capturar.';
  }
  const poke = enc.pokemonO || {};
  const nome = poke.nome || poke.species || 'selvagem';
  const raridade = poke.raridade || poke.rarity || '?';
  const inv = enc.inventory || [];
  const base = enc.chanceBaseCaptura;
  const tent = Number(enc.tentativasCaptura) || 0;
  const framboPrep = Boolean(enc.framboPreparada || enc['frambo-preparada']);
  const framboQty = enc.frambo != null ? Number(enc.frambo)
    : qtyBall(inv, 'fruta');
  const chances = enc.chances || {};
  const pct = (id) => (chances[id] != null
    ? Number(chances[id])
    : chanceComBola(base, id, framboPrep));
  const linhasChance = [
    `🔴 Poké Bola: ${pct('pokebola')}%`,
    `🔵 Super Bola: ${pct('grande-bola')}%`,
    `🟡 Ultra Bola: ${pct('ultra-bola')}%`
  ];
  if (framboPrep) {
    return [
      '🍓 *FRUTA FRAMBO UTILIZADA!*',
      '',
      `🐾 Pokémon: ${nome}`,
      '',
      '✨ *CHANCES ATUALIZADAS*',
      ...linhasChance,
      '',
      `🍓 Frutas Frambo restantes: ${Math.max(0, framboQty)}`,
      '🍓 Bônus ativo para a próxima tentativa!',
      '',
      '🎯 Escolha sua Poké Bola.',
      `${prefix} capturar 1 · ${prefix} capturar 2 · ${prefix} capturar 3`,
      `Tentativas restantes: ${3 - tent} de 3.`
    ].join('\n');
  }
  return [
    '🌿 *UM POKÉMON SELVAGEM APARECEU!*',
    '',
    `🐾 Pokémon: ${nome}`,
    `⭐ Raridade: ${String(raridade).charAt(0).toUpperCase()}${String(raridade).slice(1)}`,
    '',
    '🎯 *CHANCES DE CAPTURA*',
    ...linhasChance,
    '',
    `🍓 Frutas Frambo: ${framboQty}`,
    `*Use \`${prefix} fruta\` para aumentar suas chances!*`,
    '',
    `Tentativas restantes: ${3 - tent} de 3.`,
    `📖 Capturar: ${prefix} capturar <bola>`
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
    historico: '🚧 Histórico de ginásio ainda não persistido no Datomic.'
  };
  return map[action] || formatUnavailable(`ginasio ${action || ''}`.trim());
}

/** Catálogo A6 — mesmos ids de pokemon.domain.gym/ginasios. */
const RAID_GYMS = [
  { id: 'pedra', nome: '🪨 Pedra', nivel: 10 },
  { id: 'agua', nome: '💧 Água', nivel: 20 },
  { id: 'eletrico', nome: '⚡ Elétrico', nivel: 30 },
  { id: 'planta', nome: '🍃 Planta', nivel: 40 },
  { id: 'fogo', nome: '🔥 Fogo', nivel: 50 }
];

function normalizeGymToken(token) {
  return String(token || '')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/[^a-z0-9]+/gi, '')
    .toLowerCase()
    .trim();
}

function resolveRaidGym(token) {
  const raw = String(token || '').trim().toLowerCase();
  if (!raw) return null;
  const n = parseInt(raw, 10);
  if (!Number.isNaN(n) && String(n) === raw && n >= 1 && n <= RAID_GYMS.length) {
    return RAID_GYMS[n - 1].id;
  }
  const byId = RAID_GYMS.find(g => g.id === raw);
  if (byId) return byId.id;
  const norm = normalizeGymToken(raw);
  const byNome = RAID_GYMS.find((g) => {
    const nomeNorm = normalizeGymToken(g.nome);
    return nomeNorm === norm || nomeNorm.endsWith(norm) || g.id === norm;
  });
  return byNome ? byNome.id : null;
}

function formatRaidOpenHelp(prefix = '!pk') {
  return [
    '⚔️ *Abrir raid cooperativa*',
    'Lobby: até 5 treinadores · pode começar com 1 · 5 minutos de inscrição.',
    'O criador também precisa entrar com `raid entrar`.',
    '',
    '*Ginásios:*',
    ...RAID_GYMS.map((g, i) => `${i + 1}. ${g.nome} (nv.${g.nivel}) — \`${g.id}\``),
    '',
    `Abra direto: ${prefix} raid abrir 1   (ou o id, ex. pedra)`,
    `Sem argumento: ${prefix} raid abrir`
  ].join('\n');
}

function formatRaidPrazo(expiraMs, agoraMs) {
  const exp = Number(expiraMs);
  if (!Number.isFinite(exp) || exp <= 0) return null;
  const agora = Number.isFinite(Number(agoraMs)) ? Number(agoraMs) : Date.now();
  const sec = Math.max(0, Math.ceil((exp - agora) / 1000));
  if (sec <= 0) return '⏱️ Prazo esgotado';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `⏱️ ${m}m${String(s).padStart(2, '0')}s restantes` : `⏱️ ${s}s restantes`;
}

function formatRaidOpened(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  const chefe = r.chefe || {};
  const prazo = formatRaidPrazo(r.expira, data && data.agora);
  return [
    `📣 *Raid aberta* em ${r.nomeGinasio || r.ginasio || 'ginásio'}!`,
    `Chefe: *${chefe.nome || '?'}* · liga ${r.liga || '?'}`,
    prazo || 'Inscrições por 5 minutos · máx. 5 treinadores.',
    `Entre: ${prefix} raid entrar auto`,
    `Status: ${prefix} raid · Cancelar: ${prefix} raid cancelar`
  ].join('\n');
}

/** Lê flags booleanas do DTO mesmo se o JSON legado trouxe `suaVez?`. */
function raidFlag(obj, camelKey) {
  if (!obj || typeof obj !== 'object') return false;
  if (obj[camelKey] != null) return Boolean(obj[camelKey]);
  if (obj[`${camelKey}?`] != null) return Boolean(obj[`${camelKey}?`]);
  return false;
}

function displayTrainerName(partOrNome, playerId) {
  const nome = typeof partOrNome === 'string'
    ? partOrNome
    : (partOrNome && (partOrNome.nome || partOrNome.name));
  if (nome && String(nome).trim() && !looksLikeTechnicalId(String(nome))) {
    return String(nome).trim();
  }
  return 'Treinador';
}

/** IDs técnicos WhatsApp (@lid / @c.us / dígitos) — nunca exibir ao jogador. */
function looksLikeTechnicalId(value) {
  const s = String(value || '').trim();
  if (!s) return true;
  if (/@lid\b/i.test(s) || /@c\.us\b/i.test(s) || /@s\.whatsapp\.net\b/i.test(s)) {
    return true;
  }
  if (/^\d{8,}$/.test(s)) return true;
  if (/^@?\d{8,}@/i.test(s)) return true;
  return false;
}

/**
 * Rótulo seguro para menção visual (@Nome). Nunca telefone/LID.
 * Fallback: Treinador N (1-based na ordem da raide).
 */
function safeRaidMentionLabel(part, ordinal) {
  const raw = part && (part.nome || part.name || part.displayName || part.display_name);
  if (raw && String(raw).trim() && !looksLikeTechnicalId(raw)) {
    // Remove @ inicial e espaços extremos; preserva o nome de exibição.
    return String(raw).trim().replace(/^@+/, '');
  }
  const n = ordinal != null ? Number(ordinal) + 1 : 1;
  return `Treinador ${n}`;
}

/**
 * Constrói texto com @rótulos e lista de JIDs para whatsapp-web.js.
 * Credenciais/recompensas continuam indexadas por playerId canônico.
 */
function buildRaidMentionRoster(participants) {
  const parts = Array.isArray(participants) ? participants : [];
  const mentions = [];
  const labels = [];
  const byPlayerId = {};
  parts.forEach((p, i) => {
    const pid = p && (p.playerId || p['player-id'] || p.id);
    const label = safeRaidMentionLabel(p, i);
    const tag = `@${label}`;
    labels.push({ playerId: pid, label, tag, part: p, ordinal: i });
    if (pid && typeof pid === 'string' && pid.includes('@')) {
      mentions.push(pid);
    }
    if (pid) byPlayerId[pid] = { label, tag, part: p, ordinal: i };
  });
  return { mentions, labels, byPlayerId };
}

function formatRaidMoveLines(pokemon, prefix = '!pk') {
  const golpes = (pokemon && (pokemon.golpes || pokemon.moves)) || [];
  if (!golpes.length) {
    return ['(sem golpes)'];
  }
  return golpes.slice(0, 4).map((g, i) => {
    const nome = g.nomeExibicao || g['nome-exibicao'] || g.nome || g.slug || `Golpe ${i + 1}`;
    const classe = String(g.classe || g.class || '').toLowerCase();
    const poder = Number(g.poder || g.power || 0);
    const ofensivo = (classe === 'fisico' || classe === 'especial' || classe === 'physical' || classe === 'special')
      && poder > 0;
    if (!ofensivo) {
      return `${i + 1}. ${nome} — _indisponível_`;
    }
    return `${i + 1}. ${nome} (${poder}) → \`${prefix} raid atacar ${i + 1}\``;
  });
}

function formatRaidTurnBlock(r, prefix = '!pk') {
  if (!r || r.fase !== 'combate' || !r.vez) return [];
  const nome = displayTrainerName(r.vezNome || r.vez_nome, r.vez);
  const poke = r.vezPokemon || r.vez_pokemon || {};
  const pokeNome = poke.nome || poke.species || '?';
  const hp = r.vezHp != null ? r.vezHp : (r.vez_hp != null ? r.vez_hp : '?');
  const lines = [
    `👉 Vez de *${nome}* — ${pokeNome} (HP ${hp})`,
    ...formatRaidMoveLines(poke, prefix)
  ];
  const proxNome = r.proximaVezNome || r.proxima_vez_nome;
  if (proxNome) {
    lines.push(`⏭️ Próximo: *${displayTrainerName(proxNome)}*`);
  }
  if (raidFlag(r, 'suaVez')) {
    lines.push(`✅ É a sua vez — escolha o golpe acima.`);
  } else if (r.vezValida === false || r['vezValida?'] === false) {
    lines.push('⚠️ Ponteiro de vez inválido — avise um admin.');
  } else {
    lines.push('⏳ Aguarde a vez do treinador indicado.');
  }
  return lines;
}

function formatRaidCancelled(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  return [
    `🚫 Raid em ${r.nomeGinasio || r.ginasio || 'ginásio'} cancelada.`,
    `Abrir outra: ${prefix} raid abrir`
  ].join('\n');
}

function formatGymRanking(data) {
  const rows = (data && (data.rankingDefesas || data.ranking)) || [];
  const time = (data && data.rankingTempo) || rows;
  if (!rows.length) return '🏛️ Ainda não há defesas registradas neste ginásio.';
  const list = (items, field, suffix) => items.map((r, i) =>
    `${i + 1}. ${r.nome || r.playerId} — ${field === 'defesas' ? (r.defesas || 0) + ' defesa(s)' : Math.round((r.tempoMs || 0) / 1000) + 's'}`).join('\n');
  return `🏛️ *Ranking — ${data.gymId}*\n🛡️ Defesas vencidas\n${list(rows, 'defesas')}\n\n⏱️ Permanência acumulada\n${list(time, 'tempo-ms')}`;
}

function formatGymHistory(data) {
  const rows = (data && data.history) || [];
  if (!rows.length) return '🏛️ Ainda não há desafios registrados neste ginásio.';
  return [`🏛️ *Histórico — ${data.gymId}*`, ...rows.map(r =>
    `${r.venceu ? '✅' : '❌'} ${r.nome || r.desafiante || 'Treinador'}${r.lider ? ` vs ${r.lider}` : ''}`)].join('\n');
}

const RAID_SUB = {
  ent: 'entrar', entrar: 'entrar',
  sai: 'sair', sair: 'sair',
  ini: 'iniciar', iniciar: 'iniciar',
  atk: 'atacar', atacar: 'atacar',
  cap: 'capturar', capturar: 'capturar',
  frambo: 'frambo', framb: 'frambo', razz: 'frambo',
  fruta: 'fruta',
  tm: 'time', time: 'time',
  st: 'status', status: 'status',
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
      '🏛️ Nenhuma raid ativa neste chat.',
      `Abrir: ${prefix} raid abrir   ·   direto: ${prefix} raid abrir 1`
    ].join('\n');
  }
  const chefe = r.chefe || {};
  const prazo = r.fase === 'inscricoes'
    ? formatRaidPrazo(r.expira, data && data.agora)
    : null;
  const lines = [
    `⚔️ *Raid — ${chefe.nome || 'Chefe'}* / ${r.liga || '?'}`,
    `🏛️ ${r.nomeGinasio || r.ginasio || 'Ginásio'} · fase *${r.fase}*`,
    r.fase === 'combate' || r.fase === 'vitoria'
      ? `❤️ Chefe: ${r.hpChefe != null ? r.hpChefe : '?'} / ${r.hpMax != null ? r.hpMax : '?'}`
      : `Inscritos: ${r.nParticipantes || 0}/5`,
    prazo,
    r.capturaPendente || r['capturaPendente?']
      ? `🎯 Captura aberta — ${prefix} raid capturar  ·  ${prefix} raid fruta`
      : null,
    '',
    r.fase === 'inscricoes'
      ? `${prefix} raid entrar auto · ${prefix} raid iniciar · ${prefix} raid sair · ${prefix} raid cancelar`
      : null
  ];
  if (r.fase === 'combate') {
    lines.push(...formatRaidTurnBlock(r, prefix));
  }
  if (Array.isArray(r.participantes) && r.participantes.length) {
    lines.push('', '*Participantes*');
    for (const p of r.participantes) {
      const nome = displayTrainerName(p);
      const voce = raidFlag(p, 'voce');
      lines.push(
        `• ${voce ? '👉 ' : ''}${nome}`
        + (p.hp != null ? ` · HP ${p.hp}` : '')
        + (p.dano ? ` · dano ${p.dano}` : '')
      );
    }
  }
  return lines.filter(v => v !== null).join('\n');
}

function formatRaidJoin(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  const mode = data && data.mode;
  const modeHint = mode === 'ativo'
    ? ' (Pokémon ativo)'
    : mode === 'auto'
      ? ' (auto — melhor apto)'
      : '';
  return [
    data.refreshed
      ? `🔄 Combatente da raid atualizado!`
      : `✅ Você entrou na raid de *${r.nomeGinasio || r.ginasio || 'ginásio'}*!`,
    `Inscritos: ${r.nParticipantes || '?'}/5 · liga ${r.liga || '?'}`,
    data.collectionIndex != null
      ? `Pokémon nº ${Number(data.collectionIndex) + 1} da coleção${modeHint}.`
      : null,
    data.duplicate ? '_já inscrito com este Pokémon (idempotente)_' : null,
    `Trocar no lobby: ${prefix} escolher + ${prefix} raid entrar  ·  ou ${prefix} raid entrar <n>`,
    `Começar agora: ${prefix} raid iniciar (1+ inscrito)`
  ].filter(Boolean).join('\n');
}

function formatRaidLeave() {
  return '👋 Você saiu das inscrições da raid.';
}

function formatLeaveResult(data, prefix = '!pk') {
  const status = (data && data.status) || 'left';
  const modality = (data && data.modality) || '';
  if (status === 'no_battle') {
    return '⚠️ Você não está participando de nenhuma batalha.';
  }
  if (status === 'already_finished') {
    return 'ℹ️ Essa batalha já foi encerrada.';
  }
  if (modality === 'pvp') {
    const outcome = data && data.battle && (data.battle.outcome || data.battle.Outcome);
    if (outcome === 'cancelled' || outcome === ':cancelled') {
      return '🏳️ Desafio PvP cancelado.';
    }
    return '🏳️ Você desistiu da batalha PvP. O adversário foi notificado.';
  }
  if (modality === 'raid') {
    if (data && data.raidEnded) {
      return '🏳️ Você saiu e a raide encerrou (ninguém restou no combate).';
    }
    return '🏳️ Você saiu da raide. Os demais participantes continuam.';
  }
  return '🏳️ Você abandonou a batalha. Seus Pokémon foram preservados e você pode continuar jogando.';
}

function formatRaidStart(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  return [
    `🔥 Combate iniciado! Chefe com ${r.hpChefe || r.hpMax || '?'} HP.`,
    `❤️ Chefe: ${r.hpChefe != null ? r.hpChefe : '?'} / ${r.hpMax != null ? r.hpMax : '?'}`,
    ...formatRaidTurnBlock(r, prefix)
  ].join('\n');
}

function formatRaidRewardLines(rewards, viewerId, mentionRoster) {
  const rows = Array.isArray(rewards) ? rewards : [];
  if (!rows.length) {
    return { lines: ['🎁 *RECOMPENSAS*', '_Nenhuma recompensa liquidada nesta resposta._'], mentions: [] };
  }
  const roster = mentionRoster || { byPlayerId: {}, mentions: [], labels: [] };
  const lines = ['🎁 *RECOMPENSAS*'];
  const mentions = [];
  for (const rw of rows) {
    const pid = rw.playerId || rw['player-id'];
    const entry = (pid && roster.byPlayerId[pid]) || null;
    const tag = entry ? entry.tag : `@${safeRaidMentionLabel({ nome: rw.nome }, mentions.length)}`;
    if (pid && typeof pid === 'string' && pid.includes('@')) {
      mentions.push(pid);
    }
    const x = rw.xpPokemon != null ? rw.xpPokemon : rw['xp-pokemon'];
    lines.push(
      tag,
      `💰 +${rw.moedas != null ? rw.moedas : 0} moedas`,
      `🏅 +${rw.pe != null ? rw.pe : 0} PE`,
      x != null ? `⭐ +${x} XP` : null,
      ''
    );
  }
  while (lines.length && lines[lines.length - 1] === '') lines.pop();
  return { lines: lines.filter((l) => l !== null), mentions };
}

/**
 * Vitória de raide: texto + mentions (JIDs canônicos).
 * Retorno: `{ text, mentions }` — callers devem passar mentions ao envelope.
 */
function formatRaidVictory(data, prefix = '!pk') {
  const r = (data && data.raid) || {};
  const chefe = (r.chefe && (r.chefe.nome || r.chefe.species)) || 'Chefe';
  const participants = Array.isArray(r.participantes) ? r.participantes : [];
  const roster = buildRaidMentionRoster(participants);
  const partLines = ['👥 *PARTICIPANTES*'];
  if (roster.labels.length) {
    for (const entry of roster.labels) {
      const poke = entry.part && (entry.part.pokemon || entry.part.Pokemon);
      const pokeNome = (poke && (poke.nome || poke.species)) || '?';
      partLines.push(`${entry.tag} — Pokémon: ${pokeNome}`);
    }
  } else {
    partLines.push('_Sem participantes listados._');
  }
  const rewards = formatRaidRewardLines(data.rewards, data.playerId || data['player-id'], roster);
  const mentionSet = [];
  for (const m of [...roster.mentions, ...rewards.mentions]) {
    if (m && !mentionSet.includes(m)) mentionSet.push(m);
  }
  const text = [
    '🏆 *RAID CONCLUÍDA!*',
    `👾 Chefe derrotado: *${chefe}*`,
    '',
    ...partLines,
    '',
    ...rewards.lines,
    '',
    '🎯 *CAPTURA DO CHEFE*',
    `${prefix} raid capturar          — ver bolas / chances`,
    `${prefix} raid capturar 1|2|3    — Poké / Super / Ultra`,
    `${prefix} raid fruta             — Frambo (+chance)`,
    `${prefix} mochila`
  ].join('\n');
  return { text, mentions: mentionSet };
}

/**
 * Derrota: PE/XP de participação (valores do DTO `rewards`, já persistidos).
 * Sem captura / moedas / itens.
 */
function formatRaidDefeat(data, _prefix = '!pk') {
  const r = (data && data.raid) || {};
  const chefe = (r.chefe && (r.chefe.nome || r.chefe.species)) || 'Chefe';
  const participants = Array.isArray(r.participantes) ? r.participantes : [];
  const roster = buildRaidMentionRoster(participants);
  const rows = Array.isArray(data && data.rewards) ? data.rewards : [];
  const lines = [
    '💀 *RAID ENCERRADA — DERROTA*',
    `🐉 *${chefe}* resistiu aos ataques!`,
    '',
    'Mesmo sem vencer, seus Pokémon ganharam experiência pela batalha.',
    '',
    '🎖️ *Recompensas de participação*'
  ];
  const mentions = [];
  if (!rows.length) {
    lines.push('_Nenhuma participação efetiva liquidada._');
  } else {
    for (const rw of rows) {
      const pid = rw.playerId || rw['player-id'];
      const entry = (pid && roster.byPlayerId[pid]) || null;
      const tag = entry ? entry.tag : `@${safeRaidMentionLabel({ nome: rw.nome }, mentions.length)}`;
      if (pid && typeof pid === 'string' && pid.includes('@')) {
        mentions.push(pid);
      }
      const pe = rw.pe != null ? rw.pe : 0;
      const xp = rw.xpPokemon != null ? rw.xpPokemon : (rw['xp-pokemon'] != null ? rw['xp-pokemon'] : 0);
      const moedas = rw.moedas != null ? rw.moedas : 0;
      lines.push(
        tag,
        `👤 Treinador: +${pe} PE`,
        `⚔️ Pokémon participante: +${xp} XP`,
        `🪙 Moedas: ${moedas}`,
        ''
      );
    }
    while (lines.length && lines[lines.length - 1] === '') lines.pop();
  }
  lines.push('', '🎯 Continue treinando e tente novamente!');
  const mentionSet = [];
  for (const m of [...roster.mentions, ...mentions]) {
    if (m && !mentionSet.includes(m)) mentionSet.push(m);
  }
  return { text: lines.join('\n'), mentions: mentionSet };
}

function formatRaidAttack(data, prefix = '!pk') {
  if (data && data.venceu) {
    return formatRaidVictory(data, prefix);
  }
  if (data && data.derrota) {
    return formatRaidDefeat(data, prefix);
  }
  const r = (data && data.raid) || {};
  return [
    `💥 Dano ${data && data.dano != null ? data.dano : '?'} · revide ${data && data.revide != null ? data.revide : 0}`,
    `❤️ Chefe: ${r.hpChefe != null ? r.hpChefe : '?'} / ${r.hpMax != null ? r.hpMax : '?'}`,
    ...formatRaidTurnBlock(r, prefix),
    data && data.duplicate ? '_idempotente: mesmo operationId_' : null
  ].filter(Boolean).join('\n');
}

/** Normaliza retorno de formatRaidAttack (string | {text,mentions}). */
function coerceRaidPresent(result) {
  if (result && typeof result === 'object' && typeof result.text === 'string') {
    return {
      text: result.text,
      mentions: Array.isArray(result.mentions) ? result.mentions.filter(Boolean) : []
    };
  }
  return { text: String(result || ''), mentions: [] };
}

/**
 * Interface canônica de captura do chefe (inicial e pós-Frambo).
 * Chances vêm do domínio (`captura-ui.chances`) — nunca fixas na UI.
 * opts.afterFrambo: banner de Frambo *preparada* (ainda não consumida).
 */
function formatRaidCaptureUi(data, prefix = '!pk', opts = {}) {
  const ui = (data && (data.capturaUi || data['captura-ui'])) || null;
  const r = (data && data.raid) || {};
  const cap = r.captura || {};
  const chefe = (r.chefe && (r.chefe.nome || r.chefe.species))
    || (ui && ui.chefe)
    || 'Chefe';
  const tent = ui && ui.tentativas != null ? ui.tentativas : (cap.tentativas || 0);
  const maxT = ui && ui.maxTentativas != null
    ? ui.maxTentativas
    : (cap.maxTentativas || cap['max-tentativas'] || 3);
  const bolas = (ui && ui.bolas) || {};
  const framboQty = ui && ui.frambo != null ? ui.frambo : '?';
  const prepared = Boolean(ui && (ui.framboPreparada || ui['frambo-preparada']))
    || Boolean(cap.framboPreparada || cap['frambo-preparada'])
    || Boolean(opts && opts.afterFrambo);
  const chances = (ui && ui.chances) || cap.chances || {};
  const pct = (id) => (chances[id] != null ? `${chances[id]}%` : '?%');
  const afterFrambo = Boolean(opts && opts.afterFrambo);
  const lines = [];
  if (afterFrambo) {
    lines.push(
      '🍓 *FRUTA FRAMBO PREPARADA!*',
      '',
      'Bônus ativo na *próxima* tentativa — a fruta só é consumida ao lançar a bola.',
      ''
    );
  }
  lines.push(
    `🎯 *CAPTURA DO CHEFE — ${String(chefe).toUpperCase()}*`,
    `Tentativas: ${tent}/${maxT}`,
    prepared
      ? '🍓 Bônus Frambo: *PREPARADO* (consome 1 fruta na próxima bola)'
      : '🍓 Bônus Frambo: inativo',
    '',
    'Escolha sua Poké Bola:',
    '',
    `1️⃣ Poké Bola — ${pct('pokebola')} · estoque ${bolas.pokebola || 0}`,
    `2️⃣ Super Bola — ${pct('grande-bola')} · estoque ${bolas['grande-bola'] || 0}`,
    `3️⃣ Ultra Bola — ${pct('ultra-bola')} · estoque ${bolas['ultra-bola'] || 0}`,
    '',
    // Estoque real atual (prepare não debita; consumo só na tentativa).
    `🍓 Frutas no estoque: ${framboQty}${prepared ? ' (1 será consumida na próxima tentativa)' : ''}`,
    '',
    '*Comandos:*',
    `\`${prefix} raid capturar 1\``,
    `\`${prefix} raid capturar 2\``,
    `\`${prefix} raid capturar 3\``
  );
  if (!prepared) {
    lines.push(`\`${prefix} raid fruta\` — preparar Frambo (não consome ainda)`);
  }
  return lines.join('\n');
}

function formatRaidFrambo(data, prefix = '!pk') {
  if (data && data.duplicate && !(data.capturaUi || data['captura-ui'] || (data.raid && data.raid.captura))) {
    return '🍓 Frambo já estava preparada (idempotente).';
  }
  // Preferir UI completa recalculada (Node re-fetch após prepare).
  if (data && (data.capturaUi || data['captura-ui'] || (data.raid && data.raid.captura))) {
    return formatRaidCaptureUi(data, prefix, { afterFrambo: true });
  }
  return [
    '🍓 *FRUTA FRAMBO PREPARADA!*',
    'Bônus ativo na próxima tentativa — a fruta só é consumida ao lançar a bola.',
    '🎯 Escolha sua Poké Bola:',
    `\`${prefix} raid capturar 1\` · \`${prefix} raid capturar 2\` · \`${prefix} raid capturar 3\``
  ].join('\n');
}

function bolaLabelRaid(bolaId) {
  const id = String(bolaId || 'pokebola').toLowerCase();
  if (id === 'ultra-bola' || id === 'ultrabola') return 'Ultra Bola';
  if (id === 'grande-bola' || id === 'super-bola') return 'Grande Bola';
  if (id === 'pokebola' || id === 'poke-bola') return 'Poké Bola';
  return bolaId || 'Poké Bola';
}

function formatRaidCapture(data, prefix = '!pk') {
  const bola = data.bola || 'pokebola';
  const bolaNome = bolaLabelRaid(bola);
  const frambo = Boolean(data.frambo);
  const chefe = (data.chefe && (data.chefe.nome || data.chefe.species)) || 'chefe';
  const tent = data.tentativas != null ? Number(data.tentativas) : null;
  const maxT = 3;
  if (data.capturou) {
    return [
      `✅ Capturou *${chefe}* com ${bolaNome}${frambo ? ' + Frambo' : ''}! (${data.chance != null ? data.chance : '?'}%)`,
      'Entrou na coleção (nv.1).'
    ].join('\n');
  }
  // Raide: falha de bola ≠ fuga definitiva. Janela segue até 3 tentativas ou captura-expira.
  const aindaPode = tent == null || tent < maxT;
  if (aindaPode) {
    return [
      `💥 *O ${chefe} escapou da ${bolaNome}!*`,
      data.chance != null ? `🎯 Chance era ${data.chance}%${frambo ? ' (Frambo)' : ''}.` : null,
      '🎯 Você ainda pode tentar capturá-lo.',
      tent != null ? `Tentativas: ${tent}/${maxT}` : null,
      frambo ? '🍓 Frambo consumida nesta tentativa.' : null,
      `Tente de novo: ${prefix} raid capturar 1|2|3`,
      `Preparar Frambo: ${prefix} raid fruta`
    ].filter(Boolean).join('\n');
  }
  return [
    `💥 *O ${chefe} escapou da ${bolaNome}!*`,
    `Tentativas esgotadas (${maxT}/${maxT}).`,
    '🎁 As recompensas pela vitória foram mantidas.',
    'A janela de captura encerrou para você (sem fuga aleatória do chefe).'
  ].join('\n');
}

function formatJoyStatus(data, prefix = '!pk') {
  const beds = (data && data.enfermaria) || [];
  const feridos = (data && data.feridos) || [];
  const secsCfg = (data && (data.tempoTratamentoSegundos != null
    ? data.tempoTratamentoSegundos
    : data['tempo-tratamento-segundos']));
  const mins = secsCfg != null
    ? Math.max(1, Math.ceil(Number(secsCfg) / 60))
    : ((data && data.tempoTratamentoMinutos) || 30);
  const lines = ['🏥 *Centro Pokémon — Enfermeira Joy*'];
  if (beds.length) {
    lines.push(`Leitos: ${beds.length} (${data.prontos || 0} pronto(s))`);
    for (const b of beds) {
      const nome = (b.pokemon && (b.pokemon.nome || b.pokemon.species)) || '?';
      const restMs = b.restanteMs != null ? b.restanteMs : b['restante-ms'];
      const rest = b.pronto
        ? 'pronto — use o comando de novo para recolher'
        : formatDurationPt(Number(restMs || 0) / 1000);
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
  const restMs = data && (data.restanteMs != null ? data.restanteMs : data['restante-ms']);
  const secsCfg = data && (data.tempoTratamentoSegundos != null
    ? data.tempoTratamentoSegundos
    : data['tempo-tratamento-segundos']);
  const secs = restMs != null ? Number(restMs) / 1000
    : (secsCfg != null ? Number(secsCfg) : 1800);
  return [
    `🏥 A Enfermeira Joy recebeu *${nome}*.`,
    `Voltará com HP cheio em ${formatDurationPt(secs)}.`,
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

function formatBagKit(data, prefix = '!mochila') {
  const d = data || {};
  if (d.kitAlreadyClaimed || (d.duplicate && !(d.qty > 0))) {
    return '🎁 Você já resgatou o kit inicial.';
  }
  const texto = d.rewardsText
    || (d.qty ? `${d.qty}× Pokébola` : null)
    || formatMissionItemsFromMap(d.added || d.items);
  return `🎁 Você recebeu ${texto}!`;
}

function formatBagDaily(data, prefix = '!mochila') {
  const d = data || {};
  const tz = d.timezone || 'America/Sao_Paulo';
  if (d.dailyAlreadyClaimed || (d.duplicate && !(d.sequencia > 0 && d.items && Object.keys(d.items).length))) {
    return `🎁 O bônus diário de hoje já foi resgatado. Volte após a meia-noite (${tz}).`;
  }
  const seq = d.sequencia != null ? d.sequencia : 1;
  const texto = d.rewardsText || formatMissionItemsFromMap(d.items || d.added);
  let msg = `🎁 *Bônus diário resgatado — sequência de ${seq} dia(s):* ${texto}!`;
  if (d.frutaDourada) {
    msg += ' 🌟 Você ganhou uma Fruta Frambo Dourada!';
  }
  const pending = d.pending || {};
  const pendingQty = Object.values(pending).reduce((a, n) => a + Number(n || 0), 0);
  if (pendingQty > 0) {
    msg += ` Itens sem espaço ficaram pendentes: ${prefix} resgatar.`;
  }
  return msg;
}

function formatMissionItemsFromMap(map) {
  if (!map || typeof map !== 'object') return 'recompensas';
  const names = Object.assign({}, MISSION_ITEM_NAMES, {
    'fruta-dourada': 'Fruta Frambo Dourada',
    fruta: 'Fruta Frambo'
  });
  const order = ['pokebola', 'grande-bola', 'ultra-bola', 'fruta', 'fruta-dourada',
    'reviver', 'catalisador-evolutivo'];
  const parts = [];
  for (const id of order) {
    const q = Number(map[id] || 0);
    if (q > 0) parts.push(`${q}× ${names[id] || id}`);
  }
  for (const [id, q0] of Object.entries(map)) {
    if (order.includes(id)) continue;
    const q = Number(q0 || 0);
    if (q > 0) parts.push(`${q}× ${names[id] || id}`);
  }
  return parts.join(', ') || 'recompensas';
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

const TYPE_LABEL_PT = {
  normal: 'Normal', fire: 'Fogo', water: 'Água', electric: 'Elétrico',
  grass: 'Planta', ice: 'Gelo', fighting: 'Lutador', poison: 'Venenoso',
  ground: 'Terrestre', flying: 'Voador', psychic: 'Psíquico', bug: 'Inseto',
  rock: 'Pedra', ghost: 'Fantasma', dragon: 'Dragão', dark: 'Sombrio',
  steel: 'Aço', fairy: 'Fada'
};

function tipoLabelPt(t) {
  const k = String(t || '').toLowerCase();
  return TYPE_LABEL_PT[k] || t;
}

function candyLabel(family, qty) {
  const fam = String(family || '?');
  const pretty = fam.split('-').map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join('-');
  const n = Number(qty);
  if (n === 1) return `Doce ${pretty}`;
  return `Doces ${pretty}`;
}

function formatMissionReward(mission) {
  const parts = [];
  if (mission.xp) parts.push(`+${mission.xp} PE do treinador`);
  if (mission.moedas) parts.push(`+${mission.moedas} moedas`);
  for (const [key, label] of [['pokebolas', 'Pokébolas'], ['grandes', 'Super Bolas'],
    ['ultras', 'Ultra Bolas'], ['frutas', 'Frutas Frambo'],
    ['presentesItem', 'Cartão-presente'], ['presentes-item', 'Cartão-presente'],
    ['catalisadores', 'Catalisador Evolutivo']]) {
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

function formatMissionRewards(mission) {
  const parts = [];
  const rewards = mission.recompensas || {};
  const qty = (id) => Number(rewards[id] || 0);
  if (qty('fruta')) parts.push(`${qty('fruta')} Frambo`);
  if (qty('pokebola')) parts.push(`${qty('pokebola')} Poké Bolas`);
  if (qty('grande-bola')) parts.push(`${qty('grande-bola')} Super Bolas`);
  if (qty('ultra-bola')) parts.push(`${qty('ultra-bola')} Ultra Bolas`);
  if (qty('cartao-presente')) parts.push(`${qty('cartao-presente')} cartão-presente`);
  const doces = mission.doces || {};
  Object.keys(doces).forEach((fam) => {
    const n = Number(doces[fam] || 0);
    if (n > 0) parts.push(`${n} Doce ${fam.charAt(0).toUpperCase()}${fam.slice(1)}`);
  });
  return parts.length ? `🎁 ${parts.join(' + ')}` : null;
}

function formatMissionEventList(events, prefix) {
  const active = (events || []).filter((e) => e && (e.status === 'active' || e.available));
  if (!active.length) return '🎉 Nenhum evento de missões ativo no momento.';
  const lines = [];
  for (const ev of active) {
    const title = `${ev.emoji || '🎉'} *${String(ev.nome || ev.id || '').toUpperCase()}*`;
    lines.push(title);
    const rem = ev.remainingLabel || ev['remaining-label'] || ev.remaining_label;
    if (rem) lines.push(`⏳ Termina em ${rem}`);
    lines.push('', `${ev.emoji || '🎯'} *MISSÕES DO EVENTO*`, '');
    let i = 1;
    for (const mission of ev.missions || []) {
      const state = mission.estado;
      const icon = state === 'rewarded' ? '🎁' : state === 'completed' ? '✅' : '⬜';
      lines.push(`${i}. ${icon} ${mission.nome || mission.objetivo || mission.id} — ${mission.progresso || 0}/${mission.meta || 0}`);
      const rewardLine = formatMissionRewards(mission);
      if (rewardLine) lines.push(`   ${rewardLine}`);
      i += 1;
    }
    lines.push('');
  }
  lines.push(`Resgatar: \`${prefix} missoes evento resgatar\``);
  lines.push(`Resumo: \`${prefix} missoes\``);
  return lines.join('\n');
}

function formatSuggestedLine(mission) {
  return `• ${mission.objetivo || mission.nome || mission.id} — ${mission.progresso || 0}/${mission.meta || 0}`;
}

function formatMissionsSummary(data, prefix = '!pk') {
  const summary = (data && data.summary) || {};
  const dailyDone = summary.dailyDone != null ? summary.dailyDone : summary['daily-done'];
  const dailyTotal = summary.dailyTotal != null ? summary.dailyTotal : summary['daily-total'];
  const weeklyDone = summary.weeklyDone != null ? summary.weeklyDone : summary['weekly-done'];
  const weeklyTotal = summary.weeklyTotal != null ? summary.weeklyTotal : summary['weekly-total'];
  const dailySug = summary.dailySuggested || summary['daily-suggested']
    || ((data.daily && data.daily.missions) || []).filter((m) => m.estado === 'available' || m.estado === 'in_progress').slice(0, 2);
  const weeklySug = summary.weeklySuggested || summary['weekly-suggested']
    || ((data.weekly && data.weekly.missions) || []).filter((m) => m.estado === 'available' || m.estado === 'in_progress').slice(0, 2);
  const eventSug = summary.eventSuggested || summary['event-suggested'] || [];
  const activeEvents = summary.activeEvents || summary['active-events']
    || ((data.events || []).filter((e) => e.status === 'active' || e.available));
  const lines = [
    '📜 *MISSÕES DO TREINADOR*',
    '',
    '📊 *Seu progresso*',
    `☀️ Diárias: ${dailyDone != null ? dailyDone : '?'}/${dailyTotal != null ? dailyTotal : '?'} concluídas`,
    `📅 Semanais: ${weeklyDone != null ? weeklyDone : '?'}/${weeklyTotal != null ? weeklyTotal : '?'} concluídas`
  ];
  if (activeEvents.length) {
    const labels = activeEvents.map((e) => {
      const rem = e.remainingLabel || e['remaining-label'];
      return rem ? `${e.nome || e.id} (${rem})` : (e.nome || e.id);
    });
    lines.push(`🎉 Evento ativo: ${labels.join(', ')}`);
  }
  lines.push('', '☀️ *DIÁRIAS SUGERIDAS*');
  if (dailySug.length) dailySug.forEach((m) => lines.push(formatSuggestedLine(m)));
  else lines.push('• Nenhuma pendente — consulte a lista completa.');
  lines.push('', '📅 *SEMANAIS SUGERIDAS*');
  if (weeklySug.length) weeklySug.forEach((m) => lines.push(formatSuggestedLine(m)));
  else lines.push('• Nenhuma pendente — consulte a lista completa.');
  if (eventSug.length || activeEvents.length) {
    lines.push('', '🎉 *MISSÃO DE EVENTO*');
    if (eventSug.length) eventSug.forEach((m) => lines.push(formatSuggestedLine(m)));
    else lines.push('• Veja os objetivos com o filtro de eventos.');
  }
  lines.push(
    '',
    '🔎 *Veja todas as missões:*',
    `\`${prefix} missoes diaria\``,
    `\`${prefix} missoes semanal\``,
    `\`${prefix} missoes evento\``
  );
  return lines.join('\n');
}

function missionScope(scope) {
  const normalized = String(scope || 'all').normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  if (['daily', 'diaria', 'diarias', 'dia'].includes(normalized)) return 'daily';
  if (['weekly', 'semanal', 'semanais', 'semana'].includes(normalized)) return 'weekly';
  if (['event', 'evento', 'eventos'].includes(normalized)) return 'event';
  return 'all';
}

function formatMissions(data, scope = 'all', prefix = '!pk') {
  const selectedScope = missionScope(scope);
  if (selectedScope === 'all') return formatMissionsSummary(data, prefix);
  if (selectedScope === 'event') return formatMissionEventList(data && data.events, prefix);
  const sections = [];
  if (selectedScope === 'daily') sections.push(formatMissionList('daily', data && data.daily, prefix));
  if (selectedScope === 'weekly') sections.push(formatMissionList('weekly', data && data.weekly, prefix));
  return sections.join('\n\n');
}

function missionResult(data) {
  return data && data.reconciled && data.summary ? (data.summary.result || data.summary) : (data || {});
}

function formatMissionClaim(data, kind, prefix = '!pk') {
  const result = missionResult(data);
  if (result.empty) {
    const label = kind === 'daily' ? 'diária' : kind === 'weekly' ? 'semanal' : 'de evento';
    return `📋 Nenhuma missão ${label} concluída disponível para resgatar.`;
  }
  const lines = [`✅ ${result.ids && result.ids.length || 0} missão(ões) resgatada(s)!`];
  if (result.xp) lines.push(`✨ +${result.xp} PE do treinador`);
  if (result.coins) lines.push(`💰 +${result.coins} moedas`);
  const doces = result.doces || {};
  Object.keys(doces).forEach((fam) => {
    const n = Number(doces[fam] || 0);
    if (n > 0) lines.push(`🍬 +${n} Doce ${fam.charAt(0).toUpperCase()}${fam.slice(1)}`);
  });
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
    return '❓ Nenhum Pokémon apto (precisa de HP e golpe ofensivo). Cure com `!pk joy` ou escolha outro.';
  }
  const linhas = aptos.slice(0, 12).map((p, i) => {
    const n = (p.collectionIndex != null ? p.collectionIndex : i) + 1;
    return `${n}. ${p.nome || '?'} Nv.${p.nivel || '?'}`;
  });
  return [
    '⚔️ *Aptos para a raid*',
    ...linhas,
    `${prefix} raid entrar <n> · ${prefix} raid entrar auto`
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
  sair: 'sair', sai: 'sair',
  fruta: 'fruta', frambo: 'fruta', framb: 'fruta', razz: 'fruta'
};

function resolveCommand(tokens) {
  if (!tokens || !tokens.length) return 'menu';
  const raw = (tokens[0] || '').toLowerCase();
  // Alias legado: !pk 1 … !pk 4 escolhe o golpe pelo índice.
  if (/^[1-4]$/.test(raw)) return 'atacar';
  return ALIASES[raw] || null;
}

module.exports = {
  formatDurationPt,
  barraXp,
  PAGE_SIZE,
  envelope,
  formatTrainer,
  formatPokemonStorageLine,
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
  publicSaveLabel,
  nextSaveSlotId,
  resolveSaveSelection,
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
  formatTitles: (d, prefix = '!pk') => {
    const titles = Array.isArray(d && d.titles) ? d.titles : [];
    const selectedId = d && d.selected;
    const selectedLabel = selectedId
      ? ((titles.find((t) => t.id === selectedId) || {}).label || selectedId)
      : null;
    const head = selectedLabel
      ? `🏷️ *Títulos conquistados* (atual: *${selectedLabel}*)`
      : '🏷️ *Títulos conquistados*';
    const body = titles.length
      ? titles.map((t, i) => `${i + 1}. ${t.label || t.id}`).join('\n')
      : 'Nenhum ainda. Complete conquistas do treinador.';
    return `${head}\n${body}\n\nEscolha com ${prefix} titulo <número>.`;
  },
  formatProfessorCards: (d, prefix = '!pk') => {
    const cards = (d && d.cards) || {};
    const lines = Object.keys(cards)
      .sort()
      .filter((k) => Number(cards[k]) > 0)
      .map((k) => `🍬 ${candyLabel(k, cards[k])}: ${cards[k]}`);
    return [
      '🍬 *Doces Pokémon*',
      lines.length ? lines.join('\n') : 'Você ainda não tem Doces.',
      `Cada Doce concede +3 XP ao usar: ${prefix} professor usar <número>.`,
      `Aliases: ${prefix} professor cartoes`
    ].join('\n');
  },
  formatProfessorHelp: (prefix = '!pk') => [
    '👨‍🔬 *Professor — Doces Pokémon*',
    `Enviar: ${prefix} professor enviar <número> ou ${prefix} professor enviar 1,2,3`,
    'Cada transferência dá 1 Doce da família evolutiva. Confirme em até 5 minutos.',
    `Usar 1 Doce (+3 XP): ${prefix} professor usar <número>`,
    `Saldo: ${prefix} professor cartoes  (ou doces)`,
    `Cancelar envio: ${prefix} professor cancelar`
  ].join('\n'),
  formatProfessor: (d, prefix = '!pk') => {
    const data = d || {};
    const nested = data.summary && typeof data.summary === 'object' ? data.summary : {};
    const x = Object.assign({}, nested, data);
    if (x.cancelled) {
      return '✅ Transferência cancelada. Nenhum Pokémon foi enviado.';
    }
    if (x.confirmed) {
      const count = Number(x.count || x.n || ((x.entries && x.entries.length) || 1)) || 1;
      if (count > 1) {
        const cards = Number(x.cardsGranted != null ? x.cardsGranted : count) || count;
        const items = Number(x.itemsReturned || 0);
        return [
          `👨‍🔬 *POKÉMON ENVIADOS!*`,
          `✅ *${count}* Pokémon transferidos ao professor.`,
          `🍬 +${cards} Doces Pokémon · ${count} vaga(s) liberada(s).`,
          items > 0 ? `${items} item(ns) equipado(s) voltaram para a mochila.` : null,
          `Confira os novos números em ${prefix} tm.`
        ].filter(Boolean).join('\n');
      }
      const fam = x.family || '?';
      const nome = x.nome || x.species || 'Pokémon';
      const item = x.itemReturned || x.item;
      const total = x.cardsTotal != null ? x.cardsTotal : null;
      return [
        '👨‍🔬 *POKÉMON ENVIADO!*',
        '',
        `${nome} foi transferido.`,
        '',
        `🍬 +1 ${candyLabel(fam, 1)}`,
        total != null ? `🎒 Total: ${total} ${candyLabel(fam, total)}` : null,
        item ? 'O item equipado voltou para a mochila.' : null,
        `Confira os novos números em ${prefix} tm.`
      ].filter(Boolean).join('\n');
    }
    if (x.used) {
      const fam = x.family || '?';
      const nome = x.nome || 'Pokémon';
      const nivel = x.level != null ? x.level : '?';
      const up = x.leveledUp === true;
      return [
        `🍬 1 ${candyLabel(fam, 1)} usado em *${nome}*: +3 XP.`,
        up ? `Subiu para o nível ${nivel}!` : null
      ].filter(Boolean).join('\n');
    }
    if (x.token) {
      const entries = Array.isArray(x.entries) && x.entries.length
        ? x.entries
        : null;
      if (entries && entries.length > 1) {
        const linhas = entries.map((e) => {
          const n = e.displayIndex || ((Number(e.index) || 0) + 1);
          const nome = e.nome || e.species || 'Pokémon';
          const nivel = e.nivel != null ? e.nivel : (e.level != null ? e.level : '?');
          const shiny = e.shiny ? ' ✨' : '';
          return `• nº ${n} *${nome}* Nv.${nivel}${shiny} (${candyLabel(e.family || '?', 1)})`;
        });
        const comItem = entries.filter((e) => e.item).length;
        return [
          `⚠️ Enviar *${entries.length}* Pokémon ao professor? A transferência é definitiva.`,
          linhas.join('\n'),
          `Receberá ${entries.length} Doce(s) Pokémon (+3 XP cada ao usar).`,
          comItem ? `${comItem} item(ns) equipado(s) voltarão à mochila.` : null,
          `Confirme em até 5 minutos: ${prefix} professor confirmar ${x.token}`,
          `Cancelar: ${prefix} professor cancelar`
        ].filter(Boolean).join('\n');
      }
      const n = x.displayIndex || ((Number(x.index) || 0) + 1);
      const nome = x.nome || x.species || 'Pokémon';
      const nivel = x.nivel != null ? x.nivel : (x.level != null ? x.level : '?');
      const shiny = x.shiny ? ' ✨ SHINY' : '';
      const fam = x.family || '?';
      return [
        `⚠️ Enviar *${nome}* nº ${n} Nv.${nivel}${shiny} ao professor? A transferência é definitiva: você não poderá recuperar este Pokémon.`,
        `Receberá 1 ${candyLabel(fam, 1)} (+3 XP ao usar em outro membro da família).`,
        x.item ? 'O item equipado será devolvido à mochila.' : null,
        `Confirme em até 5 minutos: ${prefix} professor confirmar ${x.token}`,
        `Cancelar: ${prefix} professor cancelar`
      ].filter(Boolean).join('\n');
    }
    return '👨‍🔬 Operação do professor concluída.';
  },
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
  formatGymRanking,
  formatGymHistory,
  formatGymChallengeStart,
  formatGymResolve,
  resolveRaidSubcommand,
  resolveRaidGym,
  RAID_GYMS,
  formatRaidPrazo,
  formatRaidOpenHelp,
  formatRaidOpened,
  formatRaidCancelled,
  formatRaidStatus,
  formatRaidJoin,
  formatRaidLeave,
  formatLeaveResult,
  formatRaidStart,
  formatRaidAttack,
  formatRaidVictory,
  formatRaidDefeat,
  formatRaidCapture,
  formatRaidCaptureUi,
  formatRaidFrambo,
  formatRaidRewardLines,
  coerceRaidPresent,
  buildRaidMentionRoster,
  safeRaidMentionLabel,
  looksLikeTechnicalId,
  formatRaidEligible,
  raidFlag,
  displayTrainerName,
  formatRaidTurnBlock,
  formatRaidMoveLines,
  normalizeGymToken,
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
  formatBagKit,
  formatBagDaily,
  formatMissionItemsFromMap,
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
  formatPvpTurn,
  formatPvpRewards,
  pvpViewForSide,
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

/**
 * Assentos canônicos :x/:o — NUNCA espelha.
 * Espelhar por mySide fazia as barras trocarem de lado a cada turno no grupo
 * (mensagens alternam entre jogador X e O). Mantido só por compat de API.
 */
function pvpViewForSide(battle, mySide) {
  const b = battle || {};
  const side = mySide === 'o' ? 'o' : (mySide === 'x' ? 'x' : null);
  return {
    battle: b,
    viewSide: side || 'x',
    swapped: false
  };
}

function formatPvpRewards(rewards, prefix = '!pk') {
  const r = rewards || {};
  if (!r || (!r.moedas && !r.pe && !(r.pokemon && r.pokemon.length) && !r.bola)) {
    return null;
  }
  const lines = [];
  if (r.moedas) lines.push(`💰 +${r.moedas} moedas (confira com ${prefix} loja)`);
  if (r.pe) lines.push(`⭐ +${r.pe} PE de treinador (vitória)`);
  if (r.bola) lines.push(`🎒 +1 ${r.bola}`);
  const pokes = Array.isArray(r.pokemon) ? r.pokemon : [];
  if (pokes.length) {
    lines.push('✨ *XP por Pokémon:*');
    for (const p of pokes) {
      const nv = p.nivelFinal != null || p.subidas
        ? (p.subidas ? ` → Nv.${p.nivelFinal || '?'}` : '')
        : '';
      lines.push(`• ${p.treinador || '?'}: *${p.nome || '?'}* — ${p.nocautes || 0} nocaute(s), +${p.xp || 0} XP${nv}`);
    }
  }
  return lines.join('\n');
}

function formatPvpBattle(battle, prefix = '!pk', opts = {}) {
  const raw = battle || {};
  if (raw.aguardando || raw.phase === 'waiting') {
    return formatPvpChallenge(raw, opts.playerName, prefix);
  }
  const mySide = opts.mySide === 'o' || opts.mySide === 'x' ? opts.mySide : null;
  const suaVez = opts.suaVez != null
    ? Boolean(opts.suaVez)
    : Boolean(mySide && raw.vez && mySide === raw.vez);
  const nomes = raw.nomes || {};
  const vezNome = raw.vez === 'x' ? (nomes.x || 'X') : (nomes.o || 'O');
  // Assentos fixos: X sempre em cima/esquerda, O embaixo/direita (sem espelhar).
  const px = raw.pokemonX || {};
  const po = raw.pokemonO || {};
  const hp = raw.hp || {};
  const status = raw.status || {};
  const hpX = hpPair(px, hp.x);
  const hpO = hpPair(po, hp.o);
  const rewards = opts.rewards || raw.rewards || null;

  if (raw.outcome) {
    const lines = [
      '⚔️ *Batalha PvP*',
      `🐾 *${nomes.x || 'X'}* — *${nomePokemon(px)}*`,
      hpX ? barraHp(hpX.atual, hpX.max) : null,
      `👾 *${nomes.o || 'O'}* — *${nomePokemon(po)}*`,
      hpO ? barraHp(hpO.atual, hpO.max) : null,
      ''
    ].filter(v => v != null);
    if (raw.outcome === 'draw' || raw.outcome === 'cancelled') {
      lines.push(raw.outcome === 'draw' ? '🤝 Empate!' : '🚪 Desafio cancelado.');
    } else if (String(raw.outcome).startsWith('forfeit')) {
      lines.push('🏳️ Desistência — sem prêmio de vitória.');
    } else {
      const winName = raw.outcome === 'x' ? (nomes.x || 'X') : (nomes.o || 'O');
      lines.push(`🏆 *${winName}* venceu!`);
      const rewTxt = formatPvpRewards(rewards, prefix);
      if (rewTxt) lines.push(rewTxt);
      else lines.push('💰 +10 moedas');
    }
    return lines.join('\n');
  }

  const la = raw.lastAction || {};
  let acaoLinha = null;
  if (la.mensagem) acaoLinha = la.mensagem;
  else if (la.action === 'defend' || la.action === ':defend') {
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

  const atacante = raw.vez === 'o' ? po : px;
  const defensor = raw.vez === 'o' ? px : po;
  const golpes = menuGolpes(atacante, defensor.tipos || [], defensor.habilidade);
  const nGolpes = ((atacante.golpes) || []).length;

  const lines = [
    '⚔️ *Batalha PvP*',
    nomes.x || nomes.o ? `${nomes.x || 'X'} vs ${nomes.o || 'O'}` : null,
    acaoLinha,
    raw.turnIndex != null ? `⚔️ Turno ${raw.turnIndex}` : null,
    `👉 Vez de *${vezNome}*`,
    '',
    hpX ? `🐾 *${nomes.x || 'X'}* — *${nomePokemon(px)}*${emojiStatus(status.x)}` : null,
    hpX ? barraHp(hpX.atual, hpX.max) : null,
    hpO ? `👾 *${nomes.o || 'O'}* — *${nomePokemon(po)}*${emojiStatus(status.o)}` : null,
    hpO ? barraHp(hpO.atual, hpO.max) : null,
    '',
    golpes
      ? `Escolha: ${prefix} atacar <1-${nGolpes || 4}> · ${prefix} <1-${nGolpes || 4}> · ${prefix} defender · ${prefix} pocao · ${prefix} sair`
      : null,
    golpes,
    '',
    `📖 Ações: ${prefix} atacar ajuda · ${prefix} defender · ${prefix} pocao · ${prefix} sair`,
    mySide && !suaVez ? '' : null,
    mySide && !suaVez ? '⏳ Aguarde o adversário — não é a sua vez.' : null
  ].filter(v => v != null);

  return lines.join('\n');
}

/** Pós-turno PvP — mesmo envelope textual da caça (dano + estado + prêmios). */
function formatPvpTurn(data, prefix = '!pk', opts = {}) {
  const b = (data && data.battle) || data || {};
  const rewards = (data && data.rewards) || b.rewards || opts.rewards;
  return formatPvpBattle(b, prefix, Object.assign({}, opts, { rewards }));
}
