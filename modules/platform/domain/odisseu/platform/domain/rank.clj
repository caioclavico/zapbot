(ns odisseu.platform.domain.rank
  "Shape canônico do placar (espelha zapbot.rank CLJS)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn player-key
  [chat-id player-id]
  (str chat-id "|" player-id))

(defn games->edn [jogos]
  (pr-str (into (sorted-map)
                (map (fn [[k v]] [(str k) (long v)])
                     (or jogos {})))))

(defn edn->games [s]
  (let [v (edn/read-string (str (or s "{}")))]
    (into {}
          (map (fn [[k v]] [(str k) (long v)])
               (if (map? v) v {})))))

(defn effects->edn [efeitos]
  (pr-str (or efeitos {})))

(defn edn->effects [s]
  (let [v (edn/read-string (str (or s "{}")))]
    (if (map? v) v {})))

(defn normalize-player
  [info]
  (let [info (or info {})
        jogos (or (get info "jogos") (:jogos info) {})
        efeitos (or (get info "efeitos-pokemon-http") (:efeitos-pokemon-http info) {})]
    (cond-> {"nome" (str (or (get info "nome") (:nome info) "Alguém"))
             "pontos" (long (or (get info "pontos") (:pontos info) 0))
             "jogos" (into {} (map (fn [[k v]] [(str k) (long v)]) jogos))}
      (or (get info "somente-efeitos-http") (:somente-efeitos-http info))
      (assoc "somente-efeitos-http" true)
      (seq efeitos)
      (assoc "efeitos-pokemon-http"
             (into {} (map (fn [[k v]] [(str k) v]) efeitos))))))

(defn normalize-state
  [dados]
  (into {}
        (map (fn [[cid players]]
               [(str cid)
                (into {}
                      (map (fn [[pid info]]
                             [(str pid) (normalize-player info)]))
                      (or players {}))]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (normalize-state estado))

(defn apply-score
  "Incrementa 1 ponto e 1 vitória no jogo (legado pontuar!)."
  [info nome jogo]
  (let [base (or info {"nome" nome "pontos" 0 "jogos" {}})]
    (-> base
        (assoc "nome" (str nome))
        (dissoc "somente-efeitos-http")
        (update "pontos" (fnil inc 0))
        (update-in ["jogos" (str jogo)] (fnil inc 0)))))

(defn apply-penalize
  "Dec 1 se pontos > 0. Retorna [novo-info penalizado?]."
  [info]
  (let [pts (long (get info "pontos" 0))]
    (if (pos? pts)
      [(update info "pontos" dec) true]
      [info false])))

(defn top-players
  "Ordena por pontos desc; omite somente-efeitos-http; empate estável por nome."
  [players-map n]
  (->> (vals players-map)
       (remove #(get % "somente-efeitos-http"))
       (sort-by (juxt #(- (long (get % "pontos" 0)))
                      #(str (get % "nome" ""))))
       (take n)
       vec))
