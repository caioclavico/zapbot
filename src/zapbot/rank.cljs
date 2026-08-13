(ns zapbot.rank
  "Placar de pontos por chat, compartilhado entre os jogos (!velha, !naval,
  !pokemon, !quiz). Persistido via zapbot.armazenamento (sobrevive a
  reinícios/deploys, igual ao resto do estado persistido)."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

(defonce ^:private placares (atom (or (armazenamento/obter "rank") {})))

(defn- persistir! []
  (armazenamento/salvar! "rank" @placares))

(defn pontuar!
  "Registra 1 ponto pra pid (com nome) no jogo indicado (string, ex.
  \"velha\"), nesse chat."
  [cid pid nome jogo]
  (swap! placares update-in [cid pid]
         (fn [info]
           (-> (or info {"nome" nome "pontos" 0 "jogos" {}})
               (assoc "nome" nome)
               (update "pontos" inc)
               (update-in ["jogos" jogo] (fnil inc 0)))))
  (persistir!))

(defn- top [cid quantidade]
  (->> (vals (get @placares cid {}))
       (sort-by #(get % "pontos") >)
       (take quantidade)))

(def ^:private medalha ["🥇" "🥈" "🥉"])

(defn- formatar-jogador [posicao info]
  (str (get medalha posicao (str (inc posicao) "º")) " " (get info "nome")
       " - " (get info "pontos") " pts"))

(defn formatar-rank
  "Retorna o texto do rank de pontos desse chat (top 10)."
  [cid]
  (let [melhores (top cid 10)]
    (str "🏆 *Rank do tio " config/bot-name " nesse chat:*\n\n"
         (if (empty? melhores)
           (str "Ainda ninguém pontuou por aqui. Jogue " config/prefix "velha, "
                config/prefix "naval, " config/prefix "pokemon ou " config/prefix
                "quiz pra entrar no rank!")
           (str/join "\n" (map-indexed formatar-jogador melhores))))))
