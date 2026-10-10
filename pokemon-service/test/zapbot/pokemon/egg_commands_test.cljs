(ns zapbot.pokemon.egg-commands-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [pokemon-service.adventure-fixture :as fixture]
            [zapbot.pokemon.ovos-test :refer [concluir]]
            [zapbot.pokemon.egg-commands :as commands]
            [zapbot.pokemon.adventure :as adventure]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ovos :as ovos]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.adventure-store :as store]
            [zapbot.config :as config]))

(def ctx {:chat-id "g" :player-id "p@lid" :request-id "msg"})
(defn chamar [cmd args] (commands/comando! ctx cmd args))
(defn pokemon-configurar [] (core/configurar-comando-ovos! commands/comando!))
(defn texto-de [x] (if (map? x) (:texto x) x))

(deftest consultas-vazias-e-cheias
  (is (str/includes? (:texto (commands/inventario {:eggs []})) "0/9"))
  (is (str/includes? (commands/progresso {:eggs []}) "Nenhum"))
  (is (str/includes? (:texto (commands/inventario {:eggs (repeat 9 {:state :inventory :category :comum})})) "9/9")))

(deftest comandos-e-selecao-explicita
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (pokemon-configurar)
        (p/let [ajuda (core/jogar ctx "ovo ajuda")
                lista (core/jogar ctx "ovos")
                _ (adventure/conceder-incubadora! "g" "p@lid" "extra" :extra)
                _ (adventure/conceder-incubadora! "g" "p@lid" "special" :especial)
                incs (chamar "incubadoras" [])
                invalida (chamar "incubar" ["1" "desconhecida"])
                resposta (chamar "incubar" ["1" "especial"])
                progresso (chamar "ovo" [])
                ocupada (chamar "incubadoras" [])
                estado (adventure/atividade-consultar! "g" "p@lid")]
          (is (str/includes? ajuda "100 pontos"))
          (is (str/includes? (texto-de lista) "1. Ovo comum"))
          (is (str/includes? (texto-de incs) "usos ilimitados"))
          (is (str/includes? (texto-de incs) "3 usos"))
          (is (str/includes? invalida "não possui"))
          (is (str/includes? resposta "especial"))
          (is (str/includes? progresso "0/100 pontos"))
          (is (str/includes? (texto-de ocupada) "especial — ocupada"))
          (is (= (:incubator-id (first (:eggs estado)))
                 (:incubator-id (some #(when (= :especial (:kind %)) %) (:incubators estado)))))))) done)))

(deftest incubadora-padrao-ocupada-e-indisponivel
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [_ (chamar "ovos" [])
                resposta (chamar "incubar" ["1" "permanente"])
                invalido (chamar "incubar" ["9"])
                extra (chamar "incubar" ["1" "extra"])
                formato (chamar "incubar" ["0"])]
          (is (str/includes? (texto-de resposta) "permanente"))
          (is (str/includes? invalido "indisponível"))
          (is (str/includes? extra "indisponível"))
          (is (= commands/ajuda formato))))) done)))

(deftest flag-desligada-sem-consultas
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries]}]
        (set! config/adventure-enabled? false)
        (p/let [r (p/all (map #(chamar % []) ["ovos" "ovo" "incubar" "incubadoras"]))]
          (is (every? #(str/includes? % "desativado") r))
          (is (empty? @queries))
          (is (nil? (adventure/processar-vencidos!)))))) done)))

(deftest selecao-padrao-e-ocupacao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [_ (chamar "ovos" [])
                estado (store/resolver! "g" "p@lid" false @now)
                _ (store/alterar! (:account-id estado)
                    #(update % :activity-state ovos/conceder-ovo-atividade
                             (assoc (ovos/novo-ovo-atividade "second" :raro @now) :species "bulbasaur")) @now)
                _ (chamar "incubar" ["1"])
                ocupada (chamar "incubar" ["1" "permanente"])
                todas (chamar "incubar" ["1"])
                _ (adventure/conceder-incubadora! "g" "p@lid" "extra" :extra)
                auto (commands/comando! (assoc ctx :request-id "second") "incubar" ["1"])]
          (is (str/includes? ocupada "ocupada"))
          (is (str/includes? todas "Todas"))
          (is (str/includes? auto "extra"))))) done)))

(deftest handoff-confirmado-outbox-mas-cas-incerto
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (let [envios (atom [])]
          (p/let [_ (chamar "ovos" []) _ (chamar "incubar" ["1"])
                  _ (adventure/atividade-pontos! "g" "p@lid" "finish" 100)
                  _ (reset! adventure/emissor
                       (fn [_ raw]
                         (swap! envios conj (:event-id raw))
                         (reset! fault {:op :state-cas :after? true})
                         (p/resolved nil)))
                  _ (adventure/processar-vencidos!)
                  _ (adventure/preparar-inicializacao!) _ (adventure/inicializar!)
                  _ (adventure/processar-vencidos!)]
            (is (= 1 (count @envios)))
            (is (= 1 (count (treinador/equipe "g" "p@lid")))))))) done)))

