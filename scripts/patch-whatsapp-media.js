'use strict';

const fs = require('fs');

function run() {
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
        const clientPath = require.resolve('whatsapp-web.js/src/Client.js');
        patchClientResources(fs.readFileSync(clientPath, 'utf8'), true);
        console.log('Correções de mídia e recursos do whatsapp-web.js verificadas.');
        return;
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

    const clientPath = require.resolve('whatsapp-web.js/src/Client.js');
    const before = fs.readFileSync(clientPath, 'utf8');
    const after = patchClientResources(before);
    if (after !== before) fs.writeFileSync(clientPath, after);
    console.log('Registro antecipado de recursos do whatsapp-web.js verificado.');
}

function patchClientResources(source, check = false) {
    const blocks = [
        [
            '            browser = await puppeteer.connect(puppeteerOpts);\n            page = await browser.newPage();',
            '            browser = await puppeteer.connect(puppeteerOpts);\n' +
            '            this.pupBrowser = browser; // zapbot: early connected browser\n' +
            '            page = await browser.newPage();\n' +
            '            this.pupPage = page; // zapbot: early connected page',
        ],
        [
            '            page = (await browser.pages())[0];',
            '            this.pupBrowser = browser; // zapbot: early launched browser\n' +
            '            page = (await browser.pages())[0];\n' +
            '            this.pupPage = page; // zapbot: early launched page',
        ],
    ];
    for (const [original, patched] of blocks) {
        if (source.split(patched).length === 2) continue;
        if (check || source.split(original).length !== 2) {
            throw new Error('Pinned whatsapp-web.js resource-registration patch is missing or incompatible.');
        }
        source = source.replace(original, patched);
    }
    return source;
}
module.exports = { patchClientResources };
if (require.main === module) run();
