(ns zapbot.pokemon.raids-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.raids :as raids]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.treinador :as treinador]
            ["sharp" :as sharp]))

(def pokemon {"id-pokemon" "pk" "nome" "Lapras" "nivel" 1 "hp" 100 "hp-atual" 100
              "ataque" 100 "defesa" 100 "atq-esp" 100 "def-esp" 100 "veloc" 50
              "tipos" ["agua"] "raridade" "raro"
              "golpes" [{"classe" "fisico" "poder" 100 "slug" "tackle" "nome-exibicao" "Investida"}]})

(deftest chefes-e-chances-respeitam-nivel
  (is (= "raid" (core/expandir-atalho "raide")))
  (is (= "raid" (core/expandir-atalho "raid")))
  (is (every? #(= "raro" (second %)) (raids/candidatos 10)))
  (is (some #(= "lendario" (second %)) (raids/candidatos 30)))
  (is (some #(= "mitico" (second %)) (raids/candidatos 50)))
  (is (< (raids/chance-captura "mitico") (raids/chance-captura "lendario") (raids/chance-captura "raro"))))

(deftest aparicao-preserva-defensores-e-agenda-apos-reinicio
  (let [estado (atom {}) agenda (atom {})
        defensores (atom {"chat" {"pedra" {"pid" "lider" "time" [pokemon pokemon pokemon]}}})
        original @defensores]
    (with-redefs [raids/raids estado raids/agendas agenda ginasios/ocupacoes defensores
                  armazenamento/salvar! (fn [& _] nil)]
      (raids/acompanhar! "chat" 1000)
      (raids/acompanhar! "chat" 2000)
      (is (= (+ 1000 raids/intervalo) (get-in @agenda ["chat" "proxima"])))
      (let [r (raids/criar! "chat" {:id "pedra" :nome "Pedra" :nivel 10} pokemon 2000)]
        (is (raids/no-ginasio? "chat" "pedra" 2000))
        (is (not (raids/no-ginasio? "chat" "agua" 2000)))
        (is (nil? (raids/criar! "chat" {:id "agua"} pokemon 2001)))
        (is (not (raids/no-ginasio? "chat" "pedra" (get r "expira"))))
        (is (= original @defensores))))))

(deftest vitoria-premia-uma-vez-e-oferece-captura-aos-participantes-ativos
  (let [estado (atom {}) contas (atom {}) xp (atom [])]
    (with-redefs [raids/raids estado raids/agendas (atom {}) loja/contas contas
                  armazenamento/salvar! (fn [& _] nil)
                  treinador/premiar-progresso-raid! (fn [_ pid id _] (swap! xp conj [pid id]) {:xp 6 :pe 6})]
      (raids/criar! "chat" {:id "pedra" :nivel 10} pokemon 0)
      (doseq [pid ["ash" "misty" "brock"]]
        (raids/comando! "chat" pid pid ["entrar"] pokemon "iniciante" 1))
      (raids/comando! "chat" "misty" "Misty" ["iniciar"] nil nil 2)
      (swap! estado assoc-in ["chat" "hp-chefe"] 1)
      (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil 3)
      (is (= 50 (get-in @contas ["chat" "ash" "moedas"])))
      (is (= 1 (count @xp)))
      (is (some? (raids/captura-pendente "chat" "ash" 4)))
      (is (nil? (raids/captura-pendente "chat" "misty" 4)))
      (is (not (raids/no-ginasio? "chat" "pedra" 4)))
      (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil 4)
      (is (= 1 (count @xp)))
      (is (= 50 (get-in @contas ["chat" "ash" "moedas"])))
      (dotimes [_ 3] (raids/registrar-tentativa! "chat" "ash" false))
      (is (nil? (raids/captura-pendente "chat" "ash" 5))))))

(deftest captura-nao-consome-bola-sem-espaco-e-entrega-especie-normal
  (let [estado (atom {"chat" {"fase" "vitoria" "chefe" pokemon "captura-expira" js/Number.MAX_SAFE_INTEGER
                             "participantes" {"ash" {"dano" 1}}}})
        vaga (atom false) bolas (atom 0) recebido (atom nil)]
    (with-redefs [raids/raids estado armazenamento/salvar! (fn [& _] nil)
                  core/aprendizado-bloqueado? (fn [& _] false)
                  core/cabe-pokemon? (fn [& _] @vaga)
                  loja/consumir-bola! (fn [& _] (swap! bolas inc) true)
                  treinador/adicionar-pokemon! (fn [_ _ pk _ _] (reset! recebido pk) {:indice 0})
                  treinador/registrar-captura! (fn ([_ _ _] nil) ([_ _ _ _] nil))
                  cljs.core/rand-int (fn [_] 0)]
      (is (str/includes? (core/capturar-raide #js {:from "chat" :author "ash"} ["pokebola"]) "cheio"))
      (is (= 0 @bolas))
      (reset! vaga true)
      (is (str/includes? (core/capturar-raide #js {:from "chat" :author "ash"} ["pokebola"]) "Nível 1"))
      (is (= 1 @bolas))
      (is (= 1 (:nivel @recebido)))
      (is (= 100 (:hp @recebido)))
      (is (nil? (:id-pokemon @recebido)))
      (is (nil? (raids/captura-pendente "chat" "ash" (.now js/Date)))))))

(deftest derrota-expiracao-e-inscricoes-nao-liberam-captura
  (doseq [fase ["inscricoes" "combate" "derrota" "cancelada"]]
    (with-redefs [raids/raids (atom {"chat" {"fase" fase "captura-expira" 10000
                                           "participantes" {"ash" {"dano" 500}}}})]
      (is (nil? (raids/captura-pendente "chat" "ash" 1)))))
  (with-redefs [raids/raids (atom {"chat" {"fase" "vitoria" "captura-expira" 10000
                                         "participantes" {"ash" {"dano" 500}}}})]
    (is (some? (raids/captura-pendente "chat" "ash" 9999)))
    (is (nil? (raids/captura-pendente "chat" "ash" 10000)))))

(deftest moldura-de-ginasio-renderiza-como-png
  (async done
    (-> (sharp (js/Buffer.from (core/svg-cartao-evento :raid "HP do chefe: 220/440")))
        (.png)
        (.toFile "/tmp/zapbot-raide-moldura.png")
        (.then (fn [info]
                 (is (= 760 (.-width info)))
                 (is (= 400 (.-height info)))
                 (done)))
        (.catch (fn [erro] (is false (str erro)) (done))))))

(deftest rodizio-persistido-passa-por-todos-sem-sobrepor
  (let [estado (atom {}) agenda (atom {})]
    (with-redefs [raids/raids estado raids/agendas agenda armazenamento/salvar! (fn [& _] nil)]
      (doseq [[i esperado] (map-indexed vector ["pedra" "agua" "eletrico" "planta" "fogo" "pedra"])]
        (let [g (raids/proximo-ginasio "chat") agora (* i raids/intervalo)]
          (is (= esperado (:id g)))
          (raids/criar! "chat" g pokemon agora)
          (let [salvo @agenda]
            (is (nil? (raids/criar! "chat" (raids/proximo-ginasio "chat") pokemon (inc agora))))
            (is (= salvo @agenda))
            ;; Simula recarga do formato JSON persistido.
            (reset! agenda (js->clj (js/JSON.parse (js/JSON.stringify (clj->js salvo))))))))
      (is (= "pedra" (:id (raids/proximo-ginasio "outro-grupo")))))))

(deftest escalacao-automatica-ordena-aptos-e-filtra-a-liga
  (with-redefs [treinador/equipe
                (fn [& _] [(assoc pokemon "nome" "Fraco" "ataque" 10)
                           (assoc pokemon "nome" "Forte" "ataque" 200)
                           (assoc pokemon "nome" "Desmaiado" "hp-atual" 0 "ataque" 999)
                           (assoc pokemon "nome" "Fora da liga" "nivel" 100 "ataque" 500)])]
    (is (= [3 1 0] (mapv :indice (core/candidatos-escalacao "chat" "ash" nil))))
    (is (= [1 0] (mapv :indice (core/candidatos-escalacao "chat" "ash" (treinador/obter-liga "iniciante")))))))

(deftest gin-time-sem-numeros-salva-e-mostra-os-tres-mais-fortes
  (let [salvo (atom nil) fotos (atom nil)]
    (with-redefs [treinador/equipe (fn [& _] (mapv #(assoc pokemon "nome" (str %) "ataque" %) [10 40 20 30]))
                  core/aprendizado-bloqueado? (fn [& _] false)
                  raids/acompanhar! (fn [& _] nil)
                  treinador/salvar-time-ginasio! (fn [_ _ indices] (reset! salvo indices))
                  core/resposta-time-ginasio (fn [pokemons texto] (reset! fotos pokemons) texto)]
      (core/configurar-ginasio #js {:from "chat" :author "ash"} ["time"])
      (is (= [1 3 2] @salvo))
      (is (= ["40" "30" "20"] (mapv :nome @fotos))))))

(deftest desafiar-ginasio-com-raide-inscreve-sem-outro-comando
  (with-redefs [raids/acompanhar! (fn [& _] nil)
                raids/no-ginasio? (fn [_ id _] (= "pedra" id))
                core/comando-raid (fn [_ args] args)]
    (is (= ["entrar" "auto"] (core/configurar-ginasio #js {:from "chat" :author "ash"} ["desafiar" "pedra"])))
    (is (= ["atacar" "2"] (core/configurar-ginasio #js {:from "chat" :author "ash"} ["atk" "2"])))))
