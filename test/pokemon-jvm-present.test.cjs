'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('resolveCommand mapeia aliases do legado', () => {
  assert.equal(present.resolveCommand([]), 'menu');
  assert.equal(present.resolveCommand(['perfil']), 'treinador');
  assert.equal(present.resolveCommand(['tm']), 'time');
  assert.equal(present.resolveCommand(['meus']), 'time');
  assert.equal(present.resolveCommand(['equipe']), 'time');
  assert.equal(present.resolveCommand(['ativar']), 'ativar');
  assert.equal(present.resolveCommand(['iniciar']), 'iniciar');
  assert.equal(present.resolveCommand(['inicial']), 'inicial');
  assert.equal(present.resolveCommand(['ini']), 'inicial');
  assert.equal(present.resolveCommand(['capturar']), 'capturar');
  assert.equal(present.resolveCommand(['cap']), 'capturar');
  assert.equal(present.resolveCommand(['cacar']), 'cacar');
  assert.equal(present.resolveCommand(['1']), 'atacar');
  assert.equal(present.resolveCommand(['4']), 'atacar');
  assert.equal(present.resolveCommand(['batalhar']), 'batalhar');
  assert.equal(present.resolveCommand(['gin']), 'ginasio');
  assert.equal(present.resolveCommand(['raide']), 'raid');
  assert.equal(present.resolveCommand(['clima']), 'clima');
  assert.equal(present.resolveCommand(['eventos']), 'eventos');
});

test('subcomandos de ginásio e raide e mensagens de evento são apresentados', () => {
  assert.equal(present.resolveGymSubcommand('des'), 'desafiar');
  assert.equal(present.resolveGymSubcommand('hist'), 'historico');
  assert.equal(present.resolveRaidSubcommand('atk'), 'atacar');
  assert.equal(present.resolveRaidSubcommand('cap'), 'capturar');
  assert.match(present.formatWorldInfo({
    area: { id: 'vale', nome: 'Vale' },
    clima: { nome: 'Sol', tipos: ['fogo'] }
  }), /Clima de hoje: Sol/);
  assert.match(present.formatEventInfo({
    eventoAtual: { nome: 'Festival', especies: ['Pikachu'], fim: Date.now() + 60000 }
  }), /Festival|Pikachu/);
});

test('ranking e histórico de ginásio apresentam DTOs persistidos', () => {
  const ranking = present.formatGymRanking({ gymId: 'pedra', ranking: [
    { nome: 'Ash', defesas: 2, tempoMs: 5000 }
  ] });
  assert.match(ranking, /Ranking — pedra/);
  assert.match(ranking, /Ash/);
  const history = present.formatGymHistory({ gymId: 'pedra', history: [
    { nome: 'Ash', lider: 'Brock', venceu: true }
  ] });
  assert.match(history, /Histórico — pedra/);
  assert.match(history, /Ash vs Brock/);
});

test('formatMainMenu e jornada sem jargão técnico', () => {
  const novo = present.formatMainMenu({ hasTrainer: false, status: 'not_started' }, '!pk');
  assert.match(novo, /Bem-vindo/);
  assert.match(novo, /!pk iniciar/);
  assert.doesNotMatch(novo, /JVM|Cassandra|POKEMON_ADAPTER/);

  const pendente = present.formatMainMenu({
    hasTrainer: true, status: 'awaiting_starter'
  }, '!pk');
  assert.match(pendente, /inicial/);

  const completo = present.formatMainMenu({
    hasTrainer: true, status: 'completed'
  }, '!pk');
  assert.match(completo, /treinador/);
  assert.match(completo, /cacar/);

  const start = present.formatStartJourney({
    created: true,
    alreadyComplete: false,
    kit: { qty: 10, kitAlreadyClaimed: false },
    onboarding: {
      status: 'awaiting_starter',
      starterAvailable: true,
      starters: [
        { index: 1, nome: 'Bulbasaur', emoji: '🌱', tipos: ['grass', 'poison'], descricao: 'x' },
        { index: 2, nome: 'Charmander', emoji: '🔥', tipos: ['fire'], descricao: 'y' },
        { index: 3, nome: 'Squirtle', emoji: '💧', tipos: ['water'], descricao: 'z' }
      ]
    }
  }, '!pk');
  assert.match(start, /10 Pokébolas/);
  assert.match(start, /Bulbasaur/);
  assert.match(start, /Planta\/Veneno/);
  assert.match(start, /Charmander/);
  assert.match(start, /Fogo/);
  assert.match(start, /Squirtle/);
  assert.match(start, /Água/);
  assert.match(start, /inicial <número>/);

  const chosen = present.formatStarterChosen({
    nome: 'Charmander', emoji: '🔥', species: 'charmander'
  }, '!pk');
  assert.match(chosen, /Charmander/);
  assert.match(chosen, /cacar/);
});

