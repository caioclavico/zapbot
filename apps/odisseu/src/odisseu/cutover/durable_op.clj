(ns odisseu.cutover.durable-op
  "M8.4 — coordenador sintético prepare → Datomic → journal-durable → HTTP ok.
   Dois storages sem 2PC: política :strict fail-closed se journal falhar após commit.
   Stores reais (bridge) AINDA NÃO integrados — só harness sintético."
  (:require [clojure.string :as str]
            [odisseu.cutover.op-journal :as journal]))

(def state-machine
  "Estados e transições documentadas (sem atomicidade distribuída)."
  {:states #{:new :prepared :datomic-committed :journal-durable :http-acked :uncertain :aborted}
   :transitions
   [[:new :prepared "write-intent / prepare journal"]
    [:prepared :datomic-committed "transact Datomic + receipt"]
    [:datomic-committed :journal-durable "append journal + fsync"]
    [:journal-durable :http-acked "responder 200 ao Node"]
    [:datomic-committed :uncertain "journal append falhou"]
    [:prepared :aborted "Datomic falhou antes de commit"]
    [:uncertain :http-acked "PROIBIDO em política :strict"]]
   :exactly-once-across-stores? false
   :client-success-requires #{:journal-durable}})

(def domain-coverage
  "Inventário honesto: o que PRECISARIA do protocolo vs o que o harness cobre.
   wired-in-stores? = false enquanto proteção global incompleta (M8.5: só coins)."
  {:wired-in-stores? false
   :coins-delta-wired-externally? true
   :synthetic-harness-kinds #{:coins-delta :score-point :block-commands :remember-admin}
   :pokemon-needed
   [:capture :hp :xp :coins :evolution :battle :save-switch :joy-collect :shop-buy :shop-use]
   :platform-needed
   [:rank-score :bloqueio :admins :enquetes-vote :quiz-historico :lembretes
    :bugs :participantes]
   :nota
   "Ver odisseu.cutover.coins-durable/coverage para coins-delta real."})

(defn- scope-ok?
  [scope]
  (and (map? scope)
       (or (:chat scope) (get scope "chat"))))

(defn execute!
  "Executa op durável sob política.
   opts:
     :policy :strict | :best-effort
     :journal-dir
     :maint-dir
     :datomic-fn  (fn [] result)
     :hooks       crash injection points
     :op          {:operation-id :domain :kind :scope :payload :fence :replayable?}"
  [{:keys [policy journal-dir maint-dir datomic-fn hooks op]
    :or {policy :strict
         hooks {}}}]
  (let [{:keys [operation-id domain kind scope payload fence replayable?]} op
        ph (journal/payload-hash payload)]
    (when-not (scope-ok? scope)
      (throw (ex-info "durable_op_scope_required" {:scope scope})))
    (when (str/blank? (str operation-id))
      (throw (ex-info "durable_op_missing_operation_id" {})))

    (when-let [h (:before-prepare hooks)] (h))
    (let [prep (journal/write-intent!
                journal-dir
                {:operation-id operation-id
                 :domain domain
                 :kind kind
                 :scope scope
                 :payload payload
                 :fence fence
                 :replayable? replayable?}
                (select-keys hooks [:before-write :during-write
                                    :after-fsync-before-move :after-move]))
          _ (when-let [h (:after-prepare hooks)] (h))

          _ (when-let [h (:before-datomic hooks)] (h))
          dres (try
                 (when-let [h (:during-datomic hooks)] (h))
                 (datomic-fn)
                 (catch Exception ex
                   (journal/append-phase!
                    journal-dir
                    {:operation-id operation-id
                     :domain domain :kind kind :scope scope :payload payload
                     :phase :aborted
                     :datomic-confirmed? false
                     :nota (.getMessage ex)})
                   (throw ex)))
          _ (when-let [h (:after-datomic hooks)] (h))
          dup? (boolean (or (:duplicate dres) (:duplicate? dres)))

          jres
          (if dup?
            {:ok true :skipped-duplicate true}
            (try
              (when-let [h (:before-journal hooks)] (h))
              (let [entry (journal/append-phase!
                           journal-dir
                           {:operation-id operation-id
                            :domain domain :kind kind :scope scope
                            :payload payload :fence fence
                            :phase :journal-durable
                            :datomic-confirmed? true
                            :replayable? replayable?
                            :result-summary (select-keys dres [:duplicate :status])}
                           (select-keys hooks [:before-write :during-write
                                               :after-fsync-before-move :after-move]))]
                (when-let [h (:after-journal hooks)] (h))
                {:ok true :entry entry})
              (catch Exception ex
                ;; mark-uncertain sem hooks de crash (evitar loop de falha)
                (try
                  (journal/mark-uncertain!
                   journal-dir
                   {:operation-id operation-id
                    :domain domain :kind kind :scope scope :payload payload
                    :datomic-confirmed? true
                    :nota (.getMessage ex)})
                  (catch Exception _))
                (when (= policy :strict)
                  (journal/fail-closed! maint-dir "journal-after-datomic-failed"))
                {:ok false :at-risk true :error (.getMessage ex)})))]

      (cond
        dup?
        (do
          (when-let [h (:before-http hooks)] (h))
          {:status :ok
           :http-success? true
           :duplicate? true
           :phase :journal-durable
           :datomic dres
           :prepared prep
           :payload-hash ph})

        (:ok jres)
        (do
          (when-let [h (:before-http hooks)] (h))
          {:status :ok
           :http-success? true
           :duplicate? false
           :phase :journal-durable
           :datomic dres
           :journal (:entry jres)
           :prepared prep
           :payload-hash ph})

        (= policy :strict)
        {:status :durability_compromised
         :http-success? false
         :duplicate? false
         :phase :uncertain
         :datomic dres
         :journal jres
         :maintenance? true
         :payload-hash ph
         :nota "Datomic commitou; journal não. Cliente NÃO deve tratar como sucesso."}

        :else
        {:status :ok-at-risk
         :http-success? true
         :at-risk? true
         :phase :uncertain
         :datomic dres
         :journal jres
         :payload-hash ph}))))
