(ns odisseu.platform.infrastructure.datomic.store
  "Store Datomic Local para bloqueio + admins + enquetes + quiz-historico (M7.0–M7.3).
   Não substitui zapbot.armazenamento Cassandra no runtime Node."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.admins :as admins]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [odisseu.platform.domain.enquetes :as enquetes]
            [odisseu.platform.domain.quiz-historico :as quiz-hist]
            [odisseu.platform.domain.rank :as rank]
            [odisseu.platform.domain.lembretes :as lembretes]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.security MessageDigest]))

(defn- sha256-hex [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str s) "UTF-8"))]
    (str/join (map #(format "%02x" %) bytes))))

(defn- ensure-schema!
  [conn]
  (doseq [path ["migrations/platform/001-bloqueio-admins.edn"
                "migrations/platform/002-enquetes.edn"
                "migrations/platform/003-quiz-historico.edn"
                "migrations/platform/004-rank.edn"
                "migrations/platform/005-lembretes.edn"]]
    (let [f (io/file (persistence/project-root) path)
          tx (edn/read-string (slurp f))]
      (d/transact conn {:tx-data tx})))
  conn)

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
  "Idempotência: mesmo op-id + mesmo hash → mapa :duplicate; hash diferente → erro."
  [db op-id domain payload-hash]
  (when-let [[e] (first (d/q '[:find (pull ?e [*])
                               :in $ ?id
                               :where [?e :platform.op/id ?id]]
                             db op-id))]
    (let [prev (:platform.op/payload-hash e)]
      (if (= prev payload-hash)
        {:duplicate true
         :snapshot (when-let [s (:platform.op/snapshot e)]
                     (edn/read-string s))}
        (throw (ex-info "platform_receipt_payload_mismatch"
                        {:operation-id op-id
                         :domain domain
                         :expected prev
                         :got payload-hash}))))))

(defn- receipt-dup?
  [res]
  (and (map? res) (true? (:duplicate res))))

(defn- snapshot-poll
  [snap]
  (when snap (enquetes/normalize-entry snap)))

(defn- load-polls
  [db]
  (let [polls (d/q '[:find ?cid ?q ?opts ?pfx
                     :where
                     [?p :platform.poll/chat ?c]
                     [?c :platform.chat/id ?cid]
                     [?p :platform.poll/question ?q]
                     [?p :platform.poll/options ?opts]
                     [?p :platform.poll/prefix ?pfx]]
                   db)
        votes (d/q '[:find ?cid ?voter ?choice
                     :where
                     [?v :platform.poll.vote/poll ?p]
                     [?p :platform.poll/chat ?c]
                     [?c :platform.chat/id ?cid]
                     [?v :platform.poll.vote/voter ?voter]
                     [?v :platform.poll.vote/choice ?choice]]
                   db)]
    (reduce (fn [acc [cid q opts pfx]]
              (let [votos (->> votes
                               (filter #(= cid (first %)))
                               (map (fn [[_ voter choice]] [(str voter) (long choice)]))
                               (into {}))]
                (assoc acc (str cid)
                       {:pergunta (str q)
                        :opcoes (enquetes/edn->options opts)
                        :votos votos
                        :prefix (str pfx)})))
            {}
            polls)))

(defn- poll-eid
  [db chat-id]
  (ffirst (d/q '[:find ?p
                 :in $ ?cid
                 :where
                 [?c :platform.chat/id ?cid]
                 [?p :platform.poll/chat ?c]]
               db (str chat-id))))

(defn- poll-vote-eids
  [db poll-eid]
  (->> (d/q '[:find ?v
              :in $ ?p
              :where [?v :platform.poll.vote/poll ?p]]
            db poll-eid)
       (map first)
       vec))

(defn- poll-retract-tx
  [db poll-eid]
  (concat
   (map (fn [e] [:db/retractEntity e]) (poll-vote-eids db poll-eid))
   [[:db/retractEntity poll-eid]]))

