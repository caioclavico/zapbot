(ns pokemon.infrastructure.datomic.adventure-activity
  "Enrich transacional: PI + ovos nas mesmas TXs de gameplay (A6).
  Espelha o padrão de mission-events/attach — sem fila em memória."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]))

(defn- read-edn [s] (when s (edn/read-string s)))

(defn- present? [e]
  (some? (:db/id e)))

(defn- account-shell+eggs
  [db chat-id player-id]
  (let [ak (id/adventure-account-key chat-id player-id)
        e (d/pull db '[*] [:adventure-account/key ak])
        exists? (present? e)
        shell (if exists?
                (adventure-store/account-shell e read-edn present?)
                nil)
        eggs (when exists?
               (mapv #(adventure-store/egg-entity->map % read-edn present?)
                     (map first
                          (d/q '[:find (pull ?e [*])
                                 :in $ ?ak
                                 :where [?e :egg/account-key ?ak]]
                               db ak))))]
    {:exists? exists?
     :shell shell
     :eggs (vec eggs)}))

(defn- first-daily-hunt?
  [shell agora]
  (let [day (egg-activity/utc-day agora)
        marker (str "first-daily-hunt:" day)]
    (not (contains? (:receipts shell #{}) marker))))

(defn- mark-first-daily!
  "Anota no summary que o marker será gravado via event-id dedicado no apply."
  [day]
  (str "first-daily-hunt:" day))

(defn- detect-activity
  "Deriva atividade de incubação a partir do recibo/resultado canônico."
  [db writer built receipt agora]
  (let [kind (:operation-receipt/kind receipt)
        summary (or (read-edn (:operation-receipt/summary receipt)) {})
        result (:result built)
        next (or (:battle result) (:encounter result) (:raid result))
        bid (or (:battle-id next) (:battle-id result) (:battle-id summary))
        previous (when bid (d/pull db '[*] [:battle/id bid]))
        before (or (battle/decode-compact-edn (:battle/state-edn previous)) {})
        finished? (and (:active? before) (not (:active? next)))
        winner (:outcome next)
        chat-id (:chat-id writer)
        player-id (:player-id writer)
        day (egg-activity/utc-day agora)]
    (cond
      ;; Captura selvagem (encounter ou direta)
      (and (= kind "encounter-capture") (:capturou? result))
      (let [shell-eggs (account-shell+eggs db chat-id player-id)
            shell (or (:shell shell-eggs) (eggs/estado-inicial))
            first? (first-daily-hunt? shell agora)]
        {:activity :hunt-complete
         :event-id (str "egg-act:hunt:" bid)
         :first-daily-hunt? first?
         :first-daily-marker (when first? (mark-first-daily! day))
         :suppress-wild-victory-pi? true})

      (and (= kind "capture") (:capturou? result))
      (let [shell-eggs (account-shell+eggs db chat-id player-id)
            shell (or (:shell shell-eggs) (eggs/estado-inicial))
            first? (first-daily-hunt? shell agora)
            oid (:operation-receipt/id receipt)]
        {:activity :hunt-complete
         :event-id (str "egg-act:capture:" oid)
         :first-daily-hunt? first?
         :first-daily-marker (when first? (mark-first-daily! day))
         :suppress-wild-victory-pi? true})

      (and (= kind "battle-turn") finished? (= winner :win) (= :wild (:kind next)))
      {:activity :wild-victory
       :event-id (str "egg-act:wild-win:" bid)}

      (and (= kind "battle-turn") finished? (= winner :lose) (= :wild (:kind next)))
      {:activity :wild-defeat
       :event-id (str "egg-act:wild-lose:" bid)}

      (and (= kind "battle-turn") finished? (= winner :win) (= :gym (:kind next)))
      {:activity :gym-victory
       :event-id (str "egg-act:gym-win:" bid)}

      (and (= kind "battle-turn") finished? (= winner :lose) (= :gym (:kind next)))
      {:activity :gym-defeat
       :event-id (str "egg-act:gym-lose:" bid)}

      (and (= kind "gym-resolve") (:venceu? result))
      {:activity :gym-victory
       :event-id (str "egg-act:gym-resolve:" (:operation-receipt/id receipt))}

      (and (= kind "pvp-turn") finished? (#{:x :o} winner))
      (let [winner-pid (get-in before [:jogadores winner])
            loser-side (if (= winner :x) :o :x)
            loser-pid (get-in before [:jogadores loser-side])
            i-won? (= player-id winner-pid)
            opponent (if i-won? loser-pid winner-pid)]
        (when (or i-won? (= player-id loser-pid))
          {:activity (if i-won? :pvp-victory :pvp-defeat)
           :event-id (str "egg-act:pvp:" bid ":" player-id)
           :opponent-id opponent
           ;; Writer pode ser o perdedor confirmando turno — só credita o ator.
           :player-id player-id}))

      (and (= kind "raid-reward") (#{:vitoria "vitoria" "win"} (keyword (str (:outcome summary)))))
      (let [rid (or (:raid-id summary) (get-in result [:raid :raid-id]))
            cat (or (:boss-categoria summary) (:categoria summary)
                    (get-in result [:raid :boss-categoria])
                    (get-in result [:raid :categoria]))]
        {:activity :raid-victory
         :event-id (str "egg-act:raid:" rid ":" player-id)
         :raid-category cat})

      ;; raid-attack que encerra com vitória (recompensas na mesma TX às vezes)
      (and (= kind "raid-attack") (:venceu? result))
      (let [rid (or (get-in result [:raid :raid-id]) (:raid-id summary))
            cat (or (get-in result [:raid :boss-categoria])
                    (get-in result [:raid :categoria])
                    (:categoria summary))]
        {:activity :raid-victory
         :event-id (str "egg-act:raid:" rid ":" player-id)
         :raid-category cat})

      (and (= kind "mission-claim") (or (:claimed result) (:rewarded result)
                                        (:ok result)))
      (let [oid (:operation-receipt/id receipt)
            egg-cat (or (:egg-category summary) (:eggCategory summary))]
        {:activity :daily-mission
         :event-id (str "egg-act:mission:" oid)
         :mission-egg-category (when egg-cat (keyword egg-cat))})

      :else nil)))

(defn attach
  "Anexa PI/ovo à TX se a atividade for elegível. Fail-open se conta ausente
  (cria ensure). Não altera 036/037."
  [db writer built]
  (if-not (egg-activity/activity-enabled?)
    built
    (let [receipt (some #(when (and (map? %) (:operation-receipt/id %)) %) (:tx built))
          agora (System/currentTimeMillis)
          detected (when receipt (detect-activity db writer built receipt agora))]
      (if-not detected
        built
        (let [chat-id (:chat-id writer)
              player-id (:player-id writer)
              {:keys [exists? shell eggs]} (account-shell+eggs db chat-id player-id)
              opts (cond-> {:event-id (:event-id detected)
                            :activity (:activity detected)
                            :agora agora
                            :opponent-id (:opponent-id detected)
                            :raid-category (:raid-category detected)
                            :mission-egg-category (:mission-egg-category detected)
                            :first-daily-hunt? (boolean (:first-daily-hunt? detected))
                            :suppress-wild-victory-pi?
                            (boolean (:suppress-wild-victory-pi? detected))})
              ;; Marker de primeira caçada diária: aplica-se como receipt extra
              ;; via segundo apply se necessário — embutido no mesmo event quando
              ;; first-daily-hunt? dispara ovo extra no apply-activity.
              enrich (adventure-store/build-activity-enrich-tx
                      {:writer-ctx writer
                       :account-shell (or shell (eggs/estado-inicial))
                       :egg-maps eggs
                       :activity-opts opts
                       :ensure-tx? (not exists?)})
              ;; Marca first-daily no shell se ganhou a chance (mesmo sem ovo).
              marker (:first-daily-marker detected)
              enrich (if-not marker
                       enrich
                       (let [estado-edn (some (fn [form]
                                                (when (and (vector? form)
                                                           (= :db/add (first form))
                                                           (= :adventure-account/state-edn (nth form 2 nil)))
                                                  (nth form 3)))
                                              (:tx enrich))
                             shell* (when estado-edn (edn/read-string estado-edn))
                             shell** (when shell*
                                       (update shell* :receipts
                                               #(set (conj (or % #{}) marker))))]
                         (if-not shell**
                           enrich
                           (update enrich :tx
                                   (fn [tx]
                                     (mapv (fn [form]
                                             (if (and (vector? form)
                                                      (= :db/add (first form))
                                                      (= :adventure-account/state-edn (nth form 2 nil)))
                                               (assoc form 3 (pr-str shell**))
                                               form))
                                           tx))))))
              summary0 (read-edn (:operation-receipt/summary receipt))
              summary (assoc (or summary0 {})
                             :adventure-activity
                             (get-in enrich [:result :adventure-activity]))]
          (-> built
              (update :tx #(into (mapv (fn [item]
                                         (if (= item receipt)
                                           (assoc item :operation-receipt/summary (pr-str summary))
                                           item))
                                       %)
                                 (:tx enrich)))
              (assoc-in [:result :adventure-activity]
                        (get-in enrich [:result :adventure-activity]))))))))
