(ns zapbot.pokemon
  "Comando !pokemon - batalha entre duas pessoas do chat, cada uma com um
  Pokémon sorteado (nome, imagem e stats via PokeAPI - grátis, sem chave).
  Ataques consideram vantagem de tipo, chance de crítico/especial, status
  (paralisia/queimadura/veneno) e um punhado de habilidades icônicas.
  Estado guardado em memória por chat (não sobrevive a reinício do bot)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.rank :as rank]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

;; total de espécies conhecidas pela PokeAPI (até a geração 9)
(def ^:private total-pokemons 1025)

(defonce ^:private jogos (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- jogador-id [message]
  (or (.-author message) (.-from message)))

(defn- nome-de [message]
  (-> (.getContact message)
      (p/then (fn [c] (or (.-pushname c) (.-name c) (.-number c) "Alguém")))
      (p/catch (fn [_] "Alguém"))))

(defn- stat-base [dados nome-stat]
  (->> (:stats dados)
       (some #(when (= nome-stat (get-in % [:stat :name])) (:base_stat %)))))

;; Pokémon com stat base muito baixo/alto (ex.: Magikarp vs. um lendário)
;; ficam mais parecidos: cada stat é puxado pra perto da média em vez de
;; usado cru, pra deixar a batalha mais equilibrada (a pedido do usuário -
;; escolha deliberada de reduzir o quanto os stats originais pesam).
(def ^:private media-stat-referencia 75)
(def ^:private fator-compressao-stat 0.5)

(defn- suavizar-stat [valor]
  (js/Math.round (+ media-stat-referencia (* fator-compressao-stat (- valor media-stat-referencia)))))

(defn- sortear-pokemon []
  (let [id (inc (rand-int total-pokemons))]
    (-> (js/fetch (str "https://pokeapi.co/api/v2/pokemon/" id))
        (p/then (fn [res] (.json res)))
        (p/then (fn [data]
                  (let [dados (js->clj data :keywordize-keys true)]
                    {:nome       (str/capitalize (:name dados))
                     :imagem     (or (get-in dados [:sprites :other :official-artwork :front_default])
                                      (get-in dados [:sprites :front_default]))
                     :tipos      (mapv #(get-in % [:type :name]) (:types dados))
                     ;; prefere a habilidade "normal" (não-oculta); só cai pra
                     ;; oculta se por algum motivo não houver nenhuma outra
                     :habilidade (or (some #(when-not (:is_hidden %) (get-in % [:ability :name])) (:abilities dados))
                                     (get-in (first (:abilities dados)) [:ability :name]))
                     :hp         (suavizar-stat (stat-base dados "hp"))
                     :ataque     (suavizar-stat (stat-base dados "attack"))
                     :defesa     (suavizar-stat (stat-base dados "defense"))
                     :atq-esp    (suavizar-stat (stat-base dados "special-attack"))
                     :def-esp    (suavizar-stat (stat-base dados "special-defense"))
                     :veloc      (suavizar-stat (stat-base dados "speed"))}))))))

(defn- cabecalho []
  (str "⚡ *Batalha Pokémon do tio " config/bot-name "*\n\n"))

(defn- barra-hp [atual maximo]
  (let [cheios (js/Math.round (* 10 (max 0 (/ atual maximo))))]
    (str "[" (apply str (repeat cheios "█")) (apply str (repeat (- 10 cheios) "░")) "] "
         (max 0 atual) "/" maximo)))

(defn- outro [marca] (if (= marca :x) :o :x))

(def ^:private tipos-pt
  {"normal" "Normal" "fire" "Fogo" "water" "Água" "electric" "Elétrico"
   "grass" "Planta" "ice" "Gelo" "fighting" "Lutador" "poison" "Venenoso"
   "ground" "Terra" "flying" "Voador" "psychic" "Psíquico" "bug" "Inseto"
   "rock" "Pedra" "ghost" "Fantasma" "dragon" "Dragão" "dark" "Sombrio"
   "steel" "Aço" "fairy" "Fada"})

;; multiplicador de dano por [tipo do ataque][tipo do defensor] - só relações
;; != 1x precisam estar aqui, o resto cai no valor-padrão de 1 (neutro)
(def ^:private tabela-tipos
  {"normal"   {"rock" 0.5 "steel" 0.5 "ghost" 0}
   "fire"     {"grass" 2 "ice" 2 "bug" 2 "steel" 2 "fire" 0.5 "water" 0.5 "rock" 0.5 "dragon" 0.5}
   "water"    {"fire" 2 "ground" 2 "rock" 2 "water" 0.5 "grass" 0.5 "dragon" 0.5}
   "electric" {"water" 2 "flying" 2 "electric" 0.5 "grass" 0.5 "dragon" 0.5 "ground" 0}
   "grass"    {"water" 2 "ground" 2 "rock" 2 "fire" 0.5 "grass" 0.5 "poison" 0.5 "flying" 0.5 "bug" 0.5 "dragon" 0.5 "steel" 0.5}
   "ice"      {"grass" 2 "ground" 2 "flying" 2 "dragon" 2 "fire" 0.5 "water" 0.5 "ice" 0.5 "steel" 0.5}
   "fighting" {"normal" 2 "ice" 2 "rock" 2 "dark" 2 "steel" 2 "poison" 0.5 "flying" 0.5 "psychic" 0.5 "bug" 0.5 "fairy" 0.5 "ghost" 0}
   "poison"   {"grass" 2 "fairy" 2 "poison" 0.5 "ground" 0.5 "rock" 0.5 "ghost" 0.5 "steel" 0}
   "ground"   {"fire" 2 "electric" 2 "poison" 2 "rock" 2 "steel" 2 "grass" 0.5 "bug" 0.5 "flying" 0}
   "flying"   {"grass" 2 "fighting" 2 "bug" 2 "electric" 0.5 "rock" 0.5 "steel" 0.5}
   "psychic"  {"fighting" 2 "poison" 2 "psychic" 0.5 "steel" 0.5 "dark" 0}
   "bug"      {"grass" 2 "psychic" 2 "dark" 2 "fire" 0.5 "fighting" 0.5 "poison" 0.5 "flying" 0.5 "ghost" 0.5 "steel" 0.5 "fairy" 0.5}
   "rock"     {"fire" 2 "ice" 2 "flying" 2 "bug" 2 "fighting" 0.5 "ground" 0.5 "steel" 0.5}
   "ghost"    {"psychic" 2 "ghost" 2 "dark" 0.5 "normal" 0}
   "dragon"   {"dragon" 2 "steel" 0.5 "fairy" 0}
   "dark"     {"psychic" 2 "ghost" 2 "fighting" 0.5 "dark" 0.5 "fairy" 0.5}
   "steel"    {"ice" 2 "rock" 2 "fairy" 2 "fire" 0.5 "water" 0.5 "electric" 0.5 "steel" 0.5}
   "fairy"    {"fighting" 2 "dragon" 2 "dark" 2 "fire" 0.5 "poison" 0.5 "steel" 0.5}})

(defn- formatar-tipos [tipos]
  (->> tipos (map #(get tipos-pt % (str/capitalize %))) (str/join "/")))

(defn- multiplicador-vs-tipos [tipo-ataque tipos-defesa habilidade-defensor]
  (if (and (= tipo-ataque "ground") (= habilidade-defensor "levitate"))
    0
    (reduce * (map #(get-in tabela-tipos [tipo-ataque %] 1) tipos-defesa))))

(defn- melhor-multiplicador
  "Efetividade do ataque: usa o melhor dos tipos de quem ataca (o jogo não
  tem seleção de golpe) contra todos os tipos de quem defende. Considera
  Levitate (imunidade a golpes de Terra) como exceção à tabela normal."
  [tipos-ataque tipos-defesa habilidade-defensor]
  (apply max (map #(multiplicador-vs-tipos % tipos-defesa habilidade-defensor) tipos-ataque)))

;; chance de qualquer ataque sair como especial (usa atq./def. especial em
;; vez de ataque/defesa físicos) - decidida pelo jogo, não por quem ataca
(def ^:private chance-especial 30)
;; chance de acerto crítico (dano x1.5), independente de tipo/especial
(def ^:private chance-critico 10)
;; chance de um ataque de tipo elegível (fogo/elétrico/venenoso) contagiar
;; status no defensor, se ele ainda não tiver nenhum
(def ^:private chance-contagio 20)
;; chance de a paralisia travar o turno de quem ia atacar
(def ^:private chance-paralisia-trava 25)
;; ~ -1 estágio de ataque, aplicado uma vez por quem tem Intimidate ao entrar
(def ^:private fator-intimidacao (/ 2 3))

(def ^:private tipo->status
  {"fire" :queimado "electric" :paralisado "poison" :envenenado})

(def ^:private habilidades-impulso-hp-baixo
  {"blaze" "fire" "torrent" "water" "overgrow" "grass"})

(defn- descricao-ataque [especial?]
  (if especial? "🔮 *ataque especial*" "💥 *ataque*"))

(defn- texto-efetividade [multiplicador]
  (cond
    (> multiplicador 1) "\n🔥 Foi super efetivo!"
    (< multiplicador 1) "\n😕 Não foi muito efetivo..."
    :else ""))

(defn- texto-critico [critico?]
  (if critico? "\n💢 Foi um golpe crítico!" ""))

(defn- texto-impulso [impulso nome-habilidade]
  (if (> impulso 1)
    (str "\n🌟 " (str/capitalize nome-habilidade) " ativou e turbinou o ataque!")
    ""))

(defn- impulso-habilidade
  "Blaze/Torrent/Overgrow: +50% de dano quando o próprio tipo bate com a
  habilidade e o HP atual já está a 1/3 ou menos do máximo."
  [atacante hp-atual]
  (let [tipo-alvo (get habilidades-impulso-hp-baixo (:habilidade atacante))]
    (if (and tipo-alvo
             (contains? (set (:tipos atacante)) tipo-alvo)
             (<= hp-atual (quot (:hp atacante) 3)))
      1.5
      1)))

(defn- emoji-status [status]
  (case status :queimado " 🔥" :envenenado " ☠️" :paralisado " ⚡" ""))

(defn- dica-tipo [{:keys [pokemons vez]}]
  (let [meu        (get pokemons vez)
        adversario (get pokemons (outro vez))
        mult       (melhor-multiplicador (:tipos meu) (:tipos adversario) (:habilidade adversario))]
    (cond
      (> mult 1) "\n💡 Seu tipo leva vantagem nesse confronto!"
      (< mult 1) "\n💡 Seu tipo leva desvantagem nesse confronto..."
      :else "")))

(defn- mensagem-estado [{:keys [pokemons nomes vez hp defendendo status] :as jogo}]
  (str "🐾 " (get nomes :x) " - *" (get-in pokemons [:x :nome]) "*" (when (:x defendendo) " 🛡️")
       (emoji-status (:x status)) "\n"
       (barra-hp (get hp :x) (get-in pokemons [:x :hp])) "\n\n"
       "🐾 " (get nomes :o) " - *" (get-in pokemons [:o :nome]) "*" (when (:o defendendo) " 🛡️")
       (emoji-status (:o status)) "\n"
       (barra-hp (get hp :o) (get-in pokemons [:o :hp])) "\n\n"
       "Vez de " (get nomes vez) " - ataque com " config/prefix "pokemon atacar ou defenda com "
       config/prefix "pokemon defender"
       (dica-tipo jogo)))

(defn- chance-esquiva [pokemon]
  (min 50 (quot (:veloc pokemon) 2)))

(defn- calcular-dano [poder-ataque poder-defesa]
  (max 1 (+ (- poder-ataque (quot poder-defesa 2)) (rand-int 11))))

(defn- paralisou-turno? [status]
  (and (= status :paralisado) (< (rand-int 100) chance-paralisia-trava)))

(defn- dano-por-status [status hp-maximo]
  (case status
    :queimado   (max 1 (quot hp-maximo 16))
    :envenenado (max 1 (quot hp-maximo 8))
    0))

(defn- tentar-contagiar [tipos-ataque status-atual-defensor]
  (when (and (nil? status-atual-defensor) (< (rand-int 100) chance-contagio))
    (some tipo->status tipos-ataque)))

(defn- msg-status-aplicado [status nome]
  (case status
    :queimado   (str "\n🔥 *" nome "* se queimou!")
    :envenenado (str "\n☠️ *" nome "* foi envenenado!")
    :paralisado (str "\n⚡ *" nome "* ficou paralisado!")
    ""))

(defn- emoji-dot [status] (case status :queimado "🔥" :envenenado "☠️" "❓"))

(defn- aplicar-dot
  "Aplica dano de queimadura/veneno (se houver) em `marca`. Retorna
  [jogo-atualizado dano-sofrido]."
  [jogo marca]
  (let [status (get-in jogo [:status marca])
        maximo (get-in jogo [:pokemons marca :hp])
        dot    (dano-por-status status maximo)]
    (if (pos? dot)
      [(update-in jogo [:hp marca] #(max 0 (- % dot))) dot]
      [jogo 0])))

(defn- aplicar-intimidacao
  "Se um dos dois tiver Intimidate, reduz o ataque do outro ao entrar em
  batalha. Retorna [jogo mensagem-ou-nil]."
  [jogo]
  (let [x-intimida? (= "intimidate" (get-in jogo [:pokemons :x :habilidade]))
        o-intimida? (= "intimidate" (get-in jogo [:pokemons :o :habilidade]))
        reduzir     (fn [j marca] (update-in j [:pokemons marca :ataque] #(js/Math.round (* % fator-intimidacao))))]
    (cond
      (and x-intimida? o-intimida?)
      [(-> jogo (reduzir :o) (reduzir :x))
       (str "😤 *" (get-in jogo [:pokemons :x :nome]) "* e *" (get-in jogo [:pokemons :o :nome])
            "* se intimidaram mutuamente - ataque de ambos caiu!")]

      x-intimida?
      [(reduzir jogo :o)
       (str "😤 *" (get-in jogo [:pokemons :x :nome]) "* intimidou *" (get-in jogo [:pokemons :o :nome]) "*! Ataque reduzido.")]

      o-intimida?
      [(reduzir jogo :x)
       (str "😤 *" (get-in jogo [:pokemons :o :nome]) "* intimidou *" (get-in jogo [:pokemons :x :nome]) "*! Ataque reduzido.")]

      :else
      [jogo nil])))

(defn- anunciar-vitoria [cid jogo vencedor-marca motivo-extra]
  (swap! jogos dissoc cid)
  (rank/pontuar! cid (get-in jogo [:jogadores vencedor-marca]) (get-in jogo [:nomes vencedor-marca]) "pokemon")
  (str "\n\n🏆 " (get-in jogo [:nomes vencedor-marca]) " venceu" motivo-extra "!"))

(defn- resolver-ataque [atacante defensor defendendo? hp-atacante-atual]
  (let [esquivou?     (and defendendo? (< (rand-int 100) (chance-esquiva defensor)))
        especial?     (< (rand-int 100) chance-especial)
        poder-ataque  (if especial? (:atq-esp atacante) (:ataque atacante))
        poder-defesa  (if especial? (:def-esp defensor) (:defesa defensor))
        multiplicador (melhor-multiplicador (:tipos atacante) (:tipos defensor) (:habilidade defensor))
        critico?      (< (rand-int 100) chance-critico)
        impulso       (impulso-habilidade atacante hp-atacante-atual)
        fator         (* multiplicador impulso (if critico? 1.5 1))
        dano-com-tudo (* fator (calcular-dano poder-ataque poder-defesa))
        dano          (cond esquivou?             0
                             (zero? dano-com-tudo) 0
                             defendendo?           (max 1 (js/Math.round (/ dano-com-tudo 2)))
                             :else                 (max 1 (js/Math.round dano-com-tudo)))
        sufixo        (str (texto-efetividade multiplicador) (texto-critico critico?)
                            (texto-impulso impulso (:habilidade atacante)))
        mensagem      (cond
                        esquivou?
                        (str "💨 *" (:nome defensor) "* esquivou completamente do " (descricao-ataque especial?)
                             " de *" (:nome atacante) "*! Nenhum dano.")

                        (zero? dano)
                        (str (descricao-ataque especial?) " de *" (:nome atacante) "* não teve efeito em *"
                             (:nome defensor) "*! 🚫")

                        defendendo?
                        (str (descricao-ataque especial?) " de *" (:nome atacante) "*! *" (:nome defensor)
                             "* estava se defendendo e sofreu só " dano " de dano." sufixo)

                        :else
                        (str (descricao-ataque especial?) " de *" (:nome atacante) "* causou " dano
                             " de dano em *" (:nome defensor) "*!" sufixo))]
    {:dano dano :mensagem mensagem}))

(defn- criar-jogo [id nome pokemon]
  {:pokemons {:x pokemon}
   :jogadores {:x id}
   :nomes {:x nome}
   :hp {:x (:hp pokemon)}
   :defendendo {:x false}
   :status {:x nil}
   :vez :x})

(defn- legenda-pokemon [jogador-nome pokemon]
  (str jogador-nome " entrou com *" (:nome pokemon) "* (" (formatar-tipos (:tipos pokemon)) ")!\n"
       "❤️ HP: " (:hp pokemon) " | ⚔️ Ataque: " (:ataque pokemon) " | 🛡️ Defesa: " (:defesa pokemon) "\n"
       "🔮 Atq. Especial: " (:atq-esp pokemon) " | 🌀 Def. Especial: " (:def-esp pokemon)
       " | 💨 Velocidade: " (:veloc pokemon)))

(defn- enviar-imagem [message url legenda]
  (if url
    (-> (p/let [media (.fromUrl MessageMedia url)
                _     (.reply message media nil #js {:caption legenda})]
          nil)
        (p/catch (fn [err]
                   (js/console.error "Erro ao enviar imagem do pokemon:" err)
                   legenda)))
    (p/resolved legenda)))

(defn- iniciar-ou-entrar [message]
  (let [cid        (chat-id message)
        pid        (jogador-id message)
        jogo-atual (get @jogos cid)]
    (cond
      (and jogo-atual (contains? (:jogadores jogo-atual) :o))
      (p/resolved (str (cabecalho) "⏳ Já tem uma batalha rolando nesse chat entre "
                        (get-in jogo-atual [:nomes :x]) " e " (get-in jogo-atual [:nomes :o]) ".\n\n"
                        (mensagem-estado jogo-atual)))

      (and jogo-atual (= pid (get-in jogo-atual [:jogadores :x])))
      (p/resolved (str (cabecalho) "⏳ Você já abriu essa batalha, espere um adversário entrar de "
                        config/prefix "pokemon."))

      jogo-atual
      (-> (p/let [nome    (nome-de message)
                  pokemon (sortear-pokemon)]
            (let [jogo-pre (-> jogo-atual
                                (assoc-in [:jogadores :o] pid)
                                (assoc-in [:nomes :o] nome)
                                (assoc-in [:pokemons :o] pokemon)
                                (assoc-in [:hp :o] (:hp pokemon))
                                (assoc-in [:defendendo :o] false)
                                (assoc-in [:status :o] nil))
                  [jogo-novo msg-intimidacao] (aplicar-intimidacao jogo-pre)]
              (swap! jogos assoc cid jogo-novo)
              (enviar-imagem message (:imagem pokemon)
                              (str (cabecalho) (legenda-pokemon nome (get-in jogo-novo [:pokemons :o]))
                                   (when msg-intimidacao (str "\n\n" msg-intimidacao))
                                   "\n\n⚔️ Batalha começando!\n\n" (mensagem-estado jogo-novo)))))
          (p/catch (fn [err]
                     (js/console.error "Erro ao sortear pokemon:" err)
                     (str (cabecalho) "❌ Não consegui buscar um Pokémon agora (PokeAPI fora do ar?). Tente de novo."))))

      :else
      (-> (p/let [nome    (nome-de message)
                  pokemon (sortear-pokemon)]
            (let [jogo-novo (criar-jogo pid nome pokemon)]
              (swap! jogos assoc cid jogo-novo)
              (enviar-imagem message (:imagem pokemon)
                              (str (cabecalho) (legenda-pokemon nome pokemon) "\n\n"
                                   "Quem quiser topar a batalha, mande " config/prefix "pokemon pra entrar."))))
          (p/catch (fn [err]
                     (js/console.error "Erro ao sortear pokemon:" err)
                     (str (cabecalho) "❌ Não consegui buscar um Pokémon agora (PokeAPI fora do ar?). Tente de novo.")))))))

(defn- sair [message]
  (let [cid (chat-id message)]
    (if (get @jogos cid)
      (do (swap! jogos dissoc cid)
          (p/resolved (str (cabecalho) "🚪 Batalha cancelada.")))
      (p/resolved (str (cabecalho) "❓ Não tem nenhuma batalha rolando nesse chat.")))))

(defn- defender-turno [message]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (p/resolved
     (cond
       (nil? jogo)
       (str (cabecalho) "❓ Não tem batalha rolando. Digite " config/prefix "pokemon pra abrir uma.")

       (not (contains? (:jogadores jogo) :o))
       (str (cabecalho) "⏳ Ainda falta um adversário entrar. Digite " config/prefix "pokemon pra entrar.")

       (not= pid (get-in jogo [:jogadores (:vez jogo)]))
       (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo))

       :else
       (let [marca     (:vez jogo)
             alvo      (outro marca)
             pokemon   (get-in jogo [:pokemons marca])
             jogo-novo (-> jogo (assoc-in [:defendendo marca] true) (assoc :vez alvo))]
         (swap! jogos assoc cid jogo-novo)
         (str (cabecalho) "🛡️ *" (:nome pokemon) "* entrou em posição defensiva ("
              (chance-esquiva pokemon) "% de chance de esquivar do próximo ataque, dano reduzido "
              "pela metade se não esquivar)!\n\n" (mensagem-estado jogo-novo)))))))

(defn- atacar [message]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (p/resolved
     (cond
       (nil? jogo)
       (str (cabecalho) "❓ Não tem batalha rolando. Digite " config/prefix "pokemon pra abrir uma.")

       (not (contains? (:jogadores jogo) :o))
       (str (cabecalho) "⏳ Ainda falta um adversário entrar. Digite " config/prefix "pokemon pra entrar.")

       (not= pid (get-in jogo [:jogadores (:vez jogo)]))
       (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo))

       :else
       (let [atacante-marca  (:vez jogo)
             alvo-marca      (outro atacante-marca)
             atacante        (get-in jogo [:pokemons atacante-marca])
             defensor        (get-in jogo [:pokemons alvo-marca])
             status-atacante (get-in jogo [:status atacante-marca])]
         (if (paralisou-turno? status-atacante)
           (let [[jogo dot]  (aplicar-dot jogo atacante-marca)
                 hp-atacante (get-in jogo [:hp atacante-marca])
                 msg         (str "⚡ *" (:nome atacante) "* está paralisado e não conseguiu atacar!"
                                   (when (pos? dot) (str " Ainda assim sofreu " dot " de dano pelo status.")))]
             (if (zero? hp-atacante)
               (str (cabecalho) msg (anunciar-vitoria cid jogo alvo-marca " a batalha"))
               (let [jogo-novo (assoc jogo :vez alvo-marca)]
                 (swap! jogos assoc cid jogo-novo)
                 (str (cabecalho) msg "\n\n" (mensagem-estado jogo-novo)))))

           (let [defendendo?           (get-in jogo [:defendendo alvo-marca])
                 {:keys [dano mensagem]} (resolver-ataque atacante defensor defendendo?
                                                           (get-in jogo [:hp atacante-marca]))
                 jogo (update-in jogo [:hp alvo-marca] #(max 0 (- % dano)))]
             (if (zero? (get-in jogo [:hp alvo-marca]))
               (str (cabecalho) mensagem (anunciar-vitoria cid jogo atacante-marca " a batalha"))
               (let [status-defensor-atual (get-in jogo [:status alvo-marca])
                     novo-status           (when (pos? dano) (tentar-contagiar (:tipos atacante) status-defensor-atual))
                     jogo                  (cond-> jogo novo-status (assoc-in [:status alvo-marca] novo-status))
                     [jogo dot]            (aplicar-dot jogo atacante-marca)
                     msg-extra             (str (when novo-status (msg-status-aplicado novo-status (:nome defensor)))
                                                 (when (pos? dot)
                                                   (str "\n" (emoji-dot status-atacante) " *" (:nome atacante)
                                                        "* sofreu mais " dot " de dano pelo status.")))]
                 (if (zero? (get-in jogo [:hp atacante-marca]))
                   (str (cabecalho) mensagem msg-extra
                        (anunciar-vitoria cid jogo alvo-marca (str " - " (:nome atacante) " caiu por causa do próprio status")))
                   (let [jogo-novo (-> jogo (assoc-in [:defendendo alvo-marca] false) (assoc :vez alvo-marca))]
                     (swap! jogos assoc cid jogo-novo)
                     (str (cabecalho) mensagem msg-extra "\n\n" (mensagem-estado jogo-novo)))))))))))))

(defn jogar
  "!pokemon sem argumento abre/entra numa batalha; !pokemon atacar ataca;
  !pokemon defender entra em posição defensiva/evasiva; !pokemon sair
  cancela a batalha em andamento."
  [message args]
  (let [args (str/trim (str/lower-case (or args "")))]
    (cond
      (str/blank? args) (iniciar-ou-entrar message)
      (= args "sair") (sair message)
      (contains? #{"atacar" "ataque" "atirar"} args) (atacar message)
      (contains? #{"defender" "defesa" "esquivar" "evasiva"} args) (defender-turno message)
      :else (p/resolved (str (cabecalho) "❓ Use " config/prefix "pokemon (abrir/entrar), "
                              config/prefix "pokemon atacar, " config/prefix "pokemon defender ou "
                              config/prefix "pokemon sair.")))))

