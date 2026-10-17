(ns pokemon.domain.battle
  "Estado e turnos de batalha selvagem — puro, sem I/O.
  Espelha o loop atacar-selvagem / turno-selvagem do legado (sem UI)."
  (:require [pokemon.domain.capture :as capture]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.rng :as rng]
            [pokemon.domain.status :as status]))

(def golpe-padrao
  {:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
   :poder 40 :classe :fisico})

(defn- kw-status [s] (status/->kw s))

(defn- golpe-normalizado
  [g]
  (if (and (map? g) (contains? g "slug"))
    {:slug (get g "slug")
     :nome-exibicao (get g "nome-exibicao")
     :tipo (get g "tipo")
     :poder (or (get g "poder") 0)
     :classe (keyword (get g "classe" "fisico"))
     :precisao (get g "precisao")
     :status-causado (when-let [s (get g "status-causado")] (keyword s))
     :chance-status (get g "chance-status")
     :cura (get g "cura")
     :dreno (get g "dreno")
     :recuo (get g "recuo")
     :min-acertos (get g "min-acertos")
     :max-acertos (get g "max-acertos")
     :alteracoes (get g "alteracoes")
     :alvo (when-let [a (get g "alvo")] (keyword a))}
    (cond-> g
      (string? (:classe g)) (update :classe keyword))))

