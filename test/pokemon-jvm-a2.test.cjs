'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const pokeapi = require('../scripts/lib/pokemon-pokeapi.cjs');

test('aliases escolher/evoluir/aprender', () => {
  assert.equal(present.resolveCommand(['escolher']), 'escolher');
  assert.equal(present.resolveCommand(['esc']), 'escolher');
  assert.equal(present.resolveCommand(['trocar']), 'escolher');
  assert.equal(present.resolveCommand(['evoluir']), 'evoluir');
  assert.equal(present.resolveCommand(['evo']), 'evoluir');
  assert.equal(present.resolveCommand(['aprender']), 'aprender');
  assert.equal(present.resolveCommand(['apr']), 'aprender');
  assert.equal(present.resolveCommand(['reaprender']), 'reaprender');
  assert.equal(present.resolveCommand(['rmg']), 'removergolpe');
  assert.equal(present.resolveCommand(['cancelar']), 'cancelar');
});

const cadeiaSinistea = {
  chain: {
    species: { name: 'sinistea' },
    evolves_to: [{
      species: { name: 'polteageist' },
      evolution_details: [
        { trigger: { name: 'use-item' }, item: { name: 'cracked-pot' } },
        { trigger: { name: 'use-item' }, item: { name: 'chipped-pot' } },
        { trigger: { name: 'use-item' }, item: { name: 'water-stone' } }
      ],
      evolves_to: [{
        species: { name: 'gengar' },
        evolution_details: [
          { trigger: { name: 'use-item' }, item: { name: 'cracked-pot' } }
        ]
      }]
    }]
  }
};

const inventarioComCatalisador = {
  itens: [{ itemId: 'catalisador-evolutivo', qty: 1 }, { itemId: 'pedra-trovao', qty: 2 }]
};

test('Sinistea aceita cracked-pot e chipped-pot e para em Polteageist', () => {
  assert.deepEqual(pokeapi.destinosSinistea(cadeiaSinistea), ['polteageist']);
  const plano = pokeapi.planejarEvolucaoSinistea({
    species: 'sinistea',
    chain: cadeiaSinistea,
    inventario: inventarioComCatalisador
  });
  assert.equal(plano.ok, true);
  assert.equal(plano.consumir, true);
  assert.equal(plano.itemId, 'catalisador-evolutivo');
  assert.equal(plano.destino, 'polteageist');
  const explicito = pokeapi.planejarEvolucaoSinistea({
    species: 'Sinistea',
    chain: cadeiaSinistea,
    destino: 'polteageist',
    inventario: { itens: [{ 'item-id': 'catalisador-evolutivo', qty: '1' }] }
  });
  assert.equal(explicito.consumir, true);
});

test('falha de Sinistea não autoriza consumo', () => {
  const semItem = pokeapi.planejarEvolucaoSinistea({
    species: 'sinistea',
    chain: cadeiaSinistea,
    inventario: { itens: [{ itemId: 'catalisador-evolutivo', qty: 0 }] }
  });
  assert.equal(semItem.ok, false);
  assert.equal(semItem.consumir, false);
  assert.equal(semItem.motivo, 'sem-item');

  const destinoErrado = pokeapi.planejarEvolucaoSinistea({
    species: 'sinistea',
    chain: cadeiaSinistea,
    destino: 'gengar',
    inventario: inventarioComCatalisador
  });
  assert.equal(destinoErrado.consumir, false);
  assert.equal(destinoErrado.motivo, 'destino');

  const semCadeia = pokeapi.planejarEvolucaoSinistea({
    species: 'sinistea',
    chain: { chain: { species: { name: 'sinistea' }, evolves_to: [] } },
    inventario: inventarioComCatalisador
  });
  assert.equal(semCadeia.consumir, false);
  assert.equal(semCadeia.motivo, 'sem-cadeia');
});

