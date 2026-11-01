(ns pokemon.infrastructure.datomic.raid-auto-log
  "Logs estruturados (EDN numa linha) — sem PII além de chat-id/raid-id.")

(def ^:private allowed-keys
  #{:chat-id :raid-id :spawn-id :due-at :next-at :origem :result :event-id
    :active-raid-id :active-fase})

(defn emit!
  "event: keyword/string. fields: mapa plano (valores escalares, chaves allowlist)."
  [event fields]
  (let [row (cond-> {:event (name event)
                     :ts (System/currentTimeMillis)}
              (map? fields) (merge (into {}
                                         (keep (fn [[k v]]
                                                 (let [kk (if (keyword? k) k (keyword (str k)))]
                                                   (when (and (contains? allowed-keys kk)
                                                              (some? v)
                                                              (or (string? v) (number? v)
                                                                  (boolean? v) (keyword? v)))
                                                     [kk (if (keyword? v) (name v) v)])))
                                               fields))))]
    (binding [*out* *err*]
      (println (pr-str row)))
    row))
