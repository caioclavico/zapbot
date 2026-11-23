(ns pokemon.domain.raid-counter-selection
  "Seleção determinística do melhor counter para `!pk raid entrar auto`.
  Usa dados reais do chefe em `:chefe` (tipos, stats, golpes) — sem expor nome/espécie.
  Alinha ofensiva a `pokemon.domain.raid/calcular-dano` (sem STAB, como o combate).
  Sobrevivência usa `revide-chefe` (ceil HP máx / 5). Sem favorecer lendário/nível alto."
  (:require [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid-domain]
            [pokemon.domain.types :as types]))

;; Variação fixa 100% — score determinístico (mesmo `:raid-damage-var` que testes M3.6).
(def ^:private score-rng {:raid-damage-var 15})

(defn golpe-ofensivo?
  "Espelha `raid/golpe-dano?` (fisico|especial, poder>0)."
  [golpe]
  (and golpe
       (contains? #{"fisico" "especial" :fisico :especial}
                  (or (:classe golpe) (get golpe "classe")))
       (pos? (long (or (:poder golpe) (get golpe "poder") 0)))))

(defn apto?
  [pokemon]
  (and pokemon
       (pos? (long (or (:hp-atual pokemon) (:hp pokemon) 0)))
       (some golpe-ofensivo? (or (:golpes pokemon) []))))

(defn- chefe-context
  "Contexto de combate do chefe — só campos usados no dano (sem identidade)."
  [raid]
  (let [chefe (or (:chefe raid) {})
        tipos (vec (map str (or (:tipos chefe) (get chefe "tipos") [])))
        golpes (vec (or (:golpes chefe) (get chefe "golpes") []))
        hp-chefe (long (or (:hp-chefe raid)
                           (:hp chefe) (get chefe "hp")
                           100))]
    {:tipos tipos
     :habilidade (or (:habilidade chefe) (get chefe "habilidade"))
     :golpes golpes
     :ataque (long (or (:ataque chefe) (get chefe "ataque") 50))
     :atq-esp (long (or (:atq-esp chefe) (get chefe "atq-esp") 50))
     :defesa (long (or (:defesa chefe) (get chefe "defesa") 50))
     :def-esp (long (or (:def-esp chefe) (get chefe "def-esp") 50))
     :hp-chefe hp-chefe
     :present? (boolean (and (:chefe raid)
                             (or (seq tipos) (seq golpes))))}))

(defn- raid-calc-context
  "Mapa compatível com `raid/calcular-dano` na fase de lobby (defesas do chefe)."
  [raid ctx]
  (assoc raid
         :hp-chefe (:hp-chefe ctx)
         :defesa (max 1 (:defesa ctx))
         :def-esp (max 1 (:def-esp ctx))
         :chefe {:tipos (:tipos ctx) :habilidade (:habilidade ctx)}))

(defn- precisao-efetiva
  [golpe]
  (let [p (or (:precisao golpe) (get golpe "precisao"))
        chance (dmg/chance-acerto p {} {} (:ignora-precisao golpe))]
    (if chance (/ (double chance) 100.0) 1.0)))

(defn- golpes-normalizados
  [pokemon]
  (->> (or (:golpes pokemon) [])
       (mapv battle/golpe-normalizado)
       (filterv golpe-ofensivo?)))

(defn expected-dano-vs-chefe
  "Dano esperado de um golpe (variação 100%, precisão quando existir)."
  [golpe pokemon raid-ctx]
  (let [calc (raid-domain/calcular-dano golpe pokemon raid-ctx score-rng)
        dano (long (or (:dano calc) 0))]
    (* dano (precisao-efetiva golpe))))

