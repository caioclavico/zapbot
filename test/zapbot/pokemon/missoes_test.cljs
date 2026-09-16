(ns zapbot.pokemon.missoes-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [zapbot.pokemon.missoes :as missoes]))

(deftest missoes-diarias-escalam-com-o-nivel
  (testing "nível 1 mantém as metas e recompensas básicas"
    (let [explorador (first (missoes/catalogo-do-dia {"nivel" 1}))]
      (is (= 3 (:meta explorador)))
      (is (= 3 (:pokebolas explorador)))))
  (testing "a cada cinco níveis aplica mais um multiplicador"
    (let [colecionador (second (missoes/catalogo-do-dia {"nivel" 6}))]
      (is (= 4 (:meta colecionador)))
      (is (= 8 (:pokebolas colecionador)))
      (is (= 2 (:grandes colecionador))))))

(deftest progresso-diario-e-limitado-e-renovado
  (let [dia "2026-09-16"
        conta (-> {}
                  (missoes/registrar-evento dia "selvagens" 1)
                  (missoes/registrar-evento dia "selvagens" 1)
                  (missoes/registrar-evento dia "selvagens" 1)
                  (missoes/registrar-evento dia "selvagens" 1))]
    (is (= 3 (get-in conta ["missoes-diarias" "progresso" "selvagens"])))
    (is (= ["selvagens"] (mapv :id (missoes/disponiveis (get conta "missoes-diarias")))))
    (is (= {} (get (missoes/estado-do-dia conta "2026-09-17" 1) "progresso")))))

(deftest semana-comeca-na-segunda-feira
  (is (= "2026-09-14" (missoes/semana-de "2026-09-14")))
  (is (= "2026-09-14" (missoes/semana-de "2026-09-20")))
  (is (= "2026-09-21" (missoes/semana-de "2026-09-21"))))

(deftest resgate-semanal-entrega-e-marca-recompensas
  (let [semana "2026-09-14"
        conta {"moedas" 10
               "missoes-semanais" {"semana" semana
                                    "ginasios" ["pedra" "agua"]
                                    "tipos" [] "pvp" 0 "presentes" 0
                                    "resgatadas" []}}
        [nova moedas itens] (missoes/resgatar-semanais conta semana)]
    (is (= 60 moedas))
    (is (= 70 (get nova "moedas")))
    (is (= ["ginasios"] (get-in nova ["missoes-semanais" "resgatadas"])))
    (is (= {"pokebola" 8 "grande-bola" 4 "ultra-bola" 2
            "catalisador-evolutivo" 1}
           itens))))
