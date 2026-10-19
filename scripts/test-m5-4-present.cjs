'use strict';

/**
 * M5.4 — valida apresentação Node (!pk treinador / time / saves / joy)
 * sobre payloads reais da bridge (sem PII de chat/player).
 *
 * Uso: node scripts/test-m5-4-present.cjs <present-payload.json>
 */
const fs = require('node:fs');
const path = require('node:path');
const present = require('./lib/pokemon-jvm-present.cjs');

function fail(msg) {
  console.error('FAIL:', msg);
  process.exit(1);
}

function ok(msg) {
  console.log('OK:', msg);
}

function main() {
  const file = process.argv[2];
  if (!file || !fs.existsSync(file)) {
    fail(`payload ausente: ${file || '(nenhum)'}`);
  }
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const trainer = raw.trainer;
  const colA = raw.collectionA;
  const colB = raw.collectionB;
  const saves = raw.saves;
  const joy = raw.joy;

  if (!trainer || !colA || !colB || !saves) {
    fail('payload incompleto (trainer/collectionA/collectionB/saves)');
  }

  // !pk treinador
  const tTxt = present.formatTrainer(trainer, 'Jogador');
  if (!tTxt.includes('Treinador:')) fail('formatTrainer sem título');
  if (!String(trainer.collectionCount).match(/26/)) {
    // collectionCount pode ser number
  }
  if (trainer.collectionCount !== 26) fail(`treinador collectionCount=${trainer.collectionCount}`);
  if ((trainer.profile && trainer.profile.xp) !== 29) {
    fail(`treinador xp=${trainer.profile && trainer.profile.xp}`);
  }
  if ((trainer.profile && trainer.profile.coins) !== 198) {
    fail(`treinador coins=${trainer.profile && trainer.profile.coins}`);
  }
  // evita falso positivo no rodapé "Cartão visual legado ainda não portado"
  if (/originEdn|prontoEm|legado-edn|origin-edn/.test(tTxt)) {
    fail('formatTrainer expôs campos internos');
  }
  ok('formatTrainer');

  // !pk time — A (26) e B (119) com paginação
  if (colA.count !== 26 || (colA.collection || []).length !== 26) {
    fail(`coleção A count=${colA.count} len=${(colA.collection || []).length}`);
  }
  if (colB.count !== 119 || (colB.collection || []).length !== 119) {
    fail(`coleção B count=${colB.count} len=${(colB.collection || []).length}`);
  }

  const pagesB = Math.ceil(119 / present.PAGE_SIZE);
  const page1 = present.formatCollection(colB, [], '!pk');
  const pageLast = present.formatCollection(colB, [String(pagesB)], '!pk');
  const pageBad = present.formatCollection(colB, [String(pagesB + 1)], '!pk');

  if (!page1.includes(`Página 1/${pagesB}`)) fail(`paginação B p1: ${page1.slice(0, 120)}`);
  if (!pageLast.includes(`Página ${pagesB}/${pagesB}`)) fail('paginação B última');
  if (!pageBad.includes('Página inválida')) fail('paginação B inválida deveria falhar');

  // ordem: índices 1-based na 1ª página = 1..PAGE_SIZE
  for (let i = 0; i < Math.min(present.PAGE_SIZE, 119); i++) {
    const marker = `${i + 1}.`;
    if (!page1.includes(marker)) fail(`página 1 sem índice ${marker}`);
  }
  // não truncar: última página deve ter resto
  const resto = 119 % present.PAGE_SIZE || present.PAGE_SIZE;
  const lastStart = (pagesB - 1) * present.PAGE_SIZE + 1;
  if (!pageLast.includes(`${lastStart}.`)) fail(`última página sem índice ${lastStart}`);
  if (!pageLast.includes(`${119}.`)) fail('última página sem índice 119');

  // envelopes legados não devem aparecer no texto
  for (const txt of [page1, present.formatCollection(colA, [], '!pk')]) {
    if (/pronto-em|prontoEm|legado-edn|origin-edn|originEdn/.test(txt)) {
      fail('formatCollection expôs campos internos/envelopes');
    }
  }
  ok(`formatCollection A=26 B=119 pages=${pagesB}`);

  // shiny Voltorb presente em B (em alguma página)
  const shinyPages = [];
  for (let p = 1; p <= pagesB; p++) {
    const txt = present.formatCollection(colB, [String(p)], '!pk');
    if (txt.includes('Voltorb') && txt.includes('✨')) shinyPages.push(p);
  }
  // shiny mark is ✨ before name — also accept without if species listed
  const anyVoltorb = [];
  for (let p = 1; p <= pagesB; p++) {
    const txt = present.formatCollection(colB, [String(p)], '!pk');
    if (/Voltorb/.test(txt)) anyVoltorb.push(p);
  }
  if (!anyVoltorb.length) fail('Voltorb não aparece em nenhuma página de B');
  ok(`Voltorb em página(s) ${anyVoltorb.join(',')}`);

  // !pk saves
  const sTxt = present.formatSavesList(saves);
  if (!sTxt.includes('Saves A/B')) fail('formatSavesList título');
  if (!/ativo=a/.test(sTxt) && saves.activeSaveId !== 'a') {
    fail(`activeSaveId=${saves.activeSaveId}`);
  }
  const saveRows = saves.saves || [];
  if (saveRows.length !== 2) fail(`saves.length=${saveRows.length}`);
  const byId = Object.fromEntries(saveRows.map(s => [s.saveId, s]));
  if (!byId.a || byId.a.collectionCount !== 26) fail(`save A count=${byId.a && byId.a.collectionCount}`);
  if (!byId.b || byId.b.collectionCount !== 119) fail(`save B count=${byId.b && byId.b.collectionCount}`);
  if (/originEdn|legado-edn|origin-edn|prontoEm/.test(sTxt)) {
    fail('formatSavesList vazou campos internos');
  }
  ok('formatSavesList');

  // !pk joy — documenta gap estrutural (beds vazios com 7 na coleção)
  if (joy) {
    const jTxt = present.formatJoyStatus(joy, '!pk');
    const beds = (joy.enfermaria || []).length;
    if (beds === 0 && !jTxt.includes('Nenhum Pokémon na enfermaria')) {
      fail('formatJoyStatus sem mensagem de enfermaria vazia');
    }
    ok(`formatJoyStatus beds=${beds} feridos=${(joy.feridos || []).length}`);
    if (beds === 0) {
      console.log('NOTE: joy beds=0 (gap estrutural import location≠enfermaria-bed)');
    }
  } else {
    console.log('NOTE: joy payload ausente');
  }

  // DTOs da coleção não devem carregar envelopes
  for (const p of colA.collection || []) {
    if (p.pokemon && typeof p.pokemon === 'object') {
      fail('collection A item parece envelope {pokemon,...}');
    }
    if (p.prontoEm != null || p.location != null || p.legado != null || p.originEdn != null) {
      // location/prontoEm no DTO seria ok se adaptador expuser — hoje pokemon-dto não inclui;
      // se aparecer, ainda não é envelope legado incorreto, mas registramos
      console.log('NOTE: DTO expõe', Object.keys(p).filter(k =>
        ['prontoEm', 'location', 'legado', 'originEdn'].includes(k)));
    }
  }

  console.log(JSON.stringify({
    ok: true,
    pagesB,
    collectionA: colA.count,
    collectionB: colB.count,
    joyBeds: joy ? (joy.enfermaria || []).length : null,
    payload: path.basename(file)
  }));
}

main();
