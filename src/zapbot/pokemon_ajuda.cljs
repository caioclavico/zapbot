(ns zapbot.pokemon-ajuda
  "Guias de jogo consultáveis sem alterar o estado da partida."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.treinador :as treinador]))

(defn- comando [texto]
  (str config/prefix "pokemon" (when (seq texto) (str " " texto))))

(def ^:private assuntos
  {"batalha" :batalhas "batalhas" :batalhas "pvp" :batalhas
   "ginasio" :ginasios "ginasios" :ginasios
   "cacar" :cacadas "cacada" :cacadas "cacadas" :cacadas
   "liga" :ligas "ligas" :ligas
   "time" :time "equipe" :time
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

    :ginasios
    (str "🏛️ *Como jogar: ginásios*\n\n"
         "1. Veja líderes, níveis e desbloqueios com " (comando "ginasio") ".\n"
         "2. Consulte seu time: " (comando "time >") ". Escale três Pokémon diferentes e com HP: " (comando "ginasio time 1,2,3") ". Use os números da sua coleção.\n"
         "3. Veja o primeiro líder com " (comando "ginasio pedra") " e inicie com " (comando "ginasio desafiar pedra") ".\n"
         "4. Use " (comando "atacar 1") " e as demais ações de batalha. O líder responde automaticamente.\n\n"
         "São combates 3 × 3, sem ajuste dos níveis. Vença os ginásios na ordem para liberar os próximos. A escalação de ginásio é separada da liga.\n"
         "Primeira vitória: insígnia, 100 moedas, 6 XP por participante e uma pedra. Revanche premiada: 25 moedas, 2 XP e 25% de chance de pedra, uma vez por dia por ginásio. Reinicia à meia-noite de São Paulo.\n"
         "Para recuperar o time, consulte " (comando "ajuda time") ".")

    :cacadas
    (str "🌿 *Como jogar: caçadas*\n\n"
         "1. Escolha seu inicial com " (comando "inicial") ". Confira as bolas em " (comando "mochila") " e os itens em " config/prefix "loja.\n"
         "2. Use " (comando "time") " e " (comando "escolher 1") " para definir um Pokémon com HP.\n"
         "3. Inicie com " (comando "cacar") ". Os encontros variam com o bioma, horário e eventos; a força da caçada considera seu nível de treinador.\n"
         "4. Derrote o selvagem usando " (comando "atacar 1") ". Depois escolha uma bola no menu: " (comando "capturar pokebola") ".\n\n"
         "A captura exige uma bola da mochila e pode falhar. Há até três tentativas, mas o selvagem pode fugir antes.\n"
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
