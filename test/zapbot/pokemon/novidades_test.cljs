(ns zapbot.pokemon.novidades-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [zapbot.pokemon.core :as pokemon]
            [zapbot.pokemon.missoes :as missoes]
            [zapbot.pokemon.raids :as raids]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]))

(def registro {"nome" "Pikachu" "nivel" 5 "hp" 100 "hp-atual" 100
               "ataque" 60 "atq-esp" 60 "defesa" 50 "def-esp" 50
               "golpes" [{"classe" "fisico" "poder" 90 "slug" "tackle"}]})
(def lobby {"fase" "inscricoes" "criador" "a" "liga" "iniciante"
            "ordem" [] "participantes" {} "expira" 10000})
(defn dupla []
  (-> lobby (raids/entrar "a" "Ana" registro 0) first
      (raids/entrar "b" "Bia" registro 0) first))
(defn combate [] (first (raids/iniciar (dupla) "a" 1)))

(deftest semana-calendario
  (is (= "2026-09-14" (missoes/semana-de "2026-09-20")))
  (is (= "2026-09-21" (missoes/semana-de "2026-09-21")))
  (is (= "2025-12-29" (missoes/semana-de "2026-01-01"))))

(deftest semanais-distintos-resgate-unico-e-reinicio
  (let [semana "2026-09-14"
        c (-> {"moedas" 10}
              (missoes/registrar-semanal semana "ginasios" ["pedra" "pedra"])
              (missoes/registrar-semanal semana "tipos" ["fire" "fire"]))]
    (is (= 1 (missoes/progresso-semanal (missoes/estado-semanal c semana) "ginasios")))
    (is (= 1 (missoes/progresso-semanal (missoes/estado-semanal c semana) "tipos")))
    (is (= 0 (second (missoes/resgatar-semanais c semana))))
    (let [c (missoes/registrar-semanal c semana "ginasios" ["agua"])
          [pago valor] (missoes/resgatar-semanais c semana)]
      (is (= 60 valor))
      (is (= 70 (get pago "moedas")))
      (is (= [pago 0] (missoes/resgatar-semanais pago semana)))
      (is (= 0 (missoes/progresso-semanal (missoes/estado-semanal pago "2026-09-21") "ginasios"))))))

