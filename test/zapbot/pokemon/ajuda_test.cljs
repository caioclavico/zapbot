(ns zapbot.pokemon.ajuda-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [zapbot.pokemon.ajuda :as ajuda]))

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
