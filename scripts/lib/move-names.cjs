'use strict';

const fs = require('node:fs');
const path = require('node:path');

const nomes = JSON.parse(fs.readFileSync(path.join(__dirname, 'move-names-ptbr.json'), 'utf8'));

const AMBIGUOS = {
  furacao: ['drill-run', 'hurricane']
};

const ALIASES_EXPLICITOS = {
  'revolver-dagua': 'water-gun',
  'jato-de-agua': 'water-gun',
  'jato-dagua': 'water-gun',
  'chicote-de-vinha': 'vine-whip',
  'chicote-de-cipo': 'vine-whip',
  'trovoada-de-choques': 'thunder-shock',
  recolher: 'withdraw',
  gigaimpacto: 'giga-impact',
  'lanca-chamas': 'flamethrower',
  'rajada-de-fogo': 'fire-blast',
  relampago: 'thunderbolt',
  'raio-congelante': 'ice-beam',
  'deslize-de-pedras': 'rock-slide',
  'enrolar-defensivo': 'defense-curl',
  'plano-maligno': 'nasty-plot',
  descansar: 'rest',
  encarar: 'leer',
  mastigada: 'crunch',
  talho: 'slash',
  'pancada-corporal': 'body-slam',
  multiplicar: 'double-team',
  'garra-de-dragao': 'dragon-claw',
  'onda-de-choque': 'thunder-wave'
};

function semAcento(s) {
  return String(s || '')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/['’]/g, '');
}

function normalizarAcento(s) {
  return String(s || '')
    .toLowerCase()
    .trim()
    .replace(/[ _]+/g, '-')
    .replace(/[^\p{L}\p{N}'’-]/gu, '');
}

function normalizar(s) {
  return semAcento(normalizarAcento(s)).replace(/[^a-z0-9-]/g, '');
}

const aliases = new Map();
const aliasesAcento = new Map();
const colisoes = [];

function registrar(mapa, chave, slug) {
  if (!chave || nomes[chave] || AMBIGUOS[chave]) return;
  if (!mapa.has(chave)) {
    mapa.set(chave, slug);
    return;
  }
  if (mapa.get(chave) !== slug) {
    colisoes.push({ chave, a: mapa.get(chave), b: slug });
  }
}

for (const slug of Object.keys(nomes)) {
  registrar(aliases, normalizar(slug.replace(/-/g, ' ')), slug);
  registrar(aliases, normalizar(nomes[slug]), slug);
  registrar(aliasesAcento, normalizarAcento(nomes[slug]), slug);
}
for (const [chave, slug] of Object.entries(ALIASES_EXPLICITOS)) {
  aliases.set(normalizar(chave), slug);
}

for (const chave of Object.keys(AMBIGUOS)) aliases.delete(chave);

function resolver(nome) {
  const chaveAcento = normalizarAcento(nome);
  const chave = normalizar(nome);
  if (nomes[chave]) return { slug: chave };
  if (aliasesAcento.has(chaveAcento)) return { slug: aliasesAcento.get(chaveAcento) };
  if (AMBIGUOS[chave]) return { ambiguo: true, candidatos: AMBIGUOS[chave].slice() };
  if (aliases.has(chave)) return { slug: aliases.get(chave) };
  return { slug: chave, desconhecido: !nomes[chave] };
}

function nomePtBr(golpe) {
  if (!golpe) return '';
  if (typeof golpe === 'string') {
    const r = resolver(golpe);
    return r.slug && nomes[r.slug] ? nomes[r.slug] : String(golpe);
  }
  const slugBruto = golpe.slug || golpe['slug'];
  if (slugBruto && nomes[String(slugBruto)]) return nomes[String(slugBruto)];
  const bruto = golpe.nomeExibicao || golpe['nome-exibicao'] || golpe.nome || slugBruto;
  if (!bruto) return '';
  const r = resolver(bruto);
  if (r.slug && nomes[r.slug]) return nomes[r.slug];
  return String(bruto);
}

function cobertura() {
  const vazios = [];
  for (const [id, nome] of Object.entries(nomes)) {
    if (!id || !String(nome || '').trim()) vazios.push(id || nome);
  }
  return {
    total: Object.keys(nomes).length,
    traduzidos: Object.keys(nomes).length - vazios.length,
    vazios,
    colisoes
  };
}

module.exports = {
  nomes,
  AMBIGUOS,
  normalizar,
  resolver,
  nomePtBr,
  cobertura
};
