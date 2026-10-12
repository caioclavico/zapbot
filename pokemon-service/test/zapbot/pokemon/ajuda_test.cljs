(ns zapbot.pokemon.ajuda-test
  (:require [pokemon-service.regression-fixture :as fixture]
            [cljs.test :refer-macros [use-fixtures deftest is]]
            [clojure.string :as str]
            [zapbot.pokemon.ajuda :as ajuda]))

(use-fixtures :once {:before fixture/iniciar! :after fixture/parar!})

(deftest ajuda-de-ataque-aceita-comando-e-atalho
  (let [texto (ajuda/resposta "atacar ajuda")]
    (is (str/includes? texto "Como usar: atacar"))
    (is (= texto (ajuda/resposta "atk ajuda") (ajuda/resposta "ajuda ataque")))
    (is (nil? (ajuda/resposta "atacar 1")))))

(deftest ajd-exibe-todos-os-atalhos-pokemon
  (let [texto (ajuda/resposta "ajd")]
    (is (str/includes? texto "Comandos Pokémon abreviados"))
    (doseq [atalho ["ajd" "atk" "def" "cur" "pot" "pmax" "sai"
                    "cac" "cap" "ini" "dex" "pdx" "tm" "mch" "fav" "tre"
                    "gin" "lig" "evt" "des" "dsf" "fru" "ran" "hist"
                    "abr" "ent" "sal" "usa" "exc" "apa" "rm"
                    "evo" "neg" "doa" "eqp" "esc" "rmg" "apr" "reap"
                    "rev" "can" "mis" "pre" "res"]]
      (is (str/includes? texto (str "`" atalho "`")) atalho)))
  (is (= (ajuda/resposta "ajd") (ajuda/resposta "ajuda atalhos"))))

(deftest guias-da-navegacao-e-loja-sao-consultaveis-sem-acao
  (doseq [tema ["menu" "loja" "mochila" "treinador" "ovos" "ataque" "batalhas"
                "ginasios" "cacadas" "captura" "time" "professor" "espaco"
                "shiny" "semanais" "raide" "evolucao" "atalhos"]]
    (let [texto (ajuda/resposta (str "ajuda " tema))]
      (is (not (str/includes? texto "Não encontrei")))
      (is (= texto (ajuda/resposta (str tema " ajuda"))))))
  (is (str/includes? (ajuda/resposta "pvp ajuda") "pk batalhar"))
  (is (str/includes? (ajuda/resposta "loja ajuda") "não são vendidas"))
  (is (str/includes? (ajuda/resposta "captura ajuda") "pk 3"))
  (is (nil? (ajuda/resposta "atacar 1"))))
