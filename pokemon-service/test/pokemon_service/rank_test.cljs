(ns pokemon-service.rank-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.rank :as rank]))

(deftest penalizacao-inclui-token-para-resolver-texto-no-gateway
  (let [token (rank/penalizacao-texto! "rank-test-chat" "player@lid" "perdeu ponto" "zero")
        [efeito] (rank/recolher-efeitos! "rank-test-chat")]
    (is (= token (:token efeito)))
    (is (= "rank.decrement" (:type efeito)))
    (is (= "player@lid" (:playerId efeito)))
    (is (= "perdeu ponto" (:whenTrue efeito)))
    (is (= "zero" (:whenFalse efeito)))
    (is (empty? (rank/recolher-efeitos! "rank-test-chat")))))
