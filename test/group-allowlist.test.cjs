'use strict';

const {describe, it} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const al = require('../scripts/lib/group-allowlist.cjs');

const PILOTO = '120363427684548487@g.us';
const ANTIGO = '120363431663917673@g.us';
const OUTRO = '120363999999999999@g.us';
const LID = '207700373848240@lid';
const LID2 = '999999999999999@lid';
const CUS = '5511999999999@c.us';

describe('group-allowlist — parse / validação', () => {
  it('aceita @g.us literais e deduplica sem alterar IDs', () => {
    const r = al.buildAuthorizedGroups({
      allowedGroupIdsCsv: `${PILOTO}, ${PILOTO}, ${OUTRO}`,
      devGroupId: PILOTO
    });
    assert.equal(r.ok, true);
    assert.deepEqual(r.groups, [PILOTO, OUTRO]);
  });

  it('une DEV_GROUP_ID quando ausente da CSV', () => {
    const r = al.buildAuthorizedGroups({
      allowedGroupIdsCsv: OUTRO,
      devGroupId: PILOTO
    });
    assert.equal(r.ok, true);
    assert.deepEqual(r.groups, [OUTRO, PILOTO]);
  });

  it('falha segura com ID inválido (hash, parcial, @lid como grupo)', () => {
    for (const bad of ['7f683fafa410', '120363@g.usx', LID, 'not-a-group', '@g.us']) {
      const r = al.buildAuthorizedGroups({allowedGroupIdsCsv: bad});
      assert.equal(r.ok, false, bad);
      assert.equal(r.error, 'invalid_group_id');
    }
  });

  it('DEV_GROUP_ID inválido também falha', () => {
    const r = al.buildAuthorizedGroups({
      allowedGroupIdsCsv: PILOTO,
      devGroupId: 'grupo-invalido'
    });
    assert.equal(r.ok, false);
  });

  it('CSV vazia + DEV válido → só piloto', () => {
    const r = al.buildAuthorizedGroups({
      allowedGroupIdsCsv: '',
      devGroupId: PILOTO
    });
    assert.equal(r.ok, true);
    assert.deepEqual(r.groups, [PILOTO]);
  });

  it('CSV e DEV vazios → ok com lista vazia (deny-all via chatAuthorized)', () => {
    const r = al.buildAuthorizedGroups({});
    assert.equal(r.ok, true);
    assert.deepEqual(r.groups, []);
  });
});

describe('group-allowlist — chatAuthorized', () => {
  const auth = al.buildAuthorizedGroups({
    allowedGroupIdsCsv: PILOTO,
    devGroupId: PILOTO
  });

  it('grupo permitido', () => {
    assert.equal(
      al.chatAuthorized({
        chatId: PILOTO,
        stagingLike: true,
        authorized: auth
      }),
      true
    );
  });

  it('grupo desconhecido / antigo não confirmado', () => {
    assert.equal(
      al.chatAuthorized({chatId: ANTIGO, stagingLike: true, authorized: auth}),
      false
    );
    assert.equal(
      al.chatAuthorized({chatId: OUTRO, stagingLike: true, authorized: auth}),
      false
    );
  });

  it('dois grupos autorizados — comandos simultâneos independentes', () => {
    const multi = al.buildAuthorizedGroups({
      allowedGroupIdsCsv: `${PILOTO},${OUTRO}`
    });
    assert.equal(
      al.chatAuthorized({chatId: PILOTO, stagingLike: true, authorized: multi}),
      true
    );
    assert.equal(
      al.chatAuthorized({chatId: OUTRO, stagingLike: true, authorized: multi}),
      true
    );
    assert.equal(
      al.chatAuthorized({chatId: ANTIGO, stagingLike: true, authorized: multi}),
      false
    );
  });

  it('config inválida → nega todos', () => {
    const bad = al.buildAuthorizedGroups({allowedGroupIdsCsv: 'x'});
    assert.equal(
      al.chatAuthorized({chatId: PILOTO, stagingLike: true, authorized: bad}),
      false
    );
  });

  it('bootstrap nega mesmo com piloto', () => {
    assert.equal(
      al.chatAuthorized({
        chatId: PILOTO,
        stagingLike: true,
        bootstrap: true,
        authorized: auth
      }),
      false
    );
  });

  it('ALLOW_ALL_GROUPS=true aceita @g.us/@c.us (não @lid solto)', () => {
    assert.equal(
      al.chatAuthorized({
        chatId: ANTIGO,
        stagingLike: true,
        allowAllGroups: true,
        authorized: auth
      }),
      true
    );
    assert.equal(
      al.chatAuthorized({
        chatId: CUS,
        stagingLike: true,
        allowAllGroups: true,
        authorized: auth
      }),
      true
    );
    assert.equal(
      al.chatAuthorized({
        chatId: LID,
        stagingLike: true,
        allowAllGroups: true,
        authorized: auth
      }),
      false
    );
  });

  it('fora de staging-like libera (produção)', () => {
    assert.equal(
      al.chatAuthorized({
        chatId: ANTIGO,
        stagingLike: false,
        authorized: auth
      }),
      true
    );
  });
});

