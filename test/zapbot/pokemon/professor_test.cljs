(ns zapbot.pokemon.professor-test
  (:require [cljs.test :refer-macros [deftest is async testing]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.raids :as raids]
            [zapbot.pokemon.ajuda :as ajuda]))

(defn registro [id]
  {"id-pokemon" id "nome" "Pidgey" "nivel" 5 "xp-desde-nivel" 6
   "hp" 80 "hp-atual" 40 "status" "queimado" "shiny" true
   "ataque" 60 "defesa" 60 "atq-esp" 60 "def-esp" 60 "veloc" 60
   "tipos" ["normal" "flying"] "golpes" [] "amizade" 70})

(def cadeia
  {:chain {:species {:name "pidgey"}
           :evolves_to [{:species {:name "pidgeotto"}
                        :evolves_to [{:species {:name "pidgeot"}}]}]}})

(defn sem-gravacao [& _] (p/resolved nil))

(deftest familia-compartilhada-entre-estagios
  (doseq [nome ["pidgey" "pidgeotto" "pidgeot"]]
    (is (= "pidgey" (core/familia-da-cadeia cadeia nome))))
  (is (nil? (core/familia-da-cadeia cadeia "pikachu")))
  (is (nil? (core/familia-da-cadeia nil "pidgey")))
  (is (str/includes? (ajuda/resposta "professor ajuda") "+3 XP")))