(deftest cas-incerto-no-inicio-recupera-sem-consulta
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (let [envios (atom [])]
          (p/let [estado (adventure/atividade-ativar! "g" "p@lid" "welcome")
                  _ (reset! fault {:op :state-cas :after? true
                                   :match #(str/includes? (second %) "incubating")})
                  _ (-> (adventure/atividade-incubar! "g" "p@lid" "start" (:egg-id (first (:eggs estado))))
                        (p/then (fn [_] (is false "CAS deveria retornar resultado incerto")))
                        (p/catch (fn [erro] (is (= :result-unknown (:code (ex-data erro)))))))
                  _ (swap! now + 7200001)
                  _ (reset! adventure/emissor (fn [_ raw] (swap! envios conj raw) (p/resolved nil)))
                  _ (adventure/processar-vencidos!)]
            (is (= 1 (count @envios)))
            (is (= 1 (count (treinador/equipe "g" "p@lid")))))))) done)))

(deftest entrega-cas-incerta-resolvida-pelo-worker
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [fault]}]
        (let [envios (atom [])]
          (p/let [_ (chamar "ovos" []) _ (chamar "incubar" ["1"])
                  _ (reset! fault {:op :trainer-cas :after? true})
                  _ (-> (adventure/atividade-pontos! "g" "p@lid" "finish" 100)
                        (p/then (fn [_] (is false "Entrega deveria falhar na confirmação")))
                        (p/catch (fn [_] nil)))
                  _ (reset! adventure/emissor (fn [_ raw] (swap! envios conj raw) (p/resolved nil)))
                  _ (adventure/processar-vencidos!)]
            (is (= 1 (count @envios)))
            (is (= 1 (count (treinador/equipe "g" "p@lid")))))))) done)))

(deftest notificacao-duravel-tempo-e-reinicio
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now queries]}]
        (let [envios (atom [])]
          (p/let [_ (chamar "ovos" [])
                  _ (chamar "incubar" ["1"])
                  _ (swap! now + 7200001)
                  _ (reset! adventure/emissor (fn [cid raw] (swap! envios conj [cid raw]) (p/resolved nil)))
                  _ (adventure/processar-vencidos!)
                  a (adventure/atividade-consultar! "g" "p@lid")
                  _ (adventure/preparar-inicializacao!)
                  _ (adventure/inicializar!)
                  _ (adventure/processar-vencidos!)]
            (is (= :hatched (:state (first (:eggs a)))))
            (is (= 1 (count @envios)))
            (is (= "g" (ffirst @envios)))
            (is (= ["p@lid"] (:mentions (second (first @envios)))))
            (is (str/includes? (:texto (second (first @envios))) (:species (first (:eggs a)))))
            (is (= 1 (count (treinador/equipe "g" "p@lid"))))
            (is (empty? @adventure/vencimentos)))))) done)))

(deftest pontos-falha-outbox-e-reenvio-com-id-estavel
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (let [envios (atom []) tentativas (atom 0)]
          (p/let [_ (chamar "ovos" [])
                  _ (chamar "incubar" ["1"])
                  _ (adventure/atividade-pontos! "g" "p@lid" "finish" 100)
                  _ (reset! adventure/emissor
                            (fn [_ raw] (swap! envios conj raw)
                              (if (= 1 (swap! tentativas inc)) (p/rejected (js/Error. "outbox indisponível")) (p/resolved nil))))
                  _ (adventure/processar-vencidos!)
                  e (store/resolver! "g" "p@lid" false @now)
                  _ (swap! now + 60001)
                  _ (adventure/processar-vencidos!)
                  final (store/resolver! "g" "p@lid" false @now)]
            (is (= 1 (count (:hatch-notifications e))))
            (is (empty? (:hatch-notifications final)))
            (is (= 2 @tentativas))
            (is (= (:event-id (first @envios)) (:event-id (second @envios))))
            (is (= 1 (count (treinador/equipe "g" "p@lid")))))))) done)))

(deftest colecao-cheia-isolamento-e-ciclo-sem-varredura
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now cap queries]}]
        (let [envios (atom [])]
          (p/let [_ (chamar "ovos" []) _ (chamar "incubar" ["1"])
                  _ (adventure/atividade-ativar! "outro" "p@lid" "welcome")
                  _ (reset! cap 0) _ (swap! now + 7200001)
                  _ (reset! adventure/emissor (fn [cid raw] (swap! envios conj cid) (p/resolved nil)))
                  _ (reset! queries []) _ (adventure/processar-vencidos!)]
            (is (empty? @envios))
            (is (not-any? #(= :state-scan (:op %)) @queries))
            (p/let [_ (reset! cap 50) _ (swap! now + 60001)
                    _ (adventure/processar-vencidos!)
                    _ (adventure/processar-vencidos!)]
              (is (= ["g"] @envios))
              (is (= 1 (count (treinador/equipe "g" "p@lid"))))
              (is (empty? (treinador/equipe "outro" "p@lid")))))))) done)))
