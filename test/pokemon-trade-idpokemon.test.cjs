'use strict';

/**
 * Regressão do incidente "erro interno na bridge" em `troca confirmar`.
 * A coleção JVM expõe `idPokemon` (camelCase de :id-pokemon). O handler
 * Node precisa preferir esse campo ao montar a proposta.
 */
const test = require('node:test');
const assert = require('node:assert/strict');

function pokemonIdOf(poke) {
  if (!poke || typeof poke !== 'object') return null;
  return poke.idPokemon || poke.pokemonId || poke['id-pokemon'] || poke.id || poke.uuid || null;
}

test('extrai id canônico a partir de idPokemon (DTO bridge JVM)', () => {
  assert.equal(pokemonIdOf({ idPokemon: 'abc-123', nome: 'Pikachu' }), 'abc-123');
});

test('não inventa id quando só há nome (cenário que gerava internal_error)', () => {
  assert.equal(pokemonIdOf({ nome: 'Hawlucha', species: 'hawlucha' }), null);
  assert.equal(pokemonIdOf({ pokemonAName: 'Chansey' }), null);
});

test('aceita aliases legados pokemonId/id/uuid', () => {
  assert.equal(pokemonIdOf({ pokemonId: 'legacy-1' }), 'legacy-1');
  assert.equal(pokemonIdOf({ id: 'legacy-2' }), 'legacy-2');
  assert.equal(pokemonIdOf({ uuid: 'legacy-3' }), 'legacy-3');
});
