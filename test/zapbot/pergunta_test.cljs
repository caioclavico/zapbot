(ns zapbot.pergunta-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [clojure.string :as str]
            ["sharp" :as sharp]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]
            [zapbot.pergunta :as pergunta]))

(defn- mensagem-fake [envios]
  #js {:reply (fn [media _chat opcoes]
                (swap! envios conj {:media media :caption (.-caption opcoes)})
                (p/resolved nil))})

(defn- chamar [message cache preparar gerar]
  ;; Cada chamada inicia seu Gemini fake antes de with-redefs terminar; Sharp
  ;; e o cache capturam a Promise em andamento, sem rede ou sessão WhatsApp.
  (with-redefs [config/gemini-api-key "fixture"
                gemini/gerar-texto gerar
                pergunta/foto-abujamra-cache cache
                pergunta/preparar-foto-abujamra preparar]
    (pergunta/perguntar message "O que é a vida?")))

(deftest foto-real-vira-jpeg-e-compartilha-conversao
  (async done
    (let [cache (atom nil)
          conversoes (atom 0)
          chamadas-gemini (atom 0)
          envios (atom [])
          message (mensagem-fake envios)
          original pergunta/preparar-foto-abujamra
          preparar (fn [] (swap! conversoes inc) (original))
          gerar (fn [_] (swap! chamadas-gemini inc) (p/resolved "Reflexão da fixture."))
          solicitar #(chamar message cache preparar gerar)]
      (-> (p/let [primeiras (p/all [(solicitar) (solicitar)])
                  terceira (solicitar)
                  origem (.metadata (sharp (str js/__dirname "/../assets/abujamra.png")))
                  bytes (js/Buffer.from (.-data (:media (first @envios))) "base64")
                  imagem (.metadata (sharp bytes))]
            (is (= [nil nil] primeiras))
            (is (nil? terceira))
            (is (= 1 @conversoes))
            (is (= 3 @chamadas-gemini))
            (is (= 3 (count @envios)))
            (is (= "jpeg" (.-format imagem)))
            (is (= (min 640 (.-width origem)) (.-width imagem)))
            (is (<= (.-height imagem) (.-height origem)))
            (is (= "ffd8" (.toString (.subarray bytes 0 2) "hex")))
            (is (not (identical? (:media (first @envios)) (:media (second @envios)))))
            (doseq [{:keys [media caption]} @envios]
              (is (= "image/jpeg" (.-mimetype media)))
              (is (= "abujamra.jpg" (.-filename media)))
              (is (= (.-data (:media (first @envios))) (.-data media)))
              (is (= (str "🎭 *O tio " config/bot-name " provoca...*\n\n"
                          "Reflexão da fixture.\n\nMas afinal... o que é a vida?")
                     caption))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest falha-na-foto-permite-retry-sem-reusar-media
  (async done
    (let [cache (atom nil)
          conversoes (atom 0)
          envios (atom [])
          message (mensagem-fake envios)
          original pergunta/preparar-foto-abujamra
          preparar (fn []
                     (if (= 1 (swap! conversoes inc))
                       (p/rejected (js/Error. "Falha de imagem na fixture"))
                       (original)))
          gerar (fn [_] (p/resolved "Reflexão da fixture."))
          console-error (.-error js/console)]
      (set! (.-error js/console) (fn [& _]))
      (-> (p/let [fallback (chamar message cache preparar gerar)
                  _ (do (is (str/includes? fallback "A vida é isso que passa"))
                        (is (nil? @cache))
                        (is (empty? @envios)))
                  resposta (chamar message cache preparar gerar)
                  bytes (js/Buffer.from (.-data (:media (first @envios))) "base64")
                  imagem (.metadata (sharp bytes))]
            (is (nil? resposta))
            (is (= 2 @conversoes))
            (is (= 1 (count @envios)))
            (is (= "jpeg" (.-format imagem))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (set! (.-error js/console) console-error) (done)))))))
