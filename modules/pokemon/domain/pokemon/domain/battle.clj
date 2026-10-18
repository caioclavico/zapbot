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
  (assoc battle :phase :finished :outcome :lose :active? false
         :aguardando-captura? false))

(defn- finish-flee [battle]
  (assoc battle :phase :finished :outcome :flee :active? false
         :aguardando-captura? false))

(defn- finish-win [battle]
  (assoc battle :phase :opponent-fainted :outcome :win :active? false
         :aguardando-captura? true))

(defn- finish-gym-win [battle]
  (assoc battle :phase :finished :outcome :win :active? false
         :aguardando-captura? false))

(defn- finish-gym-lose [battle]
  (assoc battle :phase :finished :outcome :lose :active? false
         :aguardando-captura? false))

(defn- finish-gym-draw [battle]
  (assoc battle :phase :finished :outcome :draw :active? false
         :aguardando-captura? false))

(defn- registrar-nocaute
  "Conta KO para o Pokémon ativo do lado oposto ao caído."
  [battle caido]
  (let [marca (if (= caido :x) :o :x)
        idx (get-in battle [:indices-ativos marca])]
    (if (and (zero? (long (get-in battle [:hp caido] 0))) (some? idx))
      (update-in battle [:participacao marca idx] (fnil inc 0))
      battle)))

(defn- enviar-proximo
  "Traz o próximo da reserva; retorna nil se não houver."
  [battle marca]
  (let [reservas (vec (get-in battle [:reservas marca] []))]
    (when (seq reservas)
      (let [prox (first reservas)
            npc? (and (= :gym (:kind battle)) (= marca :o))
            pokemon (if npc?
                      prox
                      (:pokemon prox))
            idx (when-not npc? (:collection-index prox))
            hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))
            status (:status pokemon)]
        (-> battle
            (assoc-in [:reservas marca] (vec (rest reservas)))
            (assoc-in [:pokemons marca] pokemon)
            (assoc-in [:hp marca] hp)
            (assoc-in [:status marca] (kw-status status))
            (assoc-in [:estagios marca] {})
            (assoc-in [:defendendo marca] false)
            (assoc-in [:itens-usados marca] {})
            (cond-> idx (assoc-in [:indices-ativos marca] idx)
                    (and idx (nil? (get-in battle [:participacao marca idx])))
                    (assoc-in [:participacao marca idx] 0))
            (assoc :last-switch {:side marca :nome (:nome pokemon)}))))))

(defn- mark-fainted-x
  [battle]
  (if-let [idx (get-in battle [:indices-ativos :x])]
    (update battle :fainted-x (fnil conj #{}) (long idx))
    battle))

(defn- resolve-faint
  "Após HP 0: em caça encerra; em ginásio troca reserva ou encerra o match."
  [battle fainted-side]
  (if (not= :gym (:kind battle))
    (if (= fainted-side :o)
      (finish-win battle)
      (finish-lose battle))
    (let [b0 (cond-> battle (= fainted-side :x) mark-fainted-x)
          b (registrar-nocaute b0 fainted-side)
          both-down? (and (zero? (long (get-in b [:hp :x] 0)))
                          (zero? (long (get-in b [:hp :o] 0))))]
      (if both-down?
        (let [bx (or (when (zero? (get-in b [:hp :x])) (enviar-proximo b :x)) b)
              bo (or (when (zero? (get-in bx [:hp :o])) (enviar-proximo bx :o)) bx)
              still-x (pos? (long (get-in bo [:hp :x] 0)))
              still-o (pos? (long (get-in bo [:hp :o] 0)))]
          (cond
            (and still-x still-o) bo
            still-x (finish-gym-win bo)
            still-o (finish-gym-lose bo)
            :else (finish-gym-draw bo)))
        (if-let [swapped (enviar-proximo b fainted-side)]
          swapped
          (if (= fainted-side :o)
            (finish-gym-win b)
            (finish-gym-lose b)))))))

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
  "Resposta do adversário (selvagem ou líder de ginásio) após ação do jogador.
  Ginásio: sem fuga."
  [battle player-was-defending? rng]
  (cond
    (zero? (get-in battle [:hp :x]))
    (resolve-faint battle :x)

    (and (not= :gym (:kind battle))
         (< (rng/roll rng :flee 100)
            (get capture/fuga-raridade
                 (or (get-in battle [:pokemons :o :raridade]) "comum") 8)))
    (finish-flee battle)

    :else
    (let [gate (resolve-status-gate battle :o rng)]
      (if (:impedido? gate)
        (let [b (:battle gate)]
          (cond
            (zero? (get-in b [:hp :o])) (resolve-faint b :o)
            (zero? (get-in b [:hp :x])) (resolve-faint b :x)
            :else (assoc-in b [:defendendo :x] false)))
        (let [golpe (pick-wild-move (get-in battle [:pokemons :o]) rng)
              hit (apply-offensive-hit (:battle gate) :o :x golpe
                                       player-was-defending? rng)
              b (assoc-in (:battle hit) [:defendendo :x] false)
              fim (aplicar-fim-de-turno b :o)
              b (:battle fim)]
          (cond
            (zero? (get-in b [:hp :x])) (resolve-faint b :x)
            (zero? (get-in b [:hp :o])) (resolve-faint b :o)
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
                      (zero? (get-in b [:hp :o]))
                      (let [b2 (resolve-faint b :o)]
                        ;; Ginásio: novo líder entra e age nesta mesma rodada.
                        (if (and (= :gym (:kind b2)) (:active? b2)
                                 (pos? (long (get-in b2 [:hp :o] 0)))
                                 (pos? (long (get-in b2 [:hp :x] 0))))
                          (wild-response b2 defending-before? rng)
                          b2))
                      (zero? (get-in b [:hp :x])) (resolve-faint b :x)
                      :else (wild-response b defending-before? rng))]
              (-> b
                  (update :turn-index inc)
                  (assoc :last-action {:action :attack
                                       :move-index idx
                                       :dano (get-in hit [:resolved :dano] 0)
                                       :acertou? (get-in hit [:resolved :acertou?])}))))))

      (throw (err/error :unknown_battle_action {:action action})))))