(defn create-wild-battle
  "Cria estado inicial de caçada. Intimidate aplica-se na criação."
  [{:keys [battle-id chat-id player-id save-id fence-generation
           pokemon-x pokemon-o hp-x hp-o status-x status-o
           collection-index]}]
  (when-not battle-id
    (throw (err/error :missing_battle_id {})))
  (let [base {:battle-id battle-id
              :kind :wild
              :chat-id chat-id
              :player-id player-id
              :save-id save-id
              :fence-generation fence-generation
              :collection-index (long (or collection-index 0))
              :pokemons {:x pokemon-x :o pokemon-o}
              :hp {:x (long (or hp-x (:hp pokemon-x) 0))
                   :o (long (or hp-o (:hp pokemon-o) 0))}
              :status {:x (kw-status status-x) :o (kw-status status-o)}
              :estagios {:x {} :o {}}
              :defendendo {:x false :o false}
              :itens-usados {:x {} :o {}}
              :turn-index 0
              :phase :active
              :outcome nil
              :active? true}
        x-int? (= "intimidate" (or (:habilidade pokemon-x) (get pokemon-x "habilidade")))
        o-int? (= "intimidate" (or (:habilidade pokemon-o) (get pokemon-o "habilidade")))
        reduzir (fn [b marca]
                  (update-in b [:pokemons marca :ataque]
                             #(Math/round (* (double (or % 0)) (/ 2.0 3.0)))))]
    (cond-> base
      (and x-int? o-int?) (-> (reduzir :x) (reduzir :o))
      (and x-int? (not o-int?)) (reduzir :o)
      (and o-int? (not x-int?)) (reduzir :x))))

(defn- aplicar-restos
  [battle marca]
  (let [pokemon (get-in battle [:pokemons marca])
        atual (get-in battle [:hp marca])
        max-hp (long (:hp pokemon 0))
        cura (if (and (= "restos" (:item pokemon)) (pos? atual) (< atual max-hp))
               (max 1 (quot max-hp 16))
               0)
        novo (min max-hp (+ atual cura))]
    [(assoc-in battle [:hp marca] novo) (- novo atual)]))

(defn- aplicar-dot
  [battle marca]
  (let [st (get-in battle [:status marca])
        maximo (get-in battle [:pokemons marca :hp])
        dot (status/dano-por-status st maximo)]
    (if (pos? dot)
      [(update-in battle [:hp marca] #(max 0 (- % dot))) dot]
      [battle 0])))

(defn aplicar-fim-de-turno
  [battle marca]
  (let [[b _] (aplicar-restos battle marca)
        [b2 dot] (aplicar-dot b marca)]
    {:battle b2 :dot dot}))

(defn- aplicar-alteracoes
  [battle marca alteracoes]
  (reduce (fn [b {:keys [atributo estagios]}]
            (let [attr (if (keyword? atributo) atributo (keyword atributo))]
              (update-in b [:estagios marca attr]
                         (fn [atual]
                           (-> (+ (or atual 0) (long estagios)) (max -6) (min 6))))))
          battle
          (or alteracoes [])))

(defn- maybe-focus-band
  [battle def-marca hp-antes dano acertou?]
  (let [defensor (get-in battle [:pokemons def-marca])
        item (:item defensor)
        usado? (get-in battle [:itens-usados def-marca :faixa-foco])
        faixa? (and acertou?
                    (= "faixa-foco" item)
                    (= (long hp-antes) (long (:hp defensor)))
                    (>= (long dano) (long hp-antes))
                    (not usado?))]
    (if faixa?
      (-> battle
          (assoc-in [:hp def-marca] 1)
          (assoc-in [:itens-usados def-marca :faixa-foco] true))
      (assoc-in battle [:hp def-marca] (max 0 (- (long hp-antes) (long dano)))))))

(defn- finish-lose [battle]
  (assoc battle :phase :finished :outcome :lose :active? false))

(defn- finish-flee [battle]
  (assoc battle :phase :finished :outcome :flee :active? false))

(defn- finish-win [battle]
  (assoc battle :phase :opponent-fainted :outcome :win :active? false
         :aguardando-captura? true))

(defn- resolve-status-gate
  "Aplica cura espontânea / impedimento com RNG já no mapa `rng`."
  [battle marca rng]
  (let [st (get-in battle [:status marca])
        imp (status/impedimento-status st rng)
        battle (cond-> battle (:curou? imp) (assoc-in [:status marca] nil))]
    (if (:impedido? imp)
      (let [auto (if (:auto-dano? imp)
                   (status/auto-dano-confusao (get-in battle [:pokemons marca :hp]))
                   0)
            battle (update-in battle [:hp marca] #(max 0 (- % auto)))
            fim (aplicar-fim-de-turno battle marca)]
        {:battle (:battle fim)
         :impedido? true
         :auto-dano auto
         :texto (:texto imp)})
      {:battle battle :impedido? false :texto (:texto imp)})))

(defn- apply-offensive-hit
  [battle atk-marca def-marca golpe defendendo? rng]
  (let [golpe (golpe-normalizado golpe)
        atacante (get-in battle [:pokemons atk-marca])
        defensor (get-in battle [:pokemons def-marca])
        resolved (dmg/resolver-ataque
                  {:golpe golpe
                   :atacante atacante
                   :defensor defensor
                   :estagios-atacante (get-in battle [:estagios atk-marca])
                   :estagios-defensor (get-in battle [:estagios def-marca])
                   :status-defensor (get-in battle [:status def-marca])
                   :defendendo? defendendo?
                   :hp-atacante-atual (get-in battle [:hp atk-marca])
                   :rng rng})
        hp-antes (get-in battle [:hp def-marca])
        battle (maybe-focus-band battle def-marca hp-antes (:dano resolved) (:acertou? resolved))
        st-def (get-in battle [:status def-marca])
        classe (let [c (:classe golpe)] (if (keyword? c) c (keyword c)))
        novo-status (or (when (:acertou? resolved)
                          (status/tentar-status-do-golpe golpe st-def rng))
                        (when (and (:acertou? resolved)
                                   (nil? (:status-causado golpe))
                                   (contains? #{:fisico :especial} classe))
                          (status/tentar-contagiar (:tipo golpe) st-def rng)))
        battle (cond-> battle
                 novo-status (assoc-in [:status def-marca] novo-status))
        dreno (when (and (:acertou? resolved) (:dreno golpe))
                (max 1 (quot (* (:dano resolved) (long (:dreno golpe))) 100)))
        recuo (when (and (:acertou? resolved) (:recuo golpe))
                (max 1 (quot (* (:dano resolved) (long (:recuo golpe))) 100)))
        max-atk (get-in battle [:pokemons atk-marca :hp])
        battle (cond-> battle
                 dreno (update-in [:hp atk-marca] #(min max-atk (+ % dreno)))
                 recuo (update-in [:hp atk-marca] #(max 0 (- % recuo))))
        battle (if (and (:acertou? resolved) (seq (:alteracoes golpe)))
                 (let [alvo (if (= :proprio (:alvo golpe)) atk-marca def-marca)]
                   (aplicar-alteracoes battle alvo (:alteracoes golpe)))
                 battle)]
    {:battle battle :resolved resolved :status-aplicado novo-status}))

(defn- pick-wild-move
  [selvagem rng]
  (let [golpes (mapv golpe-normalizado (or (:golpes selvagem) []))
        n (count golpes)]
    (if (zero? n)
      golpe-padrao
      (let [g (nth golpes (rng/roll rng :wild-move n))]
        (if (contains? #{:fisico :especial} (:classe g))
          g
          golpe-padrao)))))

(defn- wild-response
  "Resposta do selvagem após ação válida do jogador."
  [battle player-was-defending? rng]
  (cond
    (zero? (get-in battle [:hp :x]))
    (finish-lose battle)

    (< (rng/roll rng :flee 100)
       (get capture/fuga-raridade
            (or (get-in battle [:pokemons :o :raridade]) "comum") 8))
    (finish-flee battle)

    :else
    (let [gate (resolve-status-gate battle :o rng)]
      (if (:impedido? gate)
        (let [b (:battle gate)]
          (cond
            (zero? (get-in b [:hp :o])) (finish-win b)
            (zero? (get-in b [:hp :x])) (finish-lose b)
            :else (assoc-in b [:defendendo :x] false)))
        (let [golpe (pick-wild-move (get-in battle [:pokemons :o]) rng)
              hit (apply-offensive-hit (:battle gate) :o :x golpe
                                       player-was-defending? rng)
              b (assoc-in (:battle hit) [:defendendo :x] false)
              fim (aplicar-fim-de-turno b :o)
              b (:battle fim)]
          (cond
            (zero? (get-in b [:hp :x])) (finish-lose b)
            (zero? (get-in b [:hp :o])) (finish-win b)
            :else b))))))

(defn apply-player-turn
  "Aplica :attack | :defend do jogador e a resposta do selvagem.
  Incrementa turn-index. Exige phase=:active."
  [battle {:keys [action move-index]} rng]
  (when-not (and (= :active (:phase battle)) (:active? battle))
    (throw (err/error :battle_not_active {:phase (:phase battle)})))
  (let [rng (or rng {})
        defending-before? (boolean (get-in battle [:defendendo :x]))]
    (case action
      :defend
      (let [gate (resolve-status-gate battle :x rng)]
        (if (:impedido? gate)
          (let [b (wild-response (:battle gate) false rng)]
            (-> b (update :turn-index inc)
                (assoc :last-action {:action :defend :impedido? true})))
          (let [b (assoc-in (:battle gate) [:defendendo :x] true)
                fim (aplicar-fim-de-turno b :x)
                b (wild-response (:battle fim) true rng)]
            (-> b (update :turn-index inc)
                (assoc :last-action {:action :defend})))))

      :attack
      (let [golpes (vec (or (get-in battle [:pokemons :x :golpes]) []))
            idx (long (or move-index 0))]
        (when-not (< -1 idx (count golpes))
          (throw (err/error :invalid_move_index {:idx idx :count (count golpes)})))
        (let [golpe (golpe-normalizado (nth golpes idx))
              gate (resolve-status-gate battle :x rng)]
          (if (:impedido? gate)
            (let [b (wild-response (:battle gate) false rng)]
              (-> b (update :turn-index inc)
                  (assoc :last-action {:action :attack :impedido? true :move-index idx})))
            (let [classe (:classe golpe)
                  b (:battle gate)
                  b (if (and (= :status classe) (:cura golpe))
                      (let [max-hp (get-in b [:pokemons :x :hp])
                            cura (max 1 (quot (* max-hp (long (:cura golpe))) 100))]
                        (update-in b [:hp :x] #(min max-hp (+ % cura))))
                      b)
                  hit (if (contains? #{:fisico :especial} classe)
                        (apply-offensive-hit b :x :o golpe false rng)
                        {:battle (if (seq (:alteracoes golpe))
                                   (let [alvo (if (= :adversario (:alvo golpe)) :o :x)]
                                     (aplicar-alteracoes b alvo (:alteracoes golpe)))
                                   b)
                         :resolved {:dano 0 :acertou? true}})
                  b (:battle hit)
                  fim (aplicar-fim-de-turno b :x)
                  b (:battle fim)
                  b (cond
                      (zero? (get-in b [:hp :o])) (finish-win b)
                      (zero? (get-in b [:hp :x])) (finish-lose b)
                      :else (wild-response b defending-before? rng))]
              (-> b
                  (update :turn-index inc)
                  (assoc :last-action {:action :attack
                                       :move-index idx
                                       :dano (get-in hit [:resolved :dano] 0)
                                       :acertou? (get-in hit [:resolved :acertou?])}))))))

      (throw (err/error :unknown_battle_action {:action action})))))

(defn reward-for-outcome
  "Derrota/fuga: +1 XP no Pokémon ativo (legado). Vitória espera captura."
  [outcome]
  (case outcome
    (:lose :flee) {:pokemon-xp 1 :coins 0}
    :win {:pokemon-xp 0 :coins 0 :awaiting-capture? true}
    {}))
