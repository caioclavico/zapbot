(ns zapbot.desempenho-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]))

(deftest seleciona-somente-perfil-com-prefixo-e-alias
  (with-redefs [config/prefix "!"]
    (doseq [texto ["!pk treinador" " !pokemon TREINADOR " "!pk tre"]]
      (is (desempenho/treinador? texto)))
    (doseq [texto [nil "oi" "!pk tm" "!pk treinadorxyz" "!status" "!pk atacar"]]
      (is (not (desempenho/treinador? texto)))))
  (with-redefs [config/prefix "."]
    (is (desempenho/treinador? ".pk treinador"))
    (is (not (desempenho/treinador? "!pk treinador")))))

(deftest sem-contexto-preserva-retorno-sincrono-e-promessa
  (is (= 42 (desempenho/medir! nil "dados" (fn [] 42))))
  (let [promessa (js/Promise.resolve "texto")]
    (is (identical? promessa (desempenho/medir! nil "dados" (fn [] promessa))))))

(deftest base64-preserva-bytes-e-registra-apenas-tamanhos
  (async done
    (let [logs (atom [])
          buffer (js/Buffer.from #js [0 255 31 128 3])]
      (is (= (.toString buffer "base64") (desempenho/codificar-base64! nil buffer)))
      (-> (desempenho/acompanhar! #js {}
            #(desempenho/codificar-base64! % buffer)
            #(swap! logs conj %))
          (.then (fn [texto]
                   (is (.equals buffer (js/Buffer.from texto "base64")))
                   (is (= [{:bytes 5 :base64_chars 8}] (:midias (last @logs))))
                   (is (contains? (:etapas_ms (last @logs)) "imagem_base64"))
                   (is (not (str/includes? (pr-str @logs) texto)))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest seleciona-comandos-pokemon-sem-confundir-outros-textos
  (with-redefs [config/prefix "!"]
    (doseq [texto ["!pk" "!pk time" "!pokemon ginasio desafiar agua" " !PK tre "]]
      (is (desempenho/pokemon? texto)))
    (doseq [texto [nil "oi" "!status" "!pkxyz" "texto !pk time"]]
      (is (not (desempenho/pokemon? texto))))))

(deftest consulta-pendentes-nao-expoe-mensagem-e-limpa-ao-terminar
  (async done
    (let [liberar (p/deferred)
          ctx-atual (atom nil)
          mensagem #js {:body "segredo" :from "telefone"}
          tarefa (desempenho/acompanhar! mensagem
                   (fn [ctx]
                     (reset! ctx-atual ctx)
                     (desempenho/medir! ctx "envio" #(identity liberar)))
                   (fn [_]))
          id (:id @ctx-atual)
          resumo (first (filter #(= id (:id %)) (desempenho/pendentes)))]
      (is (= ["envio"] (:pendentes resumo)))
      (is (not (re-find #"segredo|telefone" (pr-str resumo))))
      (p/resolve! liberar :ok)
      (-> tarefa
          (.then (fn [_]
                   (is (nil? (desempenho/contexto-de mensagem)))
                   (is (empty? (filter #(= id (:id %)) (desempenho/pendentes))))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest contextos-concorrentes-isolados-com-promesa-e-etapas-aninhadas
  (async done
    (let [logs (atom [])
          executar (fn [etapa demora]
                     (desempenho/acompanhar! #js {}
                      (fn [ctx] (desempenho/medir! ctx
                        "processamento"
                        (fn []
                          (p/let [_ (p/create (fn [resolve _]
                                                (js/setTimeout resolve demora)))]
                            (desempenho/medir! ctx etapa (fn [] etapa))))))
                      #(swap! logs conj %)))]
      (-> (p/all [(executar "download" 20) (executar "imagem" 5)])
          (p/then (fn [valores]
                    (is (= ["download" "imagem"] valores))
                    (let [finais (filter #(= "fim" (:evento %)) @logs)]
                      (is (= 2 (count finais)))
                      (is (= 2 (count (set (map :id finais)))))
                      (is (= #{#{"processamento" "download"} #{"processamento" "imagem"}}
                             (set (map #(set (keys (:etapas_ms %))) finais))))
                      (is (every? #(empty? (:pendentes %)) finais))
                      (is (every? #(empty? (:falhas %)) finais)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest falha-registra-etapa-e-preserva-erro-para-fallback
  (async done
    (let [logs (atom []) erro (js/Error. "erro simulado")]
      (-> (desempenho/acompanhar! #js {}
           (fn [ctx] (-> (desempenho/medir! ctx "imagem" (fn [] (p/rejected erro)))
                (p/catch (fn [recebido]
                           (is (identical? erro recebido))
                           "fallback"))))
           #(swap! logs conj %))
          (.then (fn [resultado]
                   (is (= "fallback" resultado))
                   (is (= ["imagem"] (:falhas (last @logs))))
                   (is (empty? (:pendentes (last @logs))))))
          (.catch (fn [falha] (is false (str falha))))
          (.finally done)))))

(deftest excecao-sincrona-nao-e-engolida
  (let [logs (atom []) erro (js/Error. "erro simulado")]
    (try
      (desempenho/acompanhar! #js {}
       (fn [ctx] (desempenho/medir! ctx "dados" (fn [] (throw erro))))
       #(swap! logs conj %))
      (is false "Deveria propagar o erro")
      (catch :default recebido
        (is (identical? erro recebido))))
    (is (= "erro" (:evento (last @logs))))
    (is (= ["dados"] (:falhas (last @logs))))))