test('formatTrainer produz envelope texto compatível', () => {
  const text = present.formatTrainer({
    activeSaveId: 'a',
    fenceGeneration: 2,
    collectionCount: 1,
    partyDefined: true,
    partyCount: 1,
    shinyCount: 0,
    capacidadePokemon: 50,
    ocupacaoPokemon: 1,
    colecaoCheia: false,
    economiaConfiavel: true,
    coins: 40,
    capacidadeMochila: 50,
    ocupacaoMochila: 2,
    savesCount: 2,
    saves: [
      { saveId: 'a', active: true, collectionCount: 1 },
      { saveId: 'b', active: false, collectionCount: 0 }
    ],
    hunt: { cooldownOk: true, cooldownRestanteS: 0 },
    joy: { emTratamento: 0 },
    ativoIndex: 0,
    profile: {
      nivel: 3, xp: 12, xpAtual: 2, xpNecessario: 9, coins: 40,
      vitoriasTreinador: 4,
      peGinasios: 1, peRaids: 0, sequenciaCapturas: 2, maiorSequenciaCapturas: 5
    },
    ativo: { nome: 'Bulbasaur', species: 'Bulbasaur', nivel: 12, shiny: false }
  }, 'Ash');
  assert.match(text, /Treinador: Ash/);
  assert.match(text, /Nível 3/);
  assert.match(text, /\[.*\].*2\/9/);
  assert.match(text, /Vitórias: 4/);
  assert.match(text, /Bulbasaur/);
  assert.match(text, /Save ativo: \*a\*/);
  assert.match(text, /\*a\* ✅/);
  assert.match(text, /Moedas: 40/);
  assert.match(text, /Mochila de itens: 2\/50/);
  assert.match(text, /Caça: disponível/);
  assert.doesNotMatch(text, /Perfil JVM \(dev\)/);
  const env = present.envelope('req-1', text);
  assert.equal(env.requestId, 'req-1');
  assert.equal(env.messages[0].type, 'text');
  assert.deepEqual(env.effects, []);
});

