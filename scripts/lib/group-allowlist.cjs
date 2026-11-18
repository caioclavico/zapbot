'use strict';

/**
 * Allowlist multi-grupo A6 (Fase 11.1).
 * Fonte única para Node tests + zapbot.config (via js/require).
 *
 * Grupos: IDs literais completos `@g.us` (ALLOWED_GROUP_IDS + DEV_GROUP_ID).
 * Usuários: IDs literais exact-match (`@lid` / `@c.us` / etc.) — sem normalização ambígua.
 */

const GROUP_ID_RE = /^\d+@g\.us$/;

function splitCsv(raw) {
  if (raw == null || raw === '') return [];
  return String(raw)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

function isValidGroupId(id) {
  return typeof id === 'string' && GROUP_ID_RE.test(id);
}

/**
 * @param {{ allowedGroupIdsCsv?: string, devGroupId?: string|null }} opts
 * @returns {{ ok: true, groups: string[], groupSet: Set<string> }
 *   | { ok: false, error: string, invalid: string[], groups: string[] }}
 */
function buildAuthorizedGroups(opts = {}) {
  const fromList = splitCsv(opts.allowedGroupIdsCsv);
  const invalid = fromList.filter((id) => !isValidGroupId(id));
  const dev = opts.devGroupId != null && String(opts.devGroupId).trim() !== ''
    ? String(opts.devGroupId).trim()
    : null;

  if (dev != null && !isValidGroupId(dev)) {
    invalid.push(dev);
  }

  if (invalid.length > 0) {
    return {
      ok: false,
      error: 'invalid_group_id',
      invalid: [...new Set(invalid)],
      groups: []
    };
  }

  // Deduplica preservando a primeira ocorrência literal (ordem estável).
  const seen = new Set();
  const groups = [];
  for (const id of fromList) {
    if (!seen.has(id)) {
      seen.add(id);
      groups.push(id);
    }
  }
  if (dev != null && !seen.has(dev)) {
    seen.add(dev);
    groups.push(dev);
  }

  return { ok: true, groups, groupSet: seen };
}

/**
 * @param {{
 *   chatId: unknown,
 *   stagingLike: boolean,
 *   bootstrap?: boolean,
 *   allowAllGroups?: boolean,
 *   isGroup?: boolean,
 *   authorized: ReturnType<typeof buildAuthorizedGroups>
 * }} opts
 */
function chatAuthorized(opts) {
  const {
    chatId,
    stagingLike,
    bootstrap = false,
    allowAllGroups = false,
    isGroup,
    authorized
  } = opts;

  if (bootstrap) return false;
  if (typeof chatId !== 'string' || chatId === '') return false;

  // A flag de abertura é uma política group-only em qualquer APP_ENV.
  if (allowAllGroups) {
    if (chatId.endsWith('@g.us')) return true;
    if (chatId.endsWith('@c.us')) return false;
    return isGroup === true;
  }

  // Preserva o comportamento amplo legado de produção quando a flag está OFF.
  if (!stagingLike) return true;

  if (!authorized || authorized.ok === false) return false;

  // Sufixo canônico distingue grupos e conversas privadas sem metadados.
  if (chatId.endsWith('@g.us')) {
    return authorized.groupSet.has(chatId);
  }
  if (chatId.endsWith('@c.us')) return false;

  // @lid e sufixos desconhecidos não identificam o tipo de conversa.
  // Em staging-like, IDs ambíguos permanecem negados sem autorização explícita.
  return false;
}

/**
 * Autorização de usuário — separada da de grupo.
 * STRICT=1 + lista vazia → nega todos.
 * STRICT=0 + lista vazia → libera qualquer uid string (participantes do grupo).
 * Match: igualdade literal do JID completo apenas (sem strip de @lid/@c.us).
 *
 * @param {{
 *   userId: unknown,
 *   stagingLike: boolean,
 *   strictUserAllowlist?: boolean,
 *   allowedUsersCsv?: string
 * }} opts
 */
function userAuthorized(opts) {
  const {
    userId,
    stagingLike,
    strictUserAllowlist = false,
    allowedUsersCsv = ''
  } = opts;

  if (!stagingLike) return true;

  const allowed = splitCsv(allowedUsersCsv);
  const allowedSet = new Set(allowed);

  if (strictUserAllowlist && allowed.length === 0) return false;
  if (allowed.length === 0) {
    return typeof userId === 'string' && userId.length > 0;
  }
  if (typeof userId !== 'string' || userId.length === 0) return false;
  return allowedSet.has(userId);
}

/** Ambos os gates — entrada de comando / entrega proativa. */
function environmentAllows(opts) {
  return (
    chatAuthorized(opts) &&
    userAuthorized({
      userId: opts.userId,
      stagingLike: opts.stagingLike,
      strictUserAllowlist: opts.strictUserAllowlist,
      allowedUsersCsv: opts.allowedUsersCsv
    })
  );
}

module.exports = {
  GROUP_ID_RE,
  splitCsv,
  isValidGroupId,
  buildAuthorizedGroups,
  chatAuthorized,
  userAuthorized,
  environmentAllows
};
