(ns odisseu.platform.infrastructure.migration.compare
  "Comparação de estado Cassandra-shape ↔ Datomic-shape (sintético)."
  (:require [clojure.set :as set]))

(defn normalize-bloqueio
  [m]
  (into (sorted-map)
        (map (fn [[cid info]]
               [cid {"bot?" (boolean (or (get info "bot?") (:bot? info)))
                     "comandos" (vec (sort (map str (or (get info "comandos")
                                                        (:comandos info)
                                                        []))))}]))
        (or m {})))

(defn normalize-admins
  "Omite chats sem admins (equivalente a ausente no Datomic)."
  [m]
  (into (sorted-map)
        (keep (fn [[cid ids]]
                (let [v (vec (sort (map str ids)))]
                  (when (seq v) [cid v])))
              (or m {}))))

(defn normalize-enquetes
  "Cassandra-shape → mapa ordenado comparável campo a campo."
  [m]
  (into (sorted-map)
        (map (fn [[cid info]]
               (let [info (or info {})
                     votos (or (get info "votos") (:votos info) {})]
                 [cid {"pergunta" (str (or (get info "pergunta") (:pergunta info) ""))
                       "opcoes" (mapv str (or (get info "opcoes") (:opcoes info) []))
                       "votos" (into (sorted-map)
                                     (map (fn [[k v]]
                                            [(str k) (long (if (number? v) v (Long/parseLong (str v))))])
                                          votos))
                       "prefix" (str (or (get info "prefix") (:prefix info) "!"))}])))
        (or m {})))

(defn normalize-quiz-historico
  "Omite chats com histórico vazio (equivalente a ausente)."
  [m]
  (into (sorted-map)
        (keep (fn [[cid qs]]
                (let [v (mapv str (or qs []))]
                  (when (seq v) [cid v])))
              (or m {}))))

(defn normalize-lembretes
  "id → {id,chat,texto,criador,em}; omite vazios."
  [m]
  (into (sorted-map)
        (map (fn [[id info]]
               (let [info (or info {})]
                 [(str (or (get info "id") id))
                  {"id" (str (or (get info "id") id))
                   "chat" (str (or (get info "chat") ""))
                   "texto" (str (or (get info "texto") ""))
                   "criador" (str (or (get info "criador") ""))
                   "em" (long (or (get info "em") 0))}])))
        (or m {})))

(defn normalize-bugs
  "id → campos canônicos do relatório."
  [m]
  (into (sorted-map)
        (map (fn [[id info]]
               (let [info (or info {})
                     bid (str (or (get info "id") id))]
                 [bid
                  (cond-> {"id" bid
                           "versao" (str (or (get info "versao") ""))
                           "chat" (str (or (get info "chat") ""))
                           "reportado-por" (str (or (get info "reportado-por") ""))
                           "autor-mensagem" (str (or (get info "autor-mensagem") ""))
                           "mensagem" (str (or (get info "mensagem") ""))
                           "origem" (str (or (get info "origem") "anterior"))
                           "reportado-em" (long (or (get info "reportado-em") 0))
                           "status" (str (or (get info "status") "aberto"))}
                    (get info "mensagem-em")
                    (assoc "mensagem-em" (long (get info "mensagem-em")))
                    (get info "resolvido-em")
                    (assoc "resolvido-em" (long (get info "resolvido-em"))))])))
        (or m {})))

(defn normalize-participantes
  "chat → {player → nome}."
  [m]
  (into (sorted-map)
        (map (fn [[cid names]]
               [(str cid)
                (into (sorted-map)
                      (map (fn [[pid nome]] [(str pid) (str nome)]))
                      (or names {}))]))
        (or m {})))

(defn normalize-rank
  "chat → player → info; jogos/efeitos ordenados para comparação estável."
  [m]
  (into (sorted-map)
        (map (fn [[cid players]]
               [cid
                (into (sorted-map)
                      (map (fn [[pid info]]
                             (let [info (or info {})
                                   jogos (or (get info "jogos") {})
                                   efeitos (or (get info "efeitos-pokemon-http") {})]
                               [pid
                                (cond-> {"nome" (str (or (get info "nome") ""))
                                         "pontos" (long (or (get info "pontos") 0))
                                         "jogos" (into (sorted-map)
                                                       (map (fn [[k v]] [(str k) (long v)])
                                                            jogos))}
                                  (get info "somente-efeitos-http")
                                  (assoc "somente-efeitos-http" true)
                                  (seq efeitos)
                                  (assoc "efeitos-pokemon-http"
                                         (into (sorted-map)
                                               (map (fn [[k v]] [(str k) v]) efeitos))))]))
                            (or players {})))]))
        (or m {})))

(defn diff-maps
  "Retorna {:only-left :only-right :changed} comparando mapas normalizados."
  [left right]
  (let [lk (set (keys left))
        rk (set (keys right))]
    {:only-left (into (sorted-map) (select-keys left (set/difference lk rk)))
     :only-right (into (sorted-map) (select-keys right (set/difference rk lk)))
     :changed (into (sorted-map)
                    (keep (fn [k]
                            (when (not= (get left k) (get right k))
                              [k {:left (get left k) :right (get right k)}]))
                          (set/intersection lk rk)))}))

(defn equivalent?
  [left right]
  (let [d (diff-maps left right)]
    (and (empty? (:only-left d))
         (empty? (:only-right d))
         (empty? (:changed d)))))
