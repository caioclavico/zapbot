'use strict';

/**
 * Gera PNGs 1080×1080 dos lembretes de raid automática (Fase 12.7.1).
 * Arte local — sem rede. Contagem regressiva nos pixels da imagem.
 *
 *   node scripts/generate-raid-egg-reminders.cjs
 */

const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const W = 1080;
const H = 1080;
const OUT_DIRS = [
  path.join(__dirname, '..', 'assets'),
  path.join(__dirname, '..', 'pokemon-service', 'assets')
];

function eggSvg() {
  return `
  <g transform="translate(540,620)">
    <ellipse cx="0" cy="150" rx="200" ry="48" fill="#00000066"/>
    <defs>
      <radialGradient id="aura" cx="50%" cy="45%" r="55%">
        <stop offset="0%" stop-color="#fbbf24aa"/>
        <stop offset="55%" stop-color="#a855f744"/>
        <stop offset="100%" stop-color="#00000000"/>
      </radialGradient>
      <linearGradient id="eggBody" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#fff7ed"/>
        <stop offset="45%" stop-color="#fdba74"/>
        <stop offset="100%" stop-color="#9a3412"/>
      </linearGradient>
      <linearGradient id="eggShine" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#ffffffcc"/>
        <stop offset="100%" stop-color="#ffffff00"/>
      </linearGradient>
    </defs>
    <circle cx="0" cy="0" r="280" fill="url(#aura)"/>
    <path d="M0,-260 C170,-260 260,-70 260,70 C260,210 145,290 0,290 C-145,290 -260,210 -260,70 C-260,-70 -170,-260 0,-260 Z"
          fill="url(#eggBody)" stroke="#7c2d12" stroke-width="10"/>
    <ellipse cx="-70" cy="-70" rx="70" ry="100" fill="url(#eggShine)" transform="rotate(-18)"/>
    <circle cx="-95" cy="20" r="28" fill="#7c2d12" opacity="0.55"/>
    <circle cx="85" cy="-35" r="38" fill="#7c2d12" opacity="0.45"/>
    <circle cx="35" cy="95" r="24" fill="#7c2d12" opacity="0.5"/>
    <circle cx="-50" cy="120" r="18" fill="#7c2d12" opacity="0.4"/>
    <path d="M-160,40 Q-30,-20 70,55" fill="none" stroke="#7c2d12" stroke-width="8" opacity="0.35"/>
  </g>`;
}

function gymBackdrop() {
  return `
  <defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#0f0a2a"/>
      <stop offset="40%" stop-color="#1e1b4b"/>
      <stop offset="100%" stop-color="#020617"/>
    </linearGradient>
    <linearGradient id="plat" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#fbbf24"/>
      <stop offset="100%" stop-color="#b45309"/>
    </linearGradient>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#sky)"/>
  <circle cx="120" cy="180" r="3" fill="#fde68a"/><circle cx="980" cy="220" r="2.5" fill="#fde68a"/>
  <circle cx="200" cy="320" r="2" fill="#e0e7ff"/><circle cx="860" cy="160" r="3" fill="#e0e7ff"/>
  <circle cx="960" cy="400" r="2" fill="#fde68a"/><circle cx="80" cy="450" r="2.5" fill="#e0e7ff"/>
  <rect x="140" y="420" width="70" height="380" rx="12" fill="#1e293b" stroke="#94a3b8" stroke-width="5"/>
  <rect x="870" y="420" width="70" height="380" rx="12" fill="#1e293b" stroke="#94a3b8" stroke-width="5"/>
  <path d="M110 420 L540 200 L970 420" fill="none" stroke="#fbbf24" stroke-width="16" stroke-linejoin="round"/>
  <path d="M110 420 L540 200 L970 420" fill="none" stroke="#fef3c7" stroke-width="5" stroke-linejoin="round" opacity="0.75"/>
  <ellipse cx="540" cy="920" rx="360" ry="55" fill="url(#plat)" stroke="#78350f" stroke-width="6"/>
  <ellipse cx="540" cy="908" rx="300" ry="32" fill="#fde68a" opacity="0.5"/>
  `;
}

async function build(minutes, outDir) {
  const label = `FALTAM ${minutes} MINUTOS`;
  const svg = Buffer.from(`<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
  ${gymBackdrop()}
  <rect x="60" y="40" width="960" height="200" rx="24" fill="#020617cc"/>
  <text x="540" y="100" text-anchor="middle" font-family="Arial Black, Helvetica, sans-serif"
        font-size="42" fill="#fde68a" letter-spacing="6">RAID MISTERIOSA</text>
  <text x="540" y="175" text-anchor="middle" font-family="Arial Black, Helvetica, sans-serif"
        font-size="58" font-weight="700" fill="#f8fafc" stroke="#0f172a" stroke-width="3"
        letter-spacing="2">${label}</text>
  ${eggSvg()}
  <text x="540" y="1020" text-anchor="middle" font-family="Arial Black, Helvetica, sans-serif"
        font-size="36" fill="#fde68a" letter-spacing="3">PREPARE SEUS POKÉMON!</text>
</svg>`);
  const buf = await sharp(svg).png({ compressionLevel: 9 }).toBuffer();
  fs.mkdirSync(outDir, { recursive: true });
  const file = path.join(outDir, `raid-egg-${minutes}min.png`);
  fs.writeFileSync(file, buf);
  return { file, bytes: buf.length };
}

async function main() {
  for (const dir of OUT_DIRS) {
    if (!fs.existsSync(path.dirname(dir)) && dir.includes('pokemon-service')) continue;
    for (const m of [30, 15, 5]) {
      const r = await build(m, dir);
      console.log(r.file, r.bytes, 'bytes');
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
