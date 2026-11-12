#!/usr/bin/env node
'use strict';

/**
 * Regenera o catálogo versionado de MT (learnset level-up ofensivo).
 * Fonte: PokéAPI. Saídas:
 *  - modules/pokemon/domain/pokemon/domain/data/mt_learnset_v1.edn (JVM)
 *  - scripts/lib/data/mt-learnset-v1.json (Node)
 *
 * Uso: node scripts/generate-mt-learnset.cjs
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const VERSION = 'mt-learnset-v1-2026-11';

async function fetchJson(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
  return res.json();
}

function nivelMin(move) {
  const levels = (move.version_group_details || [])
    .filter((d) => d.move_learn_method && d.move_learn_method.name === 'level-up')
    .map((d) => d.level_learned_at)
    .filter((n) => n != null)
    .sort((a, b) => a - b);
  return levels.length ? levels[0] : null;
}

async function carregarGolpe(slug) {
  const d = await fetchJson(`https://pokeapi.co/api/v2/move/${slug}`);
  const classe = d.damage_class && d.damage_class.name;
  if (!(classe === 'physical' || classe === 'special') || !d.power) return null;
  const nome = (d.names || []).find((n) => n.language && n.language.name === 'pt-BR')?.name
    || slug.split('-').map((p) => p[0].toUpperCase() + p.slice(1)).join(' ');
  return {
    slug,
    'nome-exibicao': nome,
    tipo: d.type && d.type.name,
    poder: d.power,
    classe: classe === 'physical' ? 'fisico' : 'especial'
  };
}

async function speciesLearnset(idOrName) {
  const data = await fetchJson(`https://pokeapi.co/api/v2/pokemon/${idOrName}`);
  const by = new Map();
  for (const m of data.moves || []) {
    const n = nivelMin(m);
    const slug = m.move && m.move.name;
    if (n == null || !slug) continue;
    const prev = by.get(slug);
    if (!prev || n < prev['nivel-min']) by.set(slug, { slug, 'nivel-min': n });
  }
  return {
    species: data.name,
    entries: [...by.values()].sort((a, b) => a['nivel-min'] - b['nivel-min'] || a.slug.localeCompare(b.slug))
  };
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function toEdn(v) {
  if (v === null || v === undefined) return 'nil';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'string') return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(toEdn).join(' ')}]`;
  if (typeof v === 'object') {
    return `{${Object.entries(v).map(([k, val]) => `${JSON.stringify(k)} ${toEdn(val)}`).join(' ')}}`;
  }
  return JSON.stringify(String(v));
}

async function main() {
  const ids = [];
  for (let i = 1; i <= 151; i++) ids.push(i);
  for (const extra of [152, 155, 158, 252, 255, 258, 387, 390, 393]) ids.push(extra);

  const species = {};
  const moveSlugs = new Set();
  for (const id of ids) {
    process.stderr.write(`species ${id}\n`);
    let attempt = 0;
    for (;;) {
      try {
        const { species: name, entries } = await speciesLearnset(id);
        species[name] = entries;
        for (const e of entries) moveSlugs.add(e.slug);
        break;
      } catch (e) {
        attempt += 1;
        if (attempt >= 4) throw e;
        await sleep(500 * attempt);
      }
    }
    await sleep(40);
  }

  const moves = {};
  const slugs = [...moveSlugs].sort();
  for (let i = 0; i < slugs.length; i++) {
    const slug = slugs[i];
    process.stderr.write(`move ${i + 1}/${slugs.length} ${slug}\n`);
    let attempt = 0;
    for (;;) {
      try {
        const g = await carregarGolpe(slug);
        if (g) moves[slug] = g;
        break;
      } catch (e) {
        attempt += 1;
        if (attempt >= 4) {
          process.stderr.write(`skip ${slug}: ${e.message}\n`);
          break;
        }
        await sleep(400 * attempt);
      }
    }
    await sleep(25);
  }

  for (const [sp, entries] of Object.entries(species)) {
    species[sp] = entries.filter((e) => moves[e.slug]);
  }

  const catalog = {
    version: VERSION,
    source: 'pokeapi.co level-up (gen1 + selected starters); offensive fisico/especial only',
    generatedAt: new Date().toISOString(),
    moves,
    species
  };

  const jsonPath = path.join(__dirname, 'lib/mt-learnset-v1.json');
  const ednPath = path.join(
    __dirname,
    '../modules/pokemon/domain/pokemon/domain/data/mt_learnset_v1.edn'
  );
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
  fs.writeFileSync(jsonPath, JSON.stringify(catalog));
  fs.writeFileSync(ednPath, `${toEdn(catalog)}\n`);

  console.log(JSON.stringify({
    version: catalog.version,
    species: Object.keys(species).length,
    moves: Object.keys(moves).length,
    hash: crypto.createHash('sha256').update(JSON.stringify(catalog)).digest('hex').slice(0, 16),
    jsonPath,
    ednPath
  }, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
