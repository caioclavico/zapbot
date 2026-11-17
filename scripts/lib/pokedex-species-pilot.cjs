'use strict';

/**
 * Gate de homologação da Pokédex de espécie (top-level !pokedex/!dex/!pdx).
 * !pk pokedex (coleção) não usa este módulo.
 */

function pilotAuthorized(chatId, { pilotOnly = true, pilotChatId = '' } = {}) {
  if (!pilotOnly) return true;
  const cid = String(chatId || '');
  const pilot = String(pilotChatId || '');
  return Boolean(cid && pilot && cid === pilot);
}

function foraDoPilotoMessage(prefix = '!') {
  const p = String(prefix || '!');
  return `📖 A Pokédex de espécie (${p}pokedex / ${p}dex / ${p}pdx) está em homologação `
    + `apenas no grupo piloto. A coleção pessoal (${p}pk pokedex) continua disponível normalmente.`;
}

module.exports = { pilotAuthorized, foraDoPilotoMessage };
