(ns odisseu.cutover.blocks-admins-durable
  "M8.7 — durable-op em bloqueios e administradores (Platform).
   Delega aos ports existentes; não altera semântica."
  (:require [odisseu.cutover.durable-mutation :as dmut]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.datomic.store :as pstore]))

(def coverage
  {:coins-delta-wired? true
   :rank-score-wired? true
   :blocks-admins-wired? true
   :bridge-hook? true
   :other-mutations-wired? false
   :independent-failure-domain-configured? false
   :wired-kinds #{:coins-delta :score-point
                  :set-bot-blocked :block-commands :unblock-commands
                  :remember-admin :forget-admin}
   :uncovered-pokemon
   [:capture :hp :xp :evolution :battle :save-switch :joy-collect :shop-buy :shop-use]
   :uncovered-platform
   [:enquetes-create :enquetes-vote :enquetes-close
    :quiz-historico :lembretes :bugs :participantes
    :rank-penalize :rank-effect]
   :nota
   "Coins + rank score + bloqueio/admins. Não declarar proteção global."})

(def coverage-matrix
  "Matriz honesta M8.7 — mutações existentes vs journal."
  {:pokemon
   [{:op :coins-delta :status :protected-tested :via "M8.5"}
    {:op :capture :status :implemented-no-journal}
    {:op :hp :status :implemented-no-journal}
    {:op :xp :status :implemented-no-journal}
    {:op :evolution :status :implemented-no-journal}
    {:op :battle :status :implemented-no-journal}
    {:op :save-switch :status :implemented-no-journal}
    {:op :joy-collect :status :implemented-no-journal}
    {:op :shop-buy :status :implemented-no-journal}
    {:op :shop-use :status :implemented-no-journal}]
   :rank
   [{:op :score-point :status :protected-tested :via "M8.6"}
    {:op :penalize-point :status :implemented-no-journal}
    {:op :apply-rank-effect :status :implemented-no-journal}]
   :blocks-admins
   [{:op :set-bot-blocked :status :protected-tested :via "M8.7"}
    {:op :block-commands :status :protected-tested :via "M8.7"}
    {:op :unblock-commands :status :protected-tested :via "M8.7"}
    {:op :remember-admin :status :protected-tested :via "M8.7"}
    {:op :forget-admin :status :protected-tested :via "M8.7"}]
   :enquetes
   [{:op :create-poll :status :implemented-no-journal}
    {:op :cast-vote :status :implemented-no-journal}
    {:op :close-poll :status :implemented-no-journal}]
   :quiz-historico
   [{:op :append-quiz-question :status :implemented-no-journal}]
   :lembretes
   [{:op :upsert-lembrete :status :implemented-no-journal}
    {:op :cancel-lembrete :status :implemented-no-journal}]
   :bugs-participantes
   [{:op :report-bug :status :implemented-no-journal}
    {:op :upsert-participant :status :implemented-no-journal}]})

(defn- run-durable!
  [durable op datomic-fn]
  (dmut/execute! {:durable durable :op op :datomic-fn datomic-fn}))

;;; ——— bloqueios ———

