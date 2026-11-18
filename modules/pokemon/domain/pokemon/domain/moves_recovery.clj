(ns pokemon.domain.moves-recovery
  "Recuperação determinística de golpes `(irrecuperável)` — pura, sem I/O.
  Não altera slots válidos. Dry-run por padrão; apply só com `:apply? true`.

  Ordem de resolução por slot inválido:
  1. slug/id legado recuperável via catálogo opcional
  2. nome/alias legado
  3. histórico canônico (`historico-golpes`)
  4. ofertas pendentes
  5. candidato de evolução/nível (`moves/candidato-nivel`)
  6. STAB básico via `moves/garantir-ataque-do-tipo` (determinístico)

  Sem inventar poder/tipo fora do catálogo. Sem aleatoriedade."
  (:require [clojure.string :as str]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.moves-audit :as audit]))

(defn- blank? [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn- slot-irrecuperavel?
  [g]
  (let [a (audit/analisar-golpe g)]
    (or (true? (:golpe-irrecuperavel? a))
        (contains? (set (:problemas a)) :irrecuperavel)
        (and (contains? (set (:problemas a)) :sem-nome)
             (contains? (set (:problemas a)) :sem-slug)))))

(defn- catalog-lookup
  "Resolve golpe a partir de identificador usando catálogo externo opcional."
  [ident {:keys [catalog]}]
  (let [id (when-not (blank? ident) (moves/identificador ident))
        from-ext (when (and id (map? catalog))
                   (or (get catalog id)
                       (get catalog (keyword id))
                       (get catalog (str ident))))]
    (when (map? from-ext)
      (moves/registro->golpe (moves/golpe->registro from-ext)))))

(defn- from-historico
  [registro hints]
  (some (fn [h]
          (when-not (blank? h)
            (moves/golpe-do-historico registro h)))
        hints))

(defn- from-ofertas
  [registro hints]
  (let [fila (moves/ofertas registro)
        ids (set (map moves/identificador (remove blank? hints)))]
    (some (fn [g]
            (when (contains? ids (moves/chave g)) g))
          fila)))

(defn- stab-basico
  "Primeiro STAB canônico do tipo — determinístico via garantir-ataque-do-tipo."
  [registro]
  (let [tipos (or (:tipos registro) (get registro "tipos") ["normal"])
        filled (moves/garantir-ataque-do-tipo [] tipos)]
    (first filled)))

(defn- evo-candidato
  "Preferência: golpe que seria ofertado na evolução/nível atual."
  [registro]
  (or (moves/candidato-nivel registro)
      (stab-basico registro)))

(defn- hints-from-slot
  [g]
  (let [n (battle/golpe-normalizado g)]
    (->> [(when (map? g) (or (:slug g) (get g "slug")))
          (when (map? g) (or (:nome-exibicao g) (get g "nome-exibicao")
                             (:nome g) (get g "nome")))
          (when (string? g) g)
          (:slug n) (:nome-exibicao n)]
         (remove blank?)
         distinct
         vec)))

(defn- resolve-slot
  "Retorna plano de um slot: :ok | :recovered | :needs-approval."
  [registro idx g opts]
  (if-not (slot-irrecuperavel? g)
    {:slot idx :status :ok :golpe g :action :keep}
    (let [hints (hints-from-slot g)
          via-catalog (some #(catalog-lookup % opts) hints)
          via-hist (from-historico registro hints)
          via-oferta (from-ofertas registro hints)
          via-evo (evo-candidato registro)
          ;; Catálogo/histórico/oferta = identificação do original.
          ;; Evo/STAB = substituto legítimo quando original é impossível.
          escolhido (or via-catalog via-hist via-oferta)
          fonte (cond
                  via-catalog :catalogo
                  via-hist :historico
                  via-oferta :oferta
                  :else nil)]
      (cond
        (and escolhido (not (blank? (:nome-exibicao (battle/golpe-normalizado escolhido)))))
        {:slot idx
         :status :recovered
         :action :replace
         :fonte fonte
         :hints hints
         :golpe-anterior g
         :golpe (moves/registro->golpe (moves/golpe->registro escolhido))
         :justificativa
         (str "Slot " idx " irrecuperável; restaurado via " (name fonte)
              " → " (:nome-exibicao (battle/golpe-normalizado escolhido))
              " (determinístico).")}

        ;; Substituição legítima e determinística (preferência evolução).
        (and via-evo (not (blank? (:nome-exibicao (battle/golpe-normalizado via-evo)))))
        {:slot idx
         :status :recovered
         :action :replace
         :fonte :evolucao-ou-stab
         :hints hints
         :golpe-anterior g
         :golpe (moves/registro->golpe (moves/golpe->registro via-evo))
         :justificativa
         (str "Slot " idx " sem ID original; substituto determinístico de evolução/STAB → "
              (:nome-exibicao (battle/golpe-normalizado via-evo)) ".")}

        :else
        {:slot idx
         :status :needs-approval
         :action :none
         :hints hints
         :golpe-anterior g
         :candidato nil
         :justificativa
         (str "Slot " idx " sem identificação segura nem STAB canônico.")}))))

(defn plan-pokemon
  "Dry-run de um registro. Idempotente na leitura."
  ([registro] (plan-pokemon registro nil))
  ([registro opts]
   (let [golpes (vec (or (:golpes registro) (get registro "golpes") []))
         slots (mapv #(resolve-slot registro %1 %2 opts) (range) golpes)
         recovered (filterv #(= :recovered (:status %)) slots)
         needs (filterv #(= :needs-approval (:status %)) slots)]
     {:species (str (or (:species registro) (:nome registro) "unknown"))
      :id-pokemon (:id-pokemon registro)
      :n-golpes (count golpes)
      :n-ok (count (filter #(= :ok (:status %)) slots))
      :n-recovered (count recovered)
      :n-needs-approval (count needs)
      :slots slots
      :safe-to-apply? (and (seq recovered) (empty? needs))
      :apply? false})))

(defn apply-plan
  "Aplica só slots `:recovered`. Não toca `:ok` nem `:needs-approval`.
   Idempotente: reaplicar o mesmo plano não altera slots já válidos."
  [registro plan]
  (let [golpes (vec (or (:golpes registro) (get registro "golpes") []))
        by-slot (into {} (map (juxt :slot identity) (:slots plan)))
        novos (mapv (fn [idx g]
                      (let [s (get by-slot idx)]
                        (if (and (= :recovered (:status s)) (= :replace (:action s)))
                          (moves/golpe->registro (:golpe s))
                          (if (map? g)
                            (moves/golpe->registro g)
                            g))))
                    (range) golpes)]
    (assoc registro :golpes novos)))

(defn recover-pokemon
  "Planeja e, se `:apply? true` e seguro (ou `:force-recovered-only? true`), aplica."
  ([registro] (recover-pokemon registro nil))
  ([registro {:keys [apply? force-recovered-only?] :as opts}]
   (let [plan (plan-pokemon registro opts)]
     (cond
       (not apply?)
       (assoc plan :registro registro)

       (or (:safe-to-apply? plan) force-recovered-only?)
       (let [reg' (apply-plan registro plan)
             plan2 (plan-pokemon reg' opts)]
         (assoc plan2
                :apply? true
                :registro reg'
                :applied-slots (mapv :slot (filter #(= :recovered (:status %)) (:slots plan)))))

       :else
       (assoc plan :registro registro :apply? false
              :blocked "needs-approval")))))

(defn audit-collection
  "Agrega planos anônimos (sem PII)."
  ([pokemons] (audit-collection pokemons nil))
  ([pokemons opts]
   (let [plans (mapv #(plan-pokemon % opts) pokemons)
         ruins (filterv #(or (pos? (:n-recovered %)) (pos? (:n-needs-approval %))) plans)]
     {:ok true
      :total-pokemon (count plans)
      :com-slots-invalidos (count ruins)
      :recuperaveis-seguros (count (filter :safe-to-apply? plans))
      :precisam-aprovacao (count (filter #(pos? (:n-needs-approval %)) plans))
      :amostras-species (mapv :species (take 20 ruins))
      :notes ["Auditoria RO — sem player-id/chat-id."
              "Não executa backfill. apply? permanece false."]})))
