(ns odisseu.cutover.coins-durable
  "M8.5 — durable-op no caminho real de coins-delta (store Datomic + receipts).
   Não cria implementação paralela de moedas: delega a bridge-tx/credit-coins!.
   Demais mutações permanecem sem journal."
  (:require [odisseu.cutover.durable-op :as dop]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.reward :as reward]))

(def coverage
  {:coins-delta-wired? true
   :rank-score-wired? true
   :bridge-hook? true
   :other-mutations-wired? false
   :independent-failure-domain-configured? false
   :wired-kinds #{:coins-delta :score-point}
   :uncovered-pokemon
   [:capture :hp :xp :evolution :battle :save-switch :joy-collect :shop-buy :shop-use]
   :uncovered-platform
   [:bloqueio :admins :enquetes-vote :quiz-historico :lembretes :bugs :participantes
    :rank-penalize :rank-effect]
   :nota
   "Coins + rank score no protocolo. Não declarar proteção global."})

(defn- assert-disposable-journal! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m85_journal_path_blocked" {:path p}))))

(def maintenance? policy/maintenance?)

(defn assert-writable!
  "Bloqueia novas mutações coins enquanto manutenção de durabilidade ativa."
  [maint-dir]
  (when (and maint-dir (policy/maintenance? maint-dir))
    (throw (err/error :durability_maintenance
                      {:maint-dir maint-dir
                       :hint "Reconciliar uncertain antes de novas escritas."}))))

(defn- coins-payload [coins-delta]
  (reward/reward-payload {:coins-delta (long coins-delta)}))

(defn- scope-of [req]
  {:chat (:chat-id req)
   :player (:player-id req)
   :save (:save-id req)})

(defn credit-coins!
  "Caminho real: prepare → credit-coins! (Datomic+receipt) → journal-durable → ok.
   `durable`: {:journal-dir :maint-dir :policy :hooks}
   Sem journal-dir → passa direto para bridge-tx (compat)."
  ([store req]
   (tx/credit-coins! store req))
  ([store durable req]
   (let [cfg (policy/assert-ready! durable)]
     (if-not (policy/journal-configured? cfg)
       (tx/credit-coins! store req)
       (do
         (assert-disposable-journal! (:journal-dir cfg))
         (let [delta (long (:coins-delta req))
               payload (coins-payload delta)
               op-id (str (:operation-id req))
               exec
               (dop/execute!
                {:policy (:policy cfg)
                 :journal-dir (:journal-dir cfg)
                 :maint-dir (:maint-dir cfg)
                 :hooks (:hooks cfg)
                 :datomic-fn #(tx/credit-coins! store req)
                 :op {:operation-id op-id
                      :domain :pokemon
                      :kind :coins-delta
                      :scope (scope-of req)
                      :payload payload
                      :fence (:fence-generation req)
                      :replayable? true}})]
           (when-not (:http-success? exec)
             (throw (err/error :durability_compromised
                               {:operation-id op-id
                                :status (:status exec)
                                :phase (:phase exec)
                                :maintenance? (boolean (:maintenance? exec))
                                :coins (get-in exec [:datomic :coins])
                                :nota (:nota exec)})))
           (merge (:datomic exec)
                  {:durable {:status (:status exec)
                             :phase (:phase exec)
                             :duplicate? (boolean (:duplicate? exec))
                             :payload-hash (:payload-hash exec)}})))))))

(defn make-credit-fn
  "Hook para bridge HTTP: (fn [store req] …)."
  [durable]
  (fn [store req]
    (credit-coins! store durable req)))

(defn receipt-matches-op?
  "Valida receipt Datomic vs identidade/payload da entrada de journal."
  [store entry]
  (let [op-id (:operation-id entry)
        receipt (ports/get-receipt store op-id)
        expected-pk (id/player-key (get-in entry [:scope :chat])
                                   (get-in entry [:scope :player]))
        expected-hash (reward/payload-hash (:payload entry))]
    (cond
      (nil? receipt)
      {:match? false :reason :no-receipt}

      (not= expected-pk (:player-key receipt))
      {:match? false :reason :player-mismatch :receipt receipt}

      (not= (str (get-in entry [:scope :save])) (str (:save-id receipt)))
      {:match? false :reason :save-mismatch :receipt receipt}

      (not= expected-hash (:payload-hash receipt))
      {:match? false :reason :payload-mismatch :receipt receipt
       :expected expected-hash :got (:payload-hash receipt)}

      :else
      {:match? true :receipt receipt})))

(defn reconcile-uncertain!
  "Consulta receipt real. Se commit comprovado → journal-durable sem reaplicar moedas.
   Sem commit → não inventa confirmação. Evidência insuficiente → mantém manutenção."
  [store journal-dir maint-dir operation-id]
  (let [entries (journal/list-entries journal-dir)
        for-op (filterv #(= (str operation-id) (str (:operation-id %))) entries)
        last-e (last for-op)
        phases (set (map :phase for-op))]
    (cond
      (contains? phases :journal-durable)
      {:status :already-durable :operation-id operation-id}

      (nil? last-e)
      {:status :unknown-operation :operation-id operation-id
       :keep-maintenance? true}

      :else
      (let [check (receipt-matches-op? store last-e)]
        (cond
          (:match? check)
          (do
            (journal/append-phase!
             journal-dir
             {:operation-id operation-id
              :domain (:domain last-e :pokemon)
              :kind (:kind last-e :coins-delta)
              :scope (:scope last-e)
              :payload (:payload last-e)
              :fence (:fence last-e)
              :phase :journal-durable
              :datomic-confirmed? true
              :replayable? true
              :nota "reconciled-from-receipt"})
            (let [still (journal/prepared-without-durable journal-dir)
                  uncertain? (some #(= :uncertain (:phase %))
                                   (journal/list-entries journal-dir))]
              (when (and (empty? still) (not uncertain?)
                         (maintenance? maint-dir))
                ;; Só libera se operador pedir exit explícito; aqui não auto-exit.
                nil)
              {:status :reconciled
               :operation-id operation-id
               :receipt (:receipt check)
               :coins-reapplied? false
               :pending-orphans still}))

          (= :no-receipt (:reason check))
          {:status :no-commit
           :operation-id operation-id
           :keep-maintenance? true
           :nota "Prepare/uncertain sem receipt — não confirmar."}

          :else
          {:status :evidence-insufficient
           :operation-id operation-id
           :reason (:reason check)
           :keep-maintenance? true
           :check check})))))

(defn replay-coins-entry!
  "Replay idempotente: freeze do ativo + apply-gameplay-reward (receipt).
   Exige fase :journal-durable e evidência datomic-confirmed?."
  [store entry]
  (when-not (= :coins-delta (keyword (:kind entry)))
    (throw (ex-info "replay_not_coins" {:kind (:kind entry)})))
  (when-not (true? (:datomic-confirmed? entry))
    (throw (ex-info "replay_without_datomic_evidence" {:entry entry})))
  (when-not (= :journal-durable (keyword (:phase entry)))
    (throw (ex-info "replay_phase_not_durable" {:phase (:phase entry)})))
  (let [scope (:scope entry)
        chat (:chat scope)
        player (:player scope)
        save (:save scope)
        delta (long (:coins-delta (:payload entry)))
        active (ports/active-save-id store chat player)]
    (when (and save active (not= (str save) (str active)))
      (throw (ex-info "replay_save_not_active"
                      {:journal-save save :active active})))
    (let [r (reward-app/apply-gameplay-reward-on-active!
             store chat player
             {:operation-id (:operation-id entry)
              :coins-delta delta})]
      {:duplicate (boolean (:duplicate r))
       :result r
       :replayed? (not (:duplicate r))})))

(defn resolve-storage
  [rel]
  (persistence/resolve-storage-dir rel))
