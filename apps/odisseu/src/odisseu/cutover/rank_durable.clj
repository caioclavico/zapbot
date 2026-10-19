(ns odisseu.cutover.rank-durable
  "M8.6 — durable-op no score-point real do Rank (Platform Datomic + platform.op).
   Não altera regras de pontuação: delega a ports/score-point!.
   Journal tipicamente separado do journal Pokémon (evita colisão de op-id)."
  (:require [clojure.string :as str]
            [odisseu.cutover.durable-op :as dop]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [pokemon.domain.errors :as err]))

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
    (throw (ex-info "m86_journal_path_blocked" {:path p}))))

(defn- score-payload
  [chat-id player-id name game]
  {:effect "score"
   :chat-id (str chat-id)
   :player-id (str player-id)
   :name (str name)
   :game (str game)})

(defn score-point!
  "Caminho real: prepare → score-point! (Datomic+platform.op) → journal-durable.
   durable: ver durability-policy (:mode :optional|:required-strict, :journal-dir, …).
   Aridade 6 = pass-through legado."
  ([store chat-id player-id name game operation-id]
   (ports/score-point! store chat-id player-id name game operation-id))
  ([store durable chat-id player-id name game operation-id]
   (let [cfg (policy/assert-ready! durable)]
     (if-not (policy/journal-configured? cfg)
       (ports/score-point! store chat-id player-id name game operation-id)
       (do
         (assert-disposable-journal! (:journal-dir cfg))
         (let [cid (str chat-id)
               pid (str player-id)
               nome (str name)
               jogo (str game)
               op-id (str operation-id)
               payload (score-payload cid pid nome jogo)
               exec
               (dop/execute!
                {:policy (:policy cfg)
                 :journal-dir (:journal-dir cfg)
                 :maint-dir (:maint-dir cfg)
                 :hooks (:hooks cfg)
                 :datomic-fn #(ports/score-point! store cid pid nome jogo op-id)
                 :op {:operation-id op-id
                      :domain :platform
                      :kind :score-point
                      :scope {:chat cid :player pid}
                      :payload payload
                      :fence nil
                      :replayable? true}})]
           (when-not (:http-success? exec)
             (throw (err/error :durability_compromised
                               {:operation-id op-id
                                :status (:status exec)
                                :phase (:phase exec)
                                :maintenance? (boolean (:maintenance? exec))
                                :nota (:nota exec)})))
           (merge (:datomic exec)
                  {:durable {:status (:status exec)
                             :phase (:phase exec)
                             :duplicate? (boolean (:duplicate? exec))
                             :payload-hash (:payload-hash exec)}})))))))

(defn make-score-fn
  "Hook para handlers/bridge: (fn [store cid pid nome jogo op] …)."
  [durable]
  (fn [store chat-id player-id name game operation-id]
    (score-point! store durable chat-id player-id name game operation-id)))

(defn receipt-matches-op?
  [store entry]
  (let [op-id (:operation-id entry)
        receipt (pstore/get-op-receipt store op-id)
        p (:payload entry)
        expected (pstore/score-payload-hash
                  (:chat-id p) (:player-id p) (:name p) (:game p))]
    (cond
      (nil? receipt)
      {:match? false :reason :no-receipt}

      (not= "rank" (str (:domain receipt)))
      {:match? false :reason :domain-mismatch :receipt receipt}

      (not= expected (:payload-hash receipt))
      {:match? false :reason :payload-mismatch :receipt receipt
       :expected expected :got (:payload-hash receipt)}

      (not= (str (get-in entry [:scope :chat])) (str (:chat-id p)))
      {:match? false :reason :scope-mismatch}

      :else
      {:match? true :receipt receipt})))

(defn reconcile-uncertain!
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
              :domain :platform
              :kind :score-point
              :scope (:scope last-e)
              :payload (:payload last-e)
              :phase :journal-durable
              :datomic-confirmed? true
              :replayable? true
              :nota "reconciled-from-platform-op"})
            {:status :reconciled
             :operation-id operation-id
             :receipt (:receipt check)
             :points-reapplied? false})

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

(defn replay-score-entry!
  "Replay idempotente via score-point! (platform.op)."
  [store entry]
  (when-not (= :score-point (keyword (:kind entry)))
    (throw (ex-info "replay_not_score" {:kind (:kind entry)})))
  (when-not (true? (:datomic-confirmed? entry))
    (throw (ex-info "replay_without_datomic_evidence" {:entry entry})))
  (when-not (= :journal-durable (keyword (:phase entry)))
    (throw (ex-info "replay_phase_not_durable" {:phase (:phase entry)})))
  (let [p (:payload entry)
        r (ports/score-point!
           store
           (:chat-id p) (:player-id p) (:name p) (:game p)
           (:operation-id entry))]
    {:duplicate (boolean (:duplicate r))
     :result r
     :replayed? (not (:duplicate r))}))
