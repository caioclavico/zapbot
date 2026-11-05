'use strict';

/**
 * Checagens de integração da Pokédex de espécie (sem WhatsApp real / sem prod).
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const species = require('../scripts/lib/pokemon-pokedex-species.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

function mockPikachuFetch() {
  return async (url) => {
    const u = String(url);
    if (u.includes('translate.googleapis.com')) {
      return { ok: true, json: async () => [[['traduzido', 'x']]] };
    }
    if (u.includes('/pokemon/25') || u.includes('/pokemon/pikachu')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: 25,
          name: 'pikachu',
          height: 4,
          weight: 60,
          types: [{ type: { name: 'electric' } }],
          abilities: [{ ability: { name: 'static' }, is_hidden: false }],
          stats: [
            { base_stat: 35, stat: { name: 'hp' } },
            { base_stat: 55, stat: { name: 'attack' } },
            { base_stat: 40, stat: { name: 'defense' } },
            { base_stat: 50, stat: { name: 'special-attack' } },
            { base_stat: 50, stat: { name: 'special-defense' } },
            { base_stat: 90, stat: { name: 'speed' } }
          ],
          sprites: {
            front_default: 'https://example.test/s.png',
            other: { 'official-artwork': { front_default: 'https://example.test/a.png' } }
          }
        })
      };
    }
    if (u.includes('/pokemon-species/')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          flavor_text_entries: [
            { language: { name: 'en' }, flavor_text: 'Electric mouse.' }
          ],
          evolution_chain: { url: 'https://pokeapi.co/api/v2/evolution-chain/10/' }
        })
      };
    }
    if (u.includes('/evolution-chain/')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ chain: { species: { name: 'pikachu' }, evolves_to: [] } })
      };
    }
    if (u.includes('/pokemon/')) {
      return { ok: false, status: 404, json: async () => ({}) };
    }
    throw new Error('unexpected fetch ' + u);
  };
}

test('integração: nome e número resolvem a mesma espécie', async (t) => {
  species._resetCacheForTests();
  const original = global.fetch;
  t.after(() => { global.fetch = original; });
  global.fetch = mockPikachuFetch();

  const byName = await species.lookup('pikachu', 'Odisseu');
  species._resetCacheForTests();
  const byNum = await species.lookup('25', 'Odisseu');
  assert.equal(byName.pokemon.numero, 25);
  assert.equal(byNum.pokemon.nome, 'Pikachu');
  assert.match(byName.text, /#025/);
  assert.match(byName.text, /Elétrico/);
  assert.match(byName.text, /Altura:/);
  assert.match(byName.text, /Peso:/);
  assert.match(byName.text, /HP:/);
});

test('integração: inexistente e falha de rede', async (t) => {
  species._resetCacheForTests();
  const original = global.fetch;
  t.after(() => { global.fetch = original; });

  global.fetch = async () => ({ ok: false, status: 404, json: async () => ({}) });
  const miss = await species.lookup('zzz-nope', 'Odisseu');
  assert.equal(miss.pokemon, null);
  assert.match(miss.text, /Não encontrei/);

  global.fetch = async () => { throw new Error('ECONNRESET'); };
  species._resetCacheForTests();
  const fail = await species.lookup('pikachu', 'Odisseu');
  assert.equal(fail.pokemon, null);
  assert.match(fail.text, /PokeAPI fora do ar/);
});

test('integração: imagem indisponível ainda gera cartão (placeholder)', async () => {
  const card = await media.renderPokedexCard({ nome: 'X', imagem: null });
  assert.ok(card);
  assert.equal(card.mimeType, 'image/png');
  assert.ok(card.base64.length > 50);
});

test('integração: envelope WhatsApp imagem+caption sem enviar', async (t) => {
  species._resetCacheForTests();
  const original = global.fetch;
  t.after(() => { global.fetch = original; });
  global.fetch = mockPikachuFetch();
  const result = await species.lookup('25', 'Odisseu');
  const card = await media.renderPokedexCard({
    nome: result.pokemon.nome,
    imagem: null
  });
  const env = present.envelope('req-dex-1', result.text, {
    image: card,
    caption: result.text
  });
  assert.equal(env.messages.length, 1);
  assert.equal(env.messages[0].type, 'image');
  assert.match(env.messages[0].text, /Pikachu/);
  assert.ok(env.messages[0].base64);
});

test('integração: !pk pokedex continua alias de coleção pessoal', () => {
  // present.resolveCommand cobre tokens pós-!pk; top-level !pdx fica no router cljs.
  assert.equal(present.resolveCommand(['pokedex']), 'pokedex');
  assert.equal(present.resolveCommand(['dex']), 'pokedex');
  assert.equal(present.resolveCommand(['colecao']), 'pokedex');
  assert.equal(typeof present.formatPokedex, 'function');
  assert.equal(typeof present.formatDiscoveries, 'function');
});

test('integração: formatters de raid/trade presentes (sem regressão de exports)', () => {
  const keys = Object.keys(present);
  assert.ok(keys.some((k) => /raid/i.test(k)), 'exports de raid');
  assert.ok(keys.some((k) => /trade/i.test(k)), 'exports de trade');
  assert.ok(typeof present.formatPokedex === 'function');
});
