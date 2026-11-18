(ns pokemon.application.recovery-manifest
  "Manifesto preliminar de importação A6.4 — validação sem persistência.

  Não chama confirm!, não abre Datomic, não escreve Cassandra."
  (:require [clojure.string :as str]
            [pokemon.application.recovery-economy :as economy]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.recovery-stable-id :as sid]))

(defn identity-scope
  "Escopo canônico no código: player-key = chat-id|player-id (por chat).

  appearances: [{:chat-id :player-id :fingerprint-hash}]"
  [player-id appearances]
  (let [chats (set (map :chat-id appearances))
        kinds (set (map rr/identity-kind (map :chat-id appearances)))
        fps-by-chat (->> appearances
                         (group-by :chat-id)
                         (map (fn [[c xs]]
                                [c (set (map :fingerprint-hash xs))]))
                         (into {}))
        multi-chat? (> (count chats) 1)
        distinct-progress?
        (and multi-chat?
             (< 1 (count (set (map #(hash (get fps-by-chat %)) chats)))))]
    {:player-id-kind (rr/identity-kind player-id)
     :canonical-model :per-chat
     :evidence :pokemon.domain.identities/player-key
     :chat-count (count chats)
     :chat-kinds (sort kinds)
     :multi-chat? multi-chat?
     :historically-mixed? (boolean (and multi-chat? distinct-progress?))
     :import-unit ":chat-id + :player-id (nunca player-id isolado)"
     :note (if multi-chat?
             :same-player-id-distinct-saves-per-chat
             :single-chat)}))

(defn- account-fp-hash [account]
  (some-> (rr/account-fingerprint account) :fingerprint))

(defn build-entry
  "Monta uma linha de manifesto. dry-run/persist fixos neste namespace."
  [{:keys [player-hash chat-hash player-kind sources
           trainer-status economy-status economy
           id-plan provenance fingerprint justification
           eligible? block-reasons identity-scope]}]
  {:player-hash player-hash
   :chat-hash chat-hash
   :player-kind player-kind
   :sources (vec sources)
   :provenance provenance
   :fingerprint fingerprint
   :identity {:decision (if (seq block-reasons)
                          :blocked
                          (if eligible? :eligible :await-human))
              :scope identity-scope
              :aliases-accepted 0}
   :economy {:status economy-status
             :summary (dissoc economy :by-source)
             :options (mapv :id (economy/option-for {:status economy-status}))}
   :ids id-plan
   :trainer-status trainer-status
   :eligible? (boolean eligible?)
   :block-reasons (vec block-reasons)
   :justification justification
   :dry-run true
   :persist false})

(defn validate-entry
  "Valida manifesto sem side-effects."
  [entry]
  (let [errors (cond-> []
                 (str/blank? (str (:player-hash entry))) (conj :missing-player-hash)
                 (str/blank? (str (:chat-hash entry))) (conj :missing-chat-hash)
                 (nil? (:fingerprint entry)) (conj :missing-fingerprint)
                 (nil? (get-in entry [:provenance :legacy-version])) (conj :missing-legacy-version)
                 (and (:eligible? entry)
                      (contains? (set (:block-reasons entry)) :ambiguous-ids))
                 (conj :eligible-with-ambiguous-ids)
                 (and (:eligible? entry)
                      (= :divergent (get-in entry [:economy :status])))
                 (conj :divergent-economy-not-auto-eligible))]
    {:ok (empty? errors) :errors errors :entry-player-hash (:player-hash entry)}))

(defn simulate-batch
  "Simula lote: valida entradas; não importa / não confirm!."
  [entries]
  (let [results (mapv validate-entry entries)
        ok (filterv :ok results)
        bad (filterv (complement :ok) results)]
    {:dry-run true
     :persist false
     :confirm-invoked false
     :entry-count (count entries)
     :valid-count (count ok)
     :invalid-count (count bad)
     :invalid (mapv #(select-keys % [:entry-player-hash :errors]) bad)
     :batch-ready? (and (seq entries) (empty? bad)
                        (every? :eligible? entries))}))

(defn entry-from-accounts
  "Helper de planejamento: conta treinador + loja + aparições multi-chat."
  [{:keys [chat-id player-id source account loja-appearances
           trainer-status all-chat-appearances]}]
  (let [ph (rr/anonymize-id player-id)
        ch (rr/anonymize-id chat-id)
        fp (rr/account-fingerprint account)
        ids (sid/classify-account {:chat-id chat-id :player-id player-id :account account})
        eco (economy/classify-player-economy (or loja-appearances []))
        scope (identity-scope player-id
                              (map #(hash-map :chat-id (:chat-id %)
                                              :player-id player-id
                                              :fingerprint-hash (or (:fingerprint-hash %)
                                                                    (account-fp-hash (:account %))))
                                   (or all-chat-appearances
                                       [{:chat-id chat-id :account account}])))
        block (cond-> []
                (not= :resolved trainer-status) (conj :trainer-not-resolved)
                (not (:import-ready? ids)) (conj :ids-not-ready)
                (pos? (get-in ids [:status-counts :ambiguous] 0)) (conj :ambiguous-ids)
                (= :divergent (:status eco)) (conj :economy-divergent))
        eligible? (and (= :resolved trainer-status)
                       (:import-ready? ids)
                       (not= :divergent (:status eco)))]
    (build-entry
     {:player-hash ph
      :chat-hash ch
      :player-kind (rr/identity-kind player-id)
      :sources [source]
      :trainer-status trainer-status
      :economy-status (:status eco)
      :economy eco
      :id-plan {:scheme (:scheme ids)
                :status-counts (:status-counts ids)
                :import-ready? (:import-ready? ids)
                :synthesizable (get-in ids [:status-counts :synthesizable] 0)
                :native (get-in ids [:status-counts :native] 0)
                :ambiguous (get-in ids [:status-counts :ambiguous] 0)}
      :provenance {:kind source
                   :legacy-version (case source
                                     :cassandra-v1 "v1"
                                     :cassandra-v2 "v2"
                                     :cassandra-operational "operational"
                                     "unknown")
                   :stable-id-scheme sid/scheme-version}
      :fingerprint (:fingerprint fp)
      :identity-scope (cond-> (dissoc scope :note)
                        (:multi-chat? scope)
                        (assoc :multi-chat-note :import-per-chat-unit-only))
      :eligible? eligible?
      :block-reasons block
      :justification
      (cond
        eligible? :resolved-ids-ready-economy-ok
        (seq block) (str/join "," (map name block))
        :else :await-human)})))