(defn- poll-create-tx
  [chat-id {:keys [pergunta opcoes prefix votos]}]
  (let [cid (str chat-id)]
    (concat
     [{:db/id "chat" :platform.chat/id cid}
      {:db/id "poll"
       :platform.poll/chat "chat"
       :platform.poll/question (str pergunta)
       :platform.poll/options (enquetes/options->edn opcoes)
       :platform.poll/prefix (str (or prefix "!"))}]
     (for [[voter choice] (or votos {})]
       {:db/id (str "vote-" voter)
        :platform.poll.vote/key (enquetes/vote-key cid voter)
        :platform.poll.vote/poll "poll"
        :platform.poll.vote/voter (str voter)
        :platform.poll.vote/choice (long choice)}))))

(defn- load-quiz-history
  [db]
  (->> (d/q '[:find ?cid ?qs
              :where
              [?h :platform.quiz.history/chat ?c]
              [?c :platform.chat/id ?cid]
              [?h :platform.quiz.history/questions ?qs]]
            db)
       (reduce (fn [acc [cid qs]]
                 (assoc acc (str cid) (quiz-hist/edn->questions qs)))
               {})))

(defn- quiz-history-eid
  [db chat-id]
  (ffirst (d/q '[:find ?h
                 :in $ ?cid
                 :where
                 [?c :platform.chat/id ?cid]
                 [?h :platform.quiz.history/chat ?c]]
               db (str chat-id))))

(defn- quiz-history-put-tx
  "Atualiza questions se a entidade já existe; senão cria (evita unique-conflict no reimport)."
  [db chat-id questions]
  (let [cid (str chat-id)
        qs (quiz-hist/normalize-entry questions)
        edn (quiz-hist/questions->edn qs)]
    (if-let [eid (quiz-history-eid db cid)]
      [[:db/add eid :platform.quiz.history/questions edn]]
      [{:db/id "chat" :platform.chat/id cid}
       {:platform.quiz.history/chat "chat"
        :platform.quiz.history/questions edn}])))

(defn- rank-player-eid
  [db chat-id player-id]
  (ffirst (d/q '[:find ?e
                 :in $ ?k
                 :where [?e :platform.rank.player/key ?k]]
               db (rank/player-key chat-id player-id))))

(defn- load-rank
  [db]
  (->> (d/q '[:find ?e ?cid ?pid
              :where
              [?e :platform.rank.player/key _]
              [?e :platform.rank.player/chat ?c]
              [?c :platform.chat/id ?cid]
              [?e :platform.rank.player/player-id ?pid]]
            db)
       (reduce (fn [acc [eid cid pid]]
                 (let [ent (d/pull db '[:platform.rank.player/name
                                        :platform.rank.player/points
                                        :platform.rank.player/games
                                        :platform.rank.player/http-only?
                                        :platform.rank.player/effects]
                                   eid)
                       efeitos (rank/edn->effects
                                (or (:platform.rank.player/effects ent) "{}"))]
                   (assoc-in acc [(str cid) (str pid)]
                             (cond-> {"nome" (str (:platform.rank.player/name ent))
                                      "pontos" (long (:platform.rank.player/points ent 0))
                                      "jogos" (rank/edn->games
                                               (:platform.rank.player/games ent))}
                               (:platform.rank.player/http-only? ent)
                               (assoc "somente-efeitos-http" true)
                               (seq efeitos)
                               (assoc "efeitos-pokemon-http" efeitos)))))
               {})))

