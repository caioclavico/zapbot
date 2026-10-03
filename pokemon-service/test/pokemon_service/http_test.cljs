(ns pokemon-service.http-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [zapbot.http :as http]))

(deftest http-externo-cancela-espera-e-nao-repete-requisicao
  (async done
    (let [original js/fetch
          chamadas (atom 0)
          manter-loop (js/setTimeout (fn []) 500)]
      (set! js/fetch
            (fn [_ opcoes]
              (swap! chamadas inc)
              (js/Promise.
               (fn [_ rejeitar]
                 (.addEventListener (.-signal opcoes) "abort"
                                    #(rejeitar (.-reason (.-signal opcoes))) #js {:once true})))))
      (-> (with-redefs [http/timeout-ms 10] (http/fetch! "https://pokeapi.co/teste"))
          (p/then (fn [_] (is false "Uma requisição presa não pode resolver com sucesso.")))
          (p/catch (fn [erro]
                     (is (= "TimeoutError" (.-name erro)))
                     (is (= 1 @chamadas))))
          (p/finally (fn [] (set! js/fetch original) (js/clearTimeout manter-loop) (done)))))))

(deftest http-externo-preserva-status-body-e-sinal-do-chamador
  (async done
    (let [original js/fetch
          signal (atom nil)
          controller (js/AbortController.)]
      (set! js/fetch
            (fn [_ opcoes]
              (reset! signal (.-signal opcoes))
              (p/resolved #js {:ok false :status 404 :json (fn [] (p/resolved #js {:erro "ausente"}))})))
      (-> (p/let [res (http/fetch! "https://pokeapi.co/teste" #js {:signal (.-signal controller)})
                  corpo (.json res)]
            (is (= 404 (.-status res)))
            (is (= "ausente" (.-erro corpo)))
            (.abort controller)
            (is (true? (.-aborted @signal))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (set! js/fetch original) (done)))))))
