(ns odisseu.platform.infrastructure.datomic.store
  "Store Datomic Local para bloqueio + admins (harness M7.0).
   Não substitui zapbot.armazenamento Cassandra no runtime Node."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.admins :as admins]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.security MessageDigest]))

(defn- sha256-hex [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str s) "UTF-8"))]
    (str/join (map #(format "%02x" %) bytes))))

(defn- ensure-schema!
  [conn]
  (let [path "migrations/platform/001-bloqueio-admins.edn"
        f (io/file (persistence/project-root) path)
        tx (edn/read-string (slurp f))]
    (d/transact conn {:tx-data tx})
    conn))

(defn- ensure-chat!
  [conn chat-id]
  (d/transact conn {:tx-data [{:platform.chat/id (str chat-id)}]})
  (str chat-id))

(defn- receipt-exists?
  [db op-id]
  (seq (d/q '[:find ?e
              :in $ ?id
              :where [?e :platform.op/id ?id]]
            db op-id)))

(defn- assert-receipt!
  "Idempotência: mesmo op-id + mesmo hash → :duplicate; hash diferente → erro."
  [db op-id domain payload-hash]
  (when-let [[e] (first (d/q '[:find (pull ?e [*])
                               :in $ ?id
                               :where [?e :platform.op/id ?id]]
                             db op-id))]
    (let [prev (:platform.op/payload-hash e)]
      (if (= prev payload-hash)
        :duplicate
        (throw (ex-info "platform_receipt_payload_mismatch"
                        {:operation-id op-id
                         :domain domain
                         :expected prev
                         :got payload-hash}))))))

(defn- load-blocks
  [db]
  (let [rows (d/q '[:find ?cid ?bot ?cmd
                    :where
                    [?b :platform.block/chat ?c]
                    [?c :platform.chat/id ?cid]
                    [?b :platform.block/bot? ?bot]
                    [?b :platform.block/command ?cmd]]
                  db)
        bots (d/q '[:find ?cid ?bot
                    :where
                    [?b :platform.block/chat ?c]
                    [?c :platform.chat/id ?cid]
                    [?b :platform.block/bot? ?bot]]
                  db)]
    (reduce (fn [acc [cid bot]]
              (let [cmds (->> rows
                              (filter #(= cid (first %)))
                              (map #(nth % 2))
                              set)]
                (assoc acc cid {:bot? bot :comandos cmds})))
            {}
            bots)))

