(ns zapbot.pokemon.ajuda
  "Guias de jogo consultáveis sem alterar o estado da partida."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.treinador :as treinador]))

(defn- comando [texto]
  (str config/prefix "pokemon" (when (seq texto) (str " " texto))))

(def ^:private assuntos
  {"batalha" :batalhas "batalhas" :batalhas "pvp" :batalhas
   "ginasio" :ginasios "ginasios" :ginasios
   "cacar" :cacadas "cacada" :cacadas "cacadas" :cacadas
   "liga" :ligas "ligas" :ligas
   "time" :time "equipe" :time
   "raid" :raid "raids" :raid "shiny" :shiny "missoes" :semanais "semanais" :semanais
   "evoluir" :evolucao "evolucao" :evolucao})

(defn- guia [assunto]
  (case assunto
    :batalhas
    (str "⚔️ *Como jogar: batalhas*\n\n"
         "1. Selecione uma liga e escale três Pokémon saudáveis. Veja " (comando "ajuda ligas") ".\n"
         "2. Envie " (comando "") " para abrir uma batalha. Outra pessoa do mesmo chat e da mesma liga envia o mesmo comando para entrar.\n"
         "3. Na sua vez, consulte os golpes e use " (comando "atacar 1") " (números de 1 a 4). Considere o tipo do adversário ao escolher.\n"
         "4. Você também pode usar " (comando "defender") ", " (comando "pocao") " para HP ou " (comando "curar") " para status. As curas exigem itens da mochila.\n\n"
         "Após um nocaute, entra o próximo da escalação. Vença os três adversários. Pokémon participantes podem ganhar XP e subir de nível.\n"
         "Há 30 minutos para entrar ou agir no PvP. " (comando "sair") " cancela a espera ou desiste da partida; desistir de uma batalha iniciada perde 1 ponto no rank e não dá XP nem moedas.")

    :raid
    (str "🤝 *Como jogar: raid cooperativa*\n\n"
         "1. Abra com " (comando "raid abrir iniciante") " (ou bronze, prata, ouro, diamante).\n"
         "2. Cada jogador, inclusive o criador, usa " (comando "raid entrar 1") " para inscrever um Pokémon saudável da liga. Sem número, entra o ativo. São 2 a 6 pessoas.\n"
         "3. O criador usa " (comando "raid iniciar") ". Na sua vez, use " (comando "raid atacar 1") ". Consulte HP e golpes com " (comando "raid") ".\n"
         "O chefe Snorlax contra-ataca a cada ação. Só golpes físicos ou especiais de dano são aceitos; o combate da raid usa regras próprias, sem efeitos de status, itens ou vantagens de tipo.\n"
         "O time e o HP são cópias da inscrição: dano da raid não altera sua coleção.\n"
         "Inscrições duram 15 minutos; combate, 30 minutos. O chat pode iniciar uma nova raid após 6 horas.\n"
         "Vitória: 40 moedas, 6 PE para o treinador e 6 XP para o Pokémon inscrito de cada participante que causou dano, inclusive quem caiu. Uma recompensa por jogador/chat ao dia, no mesmo fuso das missões. Se o Pokémon estiver fora da equipe, o XP fica reservado até ele voltar e você usar um comando Pokémon.\n"
         "Durante as inscrições, use " (comando "raid sair") " ou, se for o criador, " (comando "raid cancelar") ".")

    :shiny
    (str "✨ *Coleção shiny*\n\n"
         (comando "time shiny") " mostra os shiny disponíveis com fotos. Combine filtros: " (comando "time shiny fogo >") ".\n"
         (comando "pokedex shiny") " ou " (comando "shiny") " mostra as espécies shiny já registradas.\n"
         "Novas capturas permanecem no histórico após doações, trocas e evoluções. Shiny antigos ainda na coleção, na enfermaria ou em ginásios são recuperados ao consultar.\n"
         "Encontros têm chance de 1 em 512; shiny muda o visual, mantendo os atributos.")

    :semanais
    (str "📅 *Missões semanais*\n\n"
         "Consulte " (comando "missoes semanais") " e receba com " (comando "missoes semanais resgatar") ".\n"
         "• Vencer em 2 ginásios diferentes: 60 moedas.\n"
         "• Capturar Pokémon de 5 tipos diferentes: 50 moedas.\n"
         "• Vencer 3 batalhas PvP: 60 moedas.\n"
         "Cada missão pode ser resgatada uma vez por semana. Progresso começa nesta atualização; a semana reinicia na segunda-feira no fuso das missões. Resgate antes da virada.")

    :ginasios
    (str "🏛️ *Como jogar: ginásios*\n\n"
         "1. Veja líderes, níveis e desbloqueios com " (comando "ginasio") ".\n"
         "2. Consulte seu time: " (comando "time >") ". Escale três Pokémon diferentes e com HP: " (comando "ginasio time 1,2,3") ". Use os números da sua coleção.\n"
         "3. Veja o primeiro líder com " (comando "ginasio pedra") " e inicie com " (comando "ginasio desafiar pedra") ".\n"
         "4. Use " (comando "atacar 1") " e as demais ações de batalha. O líder responde automaticamente.\n\n"
         "São combates 3 × 3, sem ajuste dos níveis. Vença os ginásios na ordem para liberar os próximos. A escalação de ginásio é separada da liga.\n"
         "Primeira vitória: insígnia, 100 moedas, 6 XP por participante e uma pedra. Revanche premiada: 25 moedas, 2 XP e 25% de chance de pedra, uma vez por dia por ginásio. Reinicia à meia-noite de São Paulo.\n"
         "Derrota: 2 XP para cada Pokémon participante. O treinador ganha PE (Pontos de experiência): 6 na primeira vitória, 3 na revanche premiada e 1 na derrota.\n"
         "Quem vence assume a liderança. Os três Pokémon ficam fora da coleção disponível, inativos e reservados até outro jogador vencer. O time defende automaticamente, com HP cheio em cada desafio. Ao cair, volta à coleção com o HP que tinha ao assumir.\n"
         "Permanecer mais de 6 horas rende 50 moedas, pagas uma única vez ao ser derrubado. É possível disputar a liderança novamente no mesmo dia, mas a recompensa de vitória continua diária.\n"
         "Ranking por defesas e tempo: " (comando "ginasio ranking pedra") ". Últimas batalhas: " (comando "ginasio historico pedra") ". Sem nome, mostra todos os ginásios. Defesas começam a ser registradas nesta atualização.\n"
         "Para recuperar o time, consulte " (comando "ajuda time") ".")

    :cacadas
    (str "🌿 *Como jogar: caçadas*\n\n"
         "1. Escolha seu inicial com " (comando "inicial") ". Confira as bolas em " (comando "mochila") " e os itens em " config/prefix "loja.\n"
         "2. Use " (comando "time") " e " (comando "escolher 1") " para definir um Pokémon com HP.\n"
         "3. Inicie com " (comando "cacar") ". Os encontros variam com o bioma, horário e eventos; a força da caçada considera seu nível de treinador.\n"
         "4. Derrote o selvagem usando " (comando "atacar 1") ". Depois escolha uma bola no menu: " (comando "capturar pokebola") ".\n\n"
         "A captura exige uma bola da mochila e pode falhar. Há até três tentativas, mas o selvagem pode fugir antes.\n"
         "Cada encontro tem chance de 1 em 512 de ser ✨ Shiny: cores especiais, mesmos atributos. A característica é preservada ao evoluir, doar ou trocar.\n"
         "Durante o combate, é permitida uma troca de Pokémon com " (comando "escolher <número>") "; ela gasta sua ação.\n"
         "Só pode haver uma batalha ou caçada por vez no chat. Respeite o intervalo informado entre caçadas e aja em até 5 minutos. " (comando "sair") " abandona a caçada.")

    :ligas
    (str "🏆 *Como jogar: ligas*\n\n"
         (str/join "\n" (map #(str (:nome %) ": níveis " (:min %) "–" (:max %)) treinador/ligas))
         "\n\n1. Consulte sua seleção com " (comando "liga") ".\n"
         "2. Escolha, por exemplo, " (comando "liga bronze") ".\n"
         "3. Encontre Pokémon da faixa com " (comando "time bronze >") ".\n"
         "4. Escale três diferentes: " (comando "liga time 1,2,3") ". Substitua os números pelos da sua coleção. Todos precisam estar na faixa da liga e saudáveis para batalhar.\n"
         "5. Envie " (comando "") " para abrir ou entrar numa batalha com alguém da mesma liga.\n\n"
         "A ordem da escalação define quem começa e quem entra após cada nocaute. Não há limite adicional de diferença de nível entre times da mesma liga.\n"
         "Confira sua escalação com " (comando "liga time") ". Se um Pokémon sair da faixa ao subir de nível, ajuste o time. Regras de combate: " (comando "ajuda batalhas") ".")

    :time
    (str "🎒 *Como jogar: time e recuperação*\n\n"
         "• " (comando "time") ": cartões dos seus Pokémon.\n"
         "• " (comando "time txt") ": lista completa em texto; " (comando "time csv") ": planilha.\n"
         "• " (comando "time >") ": maior força primeiro; " (comando "time <") ": menor primeiro. A força é a soma dos seis atributos.\n"
         "• Combine filtros: " (comando "time txt fogo >") " ou " (comando "time bronze") ". Os números da coleção não mudam.\n"
         "• " (comando "escolher 2") ": define o ativo; " (comando "time ativo") ": ficha e golpes.\n"
         "• " (comando "pocao") " recupera HP e " (comando "curar") " trata status com itens da mochila.\n"
         "• " (comando "joy 1,2") ": envia os Pokémon indicados à Enfermeira Joy por 30 minutos. Veja o tempo restante em " (comando "time") ".\n\n"
         "Começando agora? Use " (comando "inicial") ", escolha uma opção e consulte " (comando "ajuda cacadas") " para ampliar sua coleção.")

    :evolucao
    (str "💎 *Como jogar: evolução*\n\n"
         "1. Consulte a ficha com " (comando "pokedex 1") " (número do Pokémon no seu time) ou " config/prefix "pokedex pikachu (nome da espécie).\n"
         "2. A ficha informa evoluções por nível e as pedras compatíveis disponíveis no bot. Ganhe XP nas batalhas para alcançar o nível necessário.\n"
         "3. Ganhe pedras nos ginásios e confira " (comando "mochila") ".\n"
         "4. Para um Pikachu compatível na posição 1, use " (comando "evoluir 1 pedra-trovao") ". A evolução consome uma pedra e exige estar fora de combate.\n\n"
         "Use o identificador do item exibido na ficha. Veja detalhes em " config/prefix "loja detalhes pedra-trovao. Guia de recompensas: " (comando "ajuda ginasios") ".")

    (str "📖 *Como jogar Pokémon*\n\n"
         "Comece com " (comando "inicial") ", escolha seu Pokémon e faça caçadas para capturar mais. Com três Pokémon, prepare seu time para ligas e ginásios.\n\n"
         (str/join "\n" (map (fn [[nome titulo]] (str "• " (comando (str "ajuda " nome)) " — " titulo))
                               [["batalhas" "turnos, golpes e vitória"]
                                ["ginasios" "líderes, insígnias e recompensas"]
                                ["cacadas" "selvagens e captura"]
                                ["ligas" "faixas de nível e escalação"]
                                ["time" "filtros, ativo e recuperação"]
                                ["shiny" "coleção histórica e fotos"]
                                ["semanais" "objetivos e recompensas semanais"]
                                ["raid" "chefe cooperativo por liga"]
                                ["evolucao" "XP e pedras de evolução"]]))
         "\n\nTambém funciona: " (comando "ginasio ajuda") " ou " (comando "cacar ajuda") ". Consultar a ajuda não gasta turno.")))

(defn resposta
  "Retorna o guia para ajuda [tema] ou <módulo> ajuda; nil para comandos de jogo."
  [args]
  (let [tokens (-> (or args "") str/lower-case (.normalize "NFD")
                   (str/replace #"[\u0300-\u036f]" "") str/trim (str/split #"\s+"))
        [primeiro segundo] tokens
        ajuda? #{"ajuda" "help" "?"}]
    (when (or (ajuda? primeiro) (ajuda? segundo))
      (let [tema (if (ajuda? primeiro) segundo primeiro)
            assunto (get assuntos tema)]
        (str (when (and (seq tema) (nil? assunto)) "❓ Não encontrei esse módulo. Escolha um dos guias abaixo.\n\n")
             (guia assunto))))))
