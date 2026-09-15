(ns zapbot.pokemon.progressao-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.shiny :as shiny]
            [zapbot.pokemon.treinador :as treinador]))

(def pokemon {:nome "Pikachu" :imagem "normal.png" :imagem-shiny "shiny.png"
              :tipos ["electric"] :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50
              :veloc 50 :nivel 1 :golpes []})

(deftest shiny-persiste-e-evolui
  (let [cid (str (random-uuid))
        brilhante (shiny/sortear pokemon 0)
        registro (treinador/pokemon->registro brilhante 0 nil)
        recarregado (js->clj (js/JSON.parse (js/JSON.stringify (clj->js registro))))]
    (is (not (:shiny? (shiny/sortear pokemon 1))))
    (is (:shiny? (first (treinador/registro->pokemon recarregado))))
    (is (= "shiny.png" (:imagem brilhante)))
    (is (= 100 (:hp brilhante)))
    (treinador/receber-doacao! cid "a" recarregado)
    (treinador/ganhar-xp-no-indice! cid "a" 0 9)
    (is (= 0 (get-in (treinador/equipe cid "a") [0 "hp-atual"])))
    (treinador/evoluir-no-indice! cid "a" 0
      (assoc pokemon :nome-novo "Raichu" :imagem-shiny "raichu-shiny.png"))
    (is (= "raichu-shiny.png" (get-in (treinador/equipe cid "a") [0 "imagem"])))
    (is (true? (get-in (treinador/equipe cid "a") [0 "shiny"])))
    (let [r (first (treinador/equipe cid "a"))]
      (treinador/receber-doacao! cid "b" r)
      (is (= r (first (treinador/equipe cid "b")))))))

(deftest pe-preserva-progresso-antigo
  (let [cid (str (random-uuid))]
    (treinador/registrar-vitoria-treinador! cid "a")
    (let [antes (treinador/xp-treinador cid "a")]
      (treinador/ganhar-pe-ginasio! cid "a" 5)
      (is (= (+ antes 5) (treinador/xp-treinador cid "a")))
      (treinador/ganhar-pe-ginasio! cid "a" 1)
      (is (= (+ antes 6) (treinador/xp-treinador cid "a")))
      (is (= 6 (:pe-ginasios (treinador/perfil-treinador cid "a")))))))

(deftest permanencia-limite-exato
  (let [ocupacao {"desde" 1000} limite (+ 1000 (* 6 60 60 1000))]
    (is (= 0 (ginasios/recompensa-permanencia nil limite)))
    (is (= 0 (ginasios/recompensa-permanencia ocupacao (dec limite))))
    (is (= 0 (ginasios/recompensa-permanencia ocupacao limite)))
    (is (= 50 (ginasios/recompensa-permanencia ocupacao (inc limite))))))

(deftest lideranca-reserva-libera-e-paga-uma-vez
  (let [cid (str (random-uuid)) registros (atom {})]
    (with-redefs [armazenamento/salvar! (fn [k v] (swap! registros assoc k v))]
      (doseq [pid ["a" "b"] i (range 4)]
        (treinador/adicionar-pokemon! cid pid (assoc pokemon :nome (str pid i)) (- 100 i) nil))
      (let [original (vec (treinador/equipe cid "a"))]
        (is (some? (ginasios/ocupar! cid "pedra" nil "a" "Ana" [0 2 3] 1000)))
        (is (= ["a1"] (mapv #(get % "nome") (treinador/equipe cid "a"))))
        (is (= [(original 0) (original 2) (original 3)] (get (ginasios/lider cid "pedra") "time")))
        (is (nil? (get-in @registros ["loja" cid "a"])))
        (let [anterior (ginasios/lider cid "pedra") agora (+ 1001 (* 6 60 60 1000))]
          (is (nil? (ginasios/ocupar! cid "pedra" anterior "a" "Ana" [0 1 2] agora)))
          (is (= 50 (:moedas (ginasios/ocupar! cid "pedra" anterior "b" "Bia" [0 1 2] agora))))
          (is (= 4 (count (treinador/equipe cid "a"))))
          (is (= [(original 1) (original 0) (original 2) (original 3)]
                 (treinador/equipe cid "a")))
          (is (= 50 (get-in @registros ["loja" cid "a" "moedas"])))
          (is (= "b" (get-in @registros ["ginasios" cid "pedra" "pid"])))
          (is (nil? (ginasios/ocupar! cid "pedra" anterior "b" "Bia" [0 1 2] agora)))
          (is (= 50 (get-in @registros ["loja" cid "a" "moedas"])))
          (is (= "b" (get (ginasios/lider cid "pedra") "pid")))
          (is (nil? (ginasios/lider "outro-chat" "pedra"))))))))

(deftest vitoria-diaria-e-reserva-invalida
  (let [cid (str (random-uuid))]
    (is (= :primeira (treinador/registrar-ginasio! cid "a" "pedra" "2026-09-14")))
    (is (nil? (treinador/registrar-ginasio! cid "a" "pedra" "2026-09-14")))
    (is (= :revanche (treinador/registrar-ginasio! cid "a" "pedra" "2026-09-15")))
    (treinador/adicionar-pokemon! cid "a" pokemon 100 nil)
    (is (thrown? js/Error (ginasios/ocupar! cid "pedra" nil "a" "Ana" [0 0 0] 0)))
    (is (= 1 (count (treinador/equipe cid "a"))))
    (is (nil? (ginasios/lider cid "pedra")))))
