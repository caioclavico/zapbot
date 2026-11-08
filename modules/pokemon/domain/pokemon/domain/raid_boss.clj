(ns pokemon.domain.raid-boss
  "Seleção dinâmica de chefes de raide a partir do catálogo completo (Fase 12.7).

  Fonte de verdade: snapshot `raid_boss_catalog.edn` (PokeAPI + suavizar-stat legado).
  Sem lista fixa de espécies — novas entradas no catálogo entram automaticamente
  quando elegíveis. Pesos por categoria são configuráveis e normalizados entre
  categorias não vazias. RNG e histórico recentes são injetáveis (testes)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.raid :as raid]))

(def catalog-resource "pokemon/domain/data/raid_boss_catalog.edn")

(def category-weights-default
  "Chance por evento (categoria), não por espécie individual."
  {"raro" 0.40
   "epico" 0.30
   "lendario" 0.20
   "mitico" 0.10})

(def eligible-rarities #{"raro" "epico" "lendario" "mitico"})

;; Janela curta de anti-repetição por grupo (espécies recentes injetadas).
(def recent-window-default 8)

;; Fator de HP de combate derivado dos atributos do chefe (clamp seguro).
;; Teto 1.55: lendário/mítico extremo exige coop (3–5), mas lobby cheio com
;; mons fortes ainda fecha — evita chefes matematicamente impossíveis.
(def fator-hp-min 0.80)
(def fator-hp-max 1.55)

(def ^:private type-move-names
  {"normal" "Investida" "fire" "Lança-chamas" "water" "Jato d'Água"
   "electric" "Choque do Trovão" "grass" "Folha Mágica" "ice" "Raio de Gelo"
   "fighting" "Soco Focado" "poison" "Bomba de Lodo" "ground" "Terremoto"
   "flying" "Golpe Aéreo" "psychic" "Psíquico" "bug" "Tesoura X"
   "rock" "Pedra Afiada" "ghost" "Bola Sombria" "dragon" "Garra de Dragão"
   "dark" "Pulso Noturno" "steel" "Canhão Flash" "fairy" "Brilho Mágico"})

(defonce ^:private catalog-atom (atom nil))

(defn- artwork-url
  [dex-id]
  (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
       "sprites/pokemon/other/official-artwork/" dex-id ".png"))

(defn- parse-catalog
  [raw]
  (let [data (cond
               (map? raw) raw
               (string? raw) (edn/read-string raw)
               :else (throw (err/error :raid_boss_catalog_invalid {:reason :type})))
        species (mapv (fn [row]
                        (let [row (if (map? row) row {})]
                          (-> row
                              (update :species str)
                              (update :nome str)
                              (update :tipos #(vec (or % [])))
                              (assoc :lendario-api? (boolean (or (:lendario-api? row)
                                                                (:lendario-api row)))
                                     :mitico-api? (boolean (or (:mitico-api? row)
                                                               (:mitico-api row)))
                                     :paradox-api? (boolean (or (:paradox-api? row)
                                                                (:paradox-api row)))
                                     :taxa-captura (when-let [t (:taxa-captura row)]
                                                     (long t))))))
                      (or (:species data) []))]
    {:version (str (:version data))
     :source (str (:source data))
     :total-pokemons (long (or (:total-pokemons data) hunt/total-pokemons))
     :species species}))

(defn load-catalog!
  "Carrega (ou recarrega) o catálogo. `source` = caminho, resource URL, mapa ou nil (resource padrão)."
  ([] (load-catalog! nil))
  ([source]
   (let [raw (cond
               (map? source) source
               (string? source) (slurp source)
               (instance? java.net.URL source) (slurp source)
               :else
               (let [res (io/resource catalog-resource)]
                 (when-not res
                   (throw (err/error :raid_boss_catalog_missing
                                     {:resource catalog-resource})))
                 (slurp res)))
         parsed (parse-catalog raw)]
     (reset! catalog-atom parsed)
     parsed)))

(defn catalog
  "Catálogo atual (carrega o resource na primeira chamada)."
  []
  (or @catalog-atom (load-catalog!)))

(defn reset-catalog-for-tests!
  "Substitui o catálogo em memória (testes). nil recarrega o resource."
  [parsed-or-nil]
  (if (nil? parsed-or-nil)
    (do (reset! catalog-atom nil) (catalog))
    (reset! catalog-atom (parse-catalog parsed-or-nil))))

(defn classify-rarity
  "Usa flags reais do catálogo + heurística de hunt (não só estágio evolutivo)."
  [species-row]
  (hunt/classificar-raridade
   {:hp (:hp species-row)
    :ataque (:ataque species-row)
    :defesa (:defesa species-row)
    :atq-esp (:atq-esp species-row)
    :def-esp (:def-esp species-row)
    :veloc (:veloc species-row)
    :taxa-captura (:taxa-captura species-row)
    :lendario-api? (:lendario-api? species-row)
    :mitico-api? (:mitico-api? species-row)
    :paradox-api? (:paradox-api? species-row)}))

(defn- eligible-row?
  "Tecnicamente elegível: stats + tipos + raridade de raide."
  [row]
  (and (string? (:species row))
       (seq (:species row))
       (seq (:tipos row))
       (pos? (long (:hp row 0)))
       (pos? (long (:ataque row 0)))
       (contains? eligible-rarities (classify-rarity row))))

(defn- with-rarity
  [row]
  (assoc row :raridade (classify-rarity row)))

(defn eligible-species
  "Todas as espécies elegíveis do catálogo atual (descoberta automática)."
  ([] (eligible-species (catalog)))
  ([cat]
   (->> (:species cat)
        (filter eligible-row?)
        (map with-rarity)
        vec)))

(defn excluded-species
  "Espécies do catálogo fora da seleção e motivo principal."
  ([] (excluded-species (catalog)))
  ([cat]
   (for [row (:species cat)
         :let [reason (cond
                        (not (seq (:tipos row))) :sem-tipos
                        (not (pos? (long (:hp row 0)))) :stats-invalidos
                        (not (contains? eligible-rarities (classify-rarity row)))
                        :raridade-baixa
                        :else nil)]
         :when reason]
     {:species (:species row)
      :generation (:generation row)
      :raridade (classify-rarity row)
      :motivo reason})))

(defn audit-catalog
  "Auditoria estática do catálogo (sem RNG / sem raids)."
  ([] (audit-catalog (catalog)))
  ([cat]
   (let [all (:species cat)
         elig (eligible-species cat)
         by-r (frequencies (map :raridade elig))
         by-g (frequencies (map :generation elig))
         excl (vec (excluded-species cat))
         excl-reasons (frequencies (map :motivo excl))]
     {:version (:version cat)
      :source (:source cat)
      :total-cadastradas (count all)
      :total-pokemons-projeto (long (:total-pokemons cat))
      :elegiveis (count elig)
      :por-raridade by-r
      :por-geracao by-g
      :excluidas (count excl)
      :exclusao-por-motivo excl-reasons
      :lista-fixa? false
      :descoberta-automatica? true
      :pesos-categoria category-weights-default})))

(defn- normalize-weights
  "Normaliza pesos só entre categorias que têm espécies."
  [weights available-categories]
  (let [filtered (into {}
                       (keep (fn [cat]
                               (when-let [w (get weights cat)]
                                 (when (pos? (double w))
                                   [cat (double w)])))
                             available-categories))
        total (reduce + 0.0 (vals filtered))]
    (when (or (empty? filtered) (<= total 0.0))
      (throw (err/error :raid_boss_no_eligible {})))
    (into {} (map (fn [[k v]] [k (/ v total)]) filtered))))

(defn- weighted-pick
  "Escolhe chave pelo peso. `roll` ∈ [0,1)."
  [weight-map roll]
  (let [roll (max 0.0 (min 0.999999999 (double roll)))]
    (loop [entries (seq weight-map)
           acc 0.0]
      (if (empty? entries)
        (key (last weight-map))
        (let [[k w] (first entries)
              acc' (+ acc (double w))]
          (if (< roll acc')
            k
            (recur (next entries) acc')))))))

(defn- species-weight
  "Peso intra-categoria: favorece BST alto sem eliminar diversidade.
   Gerações sob-representadas no histórico recente recebem leve bônus."
  [row recent-generations]
  (let [bst (double (capture/poder-total row))
        base (+ 1.0 (/ bst 600.0))
        gen (long (:generation row 1))
        gen-count (get recent-generations gen 0)
        variety (if (>= gen-count 2) 0.85 1.0)]
    (* base variety)))

(defn- pick-in-category
  [candidates recent-species recent-generations roll]
  (let [recent (set (map str recent-species))
        fresh (vec (remove #(contains? recent (str (:species %))) candidates))
        pool (if (seq fresh) fresh (vec candidates))
        weights (into {}
                      (map (fn [row]
                             [(:species row)
                              (species-weight row recent-generations)])
                           pool))
        norm (normalize-weights weights (keys weights))
        chosen (weighted-pick norm roll)]
    (some #(when (= (:species %) chosen) %) pool)))

(defn- golpes-para
  [tipos bst]
  (let [t1 (or (first tipos) "normal")
        t2 (or (second tipos) t1)
        poder (long (max 50 (min 100 (Math/round (+ 45 (/ (double bst) 12.0))))))]
    [{:nome-exibicao (get type-move-names t1 "Investida")
      :tipo t1 :poder poder :classe :fisico}
     {:nome-exibicao (get type-move-names t2 "Investida")
      :tipo t2 :poder (max 40 (- poder 10)) :classe :especial}]))

(defn fator-dificuldade
  "Dificuldade a partir dos atributos reais (não só raridade).
   Combina BST, paredes defensivas e poder ofensivo."
  [chefe]
  (let [hp (double (:hp chefe 50))
        atk (double (max (:ataque chefe 50) (:atq-esp chefe 50)))
        defs (double (/ (+ (:defesa chefe 50) (:def-esp chefe 50)) 2.0))
        bst (double (capture/poder-total chefe))
        ;; Referência ~450 BST suavizado; ofensiva/defesa em torno de 80.
        score (+ (* 0.45 (/ bst 450.0))
                 (* 0.25 (/ atk 90.0))
                 (* 0.20 (/ defs 90.0))
                 (* 0.10 (/ hp 90.0)))
        cents (-> score
                  (max fator-hp-min)
                  (min fator-hp-max)
                  (* 100.0)
                  Math/round
                  long)
        ;; Re-clamp em centésimos para não estourar o teto por drift binário.
        cents (max (long (Math/round (* fator-hp-min 100.0)))
                   (min (long (Math/round (* fator-hp-max 100.0))) cents))]
    (/ (double cents) 100.0)))

(defn materialize-boss
  "Escala o template ao nível do ginásio e anexa artwork/golpes/dificuldade."
  [row nivel]
  (let [nivel (long (or nivel 1))
        base {:nome (:nome row)
              :species (:species row)
              :tipos (:tipos row)
              :raridade (:raridade row)
              :hp (:hp row)
              :ataque (:ataque row)
              :defesa (:defesa row)
              :atq-esp (:atq-esp row)
              :def-esp (:def-esp row)
              :veloc (:veloc row)
              :imagem (artwork-url (:id row))
              :dex-id (:id row)
              :generation (:generation row)
              :golpes (golpes-para (:tipos row) (capture/poder-total row))}
        escalado (gym/escalar-nivel base nivel)
        fator (fator-dificuldade escalado)]
    (-> escalado
        (assoc :species (:species row)
               :nome (:nome row)
               :tipos (:tipos row)
               :raridade (:raridade row)
               :golpes (:golpes base)
               :imagem (:imagem base)
               :dex-id (:id row)
               :generation (:generation row)
               :fator-dificuldade fator
               :hp-atual (:hp escalado)
               :lendario-api? (boolean (:lendario-api? row))
               :mitico-api? (boolean (:mitico-api? row))
               :paradox-api? (boolean (:paradox-api? row))
               :taxa-captura (:taxa-captura row)))))

(defn select-boss
  "Sorteia chefe elegível. `rng` = fn [] → double [0,1) (categoria + espécie).
   `recent-species` = espécies a evitar temporariamente (não exclusão permanente)."
  ([]
   (select-boss {}))
  ([{:keys [catalog category-weights recent-species recent-window rng nivel]
     :or {category-weights category-weights-default
          recent-window recent-window-default
          nivel 1}}]
   (let [cat (or catalog (pokemon.domain.raid-boss/catalog))
         elig (eligible-species cat)
         _ (when (empty? elig)
             (throw (err/error :raid_boss_no_eligible {})))
         by-cat (group-by :raridade elig)
         avail (filter #(seq (get by-cat %)) (keys category-weights))
         weights (normalize-weights category-weights avail)
         rng (or rng rand)
         cat-roll (double (rng))
         sp-roll (double (rng))
         category (weighted-pick weights cat-roll)
         recent (->> (or recent-species [])
                     (map str)
                     (take (long recent-window))
                     vec)
         recent-gens (frequencies
                      (keep (fn [sp]
                              (some #(when (= (str (:species %)) sp)
                                       (:generation %))
                                    elig))
                            recent))
         row (pick-in-category (get by-cat category) recent recent-gens sp-roll)]
     {:categoria category
      :category-weights weights
      :species (:species row)
      :raridade (:raridade row)
      :chefe (materialize-boss row nivel)
      :catalog-version (:version cat)
      :rolls {:categoria cat-roll :especie sp-roll}})))

(def weights-por-liga
  "Dificuldade manual alinhada à liga do ginásio (não altera taxas de captura)."
  {"iniciante" {"raro" 0.70 "epico" 0.25 "lendario" 0.05 "mitico" 0.00}
   "bronze"    {"raro" 0.55 "epico" 0.30 "lendario" 0.12 "mitico" 0.03}
   "prata"     {"raro" 0.35 "epico" 0.35 "lendario" 0.22 "mitico" 0.08}
   "ouro"      {"raro" 0.20 "epico" 0.35 "lendario" 0.30 "mitico" 0.15}
   "diamante"  {"raro" 0.10 "epico" 0.25 "lendario" 0.40 "mitico" 0.25}})

(defn select-boss-for-ginasio
  "Combina ginásio A6 (liga/nível) + seleção dinâmica do catálogo completo.
  Prefere espécies do `:pool`/`:time` do ginásio quando elegíveis; senão
  sorteia entre todas as elegíveis com pesos da liga."
  [ginasio-id opts]
  (let [g (gym/obter-ginasio (str/lower-case (str/trim (str ginasio-id))))]
    (when-not g
      (throw (err/error :raid_gym_unknown {:ginasio ginasio-id})))
    (let [nivel (long (:nivel g))
          liga (raid/liga-para-nivel nivel)
          liga-id (:id liga)
          weights (or (get weights-por-liga liga-id) category-weights-default)
          theme (set (map str (concat (or (:pool g) []) (or (:time g) []))))
          cat (or (:catalog opts) (catalog))
          elig (eligible-species cat)
          theme-elig (filterv #(contains? theme (str (:species %))) elig)
          ;; Soft-bias: se o tema tem elegíveis (raro+), 60% sorteia só nesse pool.
          rng* (or (:rng opts) rand)
          theme-roll (double (rng*))
          use-theme? (and (seq theme-elig) (< theme-roll 0.60))
          catalog* (if use-theme?
                     (assoc cat :species (mapv #(dissoc % :raridade) theme-elig))
                     cat)
          picked (select-boss (merge (dissoc opts :rng)
                                     {:nivel nivel
                                      :catalog catalog*
                                      :category-weights weights
                                      :rng rng*}))]
      {:ginasio (:id g)
       :nome-ginasio (:nome g)
       :nivel-ginasio nivel
       :liga liga-id
       :chefe (:chefe picked)
       :categoria (:categoria picked)
       :catalog-version (:catalog-version picked)
       :rolls (:rolls picked)
       :theme-pool? use-theme?})))

(defn simulate-distribution
  "Simula N sorteios em memória (sem criar raids). Retorna frequências."
  [n opts]
  (let [n (long n)
        rng-seq (or (:rng-seq opts)
                    (let [r (java.util.Random. (long (or (:seed opts) 42)))]
                      (repeatedly #(.nextDouble r))))
        rolls (atom rng-seq)
        next-roll! (fn []
                     (let [x (first @rolls)]
                       (swap! rolls rest)
                       (double x)))
        recent (atom (vec (:recent-species opts [])))
        window (long (or (:recent-window opts) recent-window-default))
        counts-cat (atom {})
        counts-sp (atom {})
        counts-gen (atom {})]
    (dotimes [_ n]
      (let [picked (select-boss
                    (merge opts
                           {:rng next-roll!
                            :recent-species @recent
                            :recent-window window}))
            sp (:species picked)
            gen (get-in picked [:chefe :generation])]
        (swap! counts-cat update (:categoria picked) (fnil inc 0))
        (swap! counts-sp update sp (fnil inc 0))
        (swap! counts-gen update gen (fnil inc 0))
        (swap! recent #(vec (take window (cons sp %))))))
    {:n n
     :por-categoria @counts-cat
     :por-geracao @counts-gen
     :especies-distintas (count @counts-sp)
     :top-especies (->> @counts-sp
                        (sort-by val >)
                        (take 15)
                        (mapv (fn [[k v]] {:species k :n v})))
     :especie-unica-dominante?
     (let [mx (apply max 0 (vals @counts-sp))]
       (> (/ (double mx) n) 0.05))}))
