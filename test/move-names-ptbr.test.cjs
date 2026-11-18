'use strict';

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const nomes = require('../scripts/lib/move-names.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const root = path.join(__dirname, '..');

test('catálogo PT-BR cobre todos os slugs e não tem nome vazio', () => {
  const cobertura = nomes.cobertura();
  assert.equal(cobertura.vazios.length, 0);
  assert.equal(cobertura.traduzidos, cobertura.total);
  assert.ok(cobertura.total >= 900);
  assert.deepEqual(cobertura.colisoes, []);
  const exemplos = {
    tackle: 'Investida',
    withdraw: 'Recolher-se',
    flamethrower: 'Lança-Chamas',
    'water-gun': "Jato d'Água",
    'hydro-pump': 'Hidrobomba',
    thunderbolt: 'Raio',
    thunder: 'Trovão',
    screech: 'Guincho',
    'swords-dance': 'Dança das Espadas'
  };
  for (const [slug, nome] of Object.entries(exemplos)) {
    assert.equal(nomes.nomes[slug], nome, slug);
  }
});

test('os dois artefatos de nome e de efeito são o mesmo arquivo', () => {
  for (const arquivo of ['move-names-ptbr.json', 'move-effects.json']) {
    const a = fs.readFileSync(path.join(root, 'scripts/lib', arquivo), 'utf8');
    const b = fs.readFileSync(path.join(root, 'modules/pokemon/domain', arquivo), 'utf8');
    assert.equal(a, b, arquivo);
  }
});

test('o JSON de nomes é o mesmo mapa de golpes.cljs', () => {
  const src = fs.readFileSync(path.join(root, 'src/zapbot/pokemon/golpes.cljs'), 'utf8');
  const bloco = src.slice(src.indexOf('(def nomes-pt'), src.indexOf('(defn- normalizar-nome'));
  const pares = [...bloco.matchAll(/"((?:\\.|[^"\\])*)"\s+"((?:\\.|[^"\\])*)"/g)];
  assert.equal(pares.length, Object.keys(nomes.nomes).length);
  for (const [, slug, nome] of pares) {
    assert.equal(nomes.nomes[slug], nome, slug);
  }
});

test('alias inglês, português e ambiguidade de furacão', () => {
  assert.equal(nomes.resolver('Tackle').slug, 'tackle');
  assert.equal(nomes.resolver('Investida').slug, 'tackle');
  assert.equal(nomes.resolver('Recolher-se').slug, 'withdraw');
  assert.equal(nomes.nomePtBr({ nomeExibicao: 'Water Gun' }), "Jato d'Água");
  assert.equal(nomes.nomePtBr({ slug: 'withdraw', nomeExibicao: 'Withdraw' }), 'Recolher-se');
  assert.equal(nomes.resolver('Furacão').slug, 'hurricane');
  assert.equal(nomes.resolver('Furação').slug, 'drill-run');
  const ambiguo = nomes.resolver('furacao');
  assert.equal(ambiguo.ambiguo, true);
  assert.deepEqual(ambiguo.candidatos.sort(), ['drill-run', 'hurricane']);
});

test('menu de status mostra o efeito real e não a frase genérica', () => {
  const menu = present.menuGolpes({
    golpes: [
      { slug: 'withdraw', nomeExibicao: 'Withdraw', tipo: 'water', classe: 'status', poder: 0 },
      { slug: 'flamethrower', nomeExibicao: 'Flamethrower', tipo: 'fire', classe: 'especial', poder: 90 }
    ]
  }, ['grass']);
  assert.match(menu, /Recolher-se/);
  assert.match(menu, /Defesa \+2/);
  assert.match(menu, /Lança-Chamas/);
  assert.doesNotMatch(menu, /efeito especial/);
  assert.doesNotMatch(menu, /Withdraw/);
  const texto = fs.readFileSync(path.join(root, 'scripts/lib/pokemon-jvm-present.cjs'), 'utf8');
  assert.equal(texto.includes('efeito especial'), false);
});
