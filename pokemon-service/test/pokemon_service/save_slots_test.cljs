(ns pokemon-service.save-slots-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pokemon-service.save-slots :as slots]))

(defn account-json [id]
  (js/JSON.stringify #js {:ativo 0 :equipe #js [#js {"id-pokemon" id :nivel 1}]}))

(deftest origem-v1-selecionada-pelo-jogador-exato
  (let [documento #js {:p1 (js/JSON.parse (account-json "p1-save"))
                       :p2 (js/JSON.parse (account-json "p2-save"))}
        dados #js {:particionado #js [(js/JSON.stringify documento)]
                   :antigo #js []}
        selecionado (slots/selecionar-origem dados "grupo" "p2" "1")]
    (is (= "p2-save" (aget (aget selecionado "equipe") 0 "id-pokemon")))
    (is (not= "p1-save" (aget (aget selecionado "equipe") 0 "id-pokemon")))))

(deftest origem-v2-exige-grupo-e-jogador-exatos
  (let [correto #js {:grupo #js {:p (js/JSON.parse (account-json "g-p"))}
                     :outro #js {:p (js/JSON.parse (account-json "other-p"))}}
        dados #js {:particionado #js [] :antigo #js [(js/JSON.stringify correto)]}
        selecionado (slots/selecionar-origem dados "grupo" "p" "2")]
    (is (= "g-p" (aget (aget selecionado "equipe") 0 "id-pokemon")))
    (is (try (slots/selecionar-origem dados "ausente" "p" "2") false
             (catch :default erro (= "source_unavailable" (.-message erro)))))))

(deftest origem-ambigua-ou-versao-nao-suportada-falha-fechada
  (let [json (account-json "legacy")]
    (is (try (slots/selecionar-origem #js {:particionado #js [json json] :antigo #js []}
                                      "g" "p" "1") false
             (catch :default erro (= "source_unavailable" (.-message erro)))))
    (is (try (slots/selecionar-origem #js {:particionado #js [] :antigo #js []}
                                      "g" "p" "3") false
             (catch :default erro (= "source_unavailable" (.-message erro)))))))
