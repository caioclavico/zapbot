'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.join(__dirname, '..');

function source(relative) {
  return fs.readFileSync(path.join(root, relative), 'utf8');
}

test('o bundle WhatsApp não carrega o motor Pokémon legado', () => {
  for (const arquivo of ['src/zapbot/core.cljs', 'src/zapbot/router.cljs', 'src/zapbot/pokemon_http.cljs']) {
    const texto = source(arquivo);
    assert.doesNotMatch(texto, /zapbot\.pokemon\.(core|golpes|treinador|loja|raids)/, arquivo);
    assert.doesNotMatch(texto, /js\/require\s+"[^"]*pokemon\/core/, arquivo);
  }
  assert.match(source('src/zapbot/router.cljs'), /\[zapbot\.pokemon-http :as pokemon\]/);
  assert.match(source('shadow-cljs.edn'), /:main zapbot\.core\/main/);
});

test('reset, migração, catálogo e Sinistea continuam no lugar', () => {
  const shadow = source('shadow-cljs.edn');
  assert.match(shadow, /zapbot\.migrar-estado\/-main/);
  assert.match(shadow, /zapbot\.pokemon\.resetar\/-main/);
  assert.match(source('src/zapbot/pokemon/golpes.cljs'), /\(def nomes-pt/);
  assert.match(source('src/zapbot/pokemon_http.cljs'), /evoluir-sinistea!/);
  assert.match(source('src/zapbot/pokemon_http.cljs'), /catalisador-evolutivo/);
  assert.match(source('src/zapbot/pokemon_http.cljs'), /O item não foi consumido/);
});
