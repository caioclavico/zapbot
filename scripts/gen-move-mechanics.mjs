#!/usr/bin/env node
/**
 * Gera move-mechanics.json a partir da PokéAPI e reclassifica a auditoria.
 * Não altera move-effects.json: os balanceamentos já aprovados continuam lá.
 *
 * Uso:
 *   node scripts/gen-move-mechanics.mjs /tmp/pokeapi-csv
 */
import fs from 'node:fs';
import path from 'node:path';

const csvDir = process.argv[2] || '/tmp/pokeapi-csv';
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

function readCsv(name) {
  const text = fs.readFileSync(path.join(csvDir, name), 'utf8').replace(/^\uFEFF/, '');
  const lines = [];
  let row = [];
  let cell = '';
  let q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; }
        else q = false;
      } else cell += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n') { row.push(cell); lines.push(row); row = []; cell = ''; }
    else if (c !== '\r') cell += c;
  }
  if (cell.length || row.length) { row.push(cell); lines.push(row); }
  const header = lines[0];
  return lines.slice(1).filter(r => r.length > 1).map(r => {
    const o = {};
    header.forEach((h, i) => { o[h] = r[i] ?? ''; });
    return o;
  });
}

const STAT = {
  2: 'ataque', 3: 'defesa', 4: 'atq-esp', 5: 'def-esp', 6: 'veloc',
  7: 'precisao', 8: 'evasao'
};
const AILMENT_STATUS = {
  1: 'paralisado', 2: 'adormecido', 3: 'congelado', 4: 'queimado',
  5: 'envenenado', 6: 'confuso'
};
const PRIMEIRO_TURNO = new Set(['fake-out', 'first-impression']);
const PROTECAO = new Set([
  'protect', 'detect', 'spiky-shield', 'baneful-bunker', 'king-shield',
  'silk-trap', 'obstruct', 'burning-bulwark', 'max-guard', 'crafty-shield'
]);
const ATRAVESSA = new Set([
  'feint', 'shadow-force', 'phantom-force', 'hyperspace-fury', 'hyperspace-hole', 'hyperdrill'
]);
const LIMPAR_EQUIPE = new Set(['heal-bell', 'aromatherapy']);
const LIMPAR_PROPRIO = new Set(['refresh']);
const LIMPAR_ESTAGIOS = new Set(['haze', 'clear-smog']);
const LIMPAR_VOLATIL = new Set(['rapid-spin', 'mortal-spin']);
const VENENO_GRAVE = new Set(['toxic', 'toxic-thread', 'poison-fang']);

const names = JSON.parse(fs.readFileSync(path.join(root, 'modules/pokemon/domain/move-names-ptbr.json'), 'utf8'));
const effects = JSON.parse(fs.readFileSync(path.join(root, 'modules/pokemon/domain/move-effects.json'), 'utf8'));
const moves = readCsv('moves.csv');
const metaRows = readCsv('move_meta.csv');
const statRows = readCsv('move_meta_stat_changes.csv');
const prose = readCsv('move_effect_prose.csv');

const byId = new Map(moves.map(m => [m.id, m]));
const metaById = new Map(metaRows.map(m => [m.move_id, m]));
const statsById = new Map();
for (const s of statRows) {
  if (!statsById.has(s.move_id)) statsById.set(s.move_id, []);
  statsById.get(s.move_id).push(s);
}
const proseEn = new Map();
for (const p of prose) {
  if (p.local_language_id === '9') proseEn.set(p.move_effect_id, (p.short_effect || '').toLowerCase());
}
const idBySlug = new Map();
for (const m of moves) idBySlug.set(m.identifier, m.id);

