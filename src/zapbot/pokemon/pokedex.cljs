(ns zapbot.pokemon.pokedex
  "Comando !pokedex - mostra tipo, altura, peso, habilidades, status base e
  descrição de um Pokémon (via PokeAPI), traduzido pro português."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.aventuras :as aventuras]
            [zapbot.traducao :as traducao]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

;; total de espécies conhecidas pela PokeAPI (até a geração 9)
(def ^:private total-pokemons 1025)

(declare normalizar)

(defn- normalizar-cache [dados]
  (into {}
        (map (fn [[chave pokemon]]
               [chave (js->clj (clj->js pokemon) :keywordize-keys true)]))
        (or dados {})))

;; Resultado final da Pokédex (inclusive textos já traduzidos), persistido no
;; Cassandra. Assim uma segunda consulta não repete PokeAPI nem Google
;; Translate; o cache em memória é o mesmo atom durante esta execução.
(defonce ^:private cache (atom (normalizar-cache (armazenamento/obter "pokedex-cache"))))
(armazenamento/registrar! "pokedex-cache" cache normalizar-cache)

(defn- salvar-no-cache! [chave pokemon]
  (swap! cache assoc chave pokemon (normalizar (:nome pokemon)) pokemon)
  (armazenamento/salvar! "pokedex-cache" @cache)
  pokemon)

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

(defn formatar-evolucoes
  "Mostra todas as evoluções diretas e como ativá-las no bot."
  [pokemon]
  (let [slug (normalizar (:nome pokemon))
        da-cadeia (for [{:keys [nome como]} (:evolucoes pokemon)]
                    (str nome " — " como))
        ;; Mantém compatibilidade com pedras configuradas localmente, inclusive
        ;; quando a PokéAPI representa uma forma regional de modo diferente.
        por-pedra (for [[id pedra] (sort-by key aventuras/pedras)
                        :let [destino (get-in pedra [:evolucoes slug])]
                        :when destino]
                    (str (nome-formatado destino) " — " (:nome pedra) " (" id ")"))
        evolucoes (distinct (concat da-cadeia por-pedra))]
    (if (seq evolucoes) (str/join " | " evolucoes) "forma final — não evolui")))

(defn- nome-recurso [detalhe chave]
  (some-> (get-in detalhe [chave :name]) nome-formatado))

(defn- condicao-especial? [d]
  (boolean
   (or (:known_move d) (:known_move_type d) (:location d) (:party_species d)
       (:party_type d) (:relative_physical_stats d) (:min_beauty d)
       (:near_special_rock d) (:needs_overworld_rain d) (:needs_multiplayer d)
       (:turn_upside_down d) (:used_move d) (:min_steps d) (:min_damage_taken d)
       (:gender d))))

(def ^:private pedras-pokeapi
  {"water-stone" "pedra-agua" "thunder-stone" "pedra-trovao"
   "fire-stone" "pedra-fogo" "leaf-stone" "pedra-folha" "moon-stone" "pedra-lua"
   "sun-stone" "pedra-solar"})

(def ^:private itens-troca-pokeapi
  {"metal-coat" "Revestimento Metálico" "dragon-scale" "Escama de Dragão"
   "up-grade" "Upgrade" "protector" "Protetor" "kings-rock" "Pedra do Rei"
   "electirizer" "Eletrizador" "magmarizer" "Magmarizador"
   "reaper-cloth" "Tecido do Ceifador" "prism-scale" "Escama Prisma"
   "whipped-dream" "Chicote Doce" "sachet" "Sachê Perfumado"})

(defn- descrever-detalhe [d]
  (let [gatilho (get-in d [:trigger :name])
        horario (case (:time_of_day d) "day" " de dia" "night" " à noite" "")]
    (cond
      (= gatilho "trade")
      (if-let [parceiro (nome-recurso d :trade_species)]
        (str "trocar por " parceiro)
        (if-let [item-id (get-in d [:held_item :name])]
          (str "troca segurando " (get itens-troca-pokeapi item-id (nome-formatado item-id)))
          "troca entre jogadores"))

      (and (= gatilho "level-up") (not (condicao-especial? d)))
      (str (when-let [nivel (:min_level d)] (str "nível " nivel))
           (when-let [amizade (or (:min_happiness d) (:min_affection d))]
             (str (when (:min_level d) " + ") "amizade " amizade))
           horario)

      (= gatilho "use-item")
      (if-let [id (get pedras-pokeapi (get-in d [:item :name]))]
        (str (get-in aventuras/pedras [id :nome]) " (" id ")")
        (str "Catalisador Evolutivo"
             (when-let [item (nome-recurso d :item)] (str " (adapta " item ")"))))

      (condicao-especial? d) "Catalisador Evolutivo"

      :else "Catalisador Evolutivo")))

(defn- proximas-evolucoes
  [cadeia slug-atual]
  (letfn [(achar-no [no]
            (if (= slug-atual (get-in no [:species :name])) no
                (some achar-no (:evolves_to no))))]
    (when-let [atual (achar-no (:chain cadeia))]
      (vec
       (for [proximo (:evolves_to atual)]
         {:nome (nome-formatado (get-in proximo [:species :name]))
          :como (->> (:evolution_details proximo)
                     (map descrever-detalhe) distinct (str/join " ou "))})))))

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
           :evolucoes      (proximas-evolucoes cadeia (:name dados))
           :versao-evolucoes 2
           :descricao-en   (descricao-em-ingles especie)})))))

