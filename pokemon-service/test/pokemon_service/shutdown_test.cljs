(ns pokemon-service.shutdown-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.pokemon.core :as pokemon]
            [zapbot.armazenamento :as storage]))

(deftest encerramento-espera-gravacao-e-propaga-falha-apos-fechar-driver
  (async done
    (let [destinos [storage/client storage/pronto storage/filas-gravacao storage/falhas-gravacao]
          antes (mapv deref destinos)
          logs (atom [])
          observador (js/require "../runtime/shutdown.cjs")
          fecharam (atom 0)
          liberar (atom nil)
          erro (js/Error. "gravação não confirmada")
          pendente (p/create (fn [resolve _] (reset! liberar resolve)))]
      (reset! storage/client #js {:shutdown (fn [] (swap! fecharam inc) (p/resolved nil))})
      (reset! storage/pronto true)
      (reset! storage/filas-gravacao {"diagnostico" pendente})
      (reset! storage/falhas-gravacao {"diagnostico" erro})
      (let [resultado (.observe observador #(storage/encerrar!)
                                #js {:logger #(swap! logs conj (js->clj (js/JSON.parse %) :keywordize-keys true))})]
        (is (false? @storage/pronto))
        (is (= 0 @fecharam))
        (@liberar nil)
        (-> resultado
            (p/then (fn [_] (is false "Falha de durabilidade não é sucesso.")))
            (p/catch (fn [recebido]
                       (is (identical? erro recebido))
                       (is (= 1 @fecharam))
                       (is (nil? @storage/client))
                       (is (some #(and (= "shutdown_stage_failed" (:event %))
                                       (= "aguardar_todas_BANG_" (:stage %))
                                       (= 1 (get-in % [:pending :persistence_failed_modules]))) @logs))
                       (is (some #(and (= "shutdown_stage_completed" (:event %))
                                       (= "client.shutdown" (:stage %))) @logs))))
            (p/finally (fn []
                         (doseq [[destino valor] (map vector destinos antes)] (reset! destino valor))
                         (done))))))))

(deftest fechamento-espera-driver-assincrono-e-nao-ignora-sua-rejeicao
  (async done
    (let [destinos [storage/client storage/pronto storage/filas-gravacao storage/falhas-gravacao]
          antes (mapv deref destinos) terminou? (atom false)
          iniciado (atom nil) rejeitar (atom nil)
          inicio (js/Promise. (fn [resolve _] (reset! iniciado resolve)))
          driver (js/Promise. (fn [_ reject] (reset! rejeitar reject)))
          erro (js/Error. "driver shutdown failed")]
      (reset! storage/client #js {:shutdown (fn [] (@iniciado nil) driver)})
      (reset! storage/filas-gravacao {})
      (reset! storage/falhas-gravacao {})
      (let [resultado (storage/encerrar!)]
        (.then inicio (fn [_]
                        (is (false? @terminou?))
                        (@rejeitar erro)))
        (-> resultado
            (.then (fn [_] (reset! terminou? true) (is false "Falha do driver não é sucesso."))
                   (fn [recebido] (reset! terminou? true) (is (identical? erro recebido))))
            (.finally (fn []
                        (doseq [[destino valor] (map vector destinos antes)] (reset! destino valor))
                        (done))))))))

(deftest contadores-distinguem-duas-operacoes-na-mesma-fila-sem-mudar-a-ordem
  (async done
    (let [ordem (atom []) liberar (atom nil)
          bloqueada (p/create (fn [resolve _] (reset! liberar resolve)))
          primeira (pokemon/enfileirar-jogada "shutdown-diagnostico"
                                             #(do (swap! ordem conj 1) bloqueada))
          segunda (pokemon/enfileirar-jogada "shutdown-diagnostico"
                                            #(swap! ordem conj 2))]
      (is (= {:game_operations 2 :game_chat_queues 1} (pokemon/filas-pendentes)))
      (@liberar nil)
      (-> (p/all [primeira segunda])
          (p/then (fn [_]
                    (is (= [1 2] @ordem))
                    (is (= {:game_operations 0 :game_chat_queues 0} (pokemon/filas-pendentes)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest encerramento-normal-drena-duas-gravacoes-na-ordem-e-zera-contador
  (async done
    (let [destinos [storage/client storage/pronto storage/modulos storage/cache storage/confirmados
                   storage/filas-gravacao storage/falhas-gravacao storage/gravacoes-pendentes]
          antes (mapv deref destinos)
          liberar (atom nil) chamadas (atom []) fechados (atom 0)
          bloqueada (p/create (fn [resolve _] (reset! liberar resolve)))
          c #js {:execute (fn [_ args _]
                            (swap! chamadas conj (aget args 2))
                            bloqueada)
                 :shutdown (fn [] (swap! fechados inc) (p/resolved nil))}]
      (doseq [[destino valor] (map vector destinos [c true #{"diagnostico"} {} {} {} {} 0])]
        (reset! destino valor))
      (let [primeira (storage/salvar! "diagnostico" {"p" {"xp" 1}})
            segunda (storage/salvar! "diagnostico" {"p" {"xp" 2}})
            fechamento (storage/encerrar!)]
        (is (= 2 (:persistence_pending (storage/pendentes))))
        (is (= 0 @fechados))
        (@liberar nil)
        (-> (p/all [primeira segunda fechamento])
            (p/then (fn [_]
                      (is (= ["{\"xp\":1}" "{\"xp\":2}"] @chamadas))
                      (is (= 1 @fechados))
                      (is (= 0 (:persistence_pending (storage/pendentes))))))
            (p/catch (fn [erro] (is false (str erro))))
            (p/finally (fn []
                         (doseq [[destino valor] (map vector destinos antes)] (reset! destino valor))
                         (done))))))))
