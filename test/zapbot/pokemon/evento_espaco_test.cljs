(ns zapbot.pokemon.evento-espaco-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.aventuras :as aventuras]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.core :as core]))

(deftest janela-fixa-encerra-a-meia-noite-de-sao-paulo
  (let [{:keys [inicio fim]} aventuras/festival-colecao]
    (is (= (js/Date.parse "2026-09-23T21:50:00Z") inicio))
    (is (= (js/Date.parse "2026-10-01T03:00:00Z") fim))
    (is (= "00:00" (.toLocaleTimeString (js/Date. fim) "pt-BR"
                    #js {:timeZone "America/Sao_Paulo" :hour "2-digit" :minute "2-digit" :hourCycle "h23"})))
    (is (nil? (aventuras/evento-espaco (dec inicio))))
    (is (= 300 (:vagas (aventuras/evento-espaco inicio))))
    (is (some? (aventuras/evento-espaco (dec fim))))
    (is (nil? (aventuras/evento-espaco fim)))))

(deftest missao-libera-bonus-isolado-e-preserva-compras-apos-expirar
  (let [estado (atom {"chat" {"ash" {"expansoes-pc" 2 "moedas" 200}}})
        evento (atom aventuras/festival-colecao)]
    (with-redefs [loja/contas estado
                  aventuras/evento-espaco (fn [_] @evento)
                  armazenamento/salvar! (fn [& _] nil)]
      (is (= 126 (loja/capacidade-pokemon "chat" "ash")))
      (loja/registrar-missao! "chat" "ash" "capturas" 5)
      (is (= 0 (get-in (loja/progresso-evento-espaco "chat" "ash") [:progresso "selvagens"] 0)))
      (dotimes [_ 2] (loja/registrar-missao! "chat" "ash" "selvagens" 5))
      (is (= 126 (loja/capacidade-pokemon "chat" "ash")))
      (loja/registrar-missao! "chat" "ash" "selvagens" 5)
      (is (= 126 (loja/capacidade-pokemon "chat" "ash")))
      (doseq [objetivo ["pvp" "ginasios"]]
        (dotimes [_ 3] (loja/registrar-evento-espaco! "chat" "ash" objetivo)))
      (dotimes [_ 2] (loja/registrar-evento-espaco! "chat" "ash" "professor"))
      (is (= 126 (loja/capacidade-pokemon "chat" "ash")))
      (is (str/includes? (loja/registrar-evento-espaco! "chat" "ash" "professor") "300 vagas liberadas"))
      ;; A última conquista já altera a capacidade, sem consultar/resgatar o evento.
      (is (= 426 (loja/capacidade-pokemon "chat" "ash")))
      (is (nil? (loja/registrar-evento-espaco! "chat" "ash" "professor")))
      (is (= 26 (loja/capacidade-pokemon "chat" "misty")))
      (is (= 26 (loja/capacidade-pokemon "outro" "ash")))
      (loja/registrar-missao! "chat" "ash" "selvagens" 5)
      (is (= 3 (get-in (loja/progresso-evento-espaco "chat" "ash") [:progresso "selvagens"] 0)))
      (is (= 476 (:capacidade (loja/comprar-espaco-pc! "chat" "ash"))))
      (reset! evento nil)
      (is (= 176 (loja/capacidade-pokemon "chat" "ash")))
      (is (= 3 (loja/expansoes-pc "chat" "ash"))))))

(deftest vitoria-com-bolsa-cheia-encerra-cacada-ou-oferece-captura-apos-desbloqueio
  (doseq [tem-vaga? [false true]]
    (let [encerradas (atom 0)]
      (with-redefs [core/cacadas-selvagens (atom {})
                    core/cabe-pokemon? (fn [& _] tem-vaga?)
                    loja/registrar-missao! (fn [_ _ tipo _] (is (= "selvagens" tipo)) "Missão registrada")
                    core/menu-captura (fn [& _] "Menu de captura")
                    core/encerrar-cacada! (fn [& _] (swap! encerradas inc) {:xp 1})]
        (let [texto (core/preparar-captura-pos-batalha "chat" "ash" {:pokemons {:o {:raridade "comum"}}})]
          (is (str/includes? texto "Missão registrada"))
          (is (= (if tem-vaga? 0 1) @encerradas))
          (is (str/includes? texto (if tem-vaga? "Menu de captura" "Bolsa cheia"))))))))
