(ns zapbot.recursos-test
  (:require [cljs.test :refer [use-fixtures] :refer-macros [async deftest is]]
            [zapbot.config :as config]
            [zapbot.recursos :as recursos]))

(def flag-anterior (atom nil))
(use-fixtures :each
  {:before (fn [] (reset! flag-anterior config/performance-metrics-enabled)
                   (set! config/performance-metrics-enabled true))
   :after (fn [] (set! config/performance-metrics-enabled @flag-anterior))})

(deftest coletor-desativado-nao-cria-timers-nem-acessa-navegador
  (with-redefs [config/odisseu-resource-metrics-enabled false
                recursos/monitor (atom nil)
                recursos/coletor #js {:startResourceMonitor (fn [& _] (throw (js/Error. "não iniciar")))}]
    (is (nil? (recursos/iniciar! #js {})))
    (is (nil? (recursos/amostra)))
    (let [resultado (js/Promise.resolve "original")]
      (is (identical? resultado (recursos/medir-comando! (fn [] resultado)))))))

(deftest coletor-unico-usa-processo-ja-existente-e-encerra-uma-vez
  (let [criadas (atom []) paradas (atom 0) estado (atom nil)
        processo #js {:pid 987}
        ativo #js {:stop #(swap! paradas inc) :snapshot (fn [] #js {:teste true})}
        client #js {:pupBrowser #js {:process (fn [] processo)}}]
    (with-redefs [config/odisseu-resource-metrics-enabled true
                  config/odisseu-resource-metrics-interval-ms 60000
                  recursos/monitor estado
                  recursos/coletor #js {:startResourceMonitor (fn [opcoes]
                                                              (swap! criadas conj opcoes)
                                                              ativo)}]
      (recursos/iniciar! client)
      (recursos/iniciar! client)
      (is (= 1 (count @criadas)))
      (let [^js opcoes (first @criadas)]
        (is (= 60000 (.-intervalMs opcoes)))
        (is (identical? processo ((.-browserProcess opcoes))))
        (is (true? (.-teste (recursos/amostra))))
        (aset client "pupBrowser" nil)
        (is (nil? ((.-browserProcess opcoes)))))
      (recursos/parar!)
      (recursos/parar!)
      (is (= 1 @paradas))
      (is (nil? @estado)))))

(deftest latencia-sincrona-preserva-valor-e-erro-e-nao-coleta-conteudo
  (let [duracoes (atom []) erro (js/Error. "comando falhou")
        ativo #js {:recordCommandLatency #(swap! duracoes conj %)}]
    (with-redefs [recursos/monitor (atom ativo)]
      (is (= "resultado" (recursos/medir-comando! (fn [] "resultado"))))
      (try
        (recursos/medir-comando! (fn [] (throw erro)))
        (is false "Deveria propagar o erro original")
        (catch :default recebido (is (identical? erro recebido))))
      (is (= 2 (count @duracoes)))
      (is (every? #(and (number? %) (not (neg? %))) @duracoes)))))

(deftest latencia-assincrona-preserva-resolucao-e-rejeicao
  (async done
    (let [duracoes (atom []) erro (js/Error. "rejeição original")
          ativo #js {:recordCommandLatency #(swap! duracoes conj %)}
          [sucesso falha]
          (with-redefs [recursos/monitor (atom ativo)]
            [(recursos/medir-comando! #(js/Promise.resolve 42))
             (recursos/medir-comando! #(js/Promise.reject erro))])]
      (-> (js/Promise.all #js [(.then sucesso #(is (= 42 %)))
                              (.then falha
                                     (fn [_] (is false "Deveria rejeitar"))
                                     #(is (identical? erro %)))])
          (.then (fn [_]
                   (is (= 2 (count @duracoes)))
                   (is (every? #(and (number? %) (not (neg? %))) @duracoes))))
          (.catch (fn [recebido] (is false (str recebido))))
          (.finally done)))))

(deftest falhas-das-metricas-nao-interrompem-comandos-ou-parada
  (with-redefs [recursos/monitor (atom #js {:recordCommandLatency (fn [& _] (throw (js/Error. "diagnóstico")))
                                           :snapshot (fn [] (throw (js/Error. "diagnóstico")))
                                           :stop (fn [] (throw (js/Error. "diagnóstico")))})]
    (is (= "resposta" (recursos/medir-comando! (fn [] "resposta"))))
    (is (nil? (recursos/amostra)))
    (is (nil? (recursos/parar!)))
    (is (nil? @recursos/monitor))))

(deftest flag-global-desliga-coletor-mesmo-com-flag-local-ligada
  (let [nao-chamar (fn [& _] (throw (js/Error. "coleta indevida")))
        resultado (js/Promise.resolve :ok)]
    (with-redefs [config/performance-metrics-enabled false
                  config/odisseu-resource-metrics-enabled true
                  recursos/monitor (atom nil)
                  recursos/coletor #js {:startResourceMonitor nao-chamar}]
      (is (nil? (recursos/iniciar! #js {})))
      (is (identical? resultado (recursos/medir-comando! (fn [] resultado)))))))
