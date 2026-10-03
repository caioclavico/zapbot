(ns zapbot.rank
  "Emite efeitos neutros para o rank geral, cujo único proprietário é ZapBot.
  O serviço não lê nem grava o módulo Cassandra rank."
  (:require [promesa.core :as p]))

(defonce ^:private efeitos (atom {}))

(defn- adicionar! [cid efeito]
  (let [id (str (random-uuid))
        efeito (cond-> (assoc efeito :id id :chatId cid)
                 (= "rank.decrement" (:type efeito)) (assoc :token (str "{{rank:" id "}}")))]
    (swap! efeitos update cid (fnil conj []) efeito)
    efeito))

(defn pontuar! [cid pid nome jogo]
  (adicionar! cid {:type "rank.increment" :playerId pid :playerName nome :game jogo})
  (p/resolved nil))

(defn penalizacao-texto!
  "O adaptador seleciona a variante após aplicar o efeito de rank de fato."
  [cid pid texto-verdadeiro texto-falso]
  (let [efeito (adicionar! cid {:type "rank.decrement" :playerId pid
                               :whenTrue texto-verdadeiro :whenFalse texto-falso})]
    (str "{{rank:" (:id efeito) "}}")))

(defn recolher-efeitos! [cid]
  (let [pendentes (get @efeitos cid [])]
    (swap! efeitos dissoc cid)
    pendentes))
