'use strict';

/**
 * Apresentação Node (M4.3): DTOs JVM → envelope {messages,effects}.
 * Formatação WhatsApp fica fora do domínio JVM.
 */

const PAGE_SIZE = 12;

function envelope(requestId, text, extras = {}) {
  const messages = [];
  if (extras.image && extras.image.mediaId) {
    messages.push({
      type: 'image',
      text: extras.caption || text,
      mediaId: extras.image.mediaId,
      mimeType: extras.image.mimeType || 'image/jpeg',
      filename: extras.image.filename || 'pokemon-jvm.jpg'
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
  const filled = Math.round((a / m) * 8);
  return `${'█'.repeat(filled)}${'░'.repeat(8 - filled)} ${a}/${m}`;
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

function interpretarFiltros(tokens) {
  const ordem = tokens.includes('>') ? '>' : tokens.includes('<') ? '<' : null;
  const rest = tokens.filter(t => t !== '>' && t !== '<');
  let nivel = null;
  let nome = '';
  const tipos = [];
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
  return { ordem, nivel, nome: normalizar(nome), tipos, shiny };
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
    return true;
  });
  if (f.ordem === '>') list.sort((a, b) => poder(b) - poder(a));
  if (f.ordem === '<') list.sort((a, b) => poder(a) - poder(b));
  return { list, filtros: f };
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
  const items = data.collection || [];
  const { list, filtros } = filtrarColecao(items, tokens);
  const { page, pages, valida } = paginaDe(tokens, list.length);
  if (!valida && list.length) {
    return `❓ Página inválida. Escolha de 1 a ${pages}: ${prefix} time 1`;
  }
  const slice = list.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const ativoIdx = data.ativoIndex;
  const linhas = slice.map(p => {
    const idx = p.collectionIndex;
    const marker = idx === ativoIdx ? '👉 ' : '';
    return `${marker}${(idx != null ? idx + 1 : '?')}. ${shinyMark(p)}*${nomePokemon(p)}* Nv.${p.nivel || 1}`
      + ` • ${p.raridade || 'comum'}`
      + `\n   ${barraHp(p.hpAtual, p.hp)}`;
  });
  const filtroDesc = [];
  if (filtros.shiny) filtroDesc.push('✨ shiny');
  if (filtros.ordem) filtroDesc.push(filtros.ordem === '>' ? 'força ↓' : 'força ↑');
  if (filtros.nivel != null) filtroDesc.push(`nível ${filtros.nivel}`);
  if (filtros.tipos.length) filtroDesc.push(`tipo ${filtros.tipos.join('/')}`);
  if (filtros.nome) filtroDesc.push(`nome “${filtros.nome}”`);

  return [
    `🎒 *Sua coleção Pokémon* • Página ${Math.min(page, pages)}/${pages}`,
    `🧑‍🎓 Nível de treinador: ${data.trainerLevel || 1} • Save: ${data.saveId || '?'}`,
    filtroDesc.length ? `🔎 Filtros: ${filtroDesc.join(' + ')} — ${list.length} Pokémon` : `Total: ${data.count || items.length}`,
    '',
    linhas.length ? linhas.join('\n') : (items.length ? 'Nenhum Pokémon corresponde a esse filtro.' : 'Coleção vazia. Use !pk iniciar.'),
    '',
    page > 1 ? `⬅️ Anterior: ${prefix} time ${page - 1}` : null,
    page < pages ? `➡️ Próxima: ${prefix} time ${page + 1}` : null,
    `Use ${prefix} escolher <número> no legado; no JVM o ativo é o índice do save.`
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
    data.created ? '✅ *Treinador criado* (JVM)' : 'ℹ️ *Treinador já existia* (JVM)',
    `Save ativo: *${data.activeSaveId}* · fence ${data.fenceGeneration}`
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
    INSUFFICIENT_ITEM: '🎒 Você não tem essa bola. Escolha outra ou compre na loja (legado).',
    COLLECTION_FULL: '🎒 Bolsa cheia: libere espaço antes de capturar.',
    BATTLE_PENDING: '⚠️ Há batalha/encontro em andamento neste save — não dá para alternar A↔B agora.',
    BATTLE_SAVE_MISMATCH: '⚠️ Encontro pertence a outro save — callback antigo rejeitado.',
    TIMEOUT: '⏳ Bridge JVM demorou. Se foi escrita, confira com !pk recibo <id> antes de repetir.',
    CONNECT_TIMEOUT: '⚠️ Não conectou na bridge JVM. Nenhuma escrita foi ao Cassandra.',
    NETWORK: '⚠️ Bridge JVM indisponível. Nenhuma escrita foi ao Cassandra.',
    ECONNREFUSED: '⚠️ Bridge JVM indisponível. Nenhuma escrita foi ao Cassandra.'
  };
  return map[code] || `⚠️ ${err.message || 'Falha na bridge JVM'} (${code}).`;
}

function formatUnavailable(commandHint) {
  return [
    '🚧 *Comando ainda não integrado no adaptador JVM*',
    commandHint ? `Pedido: \`${commandHint}\`` : null,
    'Disponíveis: `treinador`, `meus`/`time`, `save`/`saves`, `iniciar`, `ativar a|b`, `capturar`/`cap`, `recibo <id>`.',
    '`!pk cacar` bloqueado: spawn (bioma/clima/PokeAPI) ainda não migrado — sem regra substituta.',
    'Sem fallback automático para Cassandra. Use `POKEMON_ADAPTER=legacy` para o restante.'
  ].filter(Boolean).join('\n');
}

function formatHuntBlocked() {
  return [
    '🚧 *Caçada (!pk cacar) ainda não disponível no adaptador JVM*',
    'O spawn depende de bioma, clima, PokeAPI, shiny e eventos — ainda não migrados.',
    'Não inventamos encontro substituto nesta milestone.',
    'Captura pós-vitória: `!pk capturar <bola>` quando houver encontro em `aguardando-captura`.',
    'Use `POKEMON_ADAPTER=legacy` para caçar no serviço Cassandra.'
  ].join('\n');
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

/** Aliases alinhados ao legado (ajuda.cljs / core). */
const ALIASES = {
  treinador: 'treinador', perfil: 'treinador', tre: 'treinador',
  meus: 'time', pokemons: 'time', colecao: 'time', 'coleção': 'time',
  time: 'time', equipe: 'time', tm: 'time',
  save: 'save', ativo: 'save',
  saves: 'saves', 'saves-list': 'saves',
  iniciar: 'iniciar', criar: 'iniciar',
  ativar: 'ativar',
  capturar: 'capturar', cap: 'capturar',
  cacar: 'cacar', caçar: 'cacar', hunt: 'cacar',
  recibo: 'recibo', op: 'recibo', operation: 'recibo'
};

function resolveCommand(tokens) {
  const raw = (tokens[0] || '').toLowerCase();
  return ALIASES[raw] || null;
}

module.exports = {
  PAGE_SIZE,
  envelope,
  formatTrainer,
  formatCollection,
  formatActiveSave,
  formatSavesList,
  formatEnsure,
  formatActivate,
  formatReceipt,
  formatError,
  formatUnavailable,
  formatHuntBlocked,
  formatCaptureMenu,
  formatCaptureResult,
  resolveCommand,
  filtrarColecao,
  interpretarFiltros
};
