'use strict';

/**
 * Apresentação Node (M4.3): DTOs JVM → envelope {messages,effects}.
 * Formatação WhatsApp fica fora do domínio JVM.
 */

const PAGE_SIZE = 12;

function envelope(requestId, text, extras = {}) {
  const messages = [];
  if (extras.image && (extras.image.mediaId || extras.image.base64)) {
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
    BATTLE_ALREADY_ACTIVE: '⚔️ Já existe uma batalha/encontro seu em andamento.',
    AREA_UNAVAILABLE: '🗺️ Essa área não está disponível hoje.',
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
    'Disponíveis: `treinador`, `meus`/`time`, `save`/`saves`, `iniciar`, `ativar a|b`, `cacar`, `capturar`/`cap`, `ginasio`/`gin`, `raide`/`raid`, `recibo <id>`.',
    'Sem fallback automático para Cassandra. Use `POKEMON_ADAPTER=legacy` para o restante.'
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

function formatHuntStart(plan, result, prefix = '!pk') {
  const area = plan.area || {};
  const clima = plan.clima || {};
  const selvagem = (result && result.selvagem) || {};
  const somente = result && result.somenteBatalha;
  const surto = result && result.surto;
  return [
    `${area.emoji || '🗺️'} Você entrou em *${area.nome || area.id || '?'}*!`,
    `${clima.emoji || '☁️'} Clima *${clima.nome || '?'}*: tipos ${(clima.tipos || []).join(', ') || '?'} aparecem mais.`,
    surto && plan.evento ? `🎉 ${plan.evento.nome} — encontro do evento!` : null,
    `Um ${textoRaridade(selvagem)} *${selvagem.nome || '?'}* apareceu.`,
    somente
      ? 'Bolsa cheia: esta caçada vale batalha e XP, sem captura.'
      : 'Derrote-o antes de tentar capturar!',
    '',
    `Batalha: ${prefix} atacar <golpe>`,
    `Captura: ${prefix} capturar <bola> após a vitória.`
  ].filter(Boolean).join('\n');
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
  const x = b.pokemonX || {};
  const o = b.pokemonO || {};
  return [
    `🏛️ Desafio contra *${b.liderNome || 'líder'}*!`,
    `*${x.nome || 'Você'}* vs *${o.nome || 'defensor'}*`,
    `HP: ${b.hp && b.hp.x != null ? b.hp.x : '?'} · ${b.hp && b.hp.o != null ? b.hp.o : '?'}`,
    `Reservas: você ${b.reservasX != null ? b.reservasX : 2} · líder ${b.reservasO != null ? b.reservasO : 2}`,
    golpesLinha(x) ? `Seus golpes: ${golpesLinha(x)}` : null,
    `Ataque: ${prefix} atacar <1-4>`
  ].filter(Boolean).join('\n');
}

function formatBattleTurn(data, prefix = '!pk') {
  const b = (data && data.battle) || {};
  const hp = b.hp || {};
  const o = b.pokemonO || (b.pokemons && b.pokemons.o) || {};
  const x = b.pokemonX || (b.pokemons && b.pokemons.x) || {};
  const nome = o.nome || o.species || 'adversário';
  const area = b.area || {};
  const gym = b.kind === 'gym' || Boolean(b.gymId);
  if (data.gymResolve || (!b.active && gym && b.outcome)) {
    return formatGymResolve(data.gymResolve || { venceu: b.outcome === 'win' }, b);
  }
  if (data.aguardandoCaptura || b.aguardandoCaptura) {
    return [
      `🏁 *${nome}* foi derrotado!`,
      area.nome ? `${area.emoji || '🗺️'} ${area.nome}` : null,
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
  const dano = la.dano != null ? `Último golpe: ${la.dano} de dano.` : null;
  const switchLine = b.lastSwitch && b.lastSwitch.nome
    ? `🔁 Entra *${b.lastSwitch.nome}*!`
    : null;
  return [
    gym
      ? `🏛️ *${b.gymNome || b.gymId || 'Ginásio'}* — vs ${b.liderNome || 'líder'}`
      : (area.nome ? `${area.emoji || '🗺️'} *${area.nome}*` : null),
    switchLine,
    `⚔️ Turno ${b.turnIndex != null ? b.turnIndex : '?'}`,
    `HP *${x.nome || 'você'}*: ${hp.x != null ? hp.x : '?'} · *${nome}*: ${hp.o != null ? hp.o : '?'}`,
    gym && (b.reservasX != null || b.reservasO != null)
      ? `Reservas: você ${b.reservasX != null ? b.reservasX : '?'} · líder ${b.reservasO != null ? b.reservasO : '?'}`
      : null,
    (!gym && o.tipos && o.tipos.length) ? `Tipos selvagem: ${o.tipos.join(', ')}` : null,
    dano,
    golpesLinha(x) ? `Seus golpes: ${golpesLinha(x)}` : null,
    `Continue: ${prefix} atacar <1-4>`
  ].filter(Boolean).join('\n');
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
  treinador: 'treinador', perfil: 'treinador', tre: 'treinador',
  meus: 'time', pokemons: 'time', colecao: 'time', 'coleção': 'time',
  time: 'time', equipe: 'time', tm: 'time',
  save: 'save', ativo: 'save',
  saves: 'saves', 'saves-list': 'saves',
  iniciar: 'iniciar', criar: 'iniciar',
  ativar: 'ativar',
  capturar: 'capturar', cap: 'capturar',
  cacar: 'cacar', caçar: 'cacar', cac: 'cacar', hunt: 'cacar',
  atacar: 'atacar', ataque: 'atacar', atk: 'atacar',
  ginasio: 'ginasio', ginásio: 'ginasio', ginasios: 'ginasio', ginásios: 'ginasio',
  gin: 'ginasio',
  raid: 'raid', raide: 'raid', raides: 'raid', raids: 'raid',
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
  formatHuntEligibility,
  formatHuntStart,
  formatBattleTurn,
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
  filtrarColecao,
  interpretarFiltros,
  golpesLinha
};
