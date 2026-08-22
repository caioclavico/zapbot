(ns zapbot.pokemon
  "Comando !pokemon - batalha entre duas pessoas do chat, cada uma com um
  Pokémon sorteado (nome, imagem, golpes reais e stats via PokeAPI - grátis,
  sem chave). Cada jogador escolhe qual dos 4 golpes do seu Pokémon usar a
  cada turno; ataques consideram o tipo/poder/classe do golpe escolhido,
  chance de crítico, status (paralisia/queimadura/veneno) e um punhado de
  habilidades icônicas. Estado guardado em memória por chat (não sobrevive
  a reinício do bot)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.rank :as rank]
            [zapbot.loja :as loja]
            [zapbot.treinador :as treinador]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

;; total de espécies conhecidas pela PokeAPI (até a geração 9)
(def ^:private total-pokemons 1025)

(defonce ^:private jogos (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- jogador-id [message]
  (or (.-author message) (.-from message)))

(defn- so-numero [id]
  (first (str/split id #"@")))

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

(def ^:private golpe-padrao
  {:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico})

(defn- buscar-golpe [nome]
  (-> (p/let [res  (js/fetch (str "https://pokeapi.co/api/v2/move/" nome))
              data (when (.-ok res) (.json res))]
        (when data
          (let [d      (js->clj data :keywordize-keys true)
                classe (get-in d [:damage_class :name])]
            (when (and (:power d) (contains? #{"physical" "special"} classe))
              {:nome-exibicao (->> (str/split (:name d) #"-") (map str/capitalize) (str/join " "))
               :tipo   (get-in d [:type :name])
               :poder  (:power d)
               :classe (if (= "physical" classe) :fisico :especial)}))))
      (p/catch (fn [_] nil))))

;; sorteia até 12 golpes do moveset real do Pokémon (via /move/<nome>, em
;; paralelo) e fica só com os 4 primeiros que causam dano de verdade (ignora
;; golpes de status, tipo Rugido, sem poder de ataque); se por azar/falha de
;; rede nenhum vier, cai num golpe genérico só pra a batalha não travar
(defn- escolher-golpes [moves-brutos]
  (let [candidatos (->> moves-brutos (map #(get-in % [:move :name])) shuffle (take 12))]
    (p/let [resultados (p/all (map buscar-golpe candidatos))]
      (let [validos (->> resultados (remove nil?) (take 4) vec)]
        (if (seq validos) validos [golpe-padrao])))))

(defn- com-golpes [pokemon]
  (p/let [golpes (escolher-golpes (:moves-brutos pokemon))]
    (-> pokemon (dissoc :moves-brutos) (assoc :golpes golpes))))

(defn- pokemon-de-dados [dados]
  {:nome         (str/capitalize (:name dados))
   :imagem       (or (get-in dados [:sprites :other :official-artwork :front_default])
                      (get-in dados [:sprites :front_default]))
   :tipos        (mapv #(get-in % [:type :name]) (:types dados))
   ;; prefere a habilidade "normal" (não-oculta); só cai pra
   ;; oculta se por algum motivo não houver nenhuma outra
   :habilidade   (or (some #(when-not (:is_hidden %) (get-in % [:ability :name])) (:abilities dados))
                      (get-in (first (:abilities dados)) [:ability :name]))
   :moves-brutos (:moves dados)
   :hp           (suavizar-stat (stat-base dados "hp"))
   :ataque       (suavizar-stat (stat-base dados "attack"))
   :defesa       (suavizar-stat (stat-base dados "defense"))
   :atq-esp      (suavizar-stat (stat-base dados "special-attack"))
   :def-esp      (suavizar-stat (stat-base dados "special-defense"))
   :veloc        (suavizar-stat (stat-base dados "speed"))})

(defn- sortear-pokemon []
  (let [id (inc (rand-int total-pokemons))]
    (p/let [res  (js/fetch (str "https://pokeapi.co/api/v2/pokemon/" id))
            data (.json res)]
      (pokemon-de-dados (js->clj data :keywordize-keys true)))))

(defn- buscar-pokemon-por-nome
  "Busca um pokémon específico pelo nome/slug da PokeAPI (ex.: \"charmander\"),
  em vez de sortear um id aleatório - usado pros iniciais."
  [nome]
  (p/let [res  (js/fetch (str "https://pokeapi.co/api/v2/pokemon/" nome))
          data (.json res)]
    (pokemon-de-dados (js->clj data :keywordize-keys true))))

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

(defn- emoji-golpe [golpe] (if (= :fisico (:classe golpe)) "💥" "🔮"))

(defn- linha-golpe [idx golpe tipos-defesa habilidade-defensor]
  (let [mult (multiplicador-vs-tipos (:tipo golpe) tipos-defesa habilidade-defensor)]
    (str (inc idx) ". " (emoji-golpe golpe) " *" (:nome-exibicao golpe) "* ("
         (get tipos-pt (:tipo golpe) (str/capitalize (:tipo golpe))) ", poder " (:poder golpe) ")"
         (cond (zero? mult) " 🚫" (> mult 1) " 🔥" (< mult 1) " 😕" :else ""))))

(defn- menu-golpes [pokemon tipos-defesa habilidade-defensor]
  (str/join "\n" (map-indexed #(linha-golpe %1 %2 tipos-defesa habilidade-defensor) (:golpes pokemon))))

(defn- poder-total [pokemon]
  (+ (:hp pokemon) (:ataque pokemon) (:defesa pokemon)
     (:atq-esp pokemon) (:def-esp pokemon) (:veloc pokemon)))

;; Caçada (!pokemon cacar): sorteia um selvagem com poder-total (acima)
;; próximo de um alvo calculado a partir do nível do jogador (derivado das
;; vitórias em !pokemon, ver zapbot.treinador) - mesmo padrão de reroll-até-
;; aproximar do balanceamento de PvP acima, só que mirando um alvo fixo em
;; vez de "perto do oponente".
(def ^:private poder-alvo-nivel-1 320)
(def ^:private incremento-poder-por-nivel 25)
(def ^:private tolerancia-poder-caca 60)
(def ^:private tentativas-caca 15)

(defn- poder-alvo-caca [nivel]
  (+ poder-alvo-nivel-1 (* incremento-poder-por-nivel (dec nivel))))

(defn- sortear-selvagem
  ([alvo] (sortear-selvagem alvo tentativas-caca nil))
  ([alvo tentativas-restantes melhor-ate-agora]
   (p/let [candidato (sortear-pokemon)]
     (let [diferenca (js/Math.abs (- (poder-total candidato) alvo))]
       (cond
         (<= diferenca tolerancia-poder-caca)
         candidato

         (zero? tentativas-restantes)
         (or melhor-ate-agora candidato)

         :else
         (let [melhor (if (or (nil? melhor-ate-agora)
                               (< diferenca (js/Math.abs (- (poder-total melhor-ate-agora) alvo))))
                         candidato
                         melhor-ate-agora)]
           (sortear-selvagem alvo (dec tentativas-restantes) melhor)))))))

;; chance de captura: quanto mais forte (poder-total) o selvagem, mais
;; difícil - referência em 450 (poder "neutro", ver suavizar-stat) pra 70%,
;; variando por ponto de poder acima/abaixo disso, sempre entre 15% e 90%
;; (nunca garantido, nunca impossível).
(def ^:private chance-captura-base 70)
(def ^:private chance-captura-por-poder 0.15)
(def ^:private poder-referencia-captura 450)
(def ^:private chance-captura-minima 15)
(def ^:private chance-captura-maxima 90)

(defn- chance-captura [pokemon]
  (-> (- chance-captura-base (* chance-captura-por-poder (- (poder-total pokemon) poder-referencia-captura)))
      js/Math.round
      (max chance-captura-minima)
      (min chance-captura-maxima)))

;; chance de acerto crítico (dano x1.5), independente do golpe escolhido
(def ^:private chance-critico 10)
;; chance de um golpe de tipo elegível (fogo/elétrico/venenoso) contagiar
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
  "Blaze/Torrent/Overgrow: +50% de dano quando o tipo do GOLPE escolhido bate
  com a habilidade e o HP atual já está a 1/3 ou menos do máximo."
  [atacante hp-atual tipo-golpe]
  (let [tipo-alvo (get habilidades-impulso-hp-baixo (:habilidade atacante))]
    (if (and tipo-alvo (= tipo-alvo tipo-golpe) (<= hp-atual (quot (:hp atacante) 3)))
      1.5
      1)))

(defn- emoji-status [status]
  (case status :queimado " 🔥" :envenenado " ☠️" :paralisado " ⚡" ""))

(defn- mensagem-estado [{:keys [pokemons nomes vez hp defendendo status jogadores]}]
  (let [meu        (get pokemons vez)
        adversario (get pokemons (outro vez))]
    (str "🐾 " (get nomes :x) " - *" (get-in pokemons [:x :nome]) "*" (when (:x defendendo) " 🛡️")
         (emoji-status (:x status)) "\n"
         (barra-hp (get hp :x) (get-in pokemons [:x :hp])) "\n\n"
         "🐾 " (get nomes :o) " - *" (get-in pokemons [:o :nome]) "*" (when (:o defendendo) " 🛡️")
         (emoji-status (:o status)) "\n"
         (barra-hp (get hp :o) (get-in pokemons [:o :hp])) "\n\n"
         "Vez de " (get nomes vez) " (@" (so-numero (get jogadores vez)) ") - escolha um golpe:\n"
         (menu-golpes meu (:tipos adversario) (:habilidade adversario))
         "\n\nUse " config/prefix "pokemon atacar <número>, defenda com " config/prefix
         "pokemon defender, cure um status com " config/prefix "pokemon curar, ou recupere HP com "
         config/prefix "pokemon pocao (compre curas/poções na " config/prefix "loja)")))

;; comandos normais do router resolvem uma string simples (ver zapbot.core);
;; aqui a gente precisa marcar quem tem que jogar, então resolve um mapa
;; {:texto :mentions} pra zapbot.core saber que precisa passar :mentions
;; pro .reply do whatsapp-web.js (só o texto "@numero" não vira menção de verdade).
(defn- com-mencao [jogo texto]
  {:texto texto :mentions [(get-in jogo [:jogadores (:vez jogo)])]})

(defn- chance-esquiva [pokemon]
  (min 50 (quot (:veloc pokemon) 2)))

;; a fórmula oficial de dano (a base do "42/50" abaixo) pressupõe um HP de
;; Pokémon nível 100 de verdade, que escala muito com o nível; aqui o HP é
;; só o stat base suavizado direto (sem nível), bem menor - por isso usa um
;; fator bem menor, calibrado pra um golpe típico bater uns 10-20% do HP em
;; vez de quase o HP inteiro de uma vez só.
(def ^:private fator-dano 0.22)

(defn- calcular-dano [poder-golpe poder-ataque poder-defesa]
  (max 1 (+ (js/Math.round (* fator-dano poder-golpe (/ poder-ataque poder-defesa))) (rand-int 11))))

(defn- paralisou-turno? [status]
  (and (= status :paralisado) (< (rand-int 100) chance-paralisia-trava)))

(defn- dano-por-status [status hp-maximo]
  (case status
    :queimado   (max 1 (quot hp-maximo 16))
    :envenenado (max 1 (quot hp-maximo 8))
    0))

(defn- tentar-contagiar [tipo-golpe status-atual-defensor]
  (when (and (nil? status-atual-defensor) (< (rand-int 100) chance-contagio))
    (get tipo->status tipo-golpe)))

(defn- msg-status-aplicado [status nome]
  (case status
    :queimado   (str "\n🔥 *" nome "* se queimou!")
    :envenenado (str "\n☠️ *" nome "* foi envenenado!")
    :paralisado (str "\n⚡ *" nome "* ficou paralisado!")
    ""))

(defn- emoji-dot [status] (case status :queimado "🔥" :envenenado "☠️" "❓"))

(defn- nome-status [status]
  (case status :queimado "queimadura" :envenenado "veneno" :paralisado "paralisia" "status"))

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

;; escreve o hp/status atual de cada lado de volta na equipe (zapbot.treinador)
;; do respectivo dono - chamar sempre que `jogo` mudar, senão dano/cura/status
;; não sobrevivem entre batalhas (o objetivo inteiro de ter equipe persistida)
(defn- sincronizar-equipe! [cid jogo]
  (doseq [marca [:x :o]]
    (when-let [pid (get-in jogo [:jogadores marca])]
      (treinador/atualizar-ativo! cid pid (get-in jogo [:hp marca]) (get-in jogo [:status marca])))))

(defn- anunciar-vitoria [cid jogo vencedor-marca motivo-extra]
  (sincronizar-equipe! cid jogo)
  (swap! jogos dissoc cid)
  (rank/pontuar! cid (get-in jogo [:jogadores vencedor-marca]) (get-in jogo [:nomes vencedor-marca]) "pokemon")
  (let [ganho (loja/creditar! cid (get-in jogo [:jogadores vencedor-marca]))]
    (str "\n\n🏆 " (get-in jogo [:nomes vencedor-marca]) " venceu" motivo-extra "! (+" ganho " 💰 moedas, confira com "
         config/prefix "loja)")))

(defn- resolver-ataque [golpe atacante defensor defendendo? hp-atacante-atual]
  (let [esquivou?     (and defendendo? (< (rand-int 100) (chance-esquiva defensor)))
        fisico?       (= :fisico (:classe golpe))
        poder-ataque  (if fisico? (:ataque atacante) (:atq-esp atacante))
        poder-defesa  (if fisico? (:defesa defensor) (:def-esp defensor))
        multiplicador (multiplicador-vs-tipos (:tipo golpe) (:tipos defensor) (:habilidade defensor))
        critico?      (< (rand-int 100) chance-critico)
        impulso       (impulso-habilidade atacante hp-atacante-atual (:tipo golpe))
        fator         (* multiplicador impulso (if critico? 1.5 1))
        dano-com-tudo (* fator (calcular-dano (:poder golpe) poder-ataque poder-defesa))
        dano          (cond esquivou?             0
                             (zero? dano-com-tudo) 0
                             defendendo?           (max 1 (js/Math.round (/ dano-com-tudo 2)))
                             :else                 (max 1 (js/Math.round dano-com-tudo)))
        nome-golpe    (str (emoji-golpe golpe) " *" (:nome-exibicao golpe) "*")
        sufixo        (str (texto-efetividade multiplicador) (texto-critico critico?)
                            (texto-impulso impulso (:habilidade atacante)))
        mensagem      (cond
                        esquivou?
                        (str "💨 *" (:nome defensor) "* esquivou completamente de " nome-golpe
                             " de *" (:nome atacante) "*! Nenhum dano.")

                        (zero? dano)
                        (str nome-golpe " de *" (:nome atacante) "* não teve efeito em *"
                             (:nome defensor) "*! 🚫")

                        defendendo?
                        (str nome-golpe " de *" (:nome atacante) "*! *" (:nome defensor)
                             "* estava se defendendo e sofreu só " dano " de dano." sufixo)

                        :else
                        (str nome-golpe " de *" (:nome atacante) "* causou " dano
                             " de dano em *" (:nome defensor) "*!" sufixo))]
    {:dano dano :mensagem mensagem}))

(defn- criar-jogo [id nome pokemon hp-atual status]
  {:pokemons {:x pokemon}
   :jogadores {:x id}
   :nomes {:x nome}
   :hp {:x hp-atual}
   :defendendo {:x false}
   :status {:x status}
   :vez :x})

(defn- legenda-pokemon [jogador-nome pokemon]
  (str jogador-nome " entrou com *" (:nome pokemon) "* (" (formatar-tipos (:tipos pokemon)) ")!\n"
       "❤️ HP: " (:hp pokemon) " | ⚔️ Ataque: " (:ataque pokemon) " | 🛡️ Defesa: " (:defesa pokemon) "\n"
       "🔮 Atq. Especial: " (:atq-esp pokemon) " | 🌀 Def. Especial: " (:def-esp pokemon)
       " | 💨 Velocidade: " (:veloc pokemon) "\n"
       "🎯 Golpes: " (str/join ", " (map :nome-exibicao (:golpes pokemon)))))

(defn- enviar-imagem
  ([message url legenda] (enviar-imagem message url legenda []))
  ([message url legenda mentions]
   (if url
     (-> (p/let [media (.fromUrl MessageMedia url)
                 _     (.reply message media nil #js {:caption legenda :mentions (clj->js mentions)})]
           nil)
         (p/catch (fn [err]
                    (js/console.error "Erro ao enviar imagem do pokemon:" err)
                    legenda)))
     (p/resolved legenda))))

(defn- tentar-registrar!
  "Tenta gravar jogo-novo em `jogos` pro chat `cid`, mas só se `valido?`
  (recebendo o estado atual desse chat, possivelmente nil) aprovar - evita
  que duas mensagens concorrentes (ex.: duas pessoas tentando abrir/entrar
  quase ao mesmo tempo) se baseiem no mesmo estado antigo lido antes da
  parte lenta (rede: PokeAPI/getContact) da jogada. Retorna true se gravou,
  false se `valido?` recusou (nesse caso jogo-novo é descartado)."
  [cid jogo-novo valido?]
  (swap! jogos (fn [estado] (if (valido? (get estado cid)) (assoc estado cid jogo-novo) estado)))
  (= jogo-novo (get @jogos cid)))

(defn- iniciar-ou-entrar [message]
  (let [cid        (chat-id message)
        pid        (jogador-id message)
        jogo-atual (get @jogos cid)]
    (cond
      (and jogo-atual (contains? (:jogadores jogo-atual) :o))
      (p/resolved (com-mencao jogo-atual
                    (str (cabecalho) "⏳ Já tem uma batalha rolando nesse chat entre "
                         (get-in jogo-atual [:nomes :x]) " e " (get-in jogo-atual [:nomes :o]) ".\n\n"
                         (mensagem-estado jogo-atual))))

      (and jogo-atual (= pid (get-in jogo-atual [:jogadores :x])))
      (p/resolved (str (cabecalho) "⏳ Você já abriu essa batalha, espere um adversário entrar de "
                        config/prefix "pokemon."))

      (not (treinador/tem-pokemon? cid pid))
      (p/resolved (str (cabecalho) "❓ Escolha seu pokémon inicial primeiro: " config/prefix "pokemon inicial."))

      :else
      (let [[pokemon hp-atual status] (treinador/pokemon-ativo cid pid)]
        (cond
          (<= hp-atual 0)
          (p/resolved (str (cabecalho) "😵 *" (:nome pokemon) "* desmaiou e não pode batalhar! Cure com "
                            config/prefix "pokemon pocao (fora de uma batalha) antes de tentar de novo."))

          jogo-atual
          (-> (p/let [nome (nome-de message)]
                (let [jogo-pre (-> jogo-atual
                                    (assoc-in [:jogadores :o] pid)
                                    (assoc-in [:nomes :o] nome)
                                    (assoc-in [:pokemons :o] pokemon)
                                    (assoc-in [:hp :o] hp-atual)
                                    (assoc-in [:defendendo :o] false)
                                    (assoc-in [:status :o] status)
                                    ;; sorteia quem ataca primeiro em vez de sempre favorecer quem abriu a batalha
                                    (assoc :vez (rand-nth [:x :o])))
                      [jogo-novo msg-intimidacao] (aplicar-intimidacao jogo-pre)]
                  (if (tentar-registrar! cid jogo-novo (fn [atual] (and atual (not (contains? (:jogadores atual) :o)))))
                    (enviar-imagem message (:imagem pokemon)
                                    (str (cabecalho) (legenda-pokemon nome (get-in jogo-novo [:pokemons :o]))
                                         (when msg-intimidacao (str "\n\n" msg-intimidacao))
                                         "\n\n⚔️ Batalha começando! 🎲 " (get-in jogo-novo [:nomes (:vez jogo-novo)])
                                         " tira a sorte e ataca primeiro!\n\n" (mensagem-estado jogo-novo))
                                    [(get-in jogo-novo [:jogadores (:vez jogo-novo)])])
                    (str (cabecalho) "⏳ Alguém mais rápido já entrou nessa batalha um instante antes de você. Digite "
                         config/prefix "pokemon pra ver o que rolou ou abrir uma nova."))))
              (p/catch (fn [err]
                         (js/console.error "Erro ao entrar na batalha:" err)
                         (str (cabecalho) "❌ Deu algo errado ao entrar na batalha. Tente de novo."))))

          :else
          (-> (p/let [nome (nome-de message)]
                (let [jogo-novo (criar-jogo pid nome pokemon hp-atual status)]
                  (if (tentar-registrar! cid jogo-novo nil?)
                    (enviar-imagem message (:imagem pokemon)
                                    (str (cabecalho) (legenda-pokemon nome pokemon) "\n\n"
                                         "Quem quiser topar a batalha, mande " config/prefix "pokemon pra entrar."))
                    (str (cabecalho) "⏳ Alguém abriu uma batalha nesse chat um instante antes de você. Digite "
                         config/prefix "pokemon pra entrar nela."))))
              (p/catch (fn [err]
                         (js/console.error "Erro ao abrir batalha:" err)
                         (str (cabecalho) "❌ Deu algo errado ao abrir a batalha. Tente de novo.")))))))))

(defn- sair [message]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (cond
      (nil? jogo)
      (p/resolved (str (cabecalho) "❓ Não tem nenhuma batalha rolando nesse chat."))

      ;; ainda não tem os 2 jogadores - cancela sem custo, não há adversário a beneficiar
      (not (contains? (:jogadores jogo) :o))
      (do (swap! jogos dissoc cid)
          (p/resolved (str (cabecalho) "🚪 Batalha cancelada.")))

      :else
      (let [marca-saiu (some #(when (= pid (get-in jogo [:jogadores %])) %) [:x :o])]
        (p/resolved
         (if (nil? marca-saiu)
           (str (cabecalho) "❓ Você não faz parte dessa batalha (só quem está jogando pode sair dela).")
           (str (cabecalho) "🚪 *" (get-in jogo [:nomes marca-saiu]) "* fugiu da batalha!"
                (anunciar-vitoria cid jogo (outro marca-saiu) " por desistência"))))))))

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
       (com-mencao jogo (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo)))

       :else
       (let [marca     (:vez jogo)
             alvo      (outro marca)
             pokemon   (get-in jogo [:pokemons marca])
             jogo-novo (-> jogo (assoc-in [:defendendo marca] true) (assoc :vez alvo))]
         (swap! jogos assoc cid jogo-novo)
         (sincronizar-equipe! cid jogo-novo)
         (com-mencao jogo-novo
           (str (cabecalho) "🛡️ *" (:nome pokemon) "* entrou em posição defensiva ("
                (chance-esquiva pokemon) "% de chance de esquivar do próximo ataque, dano reduzido "
                "pela metade se não esquivar)!\n\n" (mensagem-estado jogo-novo))))))))

(defn- jogador-na-batalha? [jogo pid]
  (and jogo (some #(= pid (get-in jogo [:jogadores %])) [:x :o])))

(defn- curar-fora-de-batalha [cid pid]
  (if-let [[pokemon hp-atual status] (treinador/pokemon-ativo cid pid)]
    (cond
      (nil? status)
      (str (cabecalho) "❓ *" (:nome pokemon) "* não tem nenhum status pra curar agora.")

      (not (loja/usar-cura! cid pid status))
      (str (cabecalho) "❌ Você não tem uma cura de " (nome-status status) " no inventário (compre na "
           config/prefix "loja).")

      :else
      (do (treinador/atualizar-ativo! cid pid hp-atual nil)
          (str (cabecalho) "💊 *" (:nome pokemon) "* usou uma cura e se livrou de " (nome-status status) "!")))
    (str (cabecalho) "❓ Escolha seu pokémon inicial primeiro: " config/prefix "pokemon inicial.")))

(defn- pocao-fora-de-batalha [cid pid]
  (if-let [[pokemon hp-atual status] (treinador/pokemon-ativo cid pid)]
    (let [hp-max (:hp pokemon)]
      (cond
        (>= hp-atual hp-max)
        (str (cabecalho) "❓ *" (:nome pokemon) "* já está com HP cheio.")

        :else
        (if-let [fracao (loja/usar-pocao! cid pid)]
          (let [cura    (js/Math.round (* fracao hp-max))
                hp-novo (min hp-max (+ hp-atual cura))]
            (treinador/atualizar-ativo! cid pid hp-novo status)
            (str (cabecalho) "🧪 *" (:nome pokemon) "* usou uma Poção de Vida e recuperou "
                 (- hp-novo hp-atual) " de HP! (" hp-novo "/" hp-max ")"))
          (str (cabecalho) "❌ Você não tem uma Poção de Vida no inventário (compre na " config/prefix "loja)."))))
    (str (cabecalho) "❓ Escolha seu pokémon inicial primeiro: " config/prefix "pokemon inicial.")))
(defn- curar-turno [message]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (if-not (jogador-na-batalha? jogo pid)
      (p/resolved (curar-fora-de-batalha cid pid))
      (p/resolved
       (cond
         (not (contains? (:jogadores jogo) :o))
         (str (cabecalho) "⏳ Ainda falta um adversário entrar. Digite " config/prefix "pokemon pra entrar.")

         (not= pid (get-in jogo [:jogadores (:vez jogo)]))
         (com-mencao jogo (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo)))

         :else
         (let [marca        (:vez jogo)
               status-atual (get-in jogo [:status marca])
               pokemon      (get-in jogo [:pokemons marca])]
           (cond
             (nil? status-atual)
             (com-mencao jogo (str (cabecalho) "❓ *" (:nome pokemon) "* não tem nenhum status pra curar agora.\n\n"
                                    (mensagem-estado jogo)))

             (not (loja/usar-cura! cid pid status-atual))
             (com-mencao jogo (str (cabecalho) "❌ Você não tem uma cura de " (nome-status status-atual)
                                    " no inventário (compre na " config/prefix "loja).\n\n" (mensagem-estado jogo)))

             :else
             (let [alvo      (outro marca)
                   jogo-novo (-> jogo (assoc-in [:status marca] nil) (assoc :vez alvo))]
               (swap! jogos assoc cid jogo-novo)
               (sincronizar-equipe! cid jogo-novo)
               (com-mencao jogo-novo
                 (str (cabecalho) "💊 *" (:nome pokemon) "* usou uma cura e se livrou de "
                      (nome-status status-atual) "!\n\n" (mensagem-estado jogo-novo)))))))))))

(defn- pocao-turno [message]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (if-not (jogador-na-batalha? jogo pid)
      (p/resolved (pocao-fora-de-batalha cid pid))
      (p/resolved
       (cond
         (not (contains? (:jogadores jogo) :o))
         (str (cabecalho) "⏳ Ainda falta um adversário entrar. Digite " config/prefix "pokemon pra entrar.")

         (not= pid (get-in jogo [:jogadores (:vez jogo)]))
         (com-mencao jogo (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo)))

         :else
         (let [marca    (:vez jogo)
               pokemon  (get-in jogo [:pokemons marca])
               hp-max   (:hp pokemon)
               hp-atual (get-in jogo [:hp marca])]
           (cond
             (>= hp-atual hp-max)
             (com-mencao jogo (str (cabecalho) "❓ *" (:nome pokemon) "* já está com HP cheio.\n\n"
                                    (mensagem-estado jogo)))

             :else
             (if-let [fracao (loja/usar-pocao! cid pid)]
               (let [cura      (js/Math.round (* fracao hp-max))
                     hp-novo   (min hp-max (+ hp-atual cura))
                     alvo      (outro marca)
                     jogo-novo (-> jogo (assoc-in [:hp marca] hp-novo) (assoc :vez alvo))]
                 (swap! jogos assoc cid jogo-novo)
                 (sincronizar-equipe! cid jogo-novo)
                 (com-mencao jogo-novo
                   (str (cabecalho) "🧪 *" (:nome pokemon) "* usou uma Poção de Vida e recuperou "
                        (- hp-novo hp-atual) " de HP!\n\n" (mensagem-estado jogo-novo))))
               (com-mencao jogo (str (cabecalho) "❌ Você não tem uma Poção de Vida no inventário (compre na "
                                      config/prefix "loja).\n\n" (mensagem-estado jogo))))))))))) 

(defn- parse-indice-golpe [texto total]
  (let [n (js/parseInt texto 10)]
    (when (and (not (js/isNaN n)) (<= 1 n total))
      (dec n))))

(def ^:private iniciais
  [{:slug "bulbasaur"  :emoji "🌱"}
   {:slug "charmander" :emoji "🔥"}
   {:slug "squirtle"   :emoji "💧"}])

(defn- texto-iniciais []
  (str/join "\n" (map-indexed (fn [i {:keys [slug emoji]}]
                                 (str (inc i) ". " emoji " " (str/capitalize slug)))
                               iniciais)))

(defn- escolher-inicial [message indice-texto]
  (let [cid (chat-id message)
        pid (jogador-id message)]
    (if (treinador/tem-pokemon? cid pid)
      (p/resolved (str (cabecalho) "❓ Você já tem um time. Use " config/prefix "pokemon time pra ver, ou "
                        config/prefix "pokemon escolher <número> pra trocar o ativo."))
      (let [indice (parse-indice-golpe indice-texto (count iniciais))]
        (if (nil? indice)
          (p/resolved (str (cabecalho) "🌟 *Escolha seu pokémon inicial:*\n\n" (texto-iniciais)
                            "\n\nUse " config/prefix "pokemon inicial <número>."))
          (-> (p/let [pokemon (buscar-pokemon-por-nome (:slug (nth iniciais indice)))
                      pokemon (com-golpes pokemon)]
                (treinador/adicionar-pokemon! cid pid pokemon (:hp pokemon) nil)
                (enviar-imagem message (:imagem pokemon)
                                (str (cabecalho) "🎉 Você escolheu *" (:nome pokemon) "* como seu inicial!\n\n"
                                     (legenda-pokemon "Seu time" pokemon) "\n\nUse " config/prefix
                                     "pokemon pra batalhar, ou " config/prefix "pokemon cacar pra capturar mais.")))
              (p/catch (fn [err]
                         (js/console.error "Erro ao escolher inicial:" err)
                         (str (cabecalho) "❌ Não consegui buscar esse pokémon agora. Tente de novo.")))))))))

(defn- ver-time [message]
  (let [cid (chat-id message)
        pid (jogador-id message)
        eq  (treinador/equipe cid pid)]
    (p/resolved
     (if (empty? eq)
       (str (cabecalho) "❓ Você ainda não tem nenhum pokémon. Use " config/prefix "pokemon inicial pra escolher o seu.")
       (str (cabecalho) "🎒 *Seu time:*\n\n"
            (str/join "\n" (map-indexed
                             (fn [i registro]
                               (let [[p hp-atual status] (treinador/registro->pokemon registro)]
                                 (str (inc i) ". " (if (= i (treinador/indice-ativo cid pid)) "👉 " "") "*" (:nome p)
                                      "* " (barra-hp hp-atual (:hp p)) (emoji-status status))))
                             eq))
            "\n\nUse " config/prefix "pokemon escolher <número> pra trocar o ativo (👉).")))))

(defn- escolher-ativo [message indice-texto]
  (let [cid   (chat-id message)
        pid   (jogador-id message)
        total (count (treinador/equipe cid pid))]
    (p/resolved
     (if (zero? total)
       (str (cabecalho) "❓ Você ainda não tem nenhum pokémon. Use " config/prefix "pokemon inicial.")
       (let [indice (parse-indice-golpe indice-texto total)]
         (if (nil? indice)
           (str (cabecalho) "❓ Escolha um número válido: " config/prefix "pokemon escolher <1-" total
                "> (veja com " config/prefix "pokemon time).")
           (do (treinador/definir-ativo! cid pid indice)
               (let [[p _ _] (treinador/registro->pokemon (nth (treinador/equipe cid pid) indice))]
                 (str (cabecalho) "✅ *" (:nome p) "* agora é seu pokémon ativo!")))))))))

(defn- cacar [message]
  (let [cid (chat-id message)
        pid (jogador-id message)]
    (cond
      (not (treinador/tem-pokemon? cid pid))
      (p/resolved (str (cabecalho) "❓ Escolha seu pokémon inicial primeiro: " config/prefix "pokemon inicial."))

      (not (treinador/pode-cacar? cid pid))
      (p/resolved (str (cabecalho) "⏳ Calma aí! Você pode caçar de novo em "
                        (treinador/segundos-restantes-cooldown cid pid) "s."))

      :else
      (let [nivel (treinador/nivel-jogador cid pid)]
        (-> (p/let [selvagem (sortear-selvagem (poder-alvo-caca nivel))
                    selvagem (com-golpes selvagem)]
              (treinador/registrar-cacada! cid pid)
              (let [chance    (chance-captura selvagem)
                    capturou? (< (rand-int 100) chance)]
                (if capturou?
                  (let [idx (treinador/adicionar-pokemon! cid pid selvagem (:hp selvagem) nil)]
                    (enviar-imagem message (:imagem selvagem)
                                    (str (cabecalho) "🎯 Um *" (:nome selvagem) "* selvagem apareceu! ("
                                         chance "% de chance de captura)\n\n✅ Capturado! Adicionado ao seu time (nº "
                                         (inc idx) "). Use " config/prefix "pokemon escolher " (inc idx)
                                         " pra deixar ele ativo.")))
                  (enviar-imagem message (:imagem selvagem)
                                  (str (cabecalho) "🎯 Um *" (:nome selvagem) "* selvagem apareceu! ("
                                       chance "% de chance de captura)\n\n💨 Escapou! Tente de novo em "
                                       treinador/cooldown-cacada-minutos " minutos.")))))
            (p/catch (fn [err]
                       (js/console.error "Erro ao caçar pokemon:" err)
                       (str (cabecalho) "❌ Não consegui buscar um pokémon selvagem agora. Tente de novo."))))))))

(defn- atacar [message indice-texto]
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
       (com-mencao jogo (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo)))

       :else
       (let [atacante-marca  (:vez jogo)
             alvo-marca      (outro atacante-marca)
             atacante        (get-in jogo [:pokemons atacante-marca])
             defensor        (get-in jogo [:pokemons alvo-marca])
             status-atacante (get-in jogo [:status atacante-marca])
             indice          (parse-indice-golpe indice-texto (count (:golpes atacante)))]
         (cond
           (nil? indice)
           (com-mencao jogo (str (cabecalho) "❓ Escolha um golpe válido: " config/prefix "pokemon atacar <1-"
                                  (count (:golpes atacante)) ">\n\n" (mensagem-estado jogo)))

           (paralisou-turno? status-atacante)
           (let [[jogo dot]  (aplicar-dot jogo atacante-marca)
                 hp-atacante (get-in jogo [:hp atacante-marca])
                 msg         (str "⚡ *" (:nome atacante) "* está paralisado e não conseguiu atacar!"
                                   (when (pos? dot) (str " Ainda assim sofreu " dot " de dano pelo status.")))]
             (if (zero? hp-atacante)
               (str (cabecalho) msg (anunciar-vitoria cid jogo alvo-marca " a batalha"))
               (let [jogo-novo (assoc jogo :vez alvo-marca)]
                 (swap! jogos assoc cid jogo-novo)
                 (sincronizar-equipe! cid jogo-novo)
                 (com-mencao jogo-novo (str (cabecalho) msg "\n\n" (mensagem-estado jogo-novo))))))

           :else
           (let [golpe                   (nth (:golpes atacante) indice)
                 defendendo?             (get-in jogo [:defendendo alvo-marca])
                 {:keys [dano mensagem]} (resolver-ataque golpe atacante defensor defendendo?
                                                           (get-in jogo [:hp atacante-marca]))
                 jogo (update-in jogo [:hp alvo-marca] #(max 0 (- % dano)))]
             (if (zero? (get-in jogo [:hp alvo-marca]))
               (str (cabecalho) mensagem (anunciar-vitoria cid jogo atacante-marca " a batalha"))
               (let [status-defensor-atual (get-in jogo [:status alvo-marca])
                     novo-status           (when (pos? dano) (tentar-contagiar (:tipo golpe) status-defensor-atual))
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
                     (sincronizar-equipe! cid jogo-novo)
                     (com-mencao jogo-novo (str (cabecalho) mensagem msg-extra "\n\n" (mensagem-estado jogo-novo))))))))))))))

(defn jogar
  "!pokemon inicial <1-3> escolhe seu pokémon inicial (obrigatório antes de
  batalhar/caçar); !pokemon cacar tenta encontrar e capturar um pokémon
  selvagem (nível calibrado pelas suas vitórias em !pokemon); !pokemon time
  mostra seu time capturado; !pokemon escolher <número> troca qual está
  ativo pra batalhar; !pokemon sem argumento abre/entra numa batalha (usa
  seu pokémon ativo); !pokemon atacar <1-4> usa o golpe correspondente (ver
  o menu de golpes em cada mensagem de estado); !pokemon defender entra em
  posição defensiva/evasiva; !pokemon curar usa uma cura do inventário (ver
  !loja) pro status atual (dentro ou fora de uma batalha); !pokemon pocao
  usa uma Poção de Vida do inventário pra recuperar HP (dentro ou fora de
  uma batalha); !pokemon sair cancela (se só um jogador entrou ainda) ou
  desiste - contando a vitória pro adversário - se a batalha já tiver os 2
  jogadores."
  [message args]
  (let [args         (str/trim (str/lower-case (or args "")))
        [cmd & resto] (str/split args #"\s+")]
    (cond
      (str/blank? args) (iniciar-ou-entrar message)
      (= cmd "sair") (sair message)
      (contains? #{"inicial" "iniciais"} cmd) (escolher-inicial message (first resto))
      (contains? #{"cacar" "caçar"} cmd) (cacar message)
      (contains? #{"time" "equipe"} cmd) (ver-time message)
      (= cmd "escolher") (escolher-ativo message (first resto))
      (contains? #{"atacar" "ataque" "atirar" "usar"} cmd) (atacar message (first resto))
      (contains? #{"defender" "defesa" "esquivar" "evasiva"} cmd) (defender-turno message)
      (contains? #{"curar" "cura"} cmd) (curar-turno message)
      (contains? #{"pocao" "poção" "vida"} cmd) (pocao-turno message)
      :else (p/resolved (str (cabecalho) "❓ Use " config/prefix "pokemon inicial, " config/prefix "pokemon cacar, "
                              config/prefix "pokemon time, " config/prefix "pokemon escolher <número>, "
                              config/prefix "pokemon (abrir/entrar), " config/prefix "pokemon atacar <1-4>, "
                              config/prefix "pokemon defender, " config/prefix "pokemon curar, "
                              config/prefix "pokemon pocao ou " config/prefix "pokemon sair.")))))