(defn create-gym-battle
  "Combate 3×3 de ginásio. `team-x` = 3 mapas com :collection-index;
  `team-o` = 3 Pokémon (NPC ou ocupação já com motivação)."
  [{:keys [battle-id chat-id player-id save-id fence-generation
           gym-id gym-nome team-x team-o expected-revision lider-nome
           desafiante-nome]}]
  (when-not battle-id
    (throw (err/error :missing_battle_id {})))
  (when-not (and (= 3 (count team-x)) (= 3 (count team-o)))
    (throw (err/error :invalid_gym_team
                      {:x (count team-x) :o (count team-o)})))
  (let [normalize-x (fn [p]
                      {:collection-index (long (:collection-index p))
                       :pokemon (dissoc p :collection-index)})
        xs (mapv normalize-x team-x)
        first-x (:pokemon (first xs))
        first-o (first team-o)
        idx0 (:collection-index (first xs))
        base {:battle-id battle-id
              :kind :gym
              :gym-id gym-id
              :gym-nome gym-nome
              :expected-revision (long (or expected-revision 0))
              :lider-nome lider-nome
              :desafiante-nome (or desafiante-nome player-id)
              :chat-id chat-id
              :player-id player-id
              :save-id save-id
              :fence-generation fence-generation
              :team-x-indices (mapv :collection-index xs)
              :pokemons {:x first-x :o first-o}
              :hp {:x (long (or (:hp-atual first-x) (:hp first-x) 0))
                   :o (long (or (:hp-atual first-o) (:hp first-o) 0))}
              :status {:x (kw-status (:status first-x)) :o (kw-status (:status first-o))}
              :estagios {:x {} :o {}}
              :defendendo {:x false :o false}
              :itens-usados {:x {} :o {}}
              :indices-ativos {:x idx0 :o nil}
              :participacao {:x {idx0 0} :o {}}
              :reservas {:x (vec (rest xs))
                         :o (vec (rest team-o))}
              :fainted-x #{}
              :turn-index 0
              :phase :active
              :outcome nil
              :active? true
              :aguardando-captura? false}
        x-int? (= "intimidate" (or (:habilidade first-x) (get first-x "habilidade")))
        o-int? (= "intimidate" (or (:habilidade first-o) (get first-o "habilidade")))
        reduzir (fn [b marca]
                  (update-in b [:pokemons marca :ataque]
                             #(Math/round (* (double (or % 0)) (/ 2.0 3.0)))))]
    (cond-> base
      (and x-int? o-int?) (-> (reduzir :x) (reduzir :o))
      (and x-int? (not o-int?)) (reduzir :o)
      (and o-int? (not x-int?)) (reduzir :x))))

(defn reward-for-outcome
  "Derrota/fuga: +1 XP no Pokémon ativo (legado). Vitória selvagem espera captura.
  Ginásio: XP/recompensas via resolve-challenge (não aqui)."
  [outcome]
  (case outcome
    (:lose :flee) {:pokemon-xp 1 :coins 0}
    :win {:pokemon-xp 0 :coins 0 :awaiting-capture? true}
    :draw {:pokemon-xp 0 :coins 0}
    {}))
