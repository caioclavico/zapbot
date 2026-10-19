(ns odisseu.cutover.durable-mutation
  "M8.7 — integração reutilizável prepare→Datomic→journal→HTTP.
   Não contém regras de negócio: recebe datomic-fn + identidade/payload.
   Usado por blocks/admins (e futuros domains Platform)."
  (:require [clojure.string :as str]
            [odisseu.cutover.durable-op :as dop]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [pokemon.domain.errors :as err]))

(defn- assert-disposable-journal! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "durable_mutation_path_blocked" {:path p}))))

(defn execute!
  "Executa mutação existente sob política de durabilidade.
   opts:
     :durable  cfg durability-policy
     :datomic-fn (fn [] result) — TX real com receipt
     :op {:operation-id :domain :kind :scope :payload :fence :replayable?}
     :receipt-hash-fn (fn [payload] expected-platform.op-hash) — opcional p/ reconcile
   Sem journal (:optional): chama datomic-fn direto."
  [{:keys [durable datomic-fn op]}]
  (let [cfg (policy/assert-ready! (or durable {}))]
    (if-not (policy/journal-configured? cfg)
      (datomic-fn)
      (do
        (assert-disposable-journal! (:journal-dir cfg))
        (let [exec (dop/execute!
                    {:policy (:policy cfg)
                     :journal-dir (:journal-dir cfg)
                     :maint-dir (:maint-dir cfg)
                     :hooks (:hooks cfg)
                     :datomic-fn datomic-fn
                     :op op})]
          (when-not (:http-success? exec)
            (throw (err/error :durability_compromised
                              {:operation-id (:operation-id op)
                               :status (:status exec)
                               :phase (:phase exec)
                               :maintenance? (boolean (:maintenance? exec))
                               :nota (:nota exec)})))
          (merge (:datomic exec)
                 {:durable {:status (:status exec)
                            :phase (:phase exec)
                            :duplicate? (boolean (:duplicate? exec))
                            :payload-hash (:payload-hash exec)}}))))))

(defn receipt-matches?
  "Compara receipt platform.op com hash esperado do payload canônico do store."
  [store entry expected-hash expected-domain]
  (let [receipt (pstore/get-op-receipt store (:operation-id entry))]
    (cond
      (nil? receipt)
      {:match? false :reason :no-receipt}

      (and expected-domain (not= (str expected-domain) (str (:domain receipt))))
      {:match? false :reason :domain-mismatch :receipt receipt}

      (not= expected-hash (:payload-hash receipt))
      {:match? false :reason :payload-mismatch :receipt receipt
       :expected expected-hash :got (:payload-hash receipt)}

      :else
      {:match? true :receipt receipt})))

(defn reconcile-uncertain!
  "Se receipt comprova commit → journal-durable sem reaplicar efeito.
   Usa a última entrada com :payload (prepared/uncertain), não entradas vazias."
  [store journal-dir maint-dir operation-id
   {:keys [expected-hash-fn expected-domain]}]
  (let [entries (journal/list-entries journal-dir)
        for-op (filterv #(= (str operation-id) (str (:operation-id %))) entries)
        with-payload (filterv #(map? (:payload %)) for-op)
        last-e (or (last with-payload) (last for-op))
        phases (set (map :phase for-op))]
    (cond
      (contains? phases :journal-durable)
      {:status :already-durable :operation-id operation-id}

      (nil? last-e)
      {:status :unknown-operation :operation-id operation-id
       :keep-maintenance? true}

      (not (map? (:payload last-e)))
      {:status :evidence-insufficient
       :operation-id operation-id
       :reason :missing-payload
       :keep-maintenance? true}

      :else
      (let [expected (expected-hash-fn last-e)
            check (receipt-matches? store last-e expected expected-domain)]
        (cond
          (:match? check)
          (do
            (journal/append-phase!
             journal-dir
             {:operation-id operation-id
              :domain (:domain last-e)
              :kind (:kind last-e)
              :scope (:scope last-e)
              :payload (:payload last-e)
              :phase :journal-durable
              :datomic-confirmed? true
              :replayable? true
              :nota "reconciled-from-platform-op"})
            {:status :reconciled
             :operation-id operation-id
             :receipt (:receipt check)
             :effect-reapplied? false})

          (= :no-receipt (:reason check))
          {:status :no-commit
           :operation-id operation-id
           :keep-maintenance? true}

          :else
          {:status :evidence-insufficient
           :operation-id operation-id
           :reason (:reason check)
           :keep-maintenance? true
           :check check})))))

(defn replay-entry!
  "Replay genérico: exige :journal-durable + datomic-confirmed?; aplica via apply-fn.
   apply-fn recebe a entry e deve ser idempotente (receipt)."
  [entry apply-fn]
  (when-not (true? (:datomic-confirmed? entry))
    (throw (ex-info "replay_without_datomic_evidence" {:entry entry})))
  (when-not (= :journal-durable (keyword (:phase entry)))
    (throw (ex-info "replay_phase_not_durable" {:phase (:phase entry)})))
  (let [r (apply-fn entry)]
    {:duplicate (boolean (or (:duplicate r) (:duplicate? r)))
     :result r
     :replayed? (not (or (:duplicate r) (:duplicate? r)))}))
