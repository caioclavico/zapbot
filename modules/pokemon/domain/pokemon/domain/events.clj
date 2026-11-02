(ns pokemon.domain.events
  "Eventos temáticos — catálogo + helpers de elegibilidade/bias.
  12.8.0: inativos (flag off + sem janelas).
  12.8.1: ocorrências por chat com janelas; flag POKEMON_THEMATIC_EVENTS_ENABLED."
  (:require [clojure.string :as str]
            [pokemon.domain.profile :as profile])
  (:import [java.time Instant]))

(defn thematic-events-enabled?
  "Kill-switch: default false até GO 12.8.1 no piloto."
  []
  (= "true"
     (some-> (System/getenv "POKEMON_THEMATIC_EVENTS_ENABLED")
             str
             str/lower-case
             str/trim)))

;; Famílias fósseis verificáveis (espécies-base oficiais no catálogo 1–9).
(def fossil-family-roots
  #{"omanyte" "kabuto" "aerodactyl" "lileep" "anorith" "cranidos" "shieldon"
    "tirtouga" "archen" "tyrunt" "amaura"})

(def catalog
  "Definições estáveis. Janelas de ocorrência vivem no schedule/ocorrência (não no catálogo)."
  [{:id "festival-dragoes"
    :nome "Festival dos Dragões"
    :emoji "🐉"
    :categoria "especial"
    :tipos-favorecidos #{"dragon"}
    :familias-doce ["dratini" "bagon" "gible" "axew" "deino" "goomy" "jangmo-o" "dreepy" "frigibax"]
    :missoes [{:id "cap-dragao" :nome "Capture Pokémon Dragão" :meta 3
               :objetivo "Capturar 3 Pokémon do tipo Dragão"
               :acao "captura" :tipos ["dragon"]
               :recompensas {"fruta" 2 "ultra-bola" 2}
               :doces {"dratini" 2}}
              {:id "evo-dragao" :nome "Evolua um Dragão" :meta 1
               :objetivo "Evoluir 1 Pokémon de família Dragão"
               :acao "evolucao" :familias ["dratini" "bagon" "gible" "axew" "deino" "goomy" "jangmo-o" "dreepy" "frigibax"]
               :recompensas {"fruta" 1 "cartao-presente" 1}
               :doces {"bagon" 3}}
              {:id "raid-dragao" :nome "Participe de raids" :meta 2
               :objetivo "Participar de 2 raids"
               :acao "raid-join"
               :recompensas {"ultra-bola" 1 "fruta" 1}}]
    :bias-tipos 20}
   {:id "noite-sombras"
    :nome "Noite das Sombras"
    :emoji "👻"
    :categoria "especial"
    :tipos-favorecidos #{"ghost" "dark"}
    :familias-doce ["gastly" "litwick" "zorua" "pawniard"]
    :missoes [{:id "cap-sombra" :nome "Capture Fantasma ou Sombrio" :meta 4
               :objetivo "Capturar 4 Pokémon Fantasma/Sombrio"
               :acao "captura" :tipos ["ghost" "dark"]
               :recompensas {"fruta" 2 "ultra-bola" 2 "grande-bola" 2}
               :doces {"gastly" 2}}
              {:id "bat-sombra" :nome "Vença batalhas temáticas" :meta 2
               :objetivo "Vencer 2 batalhas"
               :acao "batalha"
               :recompensas {"grande-bola" 3 "fruta" 1}}]
    :bias-tipos 18}
   {:id "tempestade-eletrica"
    :nome "Tempestade Elétrica"
    :emoji "⚡"
    :categoria "comum"
    :tipos-favorecidos #{"electric"}
    :familias-doce ["pikachu" "elekid" "magnemite"]
    :missoes [{:id "cap-eletrico" :nome "Capture Elétricos" :meta 5
               :objetivo "Capturar 5 Pokémon Elétricos"
               :acao "captura" :tipos ["electric"]
               :recompensas {"grande-bola" 3 "fruta" 1}
               :doces {"pikachu" 3}}
              {:id "evo-eletrico" :nome "Evolua um Elétrico" :meta 1
               :objetivo "Evoluir 1 família Elétrica"
               :acao "evolucao" :familias ["pikachu" "elekid" "magnemite"]
               :recompensas {"ultra-bola" 1}
               :doces {"elekid" 2}}]
    :bias-tipos 18}
   {:id "mare-misteriosa"
    :nome "Maré Misteriosa"
    :emoji "🌊"
    :categoria "comum"
    :tipos-favorecidos #{"water"}
    :familias-doce ["magikarp" "squirtle" "poliwag" "horsea" "spheal"]
    :missoes [{:id "cap-agua" :nome "Capture Pokémon Água" :meta 5
               :objetivo "Capturar 5 Pokémon Água"
               :acao "captura" :tipos ["water"]
               :recompensas {"fruta" 2 "grande-bola" 2}
               :doces {"magikarp" 3}}
              {:id "raid-agua" :nome "Raids durante a maré" :meta 1
               :objetivo "Participar de 1 raid"
               :acao "raid-join"
               :recompensas {"cartao-presente" 1 "fruta" 1}}]
    :bias-tipos 18}
   {:id "despertar-floresta"
    :nome "Despertar da Floresta"
    :emoji "🌿"
    :categoria "comum"
    :tipos-favorecidos #{"grass" "bug"}
    :familias-doce ["bulbasaur" "oddish" "scyther"]
    :missoes [{:id "cap-planta" :nome "Capture Planta/Inseto" :meta 5
               :objetivo "Capturar 5 Pokémon Planta ou Inseto"
               :acao "captura" :tipos ["grass" "bug"]
               :recompensas {"fruta" 2 "pokebola" 4}
               :doces {"bulbasaur" 2}}
              {:id "evo-planta" :nome "Evolua na floresta" :meta 1
               :objetivo "Evoluir 1 família Planta/Inseto"
               :acao "evolucao" :familias ["bulbasaur" "oddish" "scyther"]
               :recompensas {"fruta" 2}
               :doces {"oddish" 2}}]
    :bias-tipos 18}
   {:id "era-fosseis"
    :nome "Era dos Fósseis"
    :emoji "🦴"
    :categoria "especial"
    :tipos-favorecidos #{"rock" "water" "flying"}
    :familias-doce (vec (sort fossil-family-roots))
    :classificacao "fossil-family-roots"
    :missoes [{:id "cap-fossil" :nome "Capture um fóssil" :meta 3
               :objetivo "Capturar 3 Pokémon de famílias fósseis"
               :acao "captura" :familias (vec (sort fossil-family-roots))
               :recompensas {"ultra-bola" 2 "fruta" 1}
               :doces {"kabuto" 2}}
              {:id "evo-fossil" :nome "Evolua um fóssil" :meta 1
               :objetivo "Evoluir 1 família fóssil"
               :acao "evolucao" :familias (vec (sort fossil-family-roots))
               :recompensas {"ultra-bola" 1 "cartao-presente" 1}
               :doces {"omanyte" 2}}]
    :bias-tipos 15}
   {:id "eclipse-mistico"
    :nome "Eclipse Místico"
    :emoji "🌙"
    :categoria "raro"
    :tipos-favorecidos #{"psychic" "ghost" "fairy"}
    :familias-doce ["ralts" "abra" "eevee" "gastly"]
    :missoes [{:id "cap-mistico" :nome "Desafio do Eclipse" :meta 7
               :objetivo "Capturar 7 Pokémon Psíquico/Fantasma/Fada"
               :acao "captura" :tipos ["psychic" "ghost" "fairy"]
               :recompensas {"ultra-bola" 3 "cartao-presente" 2 "fruta" 2}
               :doces {"ralts" 4}}
              {:id "raid-mistico" :nome "Raids difíceis" :meta 2
               :objetivo "Vencer 2 raids"
               :acao "raid-win"
               :recompensas {"ultra-bola" 2 "fruta" 2}}]
    :bias-tipos 12}])