function num(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const mechanics = {};
for (const slug of Object.keys(names)) {
  const id = idBySlug.get(slug);
  if (!id) continue;
  const move = byId.get(id);
  const meta = metaById.get(id) || {};
  const fx = effects[slug] || null;
  const text = proseEn.get(move.effect_id) || '';
  const entry = {};
  const ailment = num(meta.meta_ailment_id);
  const statChance = num(meta.stat_chance) || 0;
  const ailmentChance = num(meta.ailment_chance) || 0;
  const flinch = num(meta.flinch_chance) || 0;
  const minHits = num(meta.min_hits);
  const maxHits = num(meta.max_hits);
  const minTurns = num(meta.min_turns);
  const maxTurns = num(meta.max_turns);
  const stats = statsById.get(id) || [];

  if (flinch > 0) entry.flinch = flinch;
  if (PRIMEIRO_TURNO.has(slug)) entry.soPrimeiroTurno = true;
  if (ATRAVESSA.has(slug)) entry.atravessaProtecao = true;
  if (maxHits != null && maxHits > 1) {
    entry.minAcertos = minHits && minHits > 0 ? minHits : 2;
    entry.maxAcertos = maxHits;
  }
  const accuracy = num(move.accuracy);
  const damageClass = String(move.damage_class_id);
  const category = num(meta.meta_category_id);
  const crit = num(meta.crit_rate) || 0;
  if ((damageClass === '2' || damageClass === '3') && (accuracy == null || accuracy === 0)) {
    entry.ignoraPrecisao = true;
  } else if (!fx?.precisao && accuracy != null && accuracy > 0 && accuracy < 100) {
    entry.precisao = accuracy;
  }
  if (text.includes('always results in a critical hit') || text.includes('always gets a critical hit')) {
    entry.criticoGarantido = true;
  } else if (crit > 0) {
    entry.chanceCritico = crit >= 2 ? 100 : 50;
  }

  if (VENENO_GRAVE.has(slug) || text.includes('badly poison')) {
    entry.familia = 'veneno-grave';
  } else if (ailment === 43 || PROTECAO.has(slug)
             || text.includes('protection for the rest of the turn')
             || text.includes('blocks damaging attacks')
             || text.includes('prevents any moves from hitting the user this turn')) {
    entry.familia = 'protecao';
  } else if (category === 9) {
    entry.familia = 'nocaute';
  } else if (category === 12 || slug === 'dragon-tail' || slug === 'circle-throw') {
    entry.familia = 'troca-forcada';
  } else if (ailment === 13 || slug === 'disable') {
    entry.familia = 'desabilitar';
  } else if (ailment === 12 || slug === 'torment') {
    entry.familia = 'tormento';
  } else if (ailment === 20 || slug === 'perish-song') {
    entry.familia = 'canto-mortal';
  } else if (slug === 'stealth-rock') {
    entry.familia = 'hazard'; entry.campo = 'rochas';
  } else if (slug === 'spikes') {
    entry.familia = 'hazard'; entry.campo = 'espinhos';
  } else if (slug === 'toxic-spikes') {
    entry.familia = 'hazard'; entry.campo = 'toxicos';
  } else if (slug === 'sticky-web') {
    entry.familia = 'hazard'; entry.campo = 'teia';
  } else if (slug === 'hail') {
    entry.familia = 'clima';
    entry.campo = 'granizo';
  } else if (['sunny-day', 'rain-dance', 'sandstorm', 'snowscape'].includes(slug)) {
    entry.familia = 'clima';
    entry.campo = { 'sunny-day': 'sol', 'rain-dance': 'chuva', 'sandstorm': 'areia', snowscape: 'neve' }[slug];
  } else if (['electric-terrain', 'grassy-terrain', 'misty-terrain'].includes(slug)) {
    entry.familia = 'terreno';
    entry.campo = { 'electric-terrain': 'eletrico', 'grassy-terrain': 'grama', 'misty-terrain': 'nevoa' }[slug];
  } else if (ailment === 18) {
    entry.familia = 'semente';
    entry.alvo = 'adversario';
  } else if (ailment === 8) {
    entry.familia = 'residual';
    entry.turnosMin = minTurns && minTurns > 0 ? minTurns : 4;
    entry.turnosMax = maxTurns && maxTurns > 0 ? maxTurns : 5;
  } else if (LIMPAR_EQUIPE.has(slug)) {
    entry.familia = 'limpar-status-equipe';
  } else if (LIMPAR_PROPRIO.has(slug)) {
    entry.familia = 'limpar-status-proprio';
  } else if (LIMPAR_ESTAGIOS.has(slug)) {
    entry.familia = 'limpar-estagios';
  } else if (LIMPAR_VOLATIL.has(slug)) {
    entry.familia = 'limpar-volatil';
  } else if (text.includes('foregoes its next turn to recharge') || text.includes('must recharge')) {
    entry.familia = 'recarga';
  } else if (
    text.includes('charges for one turn')
    || text.includes('turn to charge')
    || text.includes('hits next turn')
  ) {
    entry.familia = 'preparacao';
    if (text.includes('dodging all attacks')) entry.invulneravel = true;
    if (slug === 'solar-beam' || slug === 'solar-blade') entry.pulaCargaNoSol = true;
  }
  if (slug === 'thunder' || slug === 'hurricane') {
    entry.precisaoChuva = 100;
    entry.precisaoSol = 50;
  }
  if (slug === 'blizzard') entry.precisaoNeve = 100;

  if (entry.familia === 'protecao') {
    delete entry.alteracoes;
    delete entry.chanceAlteracao;
  } else if (!fx?.alteracoes && stats.length) {
    const alteracoes = stats
      .map(s => ({ atributo: STAT[num(s.stat_id)], estagios: num(s.change) }))
      .filter(a => a.atributo && a.estagios);
    if (alteracoes.length) {
      entry.alteracoes = alteracoes;
      entry.alvo = alteracoes.some(a => a.estagios < 0) ? 'adversario' : 'proprio';
      if (statChance > 0 && statChance < 100) entry.chanceAlteracao = statChance;
    }
  } else if (fx?.alteracoes && stats.length) {
    const presentes = new Set((fx.alteracoes || []).map(a => a.atributo));
    const extra = stats
      .map(s => ({ atributo: STAT[num(s.stat_id)], estagios: num(s.change) }))
      .filter(a => a.atributo && a.estagios && !presentes.has(a.atributo));
    if (extra.length) entry.alteracoesExtra = extra;
  }

  if (!fx?.statusCausado && entry.familia !== 'veneno-grave' && AILMENT_STATUS[ailment]) {
    entry.statusCausado = AILMENT_STATUS[ailment];
    const chance = ailmentChance > 0 ? ailmentChance : 100;
    if (chance < 100) entry.chanceStatus = chance;
  }

  const drain = num(meta.drain) || 0;
  const healing = num(meta.healing) || 0;
  if (!fx?.dreno && !fx?.recuo && drain > 0) entry.dreno = drain;
  if (!fx?.dreno && !fx?.recuo && drain < 0) entry.recuo = Math.abs(drain);
  if (!fx?.cura && healing > 0 && damageClass === '1') entry.cura = healing;

  if (Object.keys(entry).length) mechanics[slug] = entry;
}

const json = JSON.stringify(mechanics);
const targets = [
  path.join(root, 'modules/pokemon/domain/move-mechanics.json'),
  path.join(root, 'scripts/lib/move-mechanics.json')
];
for (const t of targets) fs.writeFileSync(t, json);

function covers(limite, mec) {
  if (!mec) return false;
  if (limite === 'recuo que impede a ação') return mec.flinch > 0;
  if (limite === 'vários acertos') return mec.maxAcertos > 1;
  if (limite === 'proteção') {
    return mec.familia === 'protecao' || mec.atravessaProtecao === true
      || (mec.familia === 'terreno' && mec.campo === 'nevoa');
  }
  if (limite === 'precisão') return (mec.alteracoes || mec.alteracoesExtra || []).some(a => a.atributo === 'precisao') || mec.ignoraPrecisao === true;
  if (limite === 'evasão') return (mec.alteracoes || mec.alteracoesExtra || []).some(a => a.atributo === 'evasao');
  if (limite === 'semente sanguessuga') return mec.familia === 'semente' || mec.familia === 'limpar-volatil';
  if (limite === 'veneno grave') return mec.familia === 'veneno-grave';
  if (limite === 'armadilha') return mec.familia === 'residual';
  if (limite === 'vários turnos') return ['preparacao', 'recarga', 'residual', 'canto-mortal'].includes(mec.familia);
  if (limite === 'nocaute de um golpe') return mec.familia === 'nocaute';
  if (limite === 'troca forçada') return mec.familia === 'troca-forcada';
  if (limite === 'desabilitar') return mec.familia === 'desabilitar';
  if (limite === 'tormento') return mec.familia === 'tormento';
  if (limite === 'canto mortal') return mec.familia === 'canto-mortal';
  if (limite === 'efeito de campo') return ['clima', 'terreno', 'hazard'].includes(mec.familia);
  return false;
}

const conhecidoCoberto = (texto, mec, fx) => {
  if (texto === 'metadado ausente na PokéAPI') return false;
  if (texto === 'chance de crítico diferente') return Boolean(mec?.chanceCritico || mec?.criticoGarantido);
  const alts = [...(fx?.alteracoes || []), ...(mec?.alteracoes || []), ...(mec?.alteracoesExtra || [])];
  const m = texto.match(/^(ataque especial|defesa especial|ataque|defesa|velocidade|precisão|evasão) ([+-]\d+)$/);
  if (m) {
    const nome = {
      ataque: 'ataque', defesa: 'defesa', 'ataque especial': 'atq-esp',
      'defesa especial': 'def-esp', velocidade: 'veloc', precisão: 'precisao', evasão: 'evasao'
    }[m[1]];
    const n = Number(m[2]);
    return alts.some(a => a.atributo === nome && Number(a.estagios) === n);
  }
  if (['paralisia', 'queimadura', 'confusão', 'sono', 'congelamento', 'envenenamento'].includes(texto)) {
    return Boolean(fx?.statusCausado || mec?.statusCausado || mec?.familia === 'veneno-grave');
  }
  if (texto === 'dreno') return Boolean(fx?.dreno || mec?.dreno);
  if (texto === 'recuo') return Boolean(fx?.recuo || mec?.recuo);
  if (texto === 'cura') return Boolean(fx?.cura || mec?.cura);
  return false;
};

const auditPaths = [
  path.join(root, 'modules/pokemon/domain/move-effect-audit.json'),
  path.join(root, 'scripts/lib/move-effect-audit.json')
];
const audit = JSON.parse(fs.readFileSync(auditPaths[0], 'utf8'));
const counts = {};
for (const [slug, row] of Object.entries(audit)) {
  const mec = mechanics[slug];
  const fx = effects[slug];
  const limites = (row.limites || []).filter(l => !covers(l, mec));
  const conhecidos = row.conhecidos || [];
  const descobertos = conhecidos.filter(c => !conhecidoCoberto(c, mec, fx));
  const modelado = new Set(row.modelado || []);
  if (fx?.statusCausado) modelado.add(fx.statusCausado);
  if (fx?.alteracoes) modelado.add('estágios');
  if (fx?.dreno) modelado.add('dreno');
  if (fx?.recuo) modelado.add('recuo');
  if (fx?.cura) modelado.add('cura');
  if (fx?.autodesmaio) modelado.add('autodesmaio');
  if (mec?.familia) modelado.add(mec.familia);
  if (mec?.flinch) modelado.add('flinch');
  if (mec?.maxAcertos > 1) modelado.add('acertos');
  if (mec?.alteracoes || mec?.alteracoesExtra) modelado.add('estágios');
  if (mec?.statusCausado) modelado.add(mec.statusCausado);
  if (mec?.dreno) modelado.add('dreno');
  if (mec?.recuo) modelado.add('recuo');
  if (mec?.cura) modelado.add('cura');
  if (mec?.ignoraPrecisao) modelado.add('ignora-precisao');
  if (mec?.precisao) modelado.add('precisao');
  if (mec?.chanceCritico || mec?.criticoGarantido) modelado.add('critico');
  if (mec?.atravessaProtecao) modelado.add('atravessa-protecao');
  let classe;
  if (limites.length) classe = 'mecanica-ausente';
  else if (descobertos.length) classe = 'conhecido-nao-implementado';
  else if (modelado.size) classe = 'implementado';
  else classe = 'sem-efeito-adicional';
  row.classe = classe;
  row.limites = limites;
  row.modelado = [...modelado];
  counts[classe] = (counts[classe] || 0) + 1;
}
const auditJson = JSON.stringify(audit);
for (const p of auditPaths) fs.writeFileSync(p, auditJson);
const valores = Object.values(mechanics);
console.log(JSON.stringify({
  mecanicas: Object.keys(mechanics).length,
  precisaoNumerica: valores.filter(m => m.precisao).length,
  ignoraPrecisao: valores.filter(m => m.ignoraPrecisao).length,
  criticoAlterado: valores.filter(m => m.chanceCritico || m.criticoGarantido).length,
  counts,
  familias: Object.values(mechanics).reduce((a, m) => {
    const k = m.familia || (m.flinch ? 'flinch' : m.maxAcertos ? 'acertos' : 'outro');
    a[k] = (a[k] || 0) + 1;
    return a;
  }, {})
}, null, 2));
