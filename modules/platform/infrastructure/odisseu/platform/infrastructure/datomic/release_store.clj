(ns odisseu.platform.infrastructure.datomic.release-store
  "Store transacional de releases, separado semanticamente dos lembretes."
  (:require [datomic.client.api :as d]
            [odisseu.platform.domain.release-announcements :as release]))

(defn- eid [db key]
  (ffirst (d/q '[:find ?e :in $ ?k :where [?e :platform.delivery/idempotency-key ?k]] db key)))
(defn- pull [db e]
  (when e
    (let [x (d/pull db '[*] e)]
      (release/normalize {:type (:platform.delivery/type x)
                          :release-version (:platform.delivery/release-version x)
                          :content-hash (:platform.delivery/content-hash x)
                          :chat-id (:platform.delivery/chat x)
                          :idempotency-key (:platform.delivery/idempotency-key x)
                          :eligibility (:platform.delivery/eligibility x)
                          :status (:platform.delivery/status x)
                          :claimant (:platform.delivery/claimant x)
                          :lease-until (:platform.delivery/lease-until x)
                          :fence (:platform.delivery/fence x)
                          :fail-count (:platform.delivery/fail-count x)
                          :last-error (:platform.delivery/last-error x)
                          :delivery-id (:platform.delivery/delivery-id x)
                          :message-id (:platform.delivery/message-id x)
                          :message-claimant (:platform.delivery/message-claimant x)
                          :message-fence (:platform.delivery/message-fence x)
                          :send-started-at (:platform.delivery/send-started-at x)
                          :sent-at (:platform.delivery/sent-at x)
                          :confirmed-at (:platform.delivery/confirmed-at x)
                          :em (:platform.delivery/due-at x)}))))

(defn find-release-announcement [conn key]
  (when-let [e (eid (d/db conn) (str key))] (pull (d/db conn) e)))

(defn create-release-announcement! [conn announcement]
  (let [a (release/normalize announcement) key (:idempotency-key a)]
    (when-not (and (= :release (:type a)) (seq key) (= :eligible (:eligibility a)))
      (throw (ex-info "release_invalid" {:announcement a})))
    (let [db (d/db conn) existing (eid db key)
          tx [{:platform.delivery/type :release
               :platform.delivery/release-version (:release-version a)
               :platform.delivery/content-hash (:content-hash a)
               :platform.delivery/chat (:chat-id a)
               :platform.delivery/idempotency-key key
               :platform.delivery/eligibility :eligible
               :platform.delivery/status :pending
               :platform.delivery/fence 0
               :platform.delivery/fail-count 0
               :platform.delivery/due-at (long (or (:em a) (System/currentTimeMillis)))}]]
      (if existing
        {:duplicate true :announcement (pull db existing)}
        (try
          (d/transact conn {:tx-data tx})
          {:duplicate false :announcement (find-release-announcement conn key)}
          (catch Exception e
            (if (re-find #"unique|conflict|already" (or (.getMessage e) ""))
              {:duplicate true :announcement (find-release-announcement conn key)}
              (throw e))))))))

(defn claim-release-announcement! [conn key claimant lease-ms now-ms]
  (let [db (d/db conn) e (eid db key) cur (pull db e) now (long now-ms)
        expired? (and (= :claimed (:status cur)) (< (long (or (:lease-until cur) 0)) now))
        claimable? (and cur (or (= :pending (:status cur)) (= :failed (:status cur))
                                (and expired? (nil? (:send-started-at cur))))) ]
    (if-not claimable?
      {:claimed false :reason (if cur "not_claimable" "not_found") :announcement cur}
      (let [old-status (:status cur) old-fence (:fence cur) new-fence (inc old-fence)]
        (try
          (d/transact conn {:tx-data [[:db/cas e :platform.delivery/status old-status :claimed]
                                      [:db/cas e :platform.delivery/fence old-fence new-fence]
                                      {:db/id e :platform.delivery/claimant (str claimant)
                                       :platform.delivery/lease-until (+ now (long lease-ms))}]})
          {:claimed true :fence new-fence :claimant (str claimant)
           :lease-until (+ now (long lease-ms))
           :announcement (find-release-announcement conn key)}
          (catch Exception ex
            (if (re-find #"cas-failed" (str ex))
              {:claimed false :reason "cas_conflict" :announcement (find-release-announcement conn key)}
              (throw ex))))))))

(defn complete-release-announcement! [conn key claimant fence]
  (let [db (d/db conn) e (eid db key) cur (pull db e)]
    (if (= :completed (:status cur))
      {:completed true :duplicate true :announcement cur}
      (if (and cur (= :claimed (:status cur)) (= (str claimant) (str (:claimant cur)))
             (= (long fence) (:fence cur)))
      (do (d/transact conn {:tx-data [{:db/id e :platform.delivery/status :completed
                                       :platform.delivery/confirmed-at (System/currentTimeMillis)}]})
          {:completed true :announcement (find-release-announcement conn key)})
      {:completed false :reason (if (nil? cur) "not_found" "fence_mismatch") :announcement cur}))))

(defn associate-message! [conn key claimant fence message-id]
  (let [db (d/db conn) e (eid db key) cur (pull db e)]
    (when-not (and cur (= :uncertain (:status cur)) (= (str claimant) (str (:claimant cur)))
                 (= (long fence) (:fence cur)) (seq message-id))
      (throw (ex-info "release_message_fence_mismatch" {:key key})))
    (when (and (:message-id cur) (not= (:message-id cur) message-id))
      (throw (ex-info "release_message_already_bound" {:key key})))
    (d/transact conn {:tx-data [{:db/id e :platform.delivery/message-id (str message-id)
                                 :platform.delivery/message-claimant (str claimant)
                                 :platform.delivery/message-fence (long fence)
                                 :platform.delivery/sent-at (System/currentTimeMillis)}]})
    (find-release-announcement conn key)))

(defn find-by-message-id [conn message-id]
  (when-let [[[e]] (seq (d/q '[:find ?e :in $ ?m :where [?e :platform.delivery/message-id ?m]] (d/db conn) (str message-id)))]
    (pull (d/db conn) e)))

(defn confirm-message!
  ([conn message-id ack]
   (let [a (find-by-message-id conn message-id)]
     (if a (confirm-message! conn message-id ack (:claimant a) (:fence a))
         {:confirmed false :reason "unknown_message"})))
  ([conn message-id ack claimant fence]
   (let [a (find-by-message-id conn message-id)]
    (cond
      (nil? a) {:confirmed false :reason "unknown_message"}
      (< (long ack) 1) {:confirmed false :reason "ack_insufficient" :announcement a}
      (= :completed (:status a)) {:confirmed true :duplicate true :announcement a}
      (and (= :uncertain (:status a))
           (= (str claimant) (str (:message-claimant a)))
           (= (long fence) (:message-fence a))
           (= (str claimant) (str (:claimant a)))
           (= (long fence) (:fence a))
           (> (long (or (:lease-until a) 0)) (System/currentTimeMillis)))
      (do (d/transact conn {:tx-data [{:db/id (eid (d/db conn) (:idempotency-key a))
                                       :platform.delivery/status :completed
                                       :platform.delivery/confirmed-at (System/currentTimeMillis)}]})
          {:confirmed true :announcement (find-release-announcement conn (:idempotency-key a))})
      :else {:confirmed false :reason "fence_mismatch" :announcement a}))))

(defn fail-release-announcement! [conn key claimant fence error-msg]
  (let [db (d/db conn) e (eid db key) cur (pull db e)]
    (if (and cur (#{:claimed :uncertain} (:status cur)) (= (str claimant) (str (:claimant cur)))
             (= (long fence) (:fence cur)))
      (do (d/transact conn {:tx-data [{:db/id e :platform.delivery/status (if (= "transport_uncertain" (str error-msg)) :uncertain :failed)
                                       :platform.delivery/fail-count (inc (:fail-count cur))
                                       :platform.delivery/last-error (str error-msg)}]})
          {:failed true :announcement (find-release-announcement conn key)})
      {:failed false :reason (if (nil? cur) "not_found" "fence_mismatch") :announcement cur})))

(defn begin-release-announcement-send! [conn key claimant fence]
  (let [db (d/db conn) e (eid db key) cur (pull db e) now (System/currentTimeMillis)]
    (if (and cur (= :claimed (:status cur)) (= (str claimant) (str (:claimant cur)))
             (= (long fence) (:fence cur)) (> (long (or (:lease-until cur) 0)) now))
      (do (d/transact conn {:tx-data [[:db/cas e :platform.delivery/status :claimed :uncertain]
                                      {:db/id e :platform.delivery/send-started-at now}]})
          {:started true :announcement (find-release-announcement conn key)})
      {:started false :reason (if (nil? cur) "not_found" "fence_mismatch") :announcement cur})))
