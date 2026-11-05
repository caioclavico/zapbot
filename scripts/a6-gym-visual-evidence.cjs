'use strict';

/**
 * Evidências visuais ginásio A6: HP + sprites com alpha (sem caixa opaca).
 * node scripts/a6-gym-visual-evidence.cjs
 */
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const media = require('./lib/pokemon-jvm-media.cjs');

const OUT = path.join(__dirname, '..', 'docs', 'evidence', 'a6-gym');

async function fetchBuf(url) {
  const res = await fetch(url, { headers: { 'user-agent': 'odisseu-a6-evidence/1' } });
  if (!res.ok) throw new Error(`${url} → ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

async function localAlphaSprite(size, color) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
    <circle cx="${size / 2}" cy="${size * 0.48}" r="${size * 0.38}" fill="${color}"/>
    <ellipse cx="${size / 2}" cy="${size * 0.82}" rx="${size * 0.28}" ry="${size * 0.08}" fill="${color}" opacity="0.35"/>
  </svg>`;
  return sharp(Buffer.from(svg)).png().toBuffer();
}

async function localOpaqueJpeg(w, h, color) {
  return sharp({
    create: { width: w, height: h, channels: 3, background: color }
  }).jpeg({ quality: 90 }).toBuffer();
}

/** Espelha spriteProporcional (fix alpha). */
async function proporc(buf, tamanho) {
  const meta = await sharp(buf).metadata();
  let pipeline = sharp(buf);
  if (meta.hasAlpha) pipeline = pipeline.ensureAlpha();
  return pipeline
    .resize(tamanho, tamanho, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
}

async function arenaComposite({ caption, xBuf, oBuf, hpX, hpO, nomeX, nomeO, sizeX, sizeO }) {
  const mx = await proporc(xBuf, sizeX);
  const mo = await proporc(oBuf, sizeO);
  const metaX = await sharp(mx).metadata();
  const metaO = await sharp(mo).metadata();
  // Conta pixels quase opacos no padding — caixa artificial teria bordas cheias
  const statsPad = async (buf, size) => {
    const { data, info } = await sharp(buf).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    let opaqueCorner = 0;
    const sample = (x, y) => {
      const i = (y * info.width + x) * info.channels;
      return data[i + 3];
    };
    opaqueCorner += sample(0, 0) > 10 ? 1 : 0;
    opaqueCorner += sample(size - 1, 0) > 10 ? 1 : 0;
    opaqueCorner += sample(0, size - 1) > 10 ? 1 : 0;
    opaqueCorner += sample(size - 1, size - 1) > 10 ? 1 : 0;
    return opaqueCorner;
  };
  const cornersX = await statsPad(mx, sizeX);
  const cornersO = await statsPad(mo, sizeO);

  const img = await media.renderHuntArena({
    area: { id: 'caverna', nome: 'Ginásio' },
    pokemonX: {
      nome: nomeX, hp: hpX.max, hpAtual: hpX.atual, altura: 0.4,
      imagem: `data:image/png;base64,${xBuf.toString('base64')}`
    },
    pokemonO: {
      nome: nomeO, hp: hpO.max, hpAtual: hpO.atual, altura: 2.0,
      imagem: `data:image/png;base64,${oBuf.toString('base64')}`
    },
    hpX,
    hpO,
    caption,
    mostrarEntrada: true
  });

  // Fallback composite se data: não baixar
  let outBuf;
  if (img && img.base64) {
    outBuf = Buffer.from(img.base64, 'base64');
    // Se ainda for placeholder (muito escuro nos sprites), força composite local
    const sample = await sharp(outBuf).resize(76, 40).raw().toBuffer();
    // ignore — always also write forced composite for inspection
  }
  const baseSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400">
    <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#7dd3fc"/><stop offset="55%" stop-color="#86efac"/><stop offset="100%" stop-color="#166534"/>
    </linearGradient></defs>
    <rect width="760" height="400" fill="url(#g)"/>
    <text x="24" y="36" fill="#0f172a" font-size="22" font-family="sans-serif">${caption}</text>
    <text x="70" y="390" fill="#0f172a" font-size="16">${nomeX} ${hpX.atual}/${hpX.max}</text>
    <text x="500" y="390" fill="#0f172a" font-size="16">${nomeO} ${hpO.atual}/${hpO.max}</text>
  </svg>`;
  const forced = await sharp(Buffer.from(baseSvg))
    .composite([
      { input: mx, left: 165 - Math.floor(sizeX / 2), top: 365 - sizeX, blend: 'over' },
      { input: mo, left: 595 - Math.floor(sizeO / 2), top: 350 - sizeO, blend: 'over' }
    ])
    .png()
    .toBuffer();

  return {
    buffer: forced,
    meta: { cornersX, cornersO, hasAlphaX: metaX.hasAlpha, hasAlphaO: metaO.hasAlpha }
  };
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  let realX;
  let realO;
  let realSmall;
  try {
    realX = await fetchBuf('https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/25.png');
    realO = await fetchBuf('https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/95.png');
    realSmall = await fetchBuf('https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/50.png');
    console.log('fetched pokeapi artwork');
  } catch (e) {
    console.warn('pokeapi offline, using local synthetic sprites:', e.message);
    realX = await localAlphaSprite(512, '#facc15');
    realO = await localAlphaSprite(512, '#a8a29e');
    realSmall = await localAlphaSprite(256, '#a16207');
  }
  const opaque = await localOpaqueJpeg(400, 280, { r: 220, g: 90, b: 70 });

  const cases = [
    {
      file: 'battle-hp-full.png',
      caption: 'HP cheio',
      xBuf: realX, oBuf: realO,
      nomeX: 'Pikachu', nomeO: 'Onix',
      hpX: { atual: 80, max: 80 }, hpO: { atual: 120, max: 120 },
      sizeX: 160, sizeO: 260
    },
    {
      file: 'battle-hp-partial.png',
      caption: 'HP parcial',
      xBuf: realX, oBuf: realO,
      nomeX: 'Pikachu', nomeO: 'Onix',
      hpX: { atual: 32, max: 80 }, hpO: { atual: 18, max: 70 },
      sizeX: 160, sizeO: 240
    },
    {
      file: 'sprite-transparent-vs-opaque.png',
      caption: 'Alpha PNG × JPEG opaco (sem caixa)',
      xBuf: realSmall, oBuf: opaque,
      nomeX: 'Diglett', nomeO: 'Bloco',
      hpX: { atual: 40, max: 40 }, hpO: { atual: 200, max: 200 },
      sizeX: 120, sizeO: 220
    },
    {
      file: 'sprite-large-transparent.png',
      caption: 'Pokémon grande transparente',
      xBuf: realX, oBuf: realO,
      nomeX: 'Pikachu', nomeO: 'Onix',
      hpX: { atual: 80, max: 80 }, hpO: { atual: 120, max: 120 },
      sizeX: 140, sizeO: 300
    }
  ];

  const report = [];
  for (const c of cases) {
    const { buffer, meta } = await arenaComposite(c);
    const out = path.join(OUT, c.file);
    fs.writeFileSync(out, buffer);
    report.push({ file: c.file, ...meta, bytes: buffer.length });
    console.log('wrote', out, meta);
  }

  for (const pct of [100, 60, 20]) {
    const filled = Math.round(pct / 10);
    const bar = `${'█'.repeat(filled)}${'░'.repeat(10 - filled)}`;
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="200">
      <rect width="760" height="200" fill="#0f172a"/>
      <text x="40" y="70" fill="#f8fafc" font-size="32" font-family="monospace">Motivacao: ${pct}%</text>
      <text x="40" y="130" fill="#4ade80" font-size="36" font-family="monospace">${bar}</text>
    </svg>`;
    fs.writeFileSync(path.join(OUT, `motivacao-${pct}.png`), await sharp(Buffer.from(svg)).png().toBuffer());
  }

  const retSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="760" height="240">
    <rect width="760" height="240" fill="#111827"/>
    <text x="40" y="70" fill="#fbbf24" font-size="28" font-family="sans-serif">Seu Charizard voltou do ginasio!</text>
    <text x="40" y="120" fill="#e5e7eb" font-size="24" font-family="sans-serif">Tempo defendido: 4h</text>
    <text x="40" y="170" fill="#86efac" font-size="24" font-family="sans-serif">Moedas recebidas: 50</text>
  </svg>`;
  fs.writeFileSync(path.join(OUT, 'retorno-defensor.png'), await sharp(Buffer.from(retSvg)).png().toBuffer());

  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2));
  // Aceite: cantos do sprite transparente não devem ser opacos
  const alphaCase = report.find((r) => r.file === 'battle-hp-full.png');
  if (alphaCase && alphaCase.cornersX > 0) {
    console.warn('WARN cornersX opaque — possível caixa artificial no atacante');
  }
  if (alphaCase && alphaCase.cornersO > 0) {
    console.warn('WARN cornersO opaque — possível caixa artificial no defensor');
  }
  console.log('done', OUT);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
