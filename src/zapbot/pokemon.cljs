(ns zapbot.pokemon
  "Comando !pokemon - batalha entre duas pessoas do chat, cada uma com um
  Pokémon sorteado (nome, imagem e stats via PokeAPI - grátis, sem chave).
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

(defn- sortear-pokemon []
  (let [id (inc (rand-int total-pokemons))]
    (-> (js/fetch (str "https://pokeapi.co/api/v2/pokemon/" id))
        (p/then (fn [res] (.json res)))
        (p/then (fn [data]
                  (let [dados (js->clj data :keywordize-keys true)]
                    {:nome   (str/capitalize (:name dados))
                     :imagem (or (get-in dados [:sprites :other :official-artwork :front_default])
                                 (get-in dados [:sprites :front_default]))
                     :hp     (stat-base dados "hp")
                     :ataque (stat-base dados "attack")
                     :defesa (stat-base dados "defense")
                     :veloc  (stat-base dados "speed")}))))))

(defn- cabecalho []
  (str "⚡ *Batalha Pokémon do tio " config/bot-name "*\n\n"))

(defn- barra-hp [atual maximo]
  (let [cheios (js/Math.round (* 10 (max 0 (/ atual maximo))))]
    (str "[" (apply str (repeat cheios "█")) (apply str (repeat (- 10 cheios) "░")) "] "
         (max 0 atual) "/" maximo)))

(defn- mensagem-estado [{:keys [pokemons nomes vez hp defendendo]}]
  (str "🐾 " (get nomes :x) " - *" (get-in pokemons [:x :nome]) "*" (when (:x defendendo) " 🛡️") "\n"
       (barra-hp (get hp :x) (get-in pokemons [:x :hp])) "\n\n"
       "🐾 " (get nomes :o) " - *" (get-in pokemons [:o :nome]) "*" (when (:o defendendo) " 🛡️") "\n"
       (barra-hp (get hp :o) (get-in pokemons [:o :hp])) "\n\n"
       "Vez de " (get nomes vez) " - ataque com " config/prefix "pokemon atacar ou defenda com "
       config/prefix "pokemon defender"))

(defn- outro [marca] (if (= marca :x) :o :x))

(defn- chance-esquiva [pokemon]
  (min 50 (quot (:veloc pokemon) 2)))

(defn- criar-jogo [id nome pokemon]
  {:pokemons {:x pokemon}
   :jogadores {:x id}
   :nomes {:x nome}
   :hp {:x (:hp pokemon)}
   :defendendo {:x false}
   :vez :x})

(defn- legenda-pokemon [jogador-nome pokemon]
  (str jogador-nome " entrou com *" (:nome pokemon) "*!\n"
       "❤️ HP: " (:hp pokemon) " | ⚔️ Ataque: " (:ataque pokemon)
       " | 🛡️ Defesa: " (:defesa pokemon) " | 💨 Velocidade: " (:veloc pokemon)))

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
            (let [jogo-novo (-> jogo-atual
                                 (assoc-in [:jogadores :o] pid)
                                 (assoc-in [:nomes :o] nome)
                                 (assoc-in [:pokemons :o] pokemon)
                                 (assoc-in [:hp :o] (:hp pokemon))
                                 (assoc-in [:defendendo :o] false))]
              (swap! jogos assoc cid jogo-novo)
              (enviar-imagem message (:imagem pokemon)
                              (str (cabecalho) (legenda-pokemon nome pokemon)
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

(defn- calcular-dano [atacante defensor]
  (max 1 (+ (- (:ataque atacante) (quot (:defesa defensor) 2)) (rand-int 11))))

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
       (let [atacante-marca (:vez jogo)
             alvo-marca     (outro atacante-marca)
             atacante       (get-in jogo [:pokemons atacante-marca])
             defensor       (get-in jogo [:pokemons alvo-marca])
             defendendo?    (get-in jogo [:defendendo alvo-marca])
             esquivou?      (and defendendo? (< (rand-int 100) (chance-esquiva defensor)))
             dano-base      (calcular-dano atacante defensor)
             dano           (cond esquivou? 0
                                   defendendo? (max 1 (quot dano-base 2))
                                   :else dano-base)
             hp-novo        (max 0 (- (get-in jogo [:hp alvo-marca]) dano))
             jogo           (-> jogo
                                 (assoc-in [:hp alvo-marca] hp-novo)
                                 (assoc-in [:defendendo alvo-marca] false))
             msg-ataque     (cond
                              esquivou?
                              (str "💨 *" (:nome defensor) "* esquivou completamente do ataque de *"
                                   (:nome atacante) "*! Nenhum dano.")

                              defendendo?
                              (str "🛡️ *" (:nome atacante) "* atacou! *" (:nome defensor)
                                   "* estava se defendendo e sofreu só " dano " de dano.")

                              :else
                              (str "💥 *" (:nome atacante) "* causou " dano " de dano em *"
                                   (:nome defensor) "*!"))]
         (if (zero? hp-novo)
           (do (swap! jogos dissoc cid)
               (rank/pontuar! cid (get-in jogo [:jogadores atacante-marca])
                              (get-in jogo [:nomes atacante-marca]) "pokemon")
               (str (cabecalho) msg-ataque "\n\n🏆 " (get-in jogo [:nomes atacante-marca])
                    " venceu a batalha!"))
           (let [jogo-novo (assoc jogo :vez alvo-marca)]
             (swap! jogos assoc cid jogo-novo)
             (str (cabecalho) msg-ataque "\n\n" (mensagem-estado jogo-novo)))))))))

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
