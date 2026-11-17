'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { render } = require('../runtime/egg-cards.cjs');

const out = path.join(__dirname, '../previews');
fs.mkdirSync(out, { recursive: true });

const eggGoals = { comum: 100, raro: 250, epico: 500, especial: 800 };
const egg = (category, state = 'inventory', points = 0, goal = eggGoals[category]) => ({
  category,
  state,
  points,
  pointsGoal: goal,
  progressPct: goal > 0 ? Math.min(100, Math.round((points / goal) * 100)) : 0,
  timeGoalMs: 7200000,
  ...(state === 'incubating' ? { restanteMs: 90 * 60 * 1000 } : {})
});
const incubator = (kind, activeEggId = null, uses = null, bonusPoints = 0) => ({
  kind,
  incubatorId: kind,
  activeEggId,
  uses,
  bonusPoints,
  livre: !activeEggId
});

const cases = [
  ['preview-ovos-vazio.png', 'eggs', { eggs: [], inventarioCount: 0, limiteInventario: 9 }],
  ['preview-ovos-completo.png', 'eggs', {
    eggs: [
      egg('comum', 'incubating', 45, 100),
      egg('raro', 'inventory', 0, 250),
      egg('epico', 'ready-to-hatch', 500, 500),
      egg('comum'), egg('raro'), egg('epico'),
      egg('comum'), egg('raro'), egg('epico')
    ],
    inventarioCount: 9,
    limiteInventario: 9
  }],
  ['preview-ovos-especial.png', 'eggs', {
    eggs: [egg('especial', 'inventory', 0, 800), egg('especial', 'incubating', 320, 800)],
    inventarioCount: 2,
    limiteInventario: 9
  }],
  ['preview-ovos-parcial.png', 'eggs', {
    eggs: [egg('raro', 'incubating', 125, 250), egg('comum', 'incubating', 20, 100)],
    inventarioCount: 2,
    limiteInventario: 9
  }],
  ['preview-ovos-pronto.png', 'eggs', {
    eggs: [egg('epico', 'ready-to-hatch', 500, 500), egg('comum', 'inventory')],
    inventarioCount: 2,
    limiteInventario: 9
  }],
  ['preview-ovos-nascimento-pendente.png', 'eggs', {
    eggs: [egg('raro', 'delivery-pending', 250, 250)],
    inventarioCount: 1,
    limiteInventario: 9
  }],
  ['preview-ovos-inventario-cheio.png', 'eggs', {
    eggs: Array.from({ length: 9 }, (_, i) => egg(['comum', 'raro', 'epico'][i % 3])),
    inventarioCount: 9,
    limiteInventario: 9,
    pendingEggGrants: 2
  }],
  ['preview-incubadoras-vazias.png', 'incubators', {
    incubators: [
      incubator('permanente'),
      incubator('extra', null, 3),
      incubator('especial', null, 3, 25)
    ]
  }],
  ['preview-incubadoras-ocupadas.png', 'incubators', {
    eggs: [
      { eggId: 'egg-1', category: 'comum', state: 'incubating', points: 40, pointsGoal: 100 },
      { eggId: 'egg-2', category: 'incomum', state: 'incubating', points: 80, pointsGoal: 180 },
      { eggId: 'egg-3', category: 'raro', state: 'incubating', points: 100, pointsGoal: 250 },
      { eggId: 'egg-4', category: 'epico', state: 'incubating', points: 200, pointsGoal: 500 }
    ],
    incubators: [
      { ...incubator('permanente', 'egg-1'), rarity: 'comum' },
      { ...incubator('extra', 'egg-2', 2), rarity: 'incomum' },
      { ...incubator('especial', 'egg-3', 1, 25), rarity: 'raro' },
      { ...incubator('especial', 'egg-4', 1, 25), rarity: 'epico' }
    ]
  }],
  ['preview-incubadoras-bloqueadas.png', 'incubators', {
    eggs: [{ eggId: 'egg-1', category: 'comum', state: 'incubating', points: 10, pointsGoal: 100 }],
    incubators: [
      incubator('permanente', 'egg-1'),
      incubator('extra', 'egg-2', 0),
      incubator('especial', null, 0, 25)
    ]
  }],
  ['preview-incubadora-permanente.png', 'incubators', {
    incubators: [incubator('permanente')]
  }],
  ['preview-incubadora-especial.png', 'incubators', {
    eggs: [{ eggId: 'egg-esp', category: 'especial', state: 'incubating', points: 400, pointsGoal: 800 }],
    incubators: [{ ...incubator('especial', 'egg-esp', 2, 25), rarity: 'especial' }]
  }]
];

(async () => {
  for (const [name, kind, data] of cases) {
    const b = await render(kind, data);
    fs.writeFileSync(path.join(out, name), b);
    console.log(name, b.length);
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
