(ns zapbot.pokemon.raid-lobby-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.pokemon.raid-lobby :as lobby]))

(def parametros
  {:id "r-1" :cid "g-1" :pid "ash" :nome "Ash"
   :chefe {"nome" "Lugia"} :liga "iniciante"
   :ginasio "pedra" :nome-ginasio "Pedra"
   :agora 1000 :duracao-ms 120000 :min-jogadores 2 :max-jogadores 5})

(deftest abertura-e-repeticao-segura
  (let [[raid resposta] (lobby/criar nil parametros)
        [igual erro] (lobby/criar raid (assoc parametros :id "r-2"))]
    (is (= :criada (:codigo resposta)))
    (is (= "r-1" (get raid "id")))
    (is (= 121000 (get raid "expira")))
    (is (= raid igual))
    (is (= :raide-ativa (:codigo erro)))))

(deftest nao-inicia-antes-do-prazo
  (let [[raid] (lobby/criar nil parametros)
        [igual resposta] (lobby/encerrar-inscricoes raid 120999)]
    (is (= raid igual))
    (is (= :prazo-nao-atingido (:codigo resposta)))))

(deftest lobby-fecha-e-nao-duplica-inscricao
  (let [[raid] (lobby/criar nil parametros)
        [r1] (lobby/inscrever raid "ash" {"nome" "Ash"} 1001)
        [r2 resp2] (lobby/inscrever r1 "ash" {"nome" "Ash"} 1002)
        [r3] (lobby/inscrever r2 "misty" {"nome" "Misty"} 1003)
        [final resp] (lobby/encerrar-inscricoes r3 121000)]
    (is (= :ja-inscrito (:codigo resp2)))
    (is (= ["ash" "misty"] (get r3 "ordem")))
    (is (= :pronta-para-combate (:codigo resp)))
    (is (= "pronta" (get final "fase")))))

(deftest sem-quorum-cancela
  (let [[raid] (lobby/criar nil parametros)
        [final resultado] (lobby/encerrar-inscricoes raid 121000)]
    (is (= "cancelada" (get final "fase")))
    (is (= :sem-participantes (:codigo resultado)))))

(deftest validacao-dos-limites
  (is (= :configuracao-invalida
         (:codigo (second (lobby/criar nil (assoc parametros :max-jogadores 0))))))
  (is (= :configuracao-invalida
         (:codigo (second (lobby/criar nil (assoc parametros :duracao-ms 0)))))))