describe('group-allowlist — userAuthorized (separado do grupo)', () => {
  it('STRICT=1 + lista: usuário permitido', () => {
    assert.equal(
      al.userAuthorized({
        userId: LID,
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: `${LID},${CUS}`
      }),
      true
    );
  });

  it('STRICT=1 + lista: usuário não cadastrado', () => {
    assert.equal(
      al.userAuthorized({
        userId: LID2,
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: LID
      }),
      false
    );
  });

  it('STRICT=1 + lista vazia → nega todos', () => {
    assert.equal(
      al.userAuthorized({
        userId: LID,
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: ''
      }),
      false
    );
  });

  it('STRICT=0 + lista vazia → participantes do grupo (qualquer uid string)', () => {
    assert.equal(
      al.userAuthorized({
        userId: LID2,
        stagingLike: true,
        strictUserAllowlist: false,
        allowedUsersCsv: ''
      }),
      true
    );
  });

  it('não normaliza ambiguamente (@lid ≠ local-part solto)', () => {
    assert.equal(
      al.userAuthorized({
        userId: '207700373848240',
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: LID
      }),
      false
    );
    assert.equal(
      al.userAuthorized({
        userId: LID,
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: '207700373848240'
      }),
      false
    );
  });

  it('IDs @lid e @c.us literais', () => {
    assert.equal(
      al.userAuthorized({
        userId: CUS,
        stagingLike: true,
        strictUserAllowlist: true,
        allowedUsersCsv: `${LID},${CUS}`
      }),
      true
    );
  });
});

describe('group-allowlist — environmentAllows (comando / outbox / raid notify)', () => {
  const auth = al.buildAuthorizedGroups({allowedGroupIdsCsv: PILOTO});

  it('grupo+usuário OK', () => {
    assert.equal(
      al.environmentAllows({
        chatId: PILOTO,
        userId: LID,
        stagingLike: true,
        authorized: auth,
        strictUserAllowlist: true,
        allowedUsersCsv: LID
      }),
      true
    );
  });

  it('grupo OK + usuário negado (STRICT)', () => {
    assert.equal(
      al.environmentAllows({
        chatId: PILOTO,
        userId: LID2,
        stagingLike: true,
        authorized: auth,
        strictUserAllowlist: true,
        allowedUsersCsv: LID
      }),
      false
    );
  });

  it('grupo desconhecido + usuário OK', () => {
    assert.equal(
      al.environmentAllows({
        chatId: ANTIGO,
        userId: LID,
        stagingLike: true,
        authorized: auth,
        strictUserAllowlist: false,
        allowedUsersCsv: ''
      }),
      false
    );
  });

  it('STRICT=0: participante em grupo autorizado (outbox/raid delivery)', () => {
    assert.equal(
      al.environmentAllows({
        chatId: PILOTO,
        userId: LID2,
        stagingLike: true,
        authorized: auth,
        strictUserAllowlist: false,
        allowedUsersCsv: ''
      }),
      true
    );
  });
});

describe('group-allowlist — wiring no config.cljs', () => {
  it('config referencia ALLOWED_GROUP_IDS e lib compartilhada', () => {
    const cfg = fs.readFileSync(
      path.join(__dirname, '../src/zapbot/config.cljs'),
      'utf8'
    );
    assert.match(cfg, /ALLOWED_GROUP_IDS/);
    assert.match(cfg, /group-allowlist\.cjs/);
    assert.match(cfg, /STRICT_USER_ALLOWLIST/);
    assert.match(cfg, /chatAuthorized/);
    assert.match(cfg, /userAuthorized/);
  });

  it('outbox/raid delivery e admin notify usam chat-autorizado?', () => {
    const http = fs.readFileSync(
      path.join(__dirname, '../src/zapbot/pokemon_http.cljs'),
      'utf8'
    );
    const core = fs.readFileSync(
      path.join(__dirname, '../src/zapbot/core.cljs'),
      'utf8'
    );
    assert.match(http, /config\/chat-autorizado\? \(:chatId evento\)/);
    assert.match(http, /config\/usuario-autorizado\?/);
    assert.match(core, /config\/chat-autorizado\? \(\.-chatId notification\)/);
    assert.match(core, /permitido-pelo-ambiente\?/);
  });
});
