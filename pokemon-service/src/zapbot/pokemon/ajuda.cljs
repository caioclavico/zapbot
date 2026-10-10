(ns zapbot.pokemon.ajuda
  "Guias de jogo consultáveis sem alterar o estado da partida."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.pvp :as pvp]))

(defn- comando [texto]
  (str config/prefix "pokemon" (when (seq texto) (str " " texto))))

(def ^:private assuntos
  {"batalha" :batalhas "batalhas" :batalhas "pvp" :batalhas
   "atacar" :ataques "ataque" :ataques "atk" :ataques
   "ginasio" :ginasios "ginasios" :ginasios "gin" :ginasios
   "cacar" :cacadas "cacada" :cacadas "cacadas" :cacadas "cac" :cacadas
   "capturar" :captura "captura" :captura "cap" :captura "pokebola" :captura
   "liga" :batalhas "ligas" :batalhas "lig" :batalhas
   "pc" :pc "computador" :pc "centro" :pc "espaco" :pc "espaço" :pc
   "professor" :professor
   "time" :time "equipe" :time "tm" :time
   "atalho" :atalhos "atalhos" :atalhos "abreviacoes" :atalhos "abreviacao" :atalhos
   "raide" :raid "raides" :raid "raid" :raid "raids" :raid "shiny" :shiny "missoes" :semanais "semanais" :semanais
   "evoluir" :evolucao "evolucao" :evolucao "evo" :evolucao})