test('Pikachu continua na pedra-trovão e outras evoluções especiais ficam de fora', () => {
  assert.equal(pokeapi.destinoPedra('pedra-trovao', 'pikachu'), 'raichu');
  const pikachu = pokeapi.planejarEvolucaoSinistea({
    species: 'pikachu',
    chain: {
      chain: {
        species: { name: 'pichu' },
        evolves_to: [{
          species: { name: 'pikachu' },
          evolves_to: [{
            species: { name: 'raichu' },
            evolution_details: [
              { trigger: { name: 'use-item' }, item: { name: 'thunder-stone' } }
            ]
          }]
        }]
      }
    },
    inventario: inventarioComCatalisador
  });
  assert.equal(pikachu.ok, false);
  assert.equal(pikachu.consumir, false);
  assert.equal(pikachu.motivo, 'especie');
  const cadeiaApplin = {
    chain: {
      species: { name: 'applin' },
      evolves_to: [{
        species: { name: 'flapple' },
        evolution_details: [
          { trigger: { name: 'use-item' }, item: { name: 'tart-apple' } }
        ]
      }]
    }
  };
  const applin = pokeapi.planejarEvolucaoSinistea({
    species: 'applin',
    chain: cadeiaApplin,
    inventario: inventarioComCatalisador
  });
  assert.equal(applin.consumir, false);
  assert.deepEqual(pokeapi.destinosSinistea(cadeiaApplin), []);
});

test('pedras de evolução espelham master', () => {
  assert.equal(pokeapi.destinoPedra('pedra-trovao', 'pikachu'), 'raichu');
  assert.equal(pokeapi.destinoPedra('pedra-fogo', 'eevee'), 'flareon');
  assert.equal(pokeapi.destinoPedra('pedra-agua', 'bulbasaur'), null);
  assert.deepEqual(pokeapi.pedrasCompativeis('pikachu'), ['pedra-trovao']);
});

test('escalarNivel usa fator 1.03', () => {
  const base = { hp: 100, ataque: 100, defesa: 100, atqEsp: 100, defEsp: 100, veloc: 100 };
  const n16 = pokeapi.escalarNivel(base, 16);
  assert.equal(n16.nivel, 16);
  assert.equal(n16.hp, Math.round(100 * Math.pow(1.03, 15)));
});

test('formatters A2', () => {
  assert.match(present.formatSetActive({
    ativoIndex: 1,
    pokemon: { nome: 'Squirtle' }
  }, '!pk'), /Squirtle/);
  assert.match(present.formatEvolve({
    itemId: 'pedra-trovao',
    pokemon: { nome: 'Raichu' }
  }), /Raichu/);
  assert.match(present.formatEvolve({
    itemId: 'pedra-trovao',
    pokemon: { nome: 'Raichu' }
  }), /pedra-trovao/);
  assert.match(present.formatEvolve({
    itemId: 'catalisador-evolutivo',
    pokemon: { nome: 'Polteageist' }
  }), /Catalisador Evolutivo/);
  assert.match(present.formatMoveOfferMenu({
    nome: 'Pikachu',
    golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }],
    ofertasGolpes: [{ nomeExibicao: 'Iron Tail', slug: 'iron-tail' }]
  }, '!pk'), /Iron Tail/);
  assert.match(present.formatError({ code: 'BATTLE_BUSY' }), /batalha/);
});

test('coleção orienta escolher', () => {
  const text = present.formatCollection({
    collection: [{ collectionIndex: 0, nome: 'Bulbasaur', nivel: 5, hp: 40, hpAtual: 40 }],
    ativoIndex: 0,
    trainerLevel: 1,
    count: 1,
    saveId: 'a'
  }, [], '!pk');
  assert.match(text, /escolher/);
  assert.match(text, /👉/);
});

test('evolve envia o contrato da bridge b04682f e só consome depois do plano', () => {
  const fs = require('node:fs');
  const http = fs.readFileSync(require('node:path').join(__dirname, '../src/zapbot/pokemon_http.cljs'), 'utf8');
  const bridge = fs.readFileSync(require('node:path').join(
    __dirname,
    '../modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj'
  ), 'utf8');
  const store = fs.readFileSync(require('node:path').join(
    __dirname,
    '../modules/pokemon/infrastructure/pokemon/infrastructure/datomic/store.clj'
  ), 'utf8');
  for (const chave of ['chatId', 'playerId', 'saveId', 'fenceGeneration', 'operationId', 'collectionIndex', 'itemId', 'dados']) {
    assert.match(http, new RegExp(chave));
    assert.match(bridge, new RegExp(`"${chave}"`));
  }
  assert.match(http, /and \(\.-ok plano\) \(\.-consumir plano\)/);
  assert.match(http, /catalisador-evolutivo/);
  assert.match(store, /insufficient_item/);
  const evolve = store.slice(store.indexOf('(evolve-pokemon!'), store.indexOf('(evolve-pokemon!') + 2200);
  assert.match(evolve, /inventory-adjust-tx db sk item-id -1/);
  assert.doesNotMatch(evolve, /d\/transact/);
});