test('formatTrainer recovery-v1 cheio sem moedas falsas', () => {
  const text = present.formatTrainer({
    activeSaveId: 'recovery-v1',
    activeSaveName: 'recovery-v1',
    collectionCount: 119,
    partyDefined: false,
    partyCount: null,
    shinyCount: 3,
    capacidadePokemon: 100,
    ocupacaoPokemon: 119,
    excessoPokemon: 19,
    colecaoCheia: true,
    economiaConfiavel: false,
    coinsStatus: 'unknown-pending',
    capacidadeMochila: 100,
    ocupacaoMochila: 3,
    saves: [
      { saveId: 'a', active: false, collectionCount: 0 },
      { saveId: 'b', active: false, collectionCount: 0 },
      { saveId: 'recovery-v1', displayName: 'recovery-v1', active: true, collectionCount: 119 }
    ],
    hunt: { cooldownOk: false, cooldownRestanteS: 900 },
    joy: { emTratamento: 2, proximoRestanteMs: 600000 },
    gymDefenseCount: 3,
    ativoIndex: 93,
    profile: {
      nivel: 10, xp: 127, xpAtual: 2, xpNecessario: 9,
      peGinasios: 0, peRaids: 0
    },
    ativo: { nome: 'Nidoqueen', nivel: 18, shiny: false }
  }, 'Caio', '!pk');
  assert.match(text, /119\/100 — CHEIO \(19 acima/);
  assert.match(text, /Equipe: não definida/);
  assert.doesNotMatch(text, /Equipe: 119/);
  assert.match(text, /Shiny: 3/);
  assert.match(text, /#94 \*Nidoqueen\* • Nv\.18/);
  assert.match(text, /\*recovery-v1\* ✅/);
  assert.match(text, /saldo histórico em reconciliação/);
  assert.doesNotMatch(text, /Moedas: 0/);
  assert.match(text, /Mochila de itens: 3\/100/);
  assert.match(text, /Joy: 2 em tratamento/);
  assert.match(text, /Caça: cooldown/);
  assert.match(text, /Em ginásios: 3/);
  assert.match(text, /!pk saves/);
});

test('formatTrainer equipe parcial definida sem inventar', () => {
  const text = present.formatTrainer({
    activeSaveId: 'a',
    collectionCount: 10,
    partyDefined: true,
    partyCount: 3,
    shinyCount: 0,
    capacidadePokemon: 50,
    ocupacaoPokemon: 10,
    profile: { nivel: 2, xp: 5, xpAtual: 1, xpNecessario: 7 },
    ativoIndex: 0,
    ativo: { nome: 'Squirtle', nivel: 5 },
    saves: [{ saveId: 'a', active: true, collectionCount: 10 }]
  }, 'Misty');
  assert.match(text, /Equipe: 3/);
  assert.doesNotMatch(text, /não definida/);
  assert.doesNotMatch(text, /Equipe: 10/);
});

test('formatTrainer omite campos opcionais ausentes', () => {
  const text = present.formatTrainer({
    activeSaveId: 'a',
    collectionCount: 0,
    capacidadePokemon: 50,
    ocupacaoPokemon: 0,
    economiaConfiavel: true,
    coins: 0,
    profile: { nivel: 1, xp: 0, xpAtual: 0, xpNecessario: 5 },
    saves: [{ saveId: 'a', active: true, collectionCount: 0 }]
  }, 'Novo');
  assert.match(text, /Treinador: Novo/);
  assert.match(text, /0\/50/);
  assert.doesNotMatch(text, /Joy:/);
  assert.doesNotMatch(text, /Em ginásios/);
  assert.doesNotMatch(text, /Vitórias/);
});

test('formatCollection pagina e filtra', () => {
  const collection = Array.from({ length: 15 }, (_, i) => ({
    collectionIndex: i,
    nome: i === 3 ? 'Charmander' : `Poke${i}`,
    species: i === 3 ? 'Charmander' : `Poke${i}`,
    nivel: i === 3 ? 20 : 5,
    hp: 40, hpAtual: 20, raridade: 'comum', shiny: i === 3, tipos: i === 3 ? ['fire'] : ['normal']
  }));
  const text = present.formatCollection(
    { collection, count: 15, saveId: 'a', trainerLevel: 2, ativoIndex: 0 },
    ['fogo', '1'],
    '!pk'
  );
  assert.match(text, /Charmander/);
  assert.match(text, /Filtros/);
  assert.match(text, /Página 1/);
});

test('filtro planta e grama resolvem grass; repetidos lista só espécies duplicadas', () => {
  const collection = [
    { collectionIndex: 0, nome: 'Bulba', species: 'bulbasaur', nivel: 5, hp: 40, hpAtual: 40, tipos: ['grass', 'poison'] },
    { collectionIndex: 1, nome: 'Ivy', species: 'ivysaur', nivel: 16, hp: 50, hpAtual: 50, tipos: ['grass', 'poison'] },
    { collectionIndex: 2, nome: 'Ratta A', species: 'rattata', nivel: 3, hp: 20, hpAtual: 20, tipos: ['normal'] },
    { collectionIndex: 3, nome: 'Ratta B', species: 'rattata', nivel: 4, hp: 22, hpAtual: 22, tipos: ['normal'] },
    { collectionIndex: 4, nome: 'Charmander', species: 'charmander', nivel: 5, hp: 30, hpAtual: 30, tipos: ['fire'] }
  ];
  const data = { collection, count: 5, saveId: 'a', trainerLevel: 1, ativoIndex: 0 };
  const planta = present.formatCollection(data, ['planta'], '!pk');
  assert.match(planta, /Bulba/);
  assert.match(planta, /Ivy/);
  assert.doesNotMatch(planta, /Charmander/);
  assert.match(planta, /tipo Planta/);
  assert.match(present.formatCollection(data, ['grama'], '!pk'), /Bulba/);
  const reps = present.formatCollection(data, ['repetidos'], '!pk');
  assert.match(reps, /Ratta A/);
  assert.match(reps, /Ratta B/);
  assert.doesNotMatch(reps, /Bulba/);
  assert.match(reps, /repetidos/);
});

test('formatProfessor lote lista entradas e confirma contagem', () => {
  const pending = present.formatProfessor({
    token: '321',
    entries: [
      { displayIndex: 1, nome: 'Pidgey', nivel: 5, family: 'pidgey' },
      { displayIndex: 4, nome: 'Rattata', nivel: 3, family: 'rattata', item: 'pokebola' }
    ]
  }, '!pk');
  assert.match(pending, /2\* Pokémon|Enviar \*2\*/);
  assert.match(pending, /Pidgey/);
  assert.match(pending, /Rattata/);
  assert.match(pending, /professor confirmar 321/);
  assert.match(
    present.formatProfessor({ confirmed: true, count: 2, cardsGranted: 2, itemsReturned: 1 }),
    /POKÉMON ENVIADOS|Doces Pokémon/
  );
});

test('shiny e armazenamento usam a coleção canônica', () => {
  const data = {
    collection: [{ collectionIndex: 0, nome: 'Pikachu', shiny: true, nivel: 5, hp: 20, hpAtual: 20 }],
    count: 1, saveId: 'a', trainerLevel: 1, ativoIndex: 0
  };
  assert.match(present.formatCollection(data, ['shiny'], '!pk'), /Pikachu/);
  assert.match(present.formatStorage(data, [], '!pk'), /PC Pokémon/);
  assert.match(present.formatSpace({ capacidadePokemon: 50, ocupacaoPokemon: 1 }, '!pk'), /1\/50/);
  assert.match(
    present.formatSpace({ capacidadePokemon: 100, ocupacaoPokemon: 119, excessoPokemon: 19, colecaoCheia: true }, '!pk'),
    /119\/100.*CHEIO.*19 acima/
  );
  assert.match(
    present.formatPokemonStorageLine({ capacidadePokemon: 100, ocupacaoPokemon: 119, excessoPokemon: 19 }),
    /119\/100 — CHEIO \(19 acima do limite\)/
  );
  assert.match(
    present.formatCollection(
      { collection: [{ collectionIndex: 0, nome: 'X', nivel: 1, hp: 1, hpAtual: 1 }],
        count: 119, capacidadePokemon: 100, ocupacaoPokemon: 119, excessoPokemon: 19, colecaoCheia: true,
        saveId: 'recovery-v1', trainerLevel: 10, ativoIndex: 0 },
      [], '!pk'
    ),
    /119\/100 — CHEIO/
  );
  assert.match(
    present.formatError({ code: 'COLLECTION_FULL', details: { count: 119, capacidade: 100, excesso: 19 } }),
    /119\/100/
  );
  assert.equal(present.resolveCommand(['computador']), 'pc');
  assert.equal(present.resolveCommand(['centro']), 'pc');
  assert.equal(present.resolveCommand(['espaco']), 'espaco');
  assert.equal(present.resolveCommand(['shiny']), 'shiny');
});

test('formatTeams apresenta escalações persistidas por nome e índices atuais', () => {
  const text = present.formatTeams({ teams: [
    { name: 'Liga 1', pokemonIds: ['p-1', 'p-2', 'p-3'], indices: [2, 0, 4] }
  ] }, '!pk');
  assert.match(text, /Liga 1/);
  assert.match(text, /3, 1, 5/);
  assert.match(text, /times usar/);
});

test('envelope suporta documento CSV em base64', () => {
  const env = present.envelope('csv-1', 'Seu time em CSV', {
    document: { base64: 'YWJj', mimeType: 'text/csv', filename: 'time.csv' }
  });
  assert.equal(env.messages[0].type, 'document');
  assert.equal(env.messages[0].filename, 'time.csv');
  assert.equal(env.messages[0].base64, 'YWJj');
});

test('formatError distingue pending local de recibo ausente', () => {
  assert.match(present.formatError({ code: 'OPERATION_PENDING' }), /neste processo JVM/);
  assert.match(present.formatError({ code: 'OPERATION_NOT_FOUND' }), /não persistida/);
  assert.match(present.formatError({ code: 'STALE_WRITER_FENCE' }), /obsoleto/);
  assert.match(present.formatError({ code: 'ECONNREFUSED' }), /indisponível/);
  assert.doesNotMatch(present.formatError({ code: 'ECONNREFUSED' }), /Cassandra/);
  assert.match(present.formatError({ code: 'INVALID_STARTER' }), /1, 2 ou 3/);
});

test('formatError explica os gates de troca sem expor dados do outro treinador', () => {
  // Mensagens canônicas (1d8f794 / UX trocas #N) — chave duplicada antiga não vence.
  assert.match(present.formatError({ code: 'TRADE_FORBIDDEN' }), /não pode fazer isso|proposta de troca/i);
  assert.match(present.formatError({ code: 'TRADE_ACTION_FORBIDDEN' }), /destinatário aceita|criou confirma/i);
  assert.match(present.formatError({ code: 'TRADE_SAVE_MISMATCH' }), /save ativo mudou/i);
  assert.match(present.formatError({ code: 'TRADE_EXPIRED' }), /expirou/i);
  assert.match(present.formatError({ code: 'INVALID_TRADE_TRANSITION' }), /não aceita esta ação|estado atual/i);
  assert.doesNotMatch(present.formatError({ code: 'TRADE_FORBIDDEN' }), /@lid|@c\.us|[0-9]{5,}/i);
});

test('formatUnavailable amigável sem jargão de infra', () => {
  // Lote A (c5744c3): router de ajuda — sem frase legada "não está disponível".
  const text = present.formatUnavailable('batalhar');
  assert.match(text, /não reconhecido|ajuda/i);
  assert.match(text, /batalhar/);
  assert.doesNotMatch(text, /Cassandra|POKEMON_ADAPTER|JVM/);
});

test('formatProfessor espelha master: pendente, confirmação e cartões', () => {
  const pending = present.formatProfessor({
    token: '742',
    nome: 'Pidgey',
    displayIndex: 3,
    nivel: 5,
    family: 'pidgey',
    shiny: true
  }, '!pk');
  assert.match(pending, /Enviar \*Pidgey\* nº 3/);
  assert.match(pending, /SHINY/);
  assert.match(pending, /professor confirmar 742/);
  assert.match(pending, /5 minutos/);
  assert.match(
    present.formatProfessor({ confirmed: true, nome: 'Pidgey', family: 'pidgey', itemReturned: 'pokebola' }),
    /POKÉMON ENVIADO[\s\S]*Doce Pidgey[\s\S]*mochila/s
  );
  assert.match(
    present.formatProfessor({ used: true, nome: 'Pidgeotto', family: 'pidgey', level: 6, leveledUp: true }),
    /Doce Pidgey[\s\S]*\+3 XP[\s\S]*nível 6/s
  );
  assert.match(present.formatProfessor({ cancelled: true }), /Transferência cancelada/);
  assert.match(present.formatProfessorCards({ cards: { pidgey: 2 } }), /Doces Pidgey: 2/);
  assert.match(present.formatProfessorHelp('!pk'), /professor enviar/);
  assert.match(present.formatProfessorHelp('!pk'), /Doces Pokémon/);
  assert.match(present.formatError({ code: 'PROFESSOR_BUSY' }), /envio pendente/);
  assert.match(present.formatError({ code: 'POKEMON_FAVORITE' }), /favorito/);
});

test('formatLeaveResult cobre modalidades e ausência de batalha', () => {
  assert.match(present.formatLeaveResult({ status: 'no_battle' }), /não está participando/);
  assert.match(present.formatLeaveResult({ status: 'already_finished' }), /já foi encerrada/);
  assert.match(present.formatLeaveResult({ status: 'left', modality: 'wild' }), /abandonou a batalha/);
  assert.match(present.formatLeaveResult({ status: 'left', modality: 'gym' }), /abandonou a batalha/);
  assert.match(
    present.formatLeaveResult({ status: 'left', modality: 'pvp', battle: { outcome: 'forfeit-x' } }),
    /desistiu.*PvP|adversário/
  );
  assert.match(
    present.formatLeaveResult({ status: 'left', modality: 'raid', raidEnded: false }),
    /saiu da raide.*demais participantes/
  );
  assert.match(
    present.formatLeaveResult({ status: 'left', modality: 'raid', raidEnded: true }),
    /raide encerrou/
  );
  assert.match(present.formatError({ code: 'FEATURE_DISABLED' }), /temporariamente desabilitado/);
  assert.match(present.formatError({ code: 'NOT_FOUND' }), /Rota inexistente/);
});

test('formatBagKit e formatBagDaily espelham legado', () => {
  assert.match(present.formatBagKit({ kitAlreadyClaimed: true }), /já resgatou o kit/);
  assert.match(present.formatBagKit({ qty: 10, itemId: 'pokebola' }), /10× Pokébola/);
  assert.match(
    present.formatBagDaily({ dailyAlreadyClaimed: true, timezone: 'America/Sao_Paulo' }),
    /já foi resgatado.*America\/Sao_Paulo/
  );
  const ok = present.formatBagDaily({
    sequencia: 7,
    items: { pokebola: 7, 'fruta-dourada': 1 },
    frutaDourada: true,
    pending: { pokebola: 2 }
  });
  assert.match(ok, /sequência de 7/);
  assert.match(ok, /Fruta Frambo Dourada/);
  assert.match(ok, /resgatar/);
});

test('saves: label público, resolução e próximo slot', () => {
  const saves = [
    { saveId: 'a', displayName: 'aventura', coins: 1 },
    { saveId: 'b', coins: 0 },
    { saveId: 'recovery-v1', origin: 'recovery-v1', coins: 9 }
  ];
  assert.equal(present.publicSaveLabel(saves[0]), 'aventura');
  assert.equal(present.publicSaveLabel(saves[1]), 'b');
  assert.equal(present.nextSaveSlotId(saves), 'c');
  assert.equal(present.resolveSaveSelection(saves, '1').saveId, 'a');
  assert.equal(present.resolveSaveSelection(saves, 'aventura').saveId, 'a');
  assert.equal(present.resolveSaveSelection(saves, 'b').saveId, 'b');
  assert.equal(present.resolveSaveSelection(saves, 'recovery-v1').saveId, 'recovery-v1');
  assert.equal(present.resolveSaveSelection(saves, 'nope'), null);
  const txt = present.formatSavesList({ saves, activeSaveId: 'a', fenceGeneration: 3 }, '!pk');
  assert.match(txt, /SEUS SAVES/);
  assert.match(txt, /aventura/);
  assert.match(txt, /ativo/);
  assert.match(txt, /recuperado/);
  assert.match(txt, /novo save/);
  assert.match(txt, /renomear save/);
  assert.doesNotMatch(txt, /^\• \*a\*/m);
});
