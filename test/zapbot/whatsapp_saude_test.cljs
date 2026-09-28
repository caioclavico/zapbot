(ns zapbot.whatsapp-saude-test
  (:require [cljs.test :refer-macros [deftest is]]
            ["node:events" :refer [EventEmitter]]
            [zapbot.whatsapp-saude :as saude]))

(defn cliente []
  (doto (EventEmitter.)
    (aset "pupBrowser" #js {:isConnected (fn [] true)})
    (aset "pupPage" #js {:isClosed (fn [] false)})))

(deftest eventos-controlam-saude-sem-inicializar-navegador
  (let [s (saude/criar) c (cliente)]
    (saude/acompanhar! s c)
    (is (= "degraded" (:status (saude/resposta s c))))
    (doseq [[evento esperado] [["qr" "QR"] ["authenticated" "AUTHENTICATED"]
                              ["ready" "READY"] ["disconnected" "DISCONNECTED"]
                              ["auth_failure" "AUTH_FAILURE"]]]
      (.emit c evento "teste")
      (is (= esperado (:whatsapp (saude/resposta s c))))
      (is (= (if (= esperado "READY") "ok" "degraded")
             (:status (saude/resposta s c)))))
    (.emit c "ready")
    (aset c "pupBrowser" #js {:isConnected (fn [] false)})
    (is (= "degraded" (:status (saude/resposta s c))))
    (aset c "pupBrowser" #js {:isConnected (fn [] true)})
    (aset c "pupPage" #js {:isClosed (fn [] true)})
    (is (= "degraded" (:status (saude/resposta s c))))))

(deftest watchdog-nao-muda-estado-nem-reinicia-cliente
  (let [s (saude/criar) logs (atom [])]
    (with-redefs [saude/log! #(swap! logs conj %)]
      (doseq [segundos [60 120 180]] (saude/avisar! s segundos))
      (is (= 3 (count @logs)))
      (is (= "STARTING" @(:estado s)))
      (reset! (:estado s) "QR")
      (saude/avisar! s 180)
      (is (re-find #"Aguardando leitura" (last @logs)))
      (reset! (:estado s) "READY")
      (saude/avisar! s 180)
      (is (= 4 (count @logs))))
    (saude/iniciar-avisos! s)
    (is (= 3 (count @(:timers s))))
    (saude/atualizar! s "READY" "READY.")
    (is (empty? @(:timers s)))))

(deftest health-http-diferencia-pronto-de-processo-vivo
  (let [s (saude/criar) c (cliente) codigo (atom nil) corpo (atom nil)
        res #js {:writeHead (fn [n & _] (reset! codigo n))
                 :end (fn [& [texto]] (reset! corpo texto))}]
    (saude/atender! s c #js {:method "GET" :url "/health"} res)
    (is (= 503 @codigo))
    (is (= "STARTING" (.-whatsapp (js/JSON.parse @corpo))))
    (reset! (:estado s) "READY")
    (saude/atender! s c #js {:method "GET" :url "/health"} res)
    (is (= 200 @codigo))
    (saude/atender! s c #js {:method "GET" :url "/"} res)
    (is (= 404 @codigo))))
