# Navegação Pokémon

`!pk` abre o menu principal, sem abrir uma batalha. `!pk 0` e `!pk menu` voltam ao menu. `!pk batalhar`, `!pk abrir` e `!pk entrar` abrem/aceitam PvP.

As opções são individuais por chat e jogador e expiram após cinco minutos. Um menu expirado retorna orientação e não transforma a escolha em um ataque. Comandos diretos descartam o menu atual. Mensagens sem prefixo continuam fora dessa navegação.

- Menu principal: treinador, coleção, batalhas, mochila, Enfermeira Joy (Centro Pokémon), loja e ajuda.
- `!pk meus` / `!pk pokemons`: coleção em texto; o número abre a ficha do Pokémon.
- Mochila: o número consulta o efeito do item, sem consumir ou comprar.
- Submenu de batalhas: caçada, PvP, ginásio e raide.
- Sem um menu de consulta aberto, `!pk 1` a `!pk 4` selecionam golpes na própria caçada, PvP, ginásio ou raide em combate. As regras existentes validam turno, participação e golpe.
- Na captura da caçada, os números representam Pokébola, Grande Bola e Ultra Bola.
- Aliases novos: perfil, itens, pokemons e ?.

Na loja (`!loja`, `!pk loja` ou opção 6), cada item tem um número: `!pk <número>` compra uma unidade e reapresenta o catálogo com o saldo atualizado. A lista ordenada é compartilhada entre exibição e seleção. Saldo, estoque e espaço seguem as validações das compras diretas. Transferência, cura e evolução continuam por comandos específicos. A opção 5 abre diretamente a Enfermeira Joy, com as regras e comandos existentes de atendimento.

## Validação

59 testes Node passaram. Quatro testes de navegação (13 asserções) passaram via nbb, cobrindo isolamento por pessoa/chat, expiração, opções inválidas, comandos diretos e contextos de combate/captura. Adicionado teste de integração ao domínio para verificar encaminhamento de golpe/captura e retorno ao menu; ele depende da compilação ClojureScript na CI.

A leitura sintática dos arquivos ClojureScript passou. `npm run test:domain` foi tentado, mas este ambiente não conseguiu resolver repo1.maven.org para baixar shadow-cljs. A compilação completa e os testes do domínio precisam passar na CI antes do merge. Não foi realizado merge nem deploy.