(defn- rank-player-tx
  "Upsert por :platform.rank.player/key (unique identity)."
  [db chat-id player-id info]
  (let [cid (str chat-id)
        pid (str player-id)
        info (rank/normalize-player info)
        key (rank/player-key cid pid)
        eid (rank-player-eid db cid pid)
        http? (boolean (get info "somente-efeitos-http"))
        asserts [{:db/id "chat" :platform.chat/id cid}
                 {:platform.rank.player/key key
                  :platform.rank.player/chat "chat"
                  :platform.rank.player/player-id pid
                  :platform.rank.player/name (get info "nome")
                  :platform.rank.player/points (long (get info "pontos" 0))
                  :platform.rank.player/games (rank/games->edn (get info "jogos"))
                  :platform.rank.player/effects
                  (rank/effects->edn (get info "efeitos-pokemon-http" {}))}]
        asserts (if http?
                  (concat asserts
                          [{:platform.rank.player/key key
                            :platform.rank.player/http-only? true}])
                  asserts)
        retracts (when (and eid (not http?)
                            (:platform.rank.player/http-only?
                             (d/pull db '[:platform.rank.player/http-only?] eid)))
                   [[:db/retract eid :platform.rank.player/http-only? true]])]
    (concat retracts asserts)))

(defn- reminder-eid
  [db reminder-id]
  (ffirst (d/q '[:find ?e
                 :in $ ?id
                 :where [?e :platform.reminder/id ?id]]
               db (str reminder-id))))

(defn- pull->reminder
  [ent]
  (when ent
    (lembretes/normalize-entry
     {:id (:platform.reminder/id ent)
      :chat (:platform.reminder/chat ent)
      :texto (:platform.reminder/text ent)
      :criador (:platform.reminder/creator ent)
      :em (:platform.reminder/due-at ent)
      :status (:platform.reminder/status ent)
      :claimant (:platform.reminder/claimant ent)
      :lease-until (:platform.reminder/lease-until ent)
      :fence (:platform.reminder/fence ent 0)
      :fail-count (:platform.reminder/fail-count ent 0)
      :last-error (:platform.reminder/last-error ent)})))

(defn- load-reminders
  [db]
  (->> (d/q '[:find ?e
              :where [?e :platform.reminder/id _]]
            db)
       (reduce (fn [acc [eid]]
                 (let [ent (d/pull db '[*] eid)
                       r (pull->reminder ent)]
                   (assoc acc (:id r) r)))
               {})))

(defn- reminder-put-tx
  "Upsert por :platform.reminder/id. Preserva due-at original.
   Retracts claim/error attrs quando ausentes no mapa normalizado."
  [db r]
  (let [r (lembretes/normalize-entry r)
        eid (reminder-eid db (:id r))
        base {:platform.reminder/id (:id r)
              :platform.reminder/chat (:chat r)
              :platform.reminder/text (:texto r)
              :platform.reminder/due-at (long (:em r))
              :platform.reminder/status (str (or (:status r) "pending"))
              :platform.reminder/fence (long (or (:fence r) 0))
              :platform.reminder/fail-count (long (or (:fail-count r) 0))}
        base (if (:criador r)
               (assoc base :platform.reminder/creator (:criador r))
               base)
        base (if (:claimant r)
               (assoc base :platform.reminder/claimant (:claimant r))
               base)
        base (if (:lease-until r)
               (assoc base :platform.reminder/lease-until (long (:lease-until r)))
               base)
        base (if (:last-error r)
               (assoc base :platform.reminder/last-error (:last-error r))
               base)
        retracts (when eid
                   (cond-> []
                     (nil? (:claimant r))
                     (conj [:db/retract eid :platform.reminder/claimant])
                     (nil? (:lease-until r))
                     (conj [:db/retract eid :platform.reminder/lease-until])
                     (nil? (:last-error r))
                     (conj [:db/retract eid :platform.reminder/last-error])
                     (nil? (:criador r))
                     (conj [:db/retract eid :platform.reminder/creator])))]
    (concat retracts [base])))

(defn- reminder-clear-claim-tx
  [eid]
  (when eid
    [[:db/retract eid :platform.reminder/claimant]
     [:db/retract eid :platform.reminder/lease-until]]))

(defn- cas-fail?
  [e]
  (let [msg (or (ex-message e) "")]
    (or (str/includes? msg ":db.error/cas-failed")
        (str/includes? msg "cas-failed")
        (str/includes? (str (ex-data e)) "cas-failed"))))

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
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "bloqueio" hash)]
      (if (receipt-dup? dup)
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
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "bloqueio" hash)]
      (if (receipt-dup? dup)
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
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "bloqueio" hash)]
      (if (receipt-dup? dup)
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
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "admins" hash)]
      (if (receipt-dup? dup)
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
          key (admins/admin-key cid pid)
          dup (assert-receipt! db operation-id "admins" hash)]
      (if (receipt-dup? dup)
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
    (admins/conhecido? (ports/get-admin-state this) chat-id player-ids))

  ports/PollStore
  (get-poll-state [_]
    (load-polls (d/db conn)))
  (put-poll-state! [this estado]
    ;; Retract e create em txs separadas: mesmo vote/key no mesmo tx
    ;; conflita com upsert por identity após retractEntity.
    (doseq [[cid poll] (enquetes/normalize-state estado)]
      (ensure-chat! conn cid)
      (when-let [existing (poll-eid (d/db conn) cid)]
        (d/transact conn {:tx-data (poll-retract-tx (d/db conn) existing)}))
      (d/transact conn {:tx-data (poll-create-tx cid poll)}))
    (ports/get-poll-state this))
  (get-chat-poll [this chat-id]
    (get (ports/get-poll-state this) (str chat-id)))
  (create-poll! [this chat-id poll operation-id]
    (let [cid (str chat-id)
          poll* (enquetes/normalize-entry poll)
          db (d/db conn)
          payload (str "create|" cid "|" (:pergunta poll*) "|"
                       (enquetes/options->edn (:opcoes poll*)) "|" (:prefix poll*))
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "enquetes" hash)]
      (cond
        (receipt-dup? dup)
        {:duplicate true
         :poll (or (ports/get-chat-poll this cid) (snapshot-poll (:snapshot dup)))
         :state (ports/get-poll-state this)}

        (not (enquetes/valid-create? poll*))
        (throw (ex-info "poll_invalid"
                        {:chat-id cid :poll poll*}))

        (ports/get-chat-poll this cid)
        (throw (ex-info "poll_already_open"
                        {:chat-id cid}))

        :else
        (do
          (d/transact conn {:tx-data (concat
                                      (poll-create-tx cid poll*)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "enquetes"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot (pr-str (enquetes/->persistivel poll*))}])})
          {:duplicate false
           :poll (ports/get-chat-poll this cid)
           :state (ports/get-poll-state this)}))))
  (cast-vote! [this chat-id voter-id choice operation-id]
    (let [cid (str chat-id)
          vid (str voter-id)
          n (long choice)
          db (d/db conn)
          payload (str "vote|" cid "|" vid "|" n)
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "enquetes" hash)
          poll (ports/get-chat-poll this cid)]
      (cond
        (receipt-dup? dup)
        {:duplicate true
         :poll (or (ports/get-chat-poll this cid) (snapshot-poll (:snapshot dup)))
         :state (ports/get-poll-state this)}

        (nil? poll)
        (throw (ex-info "poll_not_found" {:chat-id cid}))

        (not (enquetes/valid-choice? poll n))
        (throw (ex-info "poll_invalid_choice"
                        {:chat-id cid :choice n :options (count (:opcoes poll))}))

        :else
        (let [eid (poll-eid (d/db conn) cid)]
          (d/transact conn {:tx-data [{:platform.poll.vote/key (enquetes/vote-key cid vid)
                                       :platform.poll.vote/poll eid
                                       :platform.poll.vote/voter vid
                                       :platform.poll.vote/choice n}
                                      {:platform.op/id operation-id
                                       :platform.op/domain "enquetes"
                                       :platform.op/payload-hash hash}]})
          {:duplicate false
           :poll (ports/get-chat-poll this cid)
           :state (ports/get-poll-state this)}))))
  (close-poll! [this chat-id operation-id]
    (let [cid (str chat-id)
          db (d/db conn)
          payload (str "close|" cid)
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "enquetes" hash)
          poll (ports/get-chat-poll this cid)]
      (cond
        (receipt-dup? dup)
        {:duplicate true
         :closed true
         :poll (snapshot-poll (:snapshot dup))
         :state (ports/get-poll-state this)}

        (nil? poll)
        (throw (ex-info "poll_not_found" {:chat-id cid}))

        :else
        (let [eid (poll-eid db cid)
              snap (enquetes/->persistivel poll)]
          (d/transact conn {:tx-data (concat
                                      (poll-retract-tx db eid)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "enquetes"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot (pr-str snap)}])})
          {:duplicate false
           :closed true
           :poll poll
           :state (ports/get-poll-state this)}))))

  ports/QuizHistoryStore
  (get-quiz-history-state [_]
    (load-quiz-history (d/db conn)))
  (put-quiz-history-state! [this estado]
    (doseq [[cid qs] (quiz-hist/normalize-state estado)]
      (ensure-chat! conn cid)
      (let [db (d/db conn)]
        (if (seq qs)
          (d/transact conn {:tx-data (quiz-history-put-tx db cid qs)})
          (when-let [eid (quiz-history-eid db cid)]
            (d/transact conn {:tx-data [[:db/retractEntity eid]]})))))
    (ports/get-quiz-history-state this))
  (get-chat-quiz-history [this chat-id]
    (get (ports/get-quiz-history-state this) (str chat-id) []))
  (append-quiz-question! [this chat-id question operation-id]
    (let [cid (str chat-id)
          q (str question)
          db (d/db conn)
          payload (str "quiz-append|" cid "|" q)
          hash (sha256-hex payload)
          dup (assert-receipt! db operation-id "quiz-historico" hash)]
      (cond
        (str/blank? q)
        (throw (ex-info "quiz_history_invalid" {:chat-id cid}))

        (receipt-dup? dup)
        {:duplicate true
         :history (or (when-let [s (:snapshot dup)]
                        (quiz-hist/normalize-entry s))
                      (ports/get-chat-quiz-history this cid))
         :state (ports/get-quiz-history-state this)}

        :else
        (let [cur (ports/get-chat-quiz-history this cid)
              nxt (quiz-hist/append cur q)
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (quiz-history-put-tx db2 cid nxt)
                                      [{:platform.op/id operation-id
                                        :platform.op/domain "quiz-historico"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot (pr-str nxt)}])})
          {:duplicate false
           :history (ports/get-chat-quiz-history this cid)
           :state (ports/get-quiz-history-state this)}))))

  ports/RankStore
  (get-rank-state [_]
    (load-rank (d/db conn)))
  (put-rank-state! [this estado]
    (doseq [[cid players] (rank/normalize-state estado)
            [pid info] players]
      (ensure-chat! conn cid)
      (d/transact conn {:tx-data (rank-player-tx (d/db conn) cid pid info)}))
    (ports/get-rank-state this))
  (get-chat-rank [this chat-id]
    (get (ports/get-rank-state this) (str chat-id) {}))
  (score-point! [this chat-id player-id name game operation-id]
    (let [cid (str chat-id)
          pid (str player-id)
          jogo (str game)
          op (str operation-id)
          db (d/db conn)
          payload (str "score|" cid "|" pid "|" jogo "|" name)
          hash (sha256-hex payload)
          dup (assert-receipt! db op "rank" hash)]
      (cond
        (str/blank? op)
        (throw (ex-info "rank_missing_operation_id" {:chat-id cid}))

        (receipt-dup? dup)
        {:duplicate true
         :penalized nil
         :player (get (ports/get-chat-rank this cid) pid)
         :state (ports/get-rank-state this)}

        :else
        (let [cur (get (ports/get-chat-rank this cid) pid)
              nxt (rank/apply-score cur name jogo)
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (rank-player-tx db2 cid pid nxt)
                                      [{:platform.op/id op
                                        :platform.op/domain "rank"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot (pr-str nxt)}])})
          {:duplicate false
           :player (get (ports/get-chat-rank this cid) pid)
           :state (ports/get-rank-state this)}))))
  (penalize-point! [this chat-id player-id operation-id]
    (let [cid (str chat-id)
          pid (str player-id)
          op (str operation-id)
          db (d/db conn)
          payload (str "penalize|" cid "|" pid)
          hash (sha256-hex payload)
          dup (assert-receipt! db op "rank" hash)]
      (cond
        (str/blank? op)
        (throw (ex-info "rank_missing_operation_id" {:chat-id cid}))

        (receipt-dup? dup)
        (let [snap (:snapshot dup)]
          {:duplicate true
           :penalized (boolean (get snap "penalized"))
           :player (get (ports/get-chat-rank this cid) pid)
           :state (ports/get-rank-state this)})

        :else
        (let [cur (or (get (ports/get-chat-rank this cid) pid)
                      {"nome" "Alguém" "pontos" 0 "jogos" {}})
              [nxt penalizado?] (rank/apply-penalize cur)
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (when penalizado?
                                        (rank-player-tx db2 cid pid nxt))
                                      [{:platform.op/id op
                                        :platform.op/domain "rank"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot
                                        (pr-str {"penalized" penalizado?
                                                 "player" (if penalizado? nxt cur)})}])})
          {:duplicate false
           :penalized penalizado?
           :player (get (ports/get-chat-rank this cid) pid cur)
           :state (ports/get-rank-state this)}))))
  (apply-rank-effect! [this effect]
    (let [id (str (or (get effect "id") (:id effect) ""))
          typ (str (or (get effect "type") (:type effect) ""))
          cid (str (or (get effect "chatId") (:chatId effect) ""))
          pid (str (or (get effect "playerId") (:playerId effect) ""))
          nome (str (or (get effect "playerName") (:playerName effect)
                        (get effect "name") (:name effect) "Alguém"))
          jogo (str (or (get effect "game") (:game effect) "pokemon"))
          op (str "rank-effect:" id)
          db (d/db conn)
          payload (str "effect|" id "|" typ "|" cid "|" pid "|" jogo "|" nome)
          hash (sha256-hex payload)
          dup (assert-receipt! db op "rank" hash)]
      (cond
        (or (str/blank? id) (str/blank? cid) (str/blank? pid)
            (not (#{"rank.increment" "rank.decrement"} typ))
            (and (= "rank.increment" typ) (not= "pokemon" jogo)))
        (throw (ex-info "rank_effect_invalid" {:effect effect}))

        (receipt-dup? dup)
        (let [snap (:snapshot dup)]
          {:duplicate true
           :resultado (boolean (get snap "resultado"))
           :state (ports/get-rank-state this)})

        :else
        (let [cur (get (ports/get-chat-rank this cid) pid)
              salvo (get-in cur ["efeitos-pokemon-http" id])
              identidade {"tipo" typ "nome" nome "jogo" jogo}
              resultado (if salvo
                          (get salvo "resultado")
                          (or (= "rank.increment" typ)
                              (pos? (long (get cur "pontos" 0)))))
              _ (when (and salvo (not= identidade (dissoc salvo "resultado")))
                  (throw (ex-info "rank_effect_payload_mismatch" {:id id})))
              nxt (if salvo
                    cur
                    (let [base (or cur {"nome" nome "pontos" 0 "jogos" {}
                                        "somente-efeitos-http" true})
                          nova (case typ
                                 "rank.increment"
                                 (-> base
                                     (assoc "nome" nome)
                                     (dissoc "somente-efeitos-http")
                                     (update "pontos" (fnil inc 0))
                                     (update-in ["jogos" jogo] (fnil inc 0)))
                                 "rank.decrement"
                                 (if resultado (update base "pontos" dec) base))]
                      (assoc-in nova ["efeitos-pokemon-http" id]
                                (assoc identidade "resultado" resultado))))
              db2 (d/db conn)]
          (d/transact conn {:tx-data (concat
                                      (rank-player-tx db2 cid pid nxt)
                                      [{:platform.op/id op
                                        :platform.op/domain "rank"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot
                                        (pr-str {"resultado" resultado})}])})
          {:duplicate false
           :resultado (boolean resultado)
           :state (ports/get-rank-state this)}))))

  ports/ReminderStore
  (get-reminder-state [_]
    (load-reminders (d/db conn)))
  (put-reminder-state! [this estado]
    (doseq [[_ r] (lembretes/normalize-state estado)]
      (d/transact conn {:tx-data (reminder-put-tx (d/db conn) r)}))
    (ports/get-reminder-state this))
  (get-reminder [this reminder-id]
    (get (ports/get-reminder-state this) (str reminder-id)))
  (list-reminders [this chat-id]
    (->> (ports/get-reminder-state this)
         vals
         (filter #(and (= (str chat-id) (:chat %))
                       (lembretes/active-for-list? %)))
         (sort-by :em)
         vec))
  (create-reminder! [this reminder operation-id]
    (let [r (lembretes/normalize-entry (assoc reminder :status "pending"
                                             :fence 0 :fail-count 0))
          op (str operation-id)
          db (d/db conn)
          payload (str "create|" (:id r) "|" (:chat r) "|" (:texto r) "|"
                       (:em r) "|" (:criador r))
          hash (sha256-hex payload)
          dup (assert-receipt! db op "lembretes" hash)]
      (cond
        (or (str/blank? (:id r)) (str/blank? (:chat r)) (str/blank? (:texto r))
            (not (pos? (long (:em r)))))
        (throw (ex-info "reminder_invalid" {:reminder r}))

        (str/blank? op)
        (throw (ex-info "reminder_missing_operation_id" {:id (:id r)}))

        (receipt-dup? dup)
        {:duplicate true
         :reminder (or (ports/get-reminder this (:id r))
                       (when-let [s (:snapshot dup)]
                         (lembretes/normalize-entry s)))
         :state (ports/get-reminder-state this)}

        (ports/get-reminder this (:id r))
        (throw (ex-info "reminder_already_exists" {:id (:id r)}))

        :else
        (do
          (d/transact conn {:tx-data (concat
                                      (reminder-put-tx (d/db conn) r)
                                      [{:platform.op/id op
                                        :platform.op/domain "lembretes"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot
                                        (pr-str (lembretes/->persistivel r))}])})
          {:duplicate false
           :reminder (ports/get-reminder this (:id r))
           :state (ports/get-reminder-state this)}))))
  (cancel-reminder! [this reminder-id chat-id operation-id]
    (let [rid (str reminder-id)
          cid (str chat-id)
          op (str operation-id)
          db (d/db conn)
          payload (str "cancel|" rid "|" cid)
          hash (sha256-hex payload)
          dup (assert-receipt! db op "lembretes" hash)
          cur (ports/get-reminder this rid)]
      (cond
        (str/blank? op)
        (throw (ex-info "reminder_missing_operation_id" {:id rid}))

        (receipt-dup? dup)
        {:duplicate true
         :cancelled true
         :reminder (or (ports/get-reminder this rid)
                       (when-let [s (:snapshot dup)]
                         (lembretes/normalize-entry s)))
         :state (ports/get-reminder-state this)}

        (nil? cur)
        (throw (ex-info "reminder_not_found" {:id rid}))

        (not= cid (:chat cur))
        (throw (ex-info "reminder_wrong_chat" {:id rid :chat cid}))

        (= "completed" (:status cur))
        (throw (ex-info "reminder_already_sent" {:id rid}))

        (= "cancelled" (:status cur))
        {:duplicate false
         :cancelled true
         :reminder cur
         :state (ports/get-reminder-state this)}

        :else
        (let [eid (reminder-eid (d/db conn) rid)]
          (d/transact conn {:tx-data (concat
                                      [{:db/id eid
                                        :platform.reminder/status "cancelled"}]
                                      (reminder-clear-claim-tx eid)
                                      [{:platform.op/id op
                                        :platform.op/domain "lembretes"
                                        :platform.op/payload-hash hash
                                        :platform.op/snapshot
                                        (pr-str (lembretes/->persistivel cur))}])})
          {:duplicate false
           :cancelled true
           :reminder (ports/get-reminder this rid)
           :state (ports/get-reminder-state this)}))))
  (due-reminders [this now-ms limit]
    (let [now (long now-ms)
          lim (long (or limit 50))]
      (->> (ports/get-reminder-state this)
           vals
           (filter #(lembretes/claimable? % now))
           (sort-by :em)
           (take lim)
           vec)))
  (claim-reminder! [this reminder-id claimant lease-ms now-ms]
    (let [rid (str reminder-id)
          who (str claimant)
          now (long now-ms)
          lease (long (or lease-ms lembretes/default-lease-ms))
          until (+ now lease)
          db (d/db conn)
          eid (reminder-eid db rid)
          cur (when eid (pull->reminder (d/pull db '[*] eid)))]
      (cond
        (nil? cur)
        (throw (ex-info "reminder_not_found" {:id rid}))

        (not (lembretes/claimable? cur now))
        {:claimed false
         :reason "not_claimable"
         :reminder cur}

        :else
        (let [old-status (:status cur)
              old-fence (long (:fence cur 0))
              new-fence (inc old-fence)
              tx (concat
                  [[:db/cas eid :platform.reminder/status old-status "claimed"]
                   [:db/cas eid :platform.reminder/fence old-fence new-fence]
                   {:db/id eid
                    :platform.reminder/claimant who
                    :platform.reminder/lease-until until}])]
          (try
            (d/transact conn {:tx-data tx})
            {:claimed true
             :fence new-fence
             :lease-until until
             :claimant who
             :reminder (ports/get-reminder this rid)}
            (catch Exception e
              (if (cas-fail? e)
                {:claimed false
                 :reason "cas_conflict"
                 :reminder (ports/get-reminder this rid)}
                (throw e))))))))
  (complete-reminder! [this reminder-id claimant fence]
    (let [rid (str reminder-id)
          who (str claimant)
          fence* (long fence)
          db (d/db conn)
          eid (reminder-eid db rid)
          cur (when eid (pull->reminder (d/pull db '[*] eid)))]
      (cond
        (nil? cur)
        (throw (ex-info "reminder_not_found" {:id rid}))

        (= "completed" (:status cur))
        ;; Claimant é limpo após complete; fence permanece como geração do claim.
        (if (= fence* (long (:fence cur 0)))
          {:completed true :duplicate true :reminder cur}
          {:completed false :reason "fence_mismatch" :reminder cur})

        (not= "claimed" (:status cur))
        {:completed false :reason "not_claimed" :reminder cur}

        (or (not= who (:claimant cur)) (not= fence* (long (:fence cur 0))))
        {:completed false :reason "fence_mismatch" :reminder cur}

        :else
        (do
          (d/transact conn {:tx-data (concat
                                      [{:db/id eid
                                        :platform.reminder/status "completed"}]
                                      (reminder-clear-claim-tx eid))})
          {:completed true
           :duplicate false
           :reminder (ports/get-reminder this rid)}))))
  (fail-reminder! [this reminder-id claimant fence error-msg]
    (let [rid (str reminder-id)
          who (str claimant)
          fence* (long fence)
          err (str (or error-msg "send_failed"))
          db (d/db conn)
          eid (reminder-eid db rid)
          cur (when eid (pull->reminder (d/pull db '[*] eid)))]
      (cond
        (nil? cur)
        (throw (ex-info "reminder_not_found" {:id rid}))

        (= "completed" (:status cur))
        {:failed false :reason "already_completed" :reminder cur}

        (= "cancelled" (:status cur))
        {:failed false :reason "cancelled" :reminder cur}

        (or (not= "claimed" (:status cur))
            (not= who (:claimant cur))
            (not= fence* (long (:fence cur 0))))
        {:failed false :reason "fence_mismatch" :reminder cur}

        :else
        (let [fc (inc (long (:fail-count cur 0)))]
          (d/transact conn {:tx-data (concat
                                      [{:db/id eid
                                        :platform.reminder/status "failed"
                                        :platform.reminder/fail-count fc
                                        :platform.reminder/last-error err}]
                                      (reminder-clear-claim-tx eid))})
          {:failed true
           :reminder (ports/get-reminder this rid)})))))

(defn make-store
  [conn]
  (->PlatformStore (ensure-schema! conn)))
