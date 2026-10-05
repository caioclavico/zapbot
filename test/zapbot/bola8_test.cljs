(ns zapbot.bola8-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            ["sharp" :as sharp]
            [zapbot.bola8 :as bola8]
            [zapbot.config :as config]))

(defn- escolher! [indice message pergunta]
  (with-redefs [cljs.core/rand-nth #(nth % indice)]
    (bola8/jogar message pergunta)))

(defn- com-buffer! [render executar]
  ;; Não inicia WhatsApp. A substituição termina antes de liberar o teste async.
  (let [original (.-toBuffer (.-prototype sharp))
        log-original (.-error js/console)]
    (set! (.-toBuffer (.-prototype sharp)) render)
    (set! (.-error js/console) (fn [& _]))
    (-> (p/resolved nil)
        (p/then executar)
        (p/finally (fn []
                     (set! (.-toBuffer (.-prototype sharp)) original)
                     (set! (.-error js/console) log-original))))))

(deftest chamadas-simultaneas-compartilham-sharp-e-envios-nao-compartilham-media
  (async done
    (let [renders (atom 0) envios (atom []) liberar (atom nil)
          png (js/Buffer.from "png-fixture")
          esperado (.toString png "base64")
          message #js {:reply (fn [^js media _ ^js options]
                               (swap! envios conj {:media media :base64 (.-data media)
                                                  :mime (.-mimetype media) :nome (.-filename media)
                                                  :legenda (.-caption options)})
                               ;; O envio pode modificar seu objeto sem contaminar o cache.
                               (set! (.-data media) "mutado-pelo-envio")
                               (p/resolved nil))}]
      (-> (com-buffer!
           (fn [& _]
             (swap! renders inc)
             (js/Promise. (fn [resolve _] (reset! liberar #(resolve png)))))
           (fn [_]
             (let [primeiro (escolher! 0 message "Devo tentar?")
                   segundo (escolher! 0 message "Vai dar certo?")]
               (p/let [_ (p/delay 0)]
                 (is (= 1 @renders))
                 (is (empty? @envios))
                 (@liberar)
                 (p/let [resultados (p/all [primeiro segundo])
                         quente (escolher! 0 message "")]
                   (is (= [nil nil] resultados))
                   (is (nil? quente))
                   (is (= 1 @renders))
                   (is (= 3 (count @envios)))
                   (is (every? #(= esperado (:base64 %)) @envios))
                   (is (every? #(= "image/png" (:mime %)) @envios))
                   (is (every? #(= "bola8.png" (:nome %)) @envios))
                   (is (= [(str "🎱 *Bola 8 do tio " config/bot-name "*\n\n❓ Devo tentar?")
                           (str "🎱 *Bola 8 do tio " config/bot-name "*\n\n❓ Vai dar certo?")
                           (str "🎱 *Bola 8 do tio " config/bot-name "*")]
                          (mapv :legenda @envios)))
                   (is (not (identical? (:media (first @envios)) (:media (second @envios)))))
                   (is (not (identical? (:media (second @envios)) (:media (last @envios))))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest falha-na-rasterizacao-retorna-texto-e-permite-nova-tentativa
  (async done
    (let [renders (atom 0) envios (atom [])
          message #js {:reply (fn [media & _] (swap! envios conj (.-data media)) (p/resolved nil))}]
      (-> (com-buffer!
           (fn [& _]
             (if (= 1 (swap! renders inc))
               (p/rejected (js/Error. "Falha Sharp simulada"))
               (p/resolved (js/Buffer.from "png-recuperado"))))
           (fn [_]
             (p/let [fallback (escolher! 1 message "Posso?")
                     recuperado (escolher! 1 message "Posso?")
                     quente (escolher! 1 message "Posso?")]
               (is (= (str "🎱 *Bola 8 do tio " config/bot-name "*\n\n❓ Posso?\n\n👉 Com certeza") fallback))
               (is (nil? recuperado))
               (is (nil? quente))
               (is (= 2 @renders))
               (is (= 2 (count @envios)))
               (is (= (first @envios) (last @envios))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest falha-no-envio-preserva-imagem-ja-renderizada
  (async done
    (let [renders (atom 0) tentativas (atom 0)
          message #js {:reply (fn [& _]
                               (if (= 1 (swap! tentativas inc))
                                 (p/rejected (js/Error. "Falha envio simulada"))
                                 (p/resolved nil)))}]
      (-> (com-buffer!
           (fn [& _] (swap! renders inc) (p/resolved (js/Buffer.from "png-enviado")))
           (fn [_]
             (p/let [fallback (escolher! 2 message "")
                     enviado (escolher! 2 message "")]
               (is (= (str "🎱 *Bola 8 do tio " config/bot-name "*\n\n👉 Sem dúvida") fallback))
               (is (nil? enviado))
               (is (= 1 @renders))
               (is (= 2 @tentativas)))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest png-real-mantem-dimensoes-e-transparencia
  (async done
    (let [media (atom nil)
          message #js {:reply (fn [imagem & _] (reset! media imagem) (p/resolved nil))}]
      (-> (p/let [_ (escolher! 19 message "")
                  buffer (js/Buffer.from (.-data @media) "base64")
                  ^js meta (.metadata (sharp buffer))
                  pixels (-> (sharp buffer) (.ensureAlpha) (.raw)
                             (.toBuffer #js {:resolveWithObject true}))]
            (is (= "png" (.-format meta)))
            (is (= 500 (.-width meta)))
            (is (= 500 (.-height meta)))
            (is (true? (.-hasAlpha meta)))
            (is (= 0 (aget (.-data pixels) 3)))
            (is (= 255 (aget (.-data pixels) (+ 3 (* 4 (+ 250 (* 500 250))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))
