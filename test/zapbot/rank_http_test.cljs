(ns zapbot.rank-http-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.rank :as rank]))

(def incremento {:id "rank-1" :type "rank.increment" :chatId "teste-http"
                 :playerId "ash" :playerName "Ash" :game "pokemon"})

(deftest efeito-http-persiste-pontuacao-e-dedupe-juntos
  (async done
    (let [salvar-original armazenamento/salvar-confirmado!
          anterior @rank/placares
          gravacoes (atom [])]
      (reset! rank/placares {})
      (set! armazenamento/salvar-confirmado!
            (fn [chave valor] (swap! gravacoes conj [chave valor]) (p/resolved nil)))
      (-> (p/let [primeiro (rank/aplicar-efeito! incremento)
                  segundo (rank/aplicar-efeito! incremento)
                  penalizou (rank/aplicar-efeito! (assoc incremento :id "rank-2" :type "rank.decrement"))
                  repetido (rank/aplicar-efeito! (assoc incremento :id "rank-2" :type "rank.decrement"))]
            (is (every? true? [primeiro segundo penalizou repetido]))
            (is (= 0 (get-in @rank/placares ["teste-http" "ash" "pontos"])))
            (is (= 1 (get-in @rank/placares ["teste-http" "ash" "jogos" "pokemon"])))
            (is (= #{"rank-1" "rank-2"}
                   (set (keys (get-in @rank/placares ["teste-http" "ash" "efeitos-pokemon-http"])))))
            (is (every? #(and (= "rank" (first %))
                             (get-in (second %) ["teste-http" "ash" "efeitos-pokemon-http"])) @gravacoes)))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! armazenamento/salvar-confirmado! salvar-original)
                       (reset! rank/placares anterior)
                       (done)))))))

(deftest efeito-que-falhou-em-disco-e-reconfirmado-sem-novo-ponto
  (async done
    (let [salvar-original armazenamento/salvar-confirmado!
          anterior @rank/placares
          tentativas (atom 0)]
      (reset! rank/placares {})
      (set! armazenamento/salvar-confirmado!
            (fn [_ _]
              (if (= 1 (swap! tentativas inc))
                (p/rejected (js/Error. "Cassandra offline")) (p/resolved nil))))
      (-> (rank/aplicar-efeito! incremento)
          (p/then (fn [_] (is false "Primeira tentativa deveria falhar.")))
          (p/catch (fn [_] (rank/aplicar-efeito! incremento)))
          (p/then (fn [resultado]
                    (is (true? resultado))
                    (is (= 2 @tentativas))
                    (is (= 1 (get-in @rank/placares ["teste-http" "ash" "pontos"])))
                    (rank/aplicar-efeito! (assoc incremento :type "rank.decrement"))))
          (p/then (fn [_] (is false "Mesmo ID com payload diferente deveria falhar.")))
          (p/catch (fn [erro] (is (str/includes? (.-message erro) "conteúdo diferente"))))
          (p/finally (fn []
                       (set! armazenamento/salvar-confirmado! salvar-original)
                       (reset! rank/placares anterior)
                       (done)))))))

(deftest penalizacao-sem-pontos-nao-cria-jogador-visivel-no-rank
  (async done
    (let [salvar-original armazenamento/salvar-confirmado!
          anterior @rank/placares]
      (reset! rank/placares {})
      (set! armazenamento/salvar-confirmado! (fn [_ _] (p/resolved nil)))
      (-> (p/let [resultado (rank/aplicar-efeito! (assoc incremento :type "rank.decrement"))]
            (is (false? resultado))
            (is (str/includes? (rank/formatar-rank "teste-http") "Ainda ninguém pontuou"))
            (p/let [_ (rank/aplicar-efeito! (assoc incremento :id "rank-3"))]
              (is (str/includes? (rank/formatar-rank "teste-http") "Ash - 1 pts"))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! armazenamento/salvar-confirmado! salvar-original)
                       (reset! rank/placares anterior)
                       (done)))))))
