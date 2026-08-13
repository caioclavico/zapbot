(ns zapbot.pokemon
  "Comando !pokemon - batalha entre duas pessoas do chat, cada uma com um
  Pokémon sorteado (nome, imagem e stats via PokeAPI - grátis, sem chave).
  Estado guardado em memória por chat (não sobrevive a reinício do bot)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]))

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

(defn- mensagem-estado [{:keys [pokemons nomes vez hp]}]
  (str "🐾 " (get nomes :x) " - *" (get-in pokemons [:x :nome]) "*\n"
       (barra-hp (get hp :x) (get-in pokemons [:x :hp])) "\n\n"
       "🐾 " (get nomes :o) " - *" (get-in pokemons [:o :nome]) "*\n"
       (barra-hp (get hp :o) (get-in pokemons [:o :hp])) "\n\n"
       "Vez de " (get nomes vez) " - ataque com " config/prefix "pokemon atacar"))

(defn- outro [marca] (if (= marca :x) :o :x))

(defn- criar-jogo [id nome pokemon]
  {:pokemons {:x pokemon}
   :jogadores {:x id}
   :nomes {:x nome}
   :hp {:x (:hp pokemon)}
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
                                 (assoc-in [:hp :o] (:hp pokemon)))]
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
             dano           (calcular-dano atacante defensor)
             hp-novo        (max 0 (- (get-in jogo [:hp alvo-marca]) dano))
             jogo           (assoc-in jogo [:hp alvo-marca] hp-novo)]
         (if (zero? hp-novo)
           (do (swap! jogos dissoc cid)
               (str (cabecalho) "💥 *" (:nome atacante) "* causou " dano " de dano em *"
                    (:nome defensor) "*!\n\n🏆 " (get-in jogo [:nomes atacante-marca])
                    " venceu a batalha!"))
           (let [jogo-novo (assoc jogo :vez alvo-marca)]
             (swap! jogos assoc cid jogo-novo)
             (str (cabecalho) "💥 *" (:nome atacante) "* causou " dano " de dano em *"
                  (:nome defensor) "*!\n\n" (mensagem-estado jogo-novo)))))))))

(defn jogar
  "!pokemon sem argumento abre/entra numa batalha; !pokemon atacar ataca;
  !pokemon sair cancela a batalha em andamento."
  [message args]
  (let [args (str/trim (str/lower-case (or args "")))]
    (cond
      (str/blank? args) (iniciar-ou-entrar message)
      (= args "sair") (sair message)
      (contains? #{"atacar" "ataque" "atirar"} args) (atacar message)
      :else (p/resolved (str (cabecalho) "❓ Use " config/prefix "pokemon (abrir/entrar), "
                              config/prefix "pokemon atacar ou " config/prefix "pokemon sair.")))))
