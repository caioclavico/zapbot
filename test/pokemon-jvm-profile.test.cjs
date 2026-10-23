'use strict';
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

assert.equal(present.resolveCommand(['professor']), 'professor');
assert.equal(present.resolveCommand(['titulo']), 'titulo');
assert.equal(present.resolveCommand(['amizade']), 'amizade');
assert.match(present.formatFriendship({ value: 220, label: 'melhor amigo', species: 'Pikachu' }), /220\/255/);
assert.match(present.formatTitles({ titles: [{ id: 'campeao', label: 'Campeão' }] }), /Campeão/);
assert.match(present.formatProfessor({ token: '123' }), /123/);
console.log('pokemon-jvm-profile.test.cjs: ok');