(deftest raid-validacao-inscricoes
  (doseq [r [(assoc registro "nivel" 30) (assoc registro "hp-atual" 0) (assoc registro "golpes" [])]]
    (is (= lobby (first (raids/entrar lobby "a" "Ana" r 0)))))
  (is (= lobby (first (raids/entrar lobby "a" "Ana" registro 10000))))
  (is (= (dupla) (first (raids/entrar (dupla) "a" "Ana" registro 0))))
  (is (= lobby (first (raids/iniciar lobby "a" 0))))
  (is (= (dupla) (first (raids/iniciar (dupla) "b" 0))))
  (let [cheio (reduce #(first (raids/entrar %1 %2 %2 registro 0)) lobby (map str (range 6)))]
    (is (= cheio (first (raids/entrar cheio "extra" "Extra" registro 0))))))

(deftest raid-turnos-e-persistencia
  (let [r (js->clj (js/JSON.parse (js/JSON.stringify (clj->js (combate)))))
        [novo] (raids/atacar r "a" 0 2)]
    (is (= r (first (raids/atacar r "b" 0 2))))
    (is (= r (first (raids/atacar r "a" 8 2))))
    (is (= r (first (raids/atacar r "a" nil 2))))
    (is (= "b" (get novo "vez")))
    (is (< (get novo "hp-chefe") (get r "hp-chefe")))
    (is (= 80 (get-in novo ["participantes" "a" "hp"])))
    (is (= 100 (get-in novo ["participantes" "a" "pokemon" "hp-atual"])))
    (is (= r (first (raids/atacar r "a" 0 (get r "expira")))))))

(deftest raid-vitoria-derrota-e-desmaio
  (let [[fim] (raids/atacar (assoc (combate) "hp-chefe" 1) "a" 0 2)]
    (is (= "vitoria" (get fim "fase")))
    (is (= 1 (get-in fim ["participantes" "a" "dano"])))
    (is (= 100 (get-in fim ["participantes" "a" "hp"])))
    (is (= fim (first (raids/atacar fim "b" 0 3)))))
  (let [r (assoc-in (combate) ["participantes" "b" "hp"] 0)]
    (is (= "a" (get (first (raids/atacar r "a" 0 2)) "vez")))
    (is (= "derrota" (get (first (raids/atacar (assoc-in r ["participantes" "a" "hp"] 1) "a" 0 2)) "fase")))))

(deftest raid-premio-unico
  (let [cid (str (random-uuid))]
    (is (= 40 (loja/premiar-raid! cid "a" "2026-09-15")))
    (is (nil? (loja/premiar-raid! cid "a" "2026-09-15")))
    (is (= 40 (loja/moedas cid "a")))
    (is (= 40 (loja/premiar-raid! cid "a" "2026-09-16")))
    (is (= 80 (loja/moedas cid "a")))))

(deftest ginasio-ranking-e-isolamento
  (let [cid (str (random-uuid)) lider {"pid" "a" "nome" "Ana" "desde" 1000}]
    (ginasios/registrar-resultado! cid "pedra" lider "b" "Bia" false 2000)
    (ginasios/registrar-resultado! cid "pedra" lider "b" "Bia" true 3000)
    (ginasios/registrar-permanencia! cid "pedra" lider 61000)
    (let [dados (first (ginasios/ranking-dados cid "pedra" 62000))]
      (is (= 1 (get dados "defesas")))
      (is (= 60000 (get dados "tempo-ms"))))
    (is (empty? (ginasios/ranking-dados cid "agua" 62000)))
    (is (re-find #"Bia" (ginasios/historico cid "pedra")))
    (is (re-find #"líder defendeu" (ginasios/historico cid "pedra")))))

(deftest colecao-shiny-persiste
  (let [cid (str (random-uuid)) r (assoc registro "shiny" true "imagem" "shiny.png")]
    (is (= 1 (count (treinador/colecao-shiny! cid "a" [r r]))))
    (is (= "Pikachu" (get-in (treinador/colecao-shiny! cid "a" []) ["pikachu" "nome"])))
    (is (empty? (treinador/colecao-shiny! cid "b" [])))))

(deftest colecao-shiny-preserva-especies-na-evolucao-e-doacao
  (let [cid (str (random-uuid))
        r (assoc registro "shiny" true "imagem" "pikachu-shiny.png" "tipos" ["electric"])]
    (treinador/receber-doacao! cid "a" r)
    (treinador/evoluir-no-indice! cid "a" 0
      {:nome-novo "Raichu" :imagem "raichu.png" :imagem-shiny "raichu-shiny.png"
       :tipos ["electric"] :hp 100 :ataque 60 :defesa 50 :atq-esp 60 :def-esp 50 :veloc 50})
    (let [evoluido (first (treinador/equipe cid "a"))]
      (treinador/remover-pokemon! cid "a" 0)
      (treinador/receber-doacao! cid "b" evoluido))
    (is (= #{"pikachu" "raichu"} (set (keys (treinador/colecao-shiny! cid "a" [])))))
    (is (= #{"raichu"} (set (keys (treinador/colecao-shiny! cid "b" [])))))))

(deftest comandos-publicos
  (async done
    (let [cid (str (random-uuid))
          msg (fn [pid] #js {:from cid :author pid :getContact (fn [] (p/resolved #js {:pushname pid}))})
          r (assoc registro "tipos" ["normal"] "veloc" 50 "xp" 0 "raridade" "comum")]
      (treinador/receber-doacao! cid "a" (assoc r "shiny" true))
      (treinador/receber-doacao! cid "a" (assoc r "nome" "Eevee"))
      (treinador/receber-doacao! cid "b" r)
      (-> (p/let [filtrado (pokemon/jogar (msg "a") "time txt shiny >")
                  dex (pokemon/jogar (msg "a") "pokedex shiny")
                  semanal (pokemon/jogar (msg "a") "missoes semanais")
                  ranking (pokemon/jogar (msg "a") "ginasio ranking pedra")
                  abriu (pokemon/jogar (msg "a") "raid abrir iniciante")
                  entrou-a (pokemon/jogar (msg "a") "raid entrar 1")
                  entrou-b (pokemon/jogar (msg "b") "raid entrar 1")
                  inicio (pokemon/jogar (msg "a") "raid iniciar")
                  respostas (reduce (fn [anterior pid]
                                      (p/let [acc anterior
                                              resposta (pokemon/jogar (msg pid) "raid atacar 1")]
                                        (conj acc resposta)))
                                    (p/resolved []) (take 10 (cycle ["a" "b"])))]
            (is (re-find #"Pikachu" filtrado))
            (is (not (re-find #"Eevee" filtrado)))
            (is (re-find #"1 espécies" dex))
            (is (re-find #"Missões semanais" semanal))
            (is (re-find #"Ranking" ranking))
            (is (re-find #"Inscrições abertas" abriu))
            (is (re-find #"entrou" entrou-a))
            (is (re-find #"entrou" entrou-b))
            (is (re-find #"iniciada" inicio))
            (is (some #(re-find #"O grupo venceu" %) respostas))
            (is (= 40 (loja/moedas cid "a"))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))