(defn- guia [assunto]
  (case assunto
    :atalhos
    (str "⚡ *Comandos Pokémon abreviados*\n\n"
         "• `ajd` → esta lista de abreviações\n\n"
         "*Batalha*\n"
         "• `atk` → atacar · Ex.: " config/prefix "pk atk 1\n"
         "• `def` → defender · `cur` → curar\n"
         "• `pot` → pocao · `pmax` → pocao-maxima · `sai` → sair\n\n"
         "*Exploração e coleção*\n"
         "• `cac` → cacar · `cap` → capturar · `ini` → inicial\n"
         "• `dex` ou `pdx` → pokedex · `tm` → time · `mch` → mochila\n"
         "• `fav` → favorito · `tre` → treinador\n\n"
         "*Ginásio e eventos*\n"
         "• `gin` → ginasio · `evt` → eventos\n"
         "• Ginásio: `des`/`dsf` → desafiar · `tm` → time · `pot` → pocao\n"
         "• Ginásio: `fru` → fruta · `ran` → ranking · `hist` → historico\n"
         "  Ex.: " config/prefix "pk gin des pedra\n\n"
         "*Raid*\n"
         "• `abr` → abrir · `ent` → entrar · `ini` → iniciar · `atk` → atacar\n"
         "• `sai` → sair · `can` → cancelar\n\n"
         "*Escalações nomeadas*\n"
         "• `sal` → salvar · `usa` → usar · `exc` → excluir · `apa` → apagar · `rm` → remover\n"
         "• Destino: `gin` → ginasio\n• Compatibilidade: `lig` → guia do novo PvP\n"
         "  Ex.: " config/prefix "pk tm usa os fodoes gin\n\n"
         "*Gerenciamento*\n"
         "• `evo` → evoluir · `neg` → negociar · `doa` → doar\n"
         "• `eqp` → equipar · `esc` → escolher · `rmg` → removergolpe\n"
         "• `apr` → aprender · `reap` → reaprender · `rev` → reviver\n"
         "• `can` → cancelar · `mis` → missoes · `pre` → presente\n\n"
         "*Relatórios*\n"
         "• Em `bug`: `res` → resolver\n\n"
         "Você pode continuar usando os nomes completos. Consulte os guias com "
         config/prefix "pk ajuda.")

    :ataques
    (str "⚔️ *Como usar: atacar e ações de batalha*\n\n"
         "Na sua vez, escolha um golpe pelo número mostrado na mensagem: " (comando "atacar 1") " ou " config/prefix "pk atk 1. Confira o tipo e a efetividade contra o adversário.\n"
         "• " (comando "defender") ": tenta esquivar e reduz o dano recebido.\n"
         "• " (comando "curar") ": usa uma cura da mochila para remover status.\n"
         "• " (comando "pocao [número]") ": recupera 40% do HP; " (comando "pocao-maxima [número]") " recupera todo o HP. Sem número, usa no Pokémon ativo. Compre itens na " config/prefix "loja.\n"
         "Essas ações consomem sua vez quando aceitas. No ginásio e na caçada, o adversário responde automaticamente.\n"
         "Na caçada, você pode trocar uma vez com " (comando "trocar <número>") ", como primeira ação; a troca gasta a vez. No PvP e no ginásio, não há troca manual durante a batalha.\n"
         "Para desistir: " (comando "sair") ". Consultar esta ajuda não gasta turno.")

    :batalhas
    (str "⚔️ *Como jogar: PvP com Pokémon ativo*\n\n"
         "1. Escolha um Pokémon saudável com " (comando "escolher <número>") ".\n"
         "2. Envie " (comando "") " para abrir um desafio. Outro treinador envia o mesmo comando para aceitar com seu Pokémon ativo.\n"
         "A diferença máxima é de " pvp/diferenca-maxima-niveis " níveis para cima ou para baixo. A faixa aparece no anúncio; tentativas incompatíveis não cancelam o desafio.\n"
         "O desafio expira em " pvp/minutos-espera " minutos, sem penalidade. Há um desafio por grupo e você só pode participar de um combate por vez.\n"
         "3. Na sua vez, use " (comando "atacar 1") " (golpes de 1 a 4), " (comando "defender") ", " (comando "pocao") ", " (comando "pocao-maxima") " ou " (comando "curar") ".\n\n"
         "O PvP é 1 × 1, sem liga ou escalação obrigatória. Os níveis, atributos, tipos, habilidades e golpes reais são preservados. O nocaute encerra a partida e os participantes recebem o XP das regras existentes.\n"
         "Após começar, há 30 minutos por turno. " (comando "sair") " cancela seu desafio ou desiste; desistência não dá XP ou moedas e perde 1 ponto no rank.")

    :raid
    (str "🤝 *Raide nos ginásios*\n\n"
         "Raides não aparecem automaticamente. Em uma raide existente, os defensores ficam preservados e voltam ao terminar.\n"
         "Inscrições: 45 minutos, de 2 a 6 jogadores. Use " (comando "gin entrar 1") " com um Pokémon saudável da liga indicada.\n"
         "Escolha visual: " (comando "raide time") ". Escolha automática: " (comando "gin entrar") ".\n"
         "Qualquer inscrito pode usar " (comando "gin iniciar") ". Combate: até 30 minutos, alternando " (comando "gin atacar 1") ".\n"
         "Quem causar dano recebe 50 moedas, 6 PE e 6 XP para o Pokémon inscrito por vitória, mesmo se cair.\n"
         "Após vencer: " (comando "gin capturar pokebola") ". São 3 tentativas durante 30 minutos, usando suas bolas e uma vaga na coleção.\n"
         "Chance base: raro 35%, lendário 8%, mítico 4%; bolas melhores aumentam a chance. Capturado: nível 1 e atributos normais da espécie.\n"
         "O combate usa cópias do HP e regras próprias, sem efeitos de status, itens ou vantagens de tipo. Consulte " (comando "raide") ".")

    :shiny
    (str "✨ *Coleção shiny*\n\n"
         (comando "time shiny") " mostra os shiny disponíveis com fotos. Combine filtros: " (comando "time shiny fogo >") ".\n"
         (comando "pokedex shiny") " ou " (comando "shiny") " mostra as espécies shiny já registradas.\n"
         "Novas capturas permanecem no histórico após doações, trocas e evoluções. Shiny antigos ainda na coleção, na enfermaria ou em ginásios são recuperados ao consultar.\n"
         "Encontros têm chance de 1 em 512; shiny muda o visual, mantendo os atributos.")

    :semanais
    (str "📅 *Missões semanais*\n\n"
         "Consulte " (comando "missoes semanais") " e receba com " (comando "missoes semanais resgatar") ".\n"
         "• Vencer em 2 ginásios diferentes: 60 moedas, 8 Pokébolas, 4 Grandes e 2 Ultras.\n"
         "• Capturar Pokémon de 5 tipos diferentes: 50 moedas, 6 Pokébolas, 3 Grandes e 1 Ultra.\n"
         "• Vencer 3 batalhas PvP: 60 moedas, 8 Pokébolas, 4 Grandes e 2 Ultras.\n"
         "• Enviar 10 presentes: 50 moedas, 6 Pokébolas, 3 Grandes e 1 Ultra.\n"
         "Use !missoes para ver todas ou !missoes diarias / !missoes semanais. Presentes: !loja comprar cartao-presente e !presente @amigo.\n"
         "Cada missão pode ser resgatada uma vez por semana. Progresso começa nesta atualização; a semana reinicia na segunda-feira no fuso das missões. Resgate antes da virada.")

    :ginasios
    (str "🏛️ *Como jogar: ginásios*\n\n"
         "1. Veja líderes, níveis e desbloqueios com " (comando "ginasio") ".\n"
         "2. Monte e veja a foto dos três mais fortes aptos com " (comando "gin time") ". Para escolher manualmente: " (comando "ginasio time 1,2,3") ". Use os números da sua coleção.\n"
         "3. Veja o primeiro líder com " (comando "ginasio pedra") " e inicie com " (comando "ginasio desafiar pedra") ".\n"
         "4. Use " (comando "atacar 1") " (ou o atalho " config/prefix "pk atk 1) e as demais ações de batalha. O líder responde automaticamente. Cada ataque mostra a foto dos Pokémon na arena.\n\n"
         "São combates 3 × 3, sem ajuste dos níveis. Vença os ginásios na ordem para liberar os próximos. A escalação de ginásio é independente do Pokémon ativo usado no PvP.\n"
         "✨ *XP dos Pokémon:* somente Pokémon que realmente entraram na batalha são participantes. Cada um recebe seu XP-base e +1 XP para cada defensor que ele próprio nocauteou:\n"
         "• Derrota: 3 XP-base. Exemplo: derrubou 2 defensores antes de perder = 5 XP.\n"
         "• Primeira vitória no ginásio: 7 XP-base. Com 2 nocautes = 9 XP, além da insígnia, 100 moedas e uma pedra.\n"
         "• Revanche premiada do dia: 4 XP-base. Também entrega 25 moedas e tem 25% de chance da pedra.\n"
         "• Nova vitória no mesmo dia: 2 XP-base. O XP e o bônus por nocaute continuam valendo, mas moedas, pedra e PE diário não se repetem.\n"
         "A recompensa diária reinicia à meia-noite de São Paulo. Desistir ou deixar a batalha expirar não dá XP.\n"
         "⭐ *PE do treinador:* 6 na primeira vitória, 3 na revanche premiada e 1 na derrota. PE é separado do XP recebido pelos Pokémon.\n"
         "Quem vence assume a liderança. Os três Pokémon ficam fora da coleção disponível, inativos e reservados até outro jogador vencer. A motivação começa em 100%, cai 5 pontos por hora e 12 após cada defesa, enfraquecendo HP e atributos até o mínimo de 20%.\n"
         "O coração aparece apenas nos defensores de jogadores e mostra a motivação restante; NPCs não têm esse indicador. O líder pode recuperar 40 pontos com " (comando "ginasio pocao pedra 1") ", 20 com " (comando "ginasio fruta pedra 1") " ou tudo com uma fruta dourada. Ao ser derrotado, o time retorna desmaiado à equipe ou ao PC se ela estiver cheia.\n"
         "Permanecer mais de 6 horas rende 50 moedas, pagas uma única vez ao ser derrubado. É possível disputar a liderança novamente no mesmo dia, mas a recompensa de vitória continua diária.\n"
         "Ranking por defesas e tempo: " (comando "ginasio ranking pedra") ". Últimas batalhas: " (comando "ginasio historico pedra") ". Sem nome, mostra todos os ginásios. Defesas começam a ser registradas nesta atualização.\n"
         "Para recuperar o time, consulte " (comando "ajuda time") ".")

    :captura
    (str "🎯 *Como jogar: captura*\n\n"
         "Derrote o selvagem e escolha uma bola da mochila:\n"
         "• " (comando "capturar pokebola") "\n"
         "• " (comando "capturar grande-bola") "\n"
         "• " (comando "capturar ultra-bola") "\n\n"
         "O menu mostra o estoque e a chance de cada bola. Cada lançamento consome uma bola. São até três tentativas: o Pokémon pode fugir após uma falha e foge após a terceira.\n"
         "✨ Capturar na primeira tentativa dá +1 XP extra ao Pokémon ativo, além do XP de raridade e do bônus de sequência. Comando inválido ou falta de bola não conta como tentativa.\n"
         "Você tem 5 minutos para agir. Para desistir: " (comando "sair") ".\n"
         "Sem bolas? Compre na " config/prefix "loja ou use " config/prefix "mochila kit, "
         config/prefix "mochila diario e " config/prefix "mochila resgatar. Missões e nocautes no PvP também dão bolas.")

    :cacadas
    (str "🌿 *Como jogar: caçadas*\n\n"
         "1. Escolha seu inicial com " (comando "inicial") ". Confira as bolas em " (comando "mochila") " e os itens em " config/prefix "loja.\n"
         "2. Use " (comando "time") " e " (comando "escolher 1") " para definir um Pokémon com HP.\n"
         "Com a bolsa cheia, você pode lutar nas caçadas, mas precisa de vaga para capturar. Se vencer sem espaço, o selvagem será liberado.\n3. Consulte clima e áreas com " (comando "clima") ". Três áreas ficam disponíveis por dia; escolha, por exemplo, com " (comando "cacar floresta") ". O clima aumenta os encontros de tipos favorecidos.\n"
         "4. Derrote o selvagem usando " (comando "atacar 1") ". Depois escolha uma bola no menu: " (comando "capturar pokebola") ".\n\n"
         "A captura exige uma bola da mochila e pode falhar. Há até três tentativas, mas o selvagem pode fugir antes. Capturar de primeira dá +1 XP extra. Veja as regras em " (comando "capturar ajuda") ".\n"
         "Cada encontro tem chance de 1 em 512 de ser ✨ Shiny: cores especiais, mesmos atributos. Avistamentos, maiores níveis e primeiros shiny ficam em " (comando "pokedex descobertas") ".\n"
         "Durante o combate, é permitida uma troca de Pokémon com " (comando "escolher <número>") "; ela gasta sua ação.\n"
         "Só pode haver uma batalha ou caçada por vez no chat. Respeite o intervalo informado entre caçadas e aja em até 5 minutos. " (comando "sair") " abandona a caçada.")

    :pc
    (str "🎒 *Coleção e espaço Pokémon*\n\n"
         "Todos os seus Pokémon disponíveis ficam em " (comando "time") " (atalho !pk tm), com filtros e uma imagem de até 12 por página. " (comando "pc") " orienta sobre a coleção unificada.\n"
         "• " (comando "espaco comprar") ": +50 vagas por 200 moedas, preço fixo em todas as compras.\n"
         "Complete a missão do evento para liberar vagas temporárias. Consulte " (comando "eventos") " para o prazo. Ao terminar, os Pokémon são preservados; acima da capacidade, libere/compre vagas para novas aquisições.\n"
         "Capacidade inicial: 26 Pokémon. Joy e defensores dos ginásios também contam. Compras anteriores continuam valendo; os Pokémon do antigo PC são incorporados ao carregar os dados, preservando os índices de batalha.\n"
         "Pokémon antigos acima do limite são preservados e continuam utilizáveis. Sem vaga, novas caçadas, capturas e doações recebidas ficam bloqueadas. Retornos da Joy e dos ginásios nunca são descartados.\n"
         "Para liberar espaço, doe ou consulte " (comando "professor ajuda") ". A transferência ao professor é definitiva e dá 1 cartão de XP da família.\n"
         "Ginásios continuam usando três Pokémon da coleção. PvP usa somente o ativo.")

    :professor
    (str "👨‍🔬 *Professor — transferência e cartões de XP*\n\n"
         "• " (comando "professor enviar <número>") ": prepara o envio de um Pokémon da coleção.\n"
         "O envio é definitivo e libera uma vaga. Confirme pelo código de 3 dígitos da mensagem em até 5 minutos; para desistir: " (comando "professor cancelar") ". Não há envio em lote.\n"
         "Cada Pokémon transferido dá 1 cartão da família evolutiva. Pidgey, Pidgeotto e Pidgeot rendem cartões da família Pidgey. O item equipado volta à mochila e o registro shiny histórico é preservado.\n"
         "• " (comando "professor cartoes") ": saldo por família.\n"
         "• " (comando "professor usar <número>") ": gasta 1 cartão da família do Pokémon escolhido e concede +3 XP. São 9 XP por nível; três cartões rendem um nível. No nível 100, nenhum cartão é gasto.\n"
         "O XP segue as regras normais de atributos, HP, golpes e evolução por nível. As evoluções por pedras e trocas continuam disponíveis.\n"
         "Favoritos não podem ser enviados. Operações ficam bloqueadas durante batalha, caçada, raide e alterações pendentes. Pokémon na Joy ou defendendo ginásios devem retornar primeiro.\n"
         "Confira os números após cada transferência: " (comando "time") ".")

    :time
    (str "🎒 *Como jogar: time e recuperação*\n\n"
         "• " (comando "time") ": coleção completa, com uma imagem de até 12 Pokémon por página.\n"
         "Use " (comando "time 2") " para a página seguinte, ou " (comando "time 2 fogo >") " para manter filtros. Um número no início indica página; para nível, use nivel N.\n"
         "Compre +50 vagas com " (comando "espaco comprar") ". Ginásios usam escalações de três Pokémon; PvP usa apenas o ativo.\n"
         "Envie repetidos ao professor para liberar vagas e ganhar cartões de XP: " (comando "professor ajuda") ".\n"
         "• " (comando "time txt") ": lista completa em texto; " (comando "time csv") ": planilha.\n"
         "• " (comando "time >") ": maior força primeiro; " (comando "time <") ": menor primeiro. A força é a soma dos seis atributos.\n"
         "• Combine filtros: " (comando "time txt fogo >") " ou " (comando "time bronze") ". Os números da coleção não mudam.\n"
         "• " (comando "escolher 2") ": define o ativo; " (comando "time ativo") ": ficha e golpes.\n"
         "• " (comando "favorito 2") ": marca o favorito, que é ativado quando volta saudável para a equipe.\n"
         "• " (comando "time salvar os fodoes 1,4,7") ": salva uma escalação nomeada sem reservar os Pokémon. Liste com " (comando "times") " e aplique com " (comando "time usar os fodoes ginasio") ". O PvP não usa escalações.\n"
         "• " (comando "pocao [número]") " recupera 40% do HP; " (comando "pocao-maxima [número]") " recupera tudo. Sem número, cura o ativo. " (comando "curar") " trata status.\n"
         "• " (comando "joy 1,2") ": envia os Pokémon indicados à Enfermeira Joy por 30 minutos. Veja o tempo restante em " (comando "time") ".\n\n"
         "Começando agora? Use " (comando "inicial") ", escolha uma opção e consulte " (comando "ajuda cacadas") " para ampliar sua coleção.")

    :evolucao
    (str "💎 *Como jogar: evolução*\n\n"
         "1. Consulte a ficha com " (comando "pokedex 1") " (número do Pokémon no seu time) ou " config/prefix "pokedex pikachu (nome da espécie).\n"
         "Cartões do professor concedem XP: " (comando "professor usar <número>") ". Consulte " (comando "professor ajuda") ".\n"
         "2. A ficha informa evoluções por nível e as pedras compatíveis disponíveis no bot. Ganhe XP nas batalhas para alcançar o nível necessário.\n"
         "3. Ganhe pedras nos ginásios e confira " (comando "mochila") ".\n"
         "4. Para um Pikachu compatível na posição 1, use " (comando "evoluir 1 pedra-trovao") ". A evolução consome uma pedra e exige estar fora de combate.\n"
         "5. Evoluções por troca simples acontecem ao concluir " (comando "negociar <seu número> <número do outro> @pessoa") ". Karrablast e Shelmet precisam ser trocados entre si.\n\n"
         "Itens oficiais como Revestimento Metálico, Escama de Dragão e Upgrade vêm das missões; equipe antes de negociar e o item será consumido na evolução.\n"
         "A amizade sobe 10 pontos sempre que o Pokémon recebe XP e 2 ao receber poções ou curas. Consulte com " (comando "amizade [número]") "; evoluções por amizade ocorrem ao subir de nível, respeitando dia ou noite.\n"
         "Condições sem equivalente no WhatsApp usam 🧬 Catalisador Evolutivo, ganho em missões difíceis e semanais: " (comando "evoluir <número> especial [destino]") ".\n"
         "Use o identificador do item exibido na ficha. Veja detalhes em " config/prefix "loja detalhes pedra-trovao. Guia de recompensas: " (comando "ajuda ginasios") ".")

    (str "📖 *Como jogar Pokémon*\n\n"
         "Comece com " (comando "inicial") ", escolha seu Pokémon e faça caçadas para capturar mais. Um Pokémon ativo saudável basta para o PvP. Com três, prepare seu time de ginásio.\n\n"
         "⚡ *Atalhos:* " config/prefix "pk atk 1, " config/prefix "pk def, " config/prefix "pk cur, "
         config/prefix "pk pot, " config/prefix "pk cac, " config/prefix "pk gin, "
         config/prefix "pk tm e " config/prefix "pk dex.\n\n"
         (str/join "\n" (map (fn [[nome titulo]] (str "• " (comando (str "ajuda " nome)) " — " titulo))
                               [["ataque" "golpes, defesa, curas e poções"]
                                ["batalhas" "turnos, golpes e vitória"]
                                ["ginasios" "líderes, insígnias e recompensas"]
                                ["cacadas" "selvagens e captura"]
                                ["captura" "Pokébolas, tentativas e bônus de XP"]
                                ["time" "coleção, páginas, filtros e recuperação"]
                                ["professor" "transferência e cartões de XP"]
                                ["espaco" "estoque e expansões"]
                                ["shiny" "coleção histórica e fotos"]
                                ["semanais" "objetivos e recompensas semanais"]
                                ["raide" "chefe cooperativo por liga"]
                                ["evolucao" "XP e pedras de evolução"]]))
         "\n\nTambém funciona: " (comando "ginasio ajuda") " ou " (comando "cacar ajuda") ". Consultar a ajuda não gasta turno.")))

(defn resposta
  "Retorna o guia para ajuda [tema] ou <módulo> ajuda; nil para comandos de jogo."
  [args]
  (let [tokens (-> (or args "") str/lower-case (.normalize "NFD")
                   (str/replace #"[\u0300-\u036f]" "") str/trim (str/split #"\s+"))
        [primeiro segundo] tokens
        ajuda? #{"ajuda" "help" "?"}
        atalho-direto? (= "ajd" primeiro)]
    (when (or atalho-direto? (ajuda? primeiro) (ajuda? segundo))
      (let [tema (if atalho-direto? "atalhos" (if (ajuda? primeiro) segundo primeiro))
            assunto (get assuntos tema)]
        (str (when (and (seq tema) (nil? assunto)) "❓ Não encontrei esse módulo. Escolha um dos guias abaixo.\n\n")
             (guia assunto))))))