(defn by-id [id]
  (some #(when (= id (:id %)) %) catalog))

(defn status-at
  "upcoming|active|ended|inactive — inactive quando sem janela."
  [event instant]
  (let [now (or instant (Instant/now))
        start (or (some-> (:inicio event) Instant/parse)
                  (when (:inicio-ms event) (Instant/ofEpochMilli (long (:inicio-ms event)))))
        end (or (some-> (:fim event) Instant/parse)
                (when (:fim-ms event) (Instant/ofEpochMilli (long (:fim-ms event)))))]
    (cond
      (or (nil? start) (nil? end)) "inactive"
      (.isBefore now start) "upcoming"
      (.isBefore now end) "active"
      :else "ended")))

(defn occurrence->event-view
  "Une definição do catálogo com janela da ocorrência."
  [occurrence]
  (when-let [base (by-id (:catalog-id occurrence))]
    (merge base
           (select-keys occurrence [:occurrence-id :chat-id :inicio-ms :fim-ms :status
                                    :announced-start? :announced-end?])
           {:inicio (when (:inicio-ms occurrence)
                      (str (Instant/ofEpochMilli (long (:inicio-ms occurrence)))))
            :fim (when (:fim-ms occurrence)
                   (str (Instant/ofEpochMilli (long (:fim-ms occurrence)))))})))

(defn active-events
  "Eventos ativos. Com flag off → [].
  Aceita allowlist de catalog-ids OU lista de ocorrências já materializadas."
  ([instant] (active-events instant nil))
  ([instant allowed-or-occs]
   (if-not (thematic-events-enabled?)
     []
     (cond
       ;; Lista de ocorrências (maps com :catalog-id / :inicio-ms)
       (and (sequential? allowed-or-occs)
            (some #(or (contains? % :catalog-id) (contains? % :inicio-ms)) allowed-or-occs))
       (->> allowed-or-occs
            (map occurrence->event-view)
            (filter #(= "active" (status-at % instant)))
            vec)

       :else
       (let [allow? (if (seq allowed-or-occs) (set allowed-or-occs) (constantly true))]
         (->> catalog
              (filter #(and (allow? (:id %)) (= "active" (status-at % instant))))
              vec))))))

(defn favored-types
  [events]
  (into #{} (mapcat :tipos-favorecidos events)))

(defn type-bias-chance
  "Chance extra (0–35). Não empilha: usa o maior bias."
  [events]
  (min 35 (long (reduce max 0 (map #(long (or (:bias-tipos %) 0)) events)))))

(defn combined-type-roll-bands
  "Ordem de aplicação no sorteio de tipos (documentada):
  1) bias evento (0..bias)
  2) clima (bias .. bias+35) — 35pp fixos do clima
  3) bioma (resto)
  Total bias+clima ≤ 70 → diversidade preservada."
  [active-events]
  (let [bias (type-bias-chance active-events)]
    {:evento bias
     :clima 35
     :bioma (- 100 bias 35)
     :ordem [:evento :clima :bioma]}))

(defn pick-candy-family
  [event draw]
  (let [pool (vec (or (:familias-doce event) []))
        n (count pool)]
    (when (pos? n)
      (nth pool (mod (long (or (draw) 0)) n)))))

(defn grant-candy-map
  [doces]
  (reduce-kv (fn [m fam qty]
               (let [root (profile/family fam)
                     q (long qty)]
                 (cond-> m (pos? q) (update root (fnil + 0) q))))
             {}
             (or doces {})))

(defn species-matching-types
  "Filtra species-type-index {species #{types}} pelos tipos favorecidos.
  Não inventa espécies; índice vem do catálogo real (injetado)."
  [species-type-index favored-type-set]
  (let [fav (set (map str/lower-case favored-type-set))]
    (->> (or species-type-index {})
         (filter (fn [[_ types]]
                   (some fav (map str/lower-case types))))
         (map (comp str/lower-case key))
         sort
         vec)))

(defn species-matching-families
  [species-list family-roots]
  (let [roots (set (map #(profile/family %) family-roots))]
    (->> (or species-list [])
         (filter #(contains? roots (profile/family %)))
         (map str/lower-case)
         sort
         vec)))

(defn fossil-family?
  [species-or-family]
  (contains? fossil-family-roots (profile/family species-or-family)))

(defn merge-type-sets
  [base-tipos event-tipos]
  (into (set base-tipos) event-tipos))

(defn with-windows
  [event-id inicio fim]
  (-> (by-id event-id)
      (assoc :inicio inicio :fim fim)))

(defn announce-start-text
  [event duration-label]
  (str (:emoji event) "🔥 *" (str/upper-case (:nome event)) " COMEÇOU!*\n\n"
       "Pokémon dos tipos favorecidos estão aparecendo com mais frequência!\n\n"
       "⏳ Duração: " duration-label "\n"
       "🎯 Complete missões exclusivas.\n"
       "🍬 Ganhe Doces Pokémon de famílias especiais.\n"
       "🍓 Consiga Frutas Frambo e itens de captura.\n\n"
       "📜 Consulte:\n`!pk missoes evento`"))

(defn announce-end-text
  [event]
  (str "🏁 *" (str/upper-case (:nome event)) " ENCERRADO!*\n\n"
       "O bônus de aparição terminou.\n"
       "Obrigado a todos os treinadores que participaram!"))