(defn- load-admins
  [db]
  (->> (d/q '[:find ?cid ?pid
              :where
              [?a :platform.admin/chat ?c]
              [?c :platform.chat/id ?cid]
              [?a :platform.admin/player-id ?pid]]
            db)
       (reduce (fn [acc [cid pid]]
                 (update acc cid (fnil conj #{}) pid))
               {})))

(defn- block-eids
  [db chat-id]
  (->> (d/q '[:find ?b
              :in $ ?cid
              :where
              [?c :platform.chat/id ?cid]
              [?b :platform.block/chat ?c]]
            db (str chat-id))
       (map first)
       vec))

(defn- replace-block-tx
  "Retracta entidade(s) anteriores do chat e grava um único bloco."
  [db chat-id {:keys [bot? comandos]}]
  (let [cid (str chat-id)
        old (map (fn [e] [:db/retractEntity e]) (block-eids db cid))]
    (concat
     old
     [{:db/id "chat" :platform.chat/id cid}
      {:db/id "block"
       :platform.block/chat "chat"
       :platform.block/bot? (boolean bot?)}]
     (for [cmd comandos]
       {:db/id "block" :platform.block/command (str cmd)}))))

(defrecord PlatformStore [conn]
  ports/BlockStore
  (get-block-state [_]
    (load-blocks (d/db conn)))
  (put-block-state! [this estado]
    (doseq [[cid info] (bloqueio/normalize-state estado)]
      (ensure-chat! conn cid)
      (let [db (d/db conn)]
        (d/transact conn {:tx-data (replace-block-tx db cid info)})))
    (ports/get-block-state this))
  (get-chat-block [this chat-id]
    (get (ports/get-block-state this) (str chat-id)
         {:bot? false :comandos #{}}))
  (set-bot-blocked! [this chat-id blocked? operation-id]
    (let [db (d/db conn)
          payload (str "bot|" chat-id "|" blocked?)
          hash (sha256-hex payload)]
      (if (= :duplicate (assert-receipt! db operation-id "bloqueio" hash))
        {:duplicate true :state (ports/get-block-state this)}
        (let [cur (ports/get-chat-block this chat-id)
              nxt (assoc cur :bot? (boolean blocked?))
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (replace-block-tx db2 chat-id nxt)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "bloqueio"
                                        :platform.op/payload-hash hash}])})
          {:duplicate false :state (ports/get-block-state this)}))))
  (block-commands! [this chat-id commands operation-id]
    (let [db (d/db conn)
          cmds (vec (sort (map str commands)))
          payload (str "block|" chat-id "|" (pr-str cmds))
          hash (sha256-hex payload)]
      (if (= :duplicate (assert-receipt! db operation-id "bloqueio" hash))
        {:duplicate true :state (ports/get-block-state this)}
        (let [cur (ports/get-chat-block this chat-id)
              nxt (update cur :comandos (fnil into #{}) cmds)
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (replace-block-tx db2 chat-id nxt)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "bloqueio"
                                        :platform.op/payload-hash hash}])})
          {:duplicate false :state (ports/get-block-state this)}))))
  (unblock-commands! [this chat-id commands operation-id]
    (let [db (d/db conn)
          cmds (set (map str commands))
          payload (str "unblock|" chat-id "|" (pr-str (sort cmds)))
          hash (sha256-hex payload)]
      (if (= :duplicate (assert-receipt! db operation-id "bloqueio" hash))
        {:duplicate true :state (ports/get-block-state this)}
        (let [cur (ports/get-chat-block this chat-id)
              nxt (update cur :comandos (fnil set/difference #{}) cmds)
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (replace-block-tx db2 chat-id nxt)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "bloqueio"
                                        :platform.op/payload-hash hash}])})
          {:duplicate false :state (ports/get-block-state this)}))))

  ports/AdminStore
  (get-admin-state [_]
    (load-admins (d/db conn)))
  (put-admin-state! [this estado]
    (doseq [[cid ids] (admins/normalize-state estado)
            id ids]
      (d/transact conn {:tx-data [{:db/id "chat" :platform.chat/id (str cid)}
                                  {:platform.admin/key (admins/admin-key cid id)
                                   :platform.admin/chat "chat"
                                   :platform.admin/player-id (str id)}]}))
    (ports/get-admin-state this))
  (remember-admin! [this chat-id player-id operation-id]
    (let [db (d/db conn)
          cid (str chat-id)
          pid (str player-id)
          payload (str "promote|" cid "|" pid)
          hash (sha256-hex payload)]
      (if (= :duplicate (assert-receipt! db operation-id "admins" hash))
        {:duplicate true :state (ports/get-admin-state this)}
        (do
          (d/transact conn {:tx-data [{:db/id "chat" :platform.chat/id cid}
                                      {:platform.admin/key (admins/admin-key cid pid)
                                       :platform.admin/chat "chat"
                                       :platform.admin/player-id pid}
                                      {:platform.op/id operation-id
                                       :platform.op/domain "admins"
                                       :platform.op/payload-hash hash}]})
          {:duplicate false :state (ports/get-admin-state this)}))))
  (forget-admin! [this chat-id player-id operation-id]
    (let [db (d/db conn)
          cid (str chat-id)
          pid (str player-id)
          payload (str "demote|" cid "|" pid)
          hash (sha256-hex payload)
          key (admins/admin-key cid pid)]
      (if (= :duplicate (assert-receipt! db operation-id "admins" hash))
        {:duplicate true :state (ports/get-admin-state this)}
        (do
          (if-let [eid (ffirst (d/q '[:find ?e
                                      :in $ ?k
                                      :where [?e :platform.admin/key ?k]]
                                    db key))]
            (d/transact conn {:tx-data [[:db/retractEntity eid]
                                        {:platform.op/id operation-id
                                         :platform.op/domain "admins"
                                         :platform.op/payload-hash hash}]})
            (d/transact conn {:tx-data [{:platform.op/id operation-id
                                         :platform.op/domain "admins"
                                         :platform.op/payload-hash hash}]}))
          {:duplicate false :state (ports/get-admin-state this)}))))
  (known-admin? [this chat-id player-ids]
    (admins/conhecido? (ports/get-admin-state this) chat-id player-ids)))

(defn make-store
  [conn]
  (->PlatformStore (ensure-schema! conn)))