(defn set-bot-blocked!
  ([store chat-id blocked? operation-id]
   (ports/set-bot-blocked! store chat-id blocked? operation-id))
  ([store durable chat-id blocked? operation-id]
   (let [cid (str chat-id)
         blocked? (boolean blocked?)
         op-id (str operation-id)
         payload {:effect "bot" :chat-id cid :blocked? blocked?}]
     (run-durable! durable
                   {:operation-id op-id :domain :platform :kind :set-bot-blocked
                    :scope {:chat cid} :payload payload :replayable? true}
                   #(ports/set-bot-blocked! store cid blocked? op-id)))))

(defn block-commands!
  ([store chat-id commands operation-id]
   (ports/block-commands! store chat-id commands operation-id))
  ([store durable chat-id commands operation-id]
   (let [cid (str chat-id)
         cmds (mapv str commands)
         op-id (str operation-id)
         payload {:effect "block" :chat-id cid :commands cmds}]
     (run-durable! durable
                   {:operation-id op-id :domain :platform :kind :block-commands
                    :scope {:chat cid} :payload payload :replayable? true}
                   #(ports/block-commands! store cid cmds op-id)))))

(defn unblock-commands!
  ([store chat-id commands operation-id]
   (ports/unblock-commands! store chat-id commands operation-id))
  ([store durable chat-id commands operation-id]
   (let [cid (str chat-id)
         cmds (mapv str commands)
         op-id (str operation-id)
         payload {:effect "unblock" :chat-id cid :commands cmds}]
     (run-durable! durable
                   {:operation-id op-id :domain :platform :kind :unblock-commands
                    :scope {:chat cid} :payload payload :replayable? true}
                   #(ports/unblock-commands! store cid cmds op-id)))))

;;; ——— admins ———

(defn remember-admin!
  ([store chat-id player-id operation-id]
   (ports/remember-admin! store chat-id player-id operation-id))
  ([store durable chat-id player-id operation-id]
   (let [cid (str chat-id) pid (str player-id) op-id (str operation-id)
         payload {:effect "promote" :chat-id cid :player-id pid}]
     (run-durable! durable
                   {:operation-id op-id :domain :platform :kind :remember-admin
                    :scope {:chat cid :player pid} :payload payload :replayable? true}
                   #(ports/remember-admin! store cid pid op-id)))))

(defn forget-admin!
  ([store chat-id player-id operation-id]
   (ports/forget-admin! store chat-id player-id operation-id))
  ([store durable chat-id player-id operation-id]
   (let [cid (str chat-id) pid (str player-id) op-id (str operation-id)
         payload {:effect "demote" :chat-id cid :player-id pid}]
     (run-durable! durable
                   {:operation-id op-id :domain :platform :kind :forget-admin
                    :scope {:chat cid :player pid} :payload payload :replayable? true}
                   #(ports/forget-admin! store cid pid op-id)))))

(defn make-hooks
  "Mapa de fns para handlers/bridge."
  [durable]
  {:set-bot-blocked!
   (fn [store cid blocked? op] (set-bot-blocked! store durable cid blocked? op))
   :block-commands!
   (fn [store cid cmds op] (block-commands! store durable cid cmds op))
   :unblock-commands!
   (fn [store cid cmds op] (unblock-commands! store durable cid cmds op))
   :remember-admin!
   (fn [store cid pid op] (remember-admin! store durable cid pid op))
   :forget-admin!
   (fn [store cid pid op] (forget-admin! store durable cid pid op))})

(defn- hash-for-entry [entry]
  (let [p (:payload entry)
        effect (keyword (:effect p))]
    (case effect
      :bot (pstore/bot-payload-hash (:chat-id p) (:blocked? p))
      :block (pstore/block-commands-payload-hash (:chat-id p) (:commands p))
      :unblock (pstore/unblock-commands-payload-hash (:chat-id p) (:commands p))
      :promote (pstore/remember-admin-payload-hash (:chat-id p) (:player-id p))
      :demote (pstore/forget-admin-payload-hash (:chat-id p) (:player-id p))
      (throw (ex-info "unknown_blocks_admins_effect" {:payload p})))))

(defn- domain-for-entry [entry]
  (case (keyword (:kind entry))
    (:set-bot-blocked :block-commands :unblock-commands) "bloqueio"
    (:remember-admin :forget-admin) "admins"
    (str (:domain entry))))

(defn reconcile-uncertain!
  [store journal-dir maint-dir operation-id]
  (dmut/reconcile-uncertain!
   store journal-dir maint-dir operation-id
   {:expected-hash-fn hash-for-entry
    :expected-domain nil}))

(defn- apply-entry! [store entry]
  (let [p (:payload entry)
        op (:operation-id entry)]
    (case (keyword (:kind entry))
      :set-bot-blocked
      (ports/set-bot-blocked! store (:chat-id p) (:blocked? p) op)
      :block-commands
      (ports/block-commands! store (:chat-id p) (:commands p) op)
      :unblock-commands
      (ports/unblock-commands! store (:chat-id p) (:commands p) op)
      :remember-admin
      (ports/remember-admin! store (:chat-id p) (:player-id p) op)
      :forget-admin
      (ports/forget-admin! store (:chat-id p) (:player-id p) op)
      (throw (ex-info "replay_kind_unsupported" {:kind (:kind entry)})))))

(defn replay-entry!
  [store entry]
  (dmut/replay-entry! entry #(apply-entry! store %)))

(defn replay-journal-after!
  "Replay em ordem :seq — essencial para block→unblock / remember→forget."
  [store journal-dir after-seq]
  (let [entries (->> (journal/entries-after journal-dir after-seq)
                     (filter #(and (= :journal-durable (:phase %))
                                   (true? (:datomic-confirmed? %))
                                   (contains? #{:set-bot-blocked :block-commands
                                                :unblock-commands :remember-admin
                                                :forget-admin}
                                              (keyword (:kind %)))))
                     (sort-by :seq))]
    (mapv (fn [e]
            (assoc (replay-entry! store e)
                   :operation-id (:operation-id e)
                   :seq (:seq e)
                   :kind (:kind e)))
          entries)))