(defn dados-especie
  "Promise do mapa de espécie (mesmo formato de buscar-dados) já enriquecido
  com :descricao-pt e :habilidades-pt. Serve o cache persistido quando
  possível; senão busca na PokeAPI, traduz e grava no cache. Resolve nil
  quando a PokeAPI não conhece a entrada; rejeita se PokeAPI/tradução
  falharem - quem chama decide o que fazer."
  [entrada]
  (let [chave (normalizar entrada)]
    (if-let [pokemon (get @cache chave)]
      (if (= 2 (:versao-evolucoes pokemon))
        (p/resolved pokemon)
        (p/let [atualizado (buscar-dados entrada)]
          (when atualizado
            (p/let [descricao-pt (when (:descricao-en atualizado)
                                   (traducao/traduzir (:descricao-en atualizado) "en" "pt"))
                    habilidades-pt (traducao/traduzir (:habilidades-en atualizado) "en" "pt")]
              (salvar-no-cache! chave (assoc atualizado :descricao-pt descricao-pt
                                                        :habilidades-pt habilidades-pt))))))
      (p/let [pokemon (buscar-dados entrada)]
        (when pokemon
          (p/let [descricao-pt   (when (:descricao-en pokemon)
                                   (traducao/traduzir (:descricao-en pokemon) "en" "pt"))
                  habilidades-pt (traducao/traduzir (:habilidades-en pokemon) "en" "pt")]
            (salvar-no-cache! chave (assoc pokemon
                                           :descricao-pt descricao-pt
                                           :habilidades-pt habilidades-pt))))))))

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
       "\n🔺 Evolução: " (formatar-evolucoes pokemon)
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
    (-> (p/let [pokemon (dados-especie entrada)]
          ;; nil aqui é "não encontrado" - diferente do nil que enviar-cartao
          ;; resolve quando já respondeu direto com a imagem (ver zapbot.core/on-message)
          (if (nil? pokemon)
            (str (cabecalho) "❓ Não encontrei nenhum Pokémon com \"" entrada
                 "\". Tente pelo nome (ex.: pikachu) ou número da Pokédex (ex.: 25).")
            (enviar-cartao message pokemon
                           (montar-legenda pokemon (:descricao-pt pokemon) (:habilidades-pt pokemon)))))
        (p/catch (fn [err]
                   (js/console.error "Erro ao buscar pokedex:" err)
                   (str (cabecalho) "❌ Não consegui buscar esse Pokémon agora (PokeAPI fora do ar?). Tente de novo."))))))
