'use strict';

function idempotencyKey({ version, contentHash, chatId }) {
  return `${version}:${contentHash}:${chatId}`;
}

function ready(gates = {}) {
  return gates.jvmHealthy === true && gates.nodeHealthy === true &&
    gates.whatsappReady === true && gates.datomicAccessible === true &&
    gates.versionConfirmed === true && gates.rollbackComplete === true;
}

function render(release) {
  const highlights = (release.highlights || []).slice(0, 5)
    .map(item => `• ${item}`).join('\n');
  return `🚀 *ODISSEU POKÉMON ATUALIZADO!*\n\n📦 *Versão ${release.version}*\n\n✨ *PRINCIPAIS NOVIDADES*\n${highlights}\n\n📖 Consulte: \u0060!pk ajuda\u0060\n🆕 Novidades: \u0060!pk novidades\u0060`;
}

module.exports = { idempotencyKey, ready, render };
