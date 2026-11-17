(ns odisseu.platform.domain.release-announcements
  "Contrato puro de anúncios; entrega permanece no pipeline com lease/fence.")

(def eligible-states #{:eligible :pending :claimed :uncertain :completed :failed})
(def delivery-states #{:pending :claimed :uncertain :completed :failed})

(defn idempotency-key [{:keys [version content-hash chat-id]}]
  (when (every? #(and (string? %) (not-empty %)) [version content-hash chat-id])
    (str version ":" content-hash ":" chat-id)))

(defn eligible?
  [{:keys [enabled? jvm-healthy? node-healthy? datomic-accessible?
           whatsapp-ready? version-confirmed? rollback-complete?
           authorized?]}]
  (boolean (and enabled? jvm-healthy? node-healthy? datomic-accessible?
               whatsapp-ready? version-confirmed? rollback-complete? authorized?)))

(defn event
  [{:keys [version content-hash chat-id] :as input}]
  (let [key (idempotency-key input)]
    (when key
      {:type :release
       :release-version version
       :content-hash content-hash
       :chat-id chat-id
       :idempotency-key key
       :eligibility :eligible})))

(defn normalize [m]
  (let [m (or m {})]
    {:type (keyword (or (:type m) :release))
     :release-version (str (or (:release-version m) ""))
     :content-hash (str (or (:content-hash m) ""))
     :chat-id (str (or (:chat-id m) ""))
     :idempotency-key (str (or (:idempotency-key m) ""))
     :eligibility (keyword (or (:eligibility m) :pending))
     :status (keyword (or (:status m) :pending))
     :claimant (:claimant m) :lease-until (:lease-until m)
     :fence (long (or (:fence m) 0)) :fail-count (long (or (:fail-count m) 0))
     :last-error (:last-error m) :delivery-id (:delivery-id m) :message-id (:message-id m)
     :message-claimant (:message-claimant m) :message-fence (:message-fence m)
     :send-started-at (:send-started-at m)
     :sent-at (:sent-at m) :confirmed-at (:confirmed-at m)
     :em (long (or (:em m) 0))}))