(defn- calcular-dano-chefe-vs-mon
  "Espelha a fórmula de raid com papéis invertidos (ameaça defensiva)."
  [golpe chefe-ctx pokemon]
  (let [classe (or (:classe golpe) (get golpe "classe"))
        especial? (contains? #{"especial" :especial} classe)
        ataque (long (if especial? (:atq-esp chefe-ctx) (:ataque chefe-ctx)))
        def-mon (max 1 (long (or ((if especial? :def-esp :defesa) pokemon)
                                 (get pokemon (if especial? "def-esp" "defesa"))
                                 50)))
        poder (long (or (:poder golpe) (get golpe "poder") 0))
        tipo-golpe (str (or (:tipo golpe) (get golpe "tipo") "normal"))
        tipos-def (vec (map str (or (:tipos pokemon) (get pokemon "tipos") [])))
        hab-def (or (:habilidade pokemon) (get pokemon "habilidade"))
        mult (types/multiplicador-vs-tipos tipo-golpe tipos-def hab-def false)
        raw (long (Math/floor (+ 15 (* 0.6 poder (/ (double ataque) def-mon)))))
        base (max 10 (min 160 raw))
        com-tipo (long (Math/round (* (double base) mult)))]
    (cond
      (zero? (double mult)) 0
      (pos? com-tipo) com-tipo
      :else 1)))

(defn- revide-por-turno
  [pokemon]
  (let [max-hp (long (or (:hp pokemon) (get pokemon "hp") 100))]
    (max 1 (long (Math/ceil (/ (double max-hp) 5.0))))))

(defn legacy-auto-score
  "Critério antigo (soma stats + melhor poder) — só para comparação em testes/docs."
  [pokemon]
  (let [stats (reduce + 0
                      (map #(long (or (get pokemon %) (get pokemon (name %)) 0))
                           [:hp-atual :ataque :defesa :atq-esp :def-esp :veloc]))
        max-poder (reduce max 0
                          (map #(long (or (:poder %) (get % "poder") 0))
                               (or (:golpes pokemon) [])))]
    (+ stats (long max-poder))))

(defn score-breakdown
  "Mapa legível para testes e relatório old vs new."
  [raid pokemon]
  (let [ctx (chefe-context raid)
        raid-ctx (raid-calc-context raid ctx)
        golpes (golpes-normalizados pokemon)
        danos (mapv #(expected-dano-vs-chefe % pokemon raid-ctx) golpes)
        best-off (if (seq danos) (apply max danos) 0)
        avg-off (if (seq danos)
                  (/ (double (reduce + danos)) (count danos))
                  0.0)
        boss-moves (->> (:golpes ctx)
                        (mapv battle/golpe-normalizado)
                        (filterv golpe-ofensivo?))
        ameacas (mapv #(calcular-dano-chefe-vs-mon % ctx pokemon) boss-moves)
        worst-threat (if (seq ameacas) (apply max ameacas) 0)
        ;; Resistência: inverso da pior ameaça (turnos até cair só pelo revide).
        hp-atual (long (or (:hp-atual pokemon) (:hp pokemon) 0))
        turns-revide (/ (double hp-atual) (double (revide-por-turno pokemon)))
        defensive-bonus (if (pos? worst-threat)
                          (/ 160.0 (+ 10.0 (double worst-threat)))
                          1.0)
        combat (+ (* 0.70 (double best-off))
                  (* 0.20 (double avg-off))
                  (* 80.0 defensive-bonus)
                  (* 12.0 turns-revide)
                  (* 0.02 (double hp-atual)))]
    {:combat-score (long (Math/round (* 1000.0 combat)))
     :best-offensive (long best-off)
     :avg-offensive (long (Math/round avg-off))
     :worst-boss-threat (long worst-threat)
     :turns-vs-revide turns-revide
     :hp-atual hp-atual
     :legacy (legacy-auto-score pokemon)
     :boss-known? (:present? ctx)
     :golpe-count (count golpes)}))

(defn combat-score
  [raid pokemon]
  (:combat-score (score-breakdown raid pokemon)))

(defn- tie-breaker
  "Desempate: menor `:collection-index`."
  [pokemon]
  (long (or (:collection-index pokemon) (get pokemon "collection-index") 0)))

(defn- ranking-score
  "Score único: combat/legacy × 1e6 + bônus por índice menor."
  [raid pokemon use-combat?]
  (let [base (if use-combat?
               (combat-score raid pokemon)
               (legacy-auto-score pokemon))
        idx (tie-breaker pokemon)]
    (+ (* base 1000000) (- 1000000 idx))))

(defn pick-best-collection-index
  "Melhor Pokémon elegível desta coleção. Lança `:invalid_raid_pokemon` se nenhum apto."
  [raid colecao]
  (let [aptos (filterv #(apto? %) colecao)]
    (when-not (seq aptos)
      (throw (err/error :invalid_raid_pokemon {:auto? true})))
    (let [ctx (chefe-context raid)
          use-combat? (:present? ctx)
          best (apply max-key #(ranking-score raid % use-combat?) aptos)
          ci (:collection-index best)]
      (if (some? ci)
        (long ci)
        (long (or (first (keep-indexed
                          (fn [i p] (when (identical? p best) i))
                          colecao))
                  0))))))

(defn compare-old-vs-new
  "Para relatório: índices e scores legacy/combat de cada apto."
  [raid colecao]
  (let [aptos (filterv apto? colecao)]
    {:picked (pick-best-collection-index raid colecao)
     :candidates
     (mapv (fn [p]
             (let [br (score-breakdown raid p)
                   idx (long (or (:collection-index p) 0))]
               {:collection-index idx
                :combat (:combat-score br)
                :legacy (:legacy br)
                :best-offensive (:best-offensive br)
                :worst-threat (:worst-boss-threat br)}))
           aptos)
     :legacy-pick
     (long (or (:collection-index
                (apply max-key #(ranking-score raid % false) aptos))
                0))}))