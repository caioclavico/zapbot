/**
 * Prova offline dos gates de staging (piloto A6.4 fase 6).
 * Não conecta WhatsApp; espelha a lógica de zapbot.config.
 */
function envFlagOn(v) {
  return ["1", "true", "yes", "on"].includes(String(v ?? "false").trim().toLowerCase());
}
function envList(v) {
  return String(v ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function makeGates(env) {
  const appEnv = env.APP_ENV || "production";
  const stagingLike = ["development", "staging"].includes(appEnv);
  const allowAll = envFlagOn(env.ALLOW_ALL_GROUPS);
  const strict = envFlagOn(env.STAGING_STRICT_USER_ALLOWLIST);
  const bootstrap = appEnv === "staging" && envFlagOn(env.STAGING_BOOTSTRAP_GROUP);
  const devGroup = env.DEV_GROUP_ID || "";
  const allowed = new Set(envList(env.STAGING_ALLOWED_USERS));

  function chatAutorizado(cid) {
    if (bootstrap) return false;
    if (!stagingLike) return true;
    if (allowAll) {
      return typeof cid === "string" && (cid.endsWith("@g.us") || cid.endsWith("@c.us"));
    }
    return typeof cid === "string" && !!devGroup && cid === devGroup;
  }

  function usuarioAutorizado(uid) {
    if (!stagingLike) return true;
    if (strict && allowed.size === 0) return false;
    if (allowed.size === 0) return true;
    if (typeof uid !== "string") return false;
    const local = uid.split("@")[0];
    if (allowed.has(uid) || allowed.has(local)) return true;
    for (const a of allowed) {
      if (a === uid || a === local || a.split("@")[0] === local) return true;
    }
    return false;
  }

  return { chatAutorizado, usuarioAutorizado };
}

const recoveredChat = "120363427684548487@g.us";
const otherChat = "120363431663917673@g.us";
const pilot = "265377456066703@lid";
const otherUser = "5511999999999@c.us";
const dm = "5511888888888@c.us";

const pilotEnv = {
  APP_ENV: "staging",
  ALLOW_ALL_GROUPS: "false",
  DEV_GROUP_ID: recoveredChat,
  STAGING_STRICT_USER_ALLOWLIST: "1",
  STAGING_ALLOWED_USERS: pilot,
  STAGING_BOOTSTRAP_GROUP: "0",
};

const g = makeGates(pilotEnv);
const cases = [
  ["pilot_ok", g.chatAutorizado(recoveredChat) && g.usuarioAutorizado(pilot), true],
  ["pilot_lid_prefix", g.usuarioAutorizado("265377456066703"), true],
  ["other_user_same_chat", g.chatAutorizado(recoveredChat) && g.usuarioAutorizado(otherUser), false],
  ["other_group", g.chatAutorizado(otherChat), false],
  ["dm_blocked", g.chatAutorizado(dm), false],
  ["admin_other_group", g.chatAutorizado(otherChat) && g.usuarioAutorizado(pilot), false],
  ["empty_allowlist_strict", makeGates({ ...pilotEnv, STAGING_ALLOWED_USERS: "" }).usuarioAutorizado(pilot), false],
  ["allow_all_still_needs_user", (() => {
    const x = makeGates({ ...pilotEnv, ALLOW_ALL_GROUPS: "true" });
    return x.chatAutorizado(otherChat) && !x.usuarioAutorizado(otherUser);
  })(), true],
];

let fail = 0;
for (const [name, got, want] of cases) {
  const ok = got === want;
  console.log(`${ok ? "PASS" : "FAIL"} ${name} got=${got} want=${want}`);
  if (!ok) fail++;
}
if (fail) {
  console.error(`GATE_PROOF_FAILED ${fail}`);
  process.exit(1);
}
console.log("GATE_PROOF_OK");
