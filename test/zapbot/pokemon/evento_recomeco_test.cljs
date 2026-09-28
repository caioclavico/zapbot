(ns zapbot.pokemon.evento-recomeco-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.aventuras :as aventuras]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]))

(deftest novo-recomeco-dura-sete-dias-sem-renovar-no-reinicio
  (let [{:keys [inicio fim]} aventuras/novo-recomeco]
    (is (= (* 7 24 60 60 1000) (- fim inicio)))
    (is (nil? (aventuras/evento-recomeco (dec inicio))))
    (is (= "Novo Recomeço" (:nome (aventuras/evento-recomeco inicio))))
    (is (some? (aventuras/evento-recomeco (dec fim))))
    (is (nil? (aventuras/evento-recomeco fim)))))

(deftest bonus-antigo-nao-altera-capacidade-nem-compras
  (with-redefs [loja/contas (atom {"chat" {"ash" {"expansoes-pc" 2 "moedas" 200
                                                "missoes-eventos" {"colecao-2026-09"
                                                                   {"selvagens" 3 "pvp" 3 "ginasios" 3 "professor" 3}}}}})
                armazenamento/salvar! (fn [& _] nil)]
    (is (= 126 (loja/capacidade-pokemon "chat" "ash")))
    (is (= 176 (:capacidade (loja/comprar-espaco-pc! "chat" "ash"))))
    (is (= 26 (loja/capacidade-pokemon "chat" "misty")))))

(deftest recompensas-unicas-isoladas-persistidas-e-pendentes
  (let [estado (atom {"chat" {"ash" {"capacidade-mochila" 1}}})
        salvo (atom nil)
        evento (atom aventuras/novo-recomeco)]
    (with-redefs [loja/contas estado
                  aventuras/evento-recomeco (fn [_] @evento)
                  armazenamento/salvar! (fn [_ dados] (reset! salvo dados))]
      (dotimes [_ 3] (loja/registrar-missao! "chat" "ash" "selvagens" 1))
      (is (= 1 (loja/quantidade-item "chat" "ash" "pokebola")))
      (is (= 9 (get-in @estado ["chat" "ash" "recompensas-pendentes" "pokebola"])))
      (is (= #{"selvagens"} (:premiados (loja/progresso-evento-recomeco "chat" "ash"))))
      (reset! estado (loja/migrar-chaves-antigas
                     (js->clj (js/JSON.parse (js/JSON.stringify (clj->js @salvo))))))
      (let [antes @estado]
        (is (nil? (loja/registrar-evento-recomeco! "chat" "ash" "selvagens")))
        (is (= antes @estado)))
      (dotimes [_ 3] (loja/registrar-missao! "chat" "ash" "capturas" 1))
      (is (= 5 (get-in @estado ["chat" "ash" "recompensas-pendentes" "grande-bola"])))
      (is (= 2 (get-in @estado ["chat" "ash" "recompensas-pendentes" "reviver"])))
      (is (= {} (:progresso (loja/progresso-evento-recomeco "outro" "ash"))))
      (is (= {} (:progresso (loja/progresso-evento-recomeco "chat" "misty"))))
      (is (= 26 (loja/capacidade-pokemon "chat" "ash")))
      (reset! evento nil)
      (let [antes @estado]
        (is (nil? (loja/registrar-evento-recomeco! "chat" "misty" "capturas")))
        (is (= antes @estado)))
      ;; As recompensas já conquistadas sobrevivem ao encerramento do evento.
      (swap! estado assoc-in ["chat" "ash" "capacidade-mochila"] 50)
      (loja/resgatar-bolas! "chat" "ash" false)
      (is (= 10 (loja/quantidade-item "chat" "ash" "pokebola")))
      (is (= 5 (loja/quantidade-item "chat" "ash" "grande-bola")))
      (is (= 2 (loja/quantidade-item "chat" "ash" "reviver"))))))

(deftest vitoria-selvagem-atualiza-menu-do-evento
  (with-redefs [loja/contas (atom {})
                aventuras/evento-recomeco (fn [_] aventuras/novo-recomeco)
                armazenamento/salvar! (fn [& _] nil)
                core/cacadas-selvagens (atom {})
                core/cabe-pokemon? (fn [& _] true)
                core/menu-captura (fn [& _] "Capturar")]
    (let [mensagem #js {:from "chat" :author "ash"}]
      (is (str/includes? (core/ver-evento mensagem) "Vencer selvagens: 0/3"))
      (core/preparar-captura-pos-batalha "chat" "ash" {:pokemons {:o {:raridade "comum"}}})
      (is (str/includes? (core/ver-evento mensagem) "Vencer selvagens: 1/3"))
      (is (not (str/includes? (core/ver-evento mensagem) "300"))))))

(deftest bolsa-cheia-preserva-xp-e-conta-vitoria-no-evento
  (let [xp (atom nil) cacas (atom {})]
    (with-redefs [loja/contas (atom {}) core/cacadas-selvagens cacas
                  aventuras/evento-recomeco (fn [_] aventuras/novo-recomeco)
                  armazenamento/salvar! (fn [& _] nil)
                  core/cabe-pokemon? (fn [& _] true)
                  core/menu-captura (fn [& _] (throw (js/Error. "Não deve oferecer captura")))
                  treinador/ganhar-xp-no-indice! (fn [cid pid idx qtd] (reset! xp [cid pid idx qtd]) nil)
                  treinador/quebrar-sequencia-capturas! (fn [& _] nil)]
      (let [texto (core/preparar-captura-pos-batalha "chat" "ash"
                    {:somente-batalha? true :indice-pokemon 2
                     :pokemons {:x {:nome "Pikachu"} :o {:raridade "comum"}}})]
        (is (str/includes? texto "XP para Pikachu"))
        (is (= ["chat" "ash" 2 3] @xp))
        (is (= 1 (get-in (loja/progresso-evento-recomeco "chat" "ash") [:progresso "selvagens"])))))))
