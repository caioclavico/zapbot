(ns a6-recovery-plan
  "Planejamento A6.4: IDs estáveis, economia, manifesto dry-run.
  Sem confirm!, sem Datomic, sem Cassandra."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [pokemon.application.recovery-manifest :as man]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.recovery-stable-id :as sid]))

(defn- read-edn [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- write-edn! [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data)))
  path)

(defn -main [& args]
  (let [opts (apply hash-map args)
        private-path (get opts "--private-accounts")
        matrix-path (get opts "--matrix")
        loja-path (get opts "--loja-slim")
        out-dir (get opts "--out")]
    (when-not (and private-path matrix-path out-dir)
      (println "Uso: --private-accounts f.edn --matrix f.edn --out DIR [--loja-slim f.edn]")
      (System/exit 2))
    (let [private (read-edn private-path)
          matrix (read-edn matrix-path)
          loja (when loja-path (read-edn loja-path))
          accounts (:accounts private)
          by-player (group-by :playerId accounts)
          pending-hashes (->> (:rows (:matrix matrix))
                              (filter #(= :pending (:status %)))
                              (map :player-hash)
                              set)
          id-classified
          (mapv (fn [{:keys [chatId playerId account source]}]
                  (let [c (sid/classify-account {:chat-id chatId
                                                 :player-id playerId
                                                 :account account})]
                    {:source (keyword (name source))
                     :player-hash (rr/anonymize-id playerId)
                     :chat-hash (rr/anonymize-id chatId)
                     :import-ready? (:import-ready? c)
                     :status-counts (:status-counts c)
                     :pokemon-count (:pokemon-count c)}))
                accounts)
          id-risk (sid/collision-risk-matrix
                   (mapv (fn [{:keys [chatId playerId account]}]
                           (sid/classify-account {:chat-id chatId
                                                  :player-id playerId
                                                  :account account}))
                         (filter #(= "cassandra-v1" (name (:source %))) accounts)))
          ;; Caso multi-chat: achar player-ids com >1 chat
          multi
          (->> by-player
               (keep (fn [[pid xs]]
                       (let [chats (set (map :chatId xs))]
                         (when (> (count chats) 1)
                           (let [scope (man/identity-scope
                                        pid
                                        (map #(hash-map :chat-id (:chatId %)
                                                        :fingerprint-hash
                                                        (:fingerprint
                                                         (rr/account-fingerprint (:account %))))
                                             xs))]
                             {:player-hash (rr/anonymize-id pid)
                              :scope (dissoc scope :note)
                              :sources (sort (set (map #(keyword (name (:source %))) xs)))
                              :per-chat
                              (mapv (fn [[c ys]]
                                      {:chat-hash (rr/anonymize-id c)
                                       :appearances (count ys)
                                       :pokemon-counts
                                       (mapv #(get-in (rr/account-fingerprint (:account %))
                                                      [:pokemon-count])
                                             ys)})
                                    (group-by :chatId xs))})))))
               vec)
          ;; Manifesto: uma entrada por (player, chat, melhor fonte V1 resolved)
          entries
          (->> by-player
               (mapcat
                (fn [[pid xs]]
                  (let [by-chat (group-by :chatId xs)]
                    (for [[chat ys] by-chat
                          :let [v1 (first (filter #(= "cassandra-v1" (name (:source %))) ys))
                                row (first (filter #(= (rr/anonymize-id pid) (:player-hash %))
                                                   (:rows (:matrix matrix))))
                                status (:status row)
                                acc (:account (or v1 (first ys)))
                                src (keyword (name (:source (or v1 (first ys)))))]
                          :when acc]
                      (man/entry-from-accounts
                       {:chat-id chat
                        :player-id pid
                        :source src
                        :account acc
                        :loja-appearances [] ; economia agregada à parte (hashes distintos)
                        :trainer-status (or status :inconclusive)
                        :all-chat-appearances
                        (mapv #(hash-map :chat-id (:chatId %)
                                         :account (:account %))
                              xs)})))))
               vec)
          ;; Economia agregada do slim (hashes Node) — status counts only
          eco-agg
          (when loja
            (let [by-ph (group-by :playerHash (:appearances loja))
                  classes
                  (mapv (fn [[ph xs]]
                          (let [fps (set (map :fingerprint xs))]
                            {:player-hash ph
                             :status (cond (empty? xs) :unknown
                                           (= 1 (count fps)) :known
                                           :else :divergent)
                             :sources (sort (set (map :source xs)))
                             :coins (mapv :coins xs)}))
                        by-ph)]
              {:classified classes
               :status-counts (frequencies (map :status classes))
               :players-with-loja (count classes)
               :note :loja-hashes-are-sha256-raw-not-payload-hash}))
          sim (man/simulate-batch (filterv :eligible? entries))
          sim-all (man/simulate-batch entries)
          report {:ok true
                  :at (str (java.time.Instant/now))
                  :dry-run true
                  :confirm-invoked false
                  :stable-id-scheme sid/scheme-version
                  :id-risk-v1 id-risk
                  :id-by-source
                  (frequencies
                   (map (fn [x]
                          (str (name (:source x))
                               "|ready=" (:import-ready? x)))
                        id-classified))
                  :multi-chat-identities multi
                  :economy eco-agg
                  :manifest {:entry-count (count entries)
                             :eligible-count (count (filter :eligible? entries))
                             :blocked-reasons
                             (frequencies (mapcat :block-reasons entries))
                             :simulation-eligible-only sim
                             :simulation-all sim-all}
                  :pending-remain (count pending-hashes)
                  :aliases-accepted 0
                  :batch-gate
                  {:require-human-pending true
                   :require-id-ready true
                   :require-economy-policy true
                   :require-explicit-alias-evidence true
                   :confirm-batch-forbidden-until-approval true}}]
      (.mkdirs (io/file out-dir))
      (write-edn! (str out-dir "/id-collision-matrix.edn")
                  {:ok true :scheme sid/scheme-version :v1 id-risk
                   :per-appearance (mapv #(dissoc % :rows) id-classified)})
      (write-edn! (str out-dir "/manifest-preliminary-anonymized.edn")
                  {:ok true
                   :dry-run true
                   :entries (mapv #(-> %
                                       (update :identity dissoc :scope)
                                       (assoc :identity-scope-summary
                                              (select-keys (get-in % [:identity :scope])
                                                           [:canonical-model :multi-chat?
                                                            :historically-mixed? :chat-count])))
                                  entries)
                   :simulation sim-all})
      (write-edn! (str out-dir "/recovery-plan-summary.edn") report)
      (pp/pprint (dissoc report :economy))
      (when eco-agg (pp/pprint {:economy-status-counts (:status-counts eco-agg)
                                :economy-note (:note eco-agg)}))
      (pp/pprint {:multi-chat multi}))))
