(ns zapbot.traducao-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [zapbot.traducao :as traducao]))

(deftest cooldown-respeita-retry-after-com-limites
  (doseq [[header minimo maximo] [[nil 60000 60000] ["120" 120000 120000]
                                 ["99999" 900000 900000] ["inválido" 60000 60000]]]
    (reset! traducao/google-pausado-ate 0)
    (let [antes (js/Date.now)]
      (traducao/pausar-google! #js {:headers #js {:get (fn [_] header)}})
      (is (<= (+ antes minimo) @traducao/google-pausado-ate (+ (js/Date.now) maximo)))))
  (reset! traducao/google-pausado-ate 0))

(deftest cooldown-usa-gemini-sem-chamar-google
  (async done
    (reset! traducao/google-pausado-ate (+ (js/Date.now) 60000))
    (let [chamadas (atom [])
          resultado (with-redefs [traducao/traduzir-google (fn [& _] (swap! chamadas conj :google))
                                  traducao/traduzir-gemini (fn [& _]
                                                           (swap! chamadas conj :gemini)
                                                           (js/Promise.resolve "traduzido"))]
                      (traducao/traduzir-com-status "hello" "en" "pt"))]
      (-> resultado
          (.then (fn [texto]
                   (is (= "traduzido" texto))
                   (is (= [:gemini] @chamadas))))
          (.catch (fn [err] (is false (str err))))
          (.finally (fn [] (reset! traducao/google-pausado-ate 0) (done)))))))

(deftest resposta-429-ativa-cooldown-e-permite-retomar
  (async done
    (let [fetch-original js/global.fetch]
      (reset! traducao/google-pausado-ate 0)
      (set! js/global.fetch (fn [& _]
                             (js/Promise.resolve
                              #js {:ok false :status 429
                                   :headers #js {:get (fn [_] "120")}})))
      (-> (traducao/traduzir-google "hello" "en" "pt")
          (.then (fn [_] (is false "429 deveria rejeitar para acionar fallback")))
          (.catch (fn [_]
                    (is (> @traducao/google-pausado-ate (js/Date.now)))
                    (reset! traducao/google-pausado-ate 0)
                    (set! js/global.fetch
                          (fn [& _]
                            (js/Promise.resolve
                             #js {:ok true :json (fn [] (js/Promise.resolve #js [#js [#js ["olá"]]]))})))
                    (-> (traducao/traduzir-com-status "hello" "en" "pt")
                        (.then (fn [texto] (is (= "olá" texto)))))))
          (.catch (fn [err] (is false (str err))))
          (.finally (fn []
                      (set! js/global.fetch fetch-original)
                      (reset! traducao/google-pausado-ate 0)
                      (done)))))))
