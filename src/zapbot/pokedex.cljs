(ns zapbot.pokedex
  "Comando !pokedex - mostra tipo, altura, peso, habilidades, status base e
  descrição de um Pokémon (via PokeAPI), traduzido pro português."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

;; total de espécies conhecidas pela PokeAPI (até a geração 9)
(def ^:private total-pokemons 1025)

(def ^:private tipos-pt
  {"normal" "Normal" "fire" "Fogo" "water" "Água" "electric" "Elétrico"
   "grass" "Planta" "ice" "Gelo" "fighting" "Lutador" "poison" "Venenoso"
   "ground" "Terra" "flying" "Voador" "psychic" "Psíquico" "bug" "Inseto"
   "rock" "Pedra" "ghost" "Fantasma" "dragon" "Dragão" "dark" "Sombrio"
   "steel" "Aço" "fairy" "Fada"})

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(defn- normalizar [entrada]
  (-> entrada str/trim remover-acentos str/lower-case (str/replace #"\s+" "-")))

(defn- numero-formatado [n]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- 3 (count s))) "0")) s)))

(defn- stat-base [dados nome-stat]
  (->> (:stats dados)
       (some #(when (= nome-stat (get-in % [:stat :name])) (:base_stat %)))))

(defn- descricao-em-ingles [especie]
  (when-let [entrada (some #(when (= "en" (get-in % [:language :name])) %)
                            (:flavor_text_entries especie))]
    (-> (:flavor_text entrada) (str/replace #"\s+" " ") str/trim)))

(defn- nome-habilidade [a]
  (str (str/replace (get-in a [:ability :name]) "-" " ")
       (when (:is_hidden a) " (hidden)")))

(defn- formatar-tipos [tipos]
  (->> tipos (map #(get tipos-pt % (str/capitalize %))) (str/join "/")))

(defn- buscar-json
  "GET com parse de JSON, ou nil se a resposta não for 2xx (ex.: 404 de nome/número inválido)."
  [url]
  (p/let [res (js/fetch url)]
    (when (.-ok res) (.json res))))

(defn- nome-formatado [slug]
  (->> (str/split slug #"-") (map str/capitalize) (str/join " ")))

(defn- proximas-evolucoes-por-nivel
  "Retorna as evoluções diretas que acontecem por nível, no formato
  [{:nome :nivel}]. Cadeias de item, troca, amizade etc. ficam de fora."
  [cadeia slug-atual]
  (letfn [(achar-no [no]
            (if (= slug-atual (get-in no [:species :name]))
              no
              (some achar-no (:evolves_to no))))]
    (when-let [atual (achar-no (get cadeia :chain))]
      (->> (:evolves_to atual)
           (keep (fn [proximo]
                   (when-let [nivel (some #(when (= "level-up" (get-in % [:trigger :name]))
                                             (:min_level %))
                                          (:evolution_details proximo))]
                     {:nome (nome-formatado (get-in proximo [:species :name])) :nivel nivel})))
           vec))))

(defn- buscar-dados [entrada]
  (let [slug (normalizar entrada)]
    (p/let [dados-js   (buscar-json (str "https://pokeapi.co/api/v2/pokemon/" slug))
            especie-js (when dados-js (buscar-json (str "https://pokeapi.co/api/v2/pokemon-species/" slug)))
            cadeia-js  (when especie-js
                         (buscar-json (get-in (js->clj especie-js :keywordize-keys true) [:evolution_chain :url])))]
      (when dados-js
        (let [dados   (js->clj dados-js :keywordize-keys true)
              especie (some-> especie-js (js->clj :keywordize-keys true))
              cadeia  (some-> cadeia-js (js->clj :keywordize-keys true))]
          {:numero         (:id dados)
           :nome           (->> (str/split (:name dados) #"-") (map str/capitalize) (str/join " "))
           :imagem         (or (get-in dados [:sprites :other :official-artwork :front_default])
                                (get-in dados [:sprites :front_default]))
           :tipos          (mapv #(get-in % [:type :name]) (:types dados))
           :altura         (/ (:height dados) 10.0)
           :peso           (/ (:weight dados) 10.0)
           :habilidades-en (str/join ", " (map nome-habilidade (:abilities dados)))
           :hp             (stat-base dados "hp")
           :ataque         (stat-base dados "attack")
           :defesa         (stat-base dados "defense")
           :atq-esp        (stat-base dados "special-attack")
           :def-esp        (stat-base dados "special-defense")
           :veloc          (stat-base dados "speed")
           :evolucoes      (proximas-evolucoes-por-nivel cadeia (:name dados))
           :descricao-en   (descricao-em-ingles especie)})))))

(defn- cabecalho []
  (str "📖 *Pokédex do tio " config/bot-name "*\n\n"))

(defn- montar-legenda [pokemon descricao-pt habilidades-pt]
  (str (cabecalho)
       "#" (numero-formatado (:numero pokemon)) " *" (:nome pokemon) "*\n"
       "🏷️ Tipo: " (formatar-tipos (:tipos pokemon)) "\n"
       "📏 Altura: " (.toFixed (:altura pokemon) 1) " m | ⚖️ Peso: " (.toFixed (:peso pokemon) 1) " kg\n"
       "✨ Habilidades: " habilidades-pt "\n\n"
       "❤️ HP: " (:hp pokemon) " | ⚔️ Ataque: " (:ataque pokemon) " | 🛡️ Defesa: " (:defesa pokemon) "\n"
       "🔮 Atq. Especial: " (:atq-esp pokemon) " | 🌀 Def. Especial: " (:def-esp pokemon)
       " | 💨 Velocidade: " (:veloc pokemon)
       "\n🔺 Evolução: " (if (seq (:evolucoes pokemon))
                            (str/join " | " (map #(str (:nome %) " — nível " (:nivel %)) (:evolucoes pokemon)))
                            "não possui evolução por nível")
       (when-not (str/blank? descricao-pt) (str "\n\n📜 _" descricao-pt "_"))))

(defn- enviar-cartao [message pokemon legenda]
  (if (:imagem pokemon)
    (-> (p/let [media (.fromUrl MessageMedia (:imagem pokemon))
                _     (.reply message media nil #js {:caption legenda})]
          nil)
        (p/catch (fn [err]
                   (js/console.error "Erro ao enviar imagem da pokedex:" err)
                   legenda)))
    (p/resolved legenda)))

(defn buscar
  "Busca um Pokémon por nome ou número (ou sorteia um, sem argumento) e
  responde com uma carta de características traduzida pro português."
  [message entrada]
  (let [entrada (if (str/blank? entrada) (str (inc (rand-int total-pokemons))) entrada)]
    (-> (p/let [pokemon (buscar-dados entrada)]
          ;; nil aqui é "não encontrado" - diferente do nil que enviar-cartao
          ;; resolve quando já respondeu direto com a imagem (ver zapbot.core/on-message)
          (if (nil? pokemon)
            (str (cabecalho) "❓ Não encontrei nenhum Pokémon com \"" entrada
                 "\". Tente pelo nome (ex.: pikachu) ou número da Pokédex (ex.: 25).")
            (p/let [descricao-pt   (when (:descricao-en pokemon)
                                      (traducao/traduzir (:descricao-en pokemon) "en" "pt"))
                    habilidades-pt (traducao/traduzir (:habilidades-en pokemon) "en" "pt")]
              (enviar-cartao message pokemon (montar-legenda pokemon descricao-pt habilidades-pt)))))
        (p/catch (fn [err]
                   (js/console.error "Erro ao buscar pokedex:" err)
                   (str (cabecalho) "❌ Não consegui buscar esse Pokémon agora (PokeAPI fora do ar?). Tente de novo."))))))