(deftest transferencia-so-remove-ao-confirmar-e-premia-uma-vez
  (let [r (registro "a") outro (registro "b")
        estado (atom {"chat" {"ash" {"equipe" [r outro] "ativo" 1
                                     "time-ginasio" [0 1 nil]
                                     "times-liga" {"iniciante" [0 1 nil]}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (let [pedido (treinador/preparar-transferencia-professor! "chat" "ash" r "pidgey" 100)
            token (get pedido "token")]
        (is (= [r outro] (treinador/equipe "chat" "ash")))
        (is (empty? (treinador/cartoes-professor "chat" "ash")))
        (is (= :invalida (:status (treinador/confirmar-transferencia-professor! "chat" "misty" token 101))))
        (is (= :invalida (:status (treinador/confirmar-transferencia-professor! "outro-chat" "ash" token 101))))
        (is (= :invalida (:status (treinador/confirmar-transferencia-professor! "chat" "ash" "errado" 101))))
        ;; A confirmação sobrevive a um reload, sem guardar mapas com chaves keyword.
        (reset! estado (treinador/normalizar-xp-contas (js->clj (js/JSON.parse (js/JSON.stringify (clj->js @estado))))))
        (is (= :ok (:status (treinador/confirmar-transferencia-professor! "chat" "ash" token 101))))
        (is (= [outro] (treinador/equipe "chat" "ash")))
        (is (= 0 (treinador/indice-ativo "chat" "ash")))
        (is (= [nil 0 nil] (treinador/time-ginasio "chat" "ash")))
        (is (= [nil 0 nil] (treinador/time-liga "chat" "ash" "iniciante")))
        (is (= {"pidgey" 1} (treinador/cartoes-professor "chat" "ash")))
        (is (get-in @estado ["chat" "ash" "shiny-colecao" "pidgey"]))
        (is (= :invalida (:status (treinador/confirmar-transferencia-professor! "chat" "ash" token 102))))
        (is (= {"pidgey" 1} (treinador/cartoes-professor "chat" "ash")))))))

(deftest transferencia-invalida-se-expirou-cancelou-ou-pokemon-mudou
  (doseq [motivo [:expirou :cancelou :mudou :favorito :saiu]]
    (let [r (registro "a") estado (atom {"chat" {"ash" {"equipe" [r]}}})]
      (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
        (let [pedido (treinador/preparar-transferencia-professor! "chat" "ash" r "pidgey" 0)]
          (case motivo
            :cancelou (treinador/cancelar-transferencia-professor! "chat" "ash")
            :mudou (swap! estado assoc-in ["chat" "ash" "equipe" 0 "xp-desde-nivel"] 7)
            :favorito (swap! estado assoc-in ["chat" "ash" "favorito"] {"id" "a"})
            :saiu (swap! estado assoc-in ["chat" "ash" "equipe"] [])
            nil)
          (let [antes @estado]
            (is (= :invalida (:status (treinador/confirmar-transferencia-professor!
                                      "chat" "ash" (get pedido "token") (if (= motivo :expirou) 300000 1)))) motivo)
            (is (= antes @estado) motivo)
            (is (empty? (treinador/cartoes-professor "chat" "ash")))))))))

(deftest cartao-concede-xp-preservando-identidade-e-regras-de-nivel
  (let [r (registro "a")
        estado (atom {"chat" {"ash" {"equipe" [r] "cartoes-professor" {"pidgey" 2}
                                      "times-liga" {"iniciante" [0 nil nil]}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (= :sem-cartoes (:status (treinador/usar-cartao-professor! "chat" "ash" 0 r "pikachu"))))
      (is (= {:status :ok :nome "Pidgey" :nivel 6 :subiu? true}
             (treinador/usar-cartao-professor! "chat" "ash" 0 r "pidgey")))
      (let [novo (first (treinador/equipe "chat" "ash"))]
        (is (= 0 (get novo "xp-desde-nivel")))
        (is (= 82 (get novo "hp")))
        (is (= 42 (get novo "hp-atual")))
        (is (= 80 (get novo "amizade")))
        (is (= (select-keys r ["id-pokemon" "shiny" "status"])
               (select-keys novo ["id-pokemon" "shiny" "status"])))
        (is (= :alterado (:status (treinador/usar-cartao-professor! "chat" "ash" 0 r "pidgey"))))
        (is (= {"pidgey" 1} (treinador/cartoes-professor "chat" "ash")))))))

(deftest cartao-respeita-teto-desmaio-e-faixa-da-liga
  (doseq [nivel [10 100]]
    (let [r (assoc (registro "a") "nivel" nivel "hp-atual" 0)
          estado (atom {"chat" {"ash" {"equipe" [r] "cartoes-professor" {"pidgey" 1}
                                        "times-liga" {"iniciante" [0 nil nil]}}}})]
      (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
        (let [antes @estado resultado (treinador/usar-cartao-professor! "chat" "ash" 0 r "pidgey")]
          (if (= nivel 100)
            (do (is (= :nivel-maximo (:status resultado))) (is (= antes @estado)))
            (do (is (= 11 (:nivel resultado)))
                (is (= 0 (get-in @estado ["chat" "ash" "equipe" 0 "hp-atual"])))
                (is (= [nil nil nil] (treinador/time-liga "chat" "ash" "iniciante"))))))))))

(deftest professor-bloqueia-batalha-cacada-raid-e-evolucao
  (with-redefs [core/jogos (atom {"chat" {:jogadores {:x "ash"}}})
                core/cacadas-selvagens (atom {"caca" {:pid "ash"}})
                core/evolucoes-pendentes (atom #{["evolucao" "ash"]})
                core/remocoes-pendentes (atom {})
                raids/participando? (fn [cid _] (= cid "raid"))]
    (doseq [cid ["chat" "caca" "evolucao" "raid"]]
      (is (core/professor-bloqueado? cid "ash") cid))
    (is (not (core/professor-bloqueado? "livre" "ash")))))

(deftest favorito-nao-gera-pedido-de-transferencia
  (let [r (registro "a") estado (atom {"chat" {"ash" {"equipe" [r] "favorito" {"id" "a"}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (nil? (treinador/preparar-transferencia-professor! "chat" "ash" r "pidgey" 0)))
      (is (nil? (treinador/transferencia-professor "chat" "ash"))))))

(deftest comando-professor-exige-codigo-e-devolve-item-uma-vez
  (async done
    (let [cid "teste-professor-comando" r (assoc (registro "a") "item" "restos")
          buscar-original core/buscar-cadeia-evolucao
          message #js {:from cid :author "ash"}]
      (swap! treinador/contas assoc cid {"ash" {"equipe" [r] "colecao-unificada" true}})
      (set! core/buscar-cadeia-evolucao (fn [_] (p/resolved cadeia)))
      (-> (core/comando-professor message ["enviar" "1"])
          (p/then (fn [texto]
                    (is (str/includes? texto "transferência é definitiva"))
                    (is (str/includes? texto "SHINY"))
                    (is (= [r] (treinador/equipe cid "ash")))
                    (let [token (get (treinador/transferencia-professor cid "ash") "token")]
                      (is (str/includes? texto token))
                      (core/comando-professor message ["confirmar" token]))))
          (p/then (fn [texto]
                    (is (str/includes? texto "+1 cartão"))
                    (is (empty? (treinador/equipe cid "ash")))
                    (is (= 1 (loja/quantidade-item cid "ash" "restos")))
                    (is (false? (treinador/inicial-disponivel? cid "ash")))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! core/buscar-cadeia-evolucao buscar-original)
                       (swap! treinador/contas dissoc cid)
                       (swap! loja/contas dissoc cid)
                       (done)))))))

(deftest comando-cartao-processa-subida-e-libera-bloqueio
  (async done
    (let [cid "teste-professor-xp" r (registro "a")
          message #js {:from cid :author "ash"}
          buscar-original core/buscar-cadeia-evolucao
          evoluir-original core/verificar-evolucao!
          aprender-original core/aprender-golpe-por-nivel!
          chamadas (atom [])]
      (swap! treinador/contas assoc cid {"ash" {"equipe" [r] "colecao-unificada" true
                                               "cartoes-professor" {"pidgey" 2}}})
      (set! core/buscar-cadeia-evolucao (fn [_] (p/resolved cadeia)))
      (set! core/verificar-evolucao!
            (fn ([_ _ _] (p/resolved nil))
                ([_ _ _ idx] (swap! chamadas conj [:evolucao idx]) (p/resolved nil))))
      (set! core/aprender-golpe-por-nivel!
            (fn ([_ _ _ _] (p/resolved nil))
                ([_ _ _ nivel idx] (swap! chamadas conj [:golpe idx nivel]) (p/resolved nil))))
      (-> (core/jogar-comando message "professor usar 1")
          (p/then (fn [texto]
                    (is (str/includes? texto "+3 XP"))
                    (is (str/includes? texto "nível 6"))
                    (is (= [[:evolucao 0] [:golpe 0 6]] @chamadas))
                    (is (not (contains? @core/evolucoes-pendentes [cid "ash"])))
                    (core/jogar-comando message "professor usar 1")))
          (p/then (fn [texto]
                    (is (str/includes? texto "XP 3/9"))
                    (is (= 2 (count @chamadas)))
                    (core/jogar-comando message "professor usar 1")))
          (p/then (fn [texto]
                    (is (str/includes? texto "não tem cartões"))
                    (is (= 0 (get (treinador/cartoes-professor cid "ash") "pidgey")))
                    (set! core/buscar-cadeia-evolucao (fn [_] (p/resolved nil)))
                    (core/jogar-comando message "professor enviar 1")))
          (p/then (fn [texto]
                    (is (str/includes? texto "nada foi consumido"))
                    (is (= 1 (count (treinador/equipe cid "ash"))))
                    (is (nil? (treinador/transferencia-professor cid "ash")))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! core/buscar-cadeia-evolucao buscar-original)
                       (set! core/verificar-evolucao! evoluir-original)
                       (set! core/aprender-golpe-por-nivel! aprender-original)
                       (swap! treinador/contas dissoc cid)
                       (done)))))))
