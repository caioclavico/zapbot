'use strict';

const fs = require('fs');

const arquivo = require.resolve(
    'whatsapp-web.js/src/util/Injected/Utils.js',
);
const correcao = [
    '        // MediaData carries a private ID that must not replace the MsgKey.',
    '        delete message.__x_id;',
].join('\n');
const marcador =
    "        // Bot's won't reply if canonicalUrl is set (linking)";

let codigo = fs.readFileSync(arquivo, 'utf8');

if (process.argv.includes('--check')) {
    if (!codigo.includes('delete message.__x_id;')) {
        throw new Error(
            'Correção de mídia ausente no whatsapp-web.js instalado.',
        );
    }
    console.log('Correção de mídia do whatsapp-web.js verificada.');
    process.exit(0);
}

if (!codigo.includes('delete message.__x_id;')) {
    const ocorrencias = codigo.split(marcador).length - 1;
    if (ocorrencias !== 1) {
        throw new Error(
            `Não foi possível aplicar a correção de mídia: marcador encontrado ${ocorrencias} vez(es).`,
        );
    }

    codigo = codigo.replace(marcador, `${correcao}\n\n${marcador}`);
    fs.writeFileSync(arquivo, codigo);
    console.log('Correção de mídia aplicada ao whatsapp-web.js.');
} else {
    console.log('Correção de mídia do whatsapp-web.js já estava aplicada.');
}
