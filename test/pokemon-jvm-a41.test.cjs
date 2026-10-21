'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases clima/areas/mapa/eventos', () => {
  assert.equal(present.resolveCommand(['clima']), 'clima');
  assert.equal(present.resolveCommand(['areas']), 'clima');
  assert.equal(present.resolveCommand(['áreas']), 'clima');
  assert.equal(present.resolveCommand(['mapa']), 'clima');
  assert.equal(present.resolveCommand(['eventos']), 'eventos');
  assert.equal(present.resolveCommand(['evento']), 'eventos');
  assert.equal(present.resolveCommand(['evt']), 'eventos');
});

test('formatWorldInfo usa resumoTexto da JVM (paridade master)', () => {
  const texto = present.formatWorldInfo({
    resumoTexto: '☀️ *Clima de hoje: Ensolarado* — favorece fire, grass, ground.\n📍 *Bioma deste horário:* 🌳 floresta\n🕒 *Rotação de hoje:* 🌳 floresta • 🏖️ praia • 🪨 caverna\n🚫 *Sem acesso por horário hoje:* 🏙️ cidade • 🌊 lago • 🌋 vulcao',
    mundo: { areaPadrao: { id: 'floresta' } }
  }, '!pk');
  assert.match(texto, /\*Clima de hoje: Ensolarado\*/);
  assert.match(texto, /Bioma deste horário/);
  assert.match(texto, /!pk cacar/);
});

test('formatEventInfo espelha ver-evento', () => {
  const fim = Date.now() + 90 * 60 * 1000;
  const text = present.formatEventInfo({
    eventoAtual: {
      nome: 'Festival das Águas',
      especies: ['poliwag', 'shellder', 'staryu', 'eevee'],
      fim,
      chance: 50
    }
  }, '!pk');
  assert.match(text, /Festival das Águas/);
  assert.match(text, /poliwag/);
  assert.match(text, /50% das caçadas/);
  assert.match(text, /6 horas/);
  assert.match(text, /!pk cacar/);
});

test('AREA_UNAVAILABLE anexa resumo quando details trazem', () => {
  const text = present.formatError({
    code: 'AREA_UNAVAILABLE',
    details: { resumoTexto: '☀️ *Clima de hoje: Neve*' }
  });
  assert.match(text, /não está disponível/);
  assert.match(text, /\*Clima de hoje: Neve\*/);
  assert.match(text, /cacar/);
});
