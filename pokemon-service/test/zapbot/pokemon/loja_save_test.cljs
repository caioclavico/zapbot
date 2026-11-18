(ns zapbot.pokemon.loja-save-test
  "Isolamento e durabilidade do envelope save-bound-v1 da loja."
  (:require [pokemon-service.regression-fixture :as fixture]
            [cljs.test :refer-macros [use-fixtures async deftest is]]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.pokemon.loja :as loja]
            [zapbot.armazenamento :as armazenamento]))

(use-fixtures :once {:before fixture/iniciar! :after fixture/parar!})

(defn- economia-save [cid pid save-id]
  (get-in @loja/contas [cid pid "saves" save-id]))

(defn- salvar-confirmando!
  "Simula LWT bem-sucedido: só então o snapshot entra em confirmados."
  ([] (salvar-confirmando! nil))
  ([gravacoes]
   (fn [chave valor]
     (when gravacoes (swap! gravacoes inc))
     (swap! armazenamento/confirmados assoc chave valor)
     (p/resolved nil))))

(defn- com-loja-save!
  "Liga flags e substitui atoms/fns; restaura no finally (seguro com async)."
  [estado ctx-atom salvar! f]
  (let [flags [config/save-transitions-enabled? config/canonical-trainer-enabled?]
        antes-contas @loja/contas
        antes-vinculos @loja/vinculos-save
        antes-duraveis @loja/vinculos-duraveis
        antes-filas @loja/filas-vinculo
        antes-confirmados @armazenamento/confirmados
        antes-filas-gravacao @armazenamento/filas-gravacao
        antes-falhas @armazenamento/falhas-gravacao
        antes-salvar armazenamento/salvar!
        antes-aguardar armazenamento/aguardar-todas!
        antes-ctx armazenamento/contexto-writer-jogador]
    (set! config/save-transitions-enabled? true)
    (set! config/canonical-trainer-enabled? true)
    (reset! loja/contas estado)
    (reset! loja/vinculos-save {})
    (reset! loja/vinculos-duraveis {})
    (reset! loja/filas-vinculo {})
    (reset! armazenamento/confirmados {})
    (reset! armazenamento/filas-gravacao {})
    (reset! armazenamento/falhas-gravacao {})
    (set! armazenamento/salvar! salvar!)
    (set! armazenamento/aguardar-todas! (fn [] (p/resolved nil)))
    (set! armazenamento/contexto-writer-jogador
          (fn [pid] (when ctx-atom (get @ctx-atom pid))))
    (-> (f)
        (p/finally (fn []
                     (reset! loja/contas antes-contas)
                     (reset! loja/vinculos-save antes-vinculos)
                     (reset! loja/vinculos-duraveis antes-duraveis)
                     (reset! loja/filas-vinculo antes-filas)
                     (reset! armazenamento/confirmados antes-confirmados)
                     (reset! armazenamento/filas-gravacao antes-filas-gravacao)
                     (reset! armazenamento/falhas-gravacao antes-falhas)
                     (set! armazenamento/salvar! antes-salvar)
                     (set! armazenamento/aguardar-todas! antes-aguardar)
                     (set! armazenamento/contexto-writer-jogador antes-ctx)
                     (set! config/save-transitions-enabled? (nth flags 0))
                     (set! config/canonical-trainer-enabled? (nth flags 1)))))))

(deftest compras-isolam-moedas-entre-saves-a-e-b
  (async done
    (let [estado {"g" {"ash" {"moedas" 200 "inventario" {"cartao-presente" 0}}
                       "peer" {"moedas" 80 "inventario" {}}}}
          ctx-atom (atom nil)]
      (-> (com-loja-save!
           estado ctx-atom (salvar-confirmando!)
           (fn []
             (p/let [_ (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                     _ (reset! ctx-atom {"ash" {:save-id "save-A" :fence-generation 1 :transition-owner ""}})
                     _ (loja/comprar {:chat-id "g" :player-id "ash"} "fruta")
                     _ (is (= 190 (get (economia-save "g" "ash" "save-A") "moedas")))
                     _ (loja/garantir-vinculo-duravel! "g" "ash" "save-B" 2)
                     _ (reset! ctx-atom {"ash" {:save-id "save-B" :fence-generation 2 :transition-owner ""}})
                     _ (loja/creditar-quantia! "g" "ash" 50)
                     _ (loja/comprar {:chat-id "g" :player-id "ash"} "fruta")
                     _ (is (= 40 (get (economia-save "g" "ash" "save-B") "moedas")))
                     _ (is (= 190 (get (economia-save "g" "ash" "save-A") "moedas")))
                     _ (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 3)
                     _ (reset! ctx-atom {"ash" {:save-id "save-A" :fence-generation 3 :transition-owner ""}})
                     _ (is (= 190 (loja/moedas "g" "ash")))
                     _ (loja/garantir-vinculo-duravel! "g" "peer" "peer-A" 1)]
               (is (= 80 (get (economia-save "g" "peer" "peer-A") "moedas")))
               (is (= 80 (get-in (armazenamento/estado-confirmado "loja")
                                 ["g" "peer" "saves" "peer-A" "moedas"]))
                   "peer permanece no snapshot confirmado"))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest retry-obsoleto-e-reserva-bloqueiam-escrita
  (async done
    (let [ctx-atom (atom nil)
          codigo (fn [e] (:code (ex-data e)))]
      (-> (com-loja-save!
           {} ctx-atom (salvar-confirmando!)
           (fn []
             (p/let [_ (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                     _ (reset! ctx-atom {"ash" {:save-id "save-A" :fence-generation 1 :transition-owner ""}})
                     _ (loja/creditar-quantia! "g" "ash" 100)
                     _ (loja/garantir-vinculo-duravel! "g" "ash" "save-B" 2)
                     _ (reset! ctx-atom {"ash" {:save-id "save-A" :fence-generation 1 :transition-owner ""}})]
               (try
                 (loja/comprar {:chat-id "g" :player-id "ash"} "fruta")
                 (is false "retry obsoleto deveria falhar")
                 (catch :default e
                   (is (= :loja-save-fence-conflict (codigo e)))))
               (is (= 100 (get (economia-save "g" "ash" "save-A") "moedas")))
               (reset! ctx-atom {"ash" {:save-id "save-B" :fence-generation 2 :transition-owner "op-1"}})
               (try
                 (loja/creditar-quantia! "g" "ash" 10)
                 (is false "reserva deveria bloquear")
                 (catch :default e
                   (is (= :loja-transition-reserved (codigo e)))))
               true)))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest vinculo-duravel-antes-de-alternar-sem-compras
  (async done
    (let [gravacoes (atom 0)]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 150 "inventario" {"pocao" 2}}}}
           nil
           (salvar-confirmando! gravacoes)
           (fn []
             (p/let [a (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                     _ (is (true? (:durable a)))
                     _ (is (pos? @gravacoes))
                     _ (is (loja/vinculo-duravel? "g" "ash" "save-A"))
                     b (loja/garantir-vinculo-duravel! "g" "ash" "save-B" 2)
                     _ (is (true? (:durable b)))
                     _ (is (= 150 (get (economia-save "g" "ash" "save-A") "moedas")))
                     _ (is (= 0 (get (economia-save "g" "ash" "save-B") "moedas" 0)))
                     _ (is (= 150 (get-in (armazenamento/estado-confirmado "loja")
                                          ["g" "ash" "saves" "save-A" "moedas"]))
                           "envelope de A permanece no confirmado após criar B")
                     again (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 3)]
               (is (true? (:idempotent again)))
               (is (= 150 (loja/moedas "g" "ash")))
               (is (= "save-A" (get-in @loja/contas ["g" "ash" "legacy-claim" "save-id"]))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest reinicio-reobserva-envelope-sem-duplicar-claim
  (async done
    (let [gravacoes (atom 0)
          estado (assoc-in {} ["g" "ash"]
                           {"schema-version-loja" "save-bound-v1"
                            "legacy-claim" {"save-id" "save-A"
                                            "status" "confirmed"
                                            "fence-generation" 1}
                            "saves" {"save-A" {"moedas" 40 "inventario" {}}}})
          ;; Hidratação já deixou o envelope no snapshot confirmado.
          seed {"loja" estado}]
      (-> (com-loja-save!
           estado nil
           (fn [chave valor]
             (swap! gravacoes inc)
             (swap! armazenamento/confirmados assoc chave valor)
             (p/resolved nil))
           (fn []
             (reset! armazenamento/confirmados seed)
             (p/let [r (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                     _ (is (true? (:idempotent r)))
                     _ (is (true? (:reobserved r)))
                     _ (is (= 0 @gravacoes)
                           "hidratação já confirmou: sem compra nem regravação")
                     _ (is (loja/vinculo-duravel? "g" "ash" "save-A"))
                     retry (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)]
               (is (true? (:idempotent retry)))
               (is (= 0 @gravacoes))
               (is (loja/vinculo-duravel? "g" "ash" "save-A")))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest reinicio-durante-migracao-retenta-persistencia
  (async done
    (let [tentativas (atom 0)
          falhou? (atom true)]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 25}}}
           nil
           (fn [chave valor]
             (swap! tentativas inc)
             (if @falhou?
               (do (reset! falhou? false)
                   (p/rejected (ex-info "resposta Cassandra perdida" {:code :result-unknown})))
               (do (swap! armazenamento/confirmados assoc chave valor)
                   (p/resolved nil))))
           (fn []
             (p/let [_ (p/catch (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                               (fn [e]
                                 (is (= :result-unknown (:code (ex-data e))))
                                 (is (not (loja/vinculo-duravel? "g" "ash" "save-A"))
                                     "falha não cria recibo local")
                                 nil))
                     ok (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)]
               (is (true? (:durable ok)))
               (is (>= @tentativas 2))
               (is (= 25 (get (economia-save "g" "ash" "save-A") "moedas")))
               (is (loja/vinculo-duravel? "g" "ash" "save-A")))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest noop-salvar-sem-confirmados-nao-libera-a-b
  "Fila vazia / salvar! no-op sem atualizar confirmados ≠ vínculo durável."
  (async done
    (let [codigo (fn [e] (:code (ex-data e)))]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 60}}}
           nil
           ;; Resolve sem tocar confirmados: simula hidratação/stale no-op.
           (fn [& _] (p/resolved nil))
           (fn []
             (p/let [_ (p/catch (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                               (fn [e]
                                 (is (= :loja-save-binding-unconfirmed (codigo e)))
                                 (is (not (loja/vinculo-duravel? "g" "ash" "save-A")))
                                 (is (nil? (armazenamento/estado-confirmado "loja")))
                                 nil))
                     _ (p/catch (loja/garantir-vinculo-duravel! "g" "ash" "save-B" 2)
                               (fn [e]
                                 (is (= :loja-save-binding-unconfirmed (codigo e))
                                     "gravação perdida não permite A→B")
                                 (is (not (loja/vinculo-duravel? "g" "ash" "save-B")))
                                 nil))]
               true)))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest aguardar-com-falha-cas-propaga-sem-recibo
  (async done
    (let [codigo (fn [e] (:code (ex-data e)))
          antes-aguardar armazenamento/aguardar-todas!]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 12}}}
           nil
           (fn [chave _valor]
             ;; Fila "limpa" com falha registada: aguardar-todas! deve propagar.
             (swap! armazenamento/filas-gravacao assoc chave (p/resolved nil))
             (swap! armazenamento/falhas-gravacao assoc chave
                    (ex-info "CAS loja" {:code :result-unknown}))
             (p/resolved nil))
           (fn []
             (set! armazenamento/aguardar-todas! antes-aguardar)
             (p/let [_ (p/catch (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                               (fn [e]
                                 (is (= :result-unknown (codigo e)))
                                 (is (not (loja/vinculo-duravel? "g" "ash" "save-A")))
                                 (is (nil? (get-in @loja/vinculos-duraveis [["g" "ash"] :save-id])))
                                 nil))]
               true)))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest legado-ambiguo-apos-transicao-nao-vai-para-save-ativo
  (async done
    (let [codigo (fn [e] (:code (ex-data e)))]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 99 "inventario" {"fruta" 1}}}}
           nil
           (salvar-confirmando!)
           (fn []
             (-> (loja/garantir-vinculo-duravel! "g" "ash" "save-B" 3)
                 (p/then (fn [_] (is false "deveria falhar fechado")))
                 (p/catch (fn [e]
                            (is (= :loja-save-ambiguous (codigo e)))
                            (is (nil? (get-in @loja/contas ["g" "ash" "schema-version-loja"])))
                            (is (nil? (armazenamento/estado-confirmado "loja"))))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest concorrencia-migracao-serializa-por-jogador
  (async done
    (let [ordem (atom [])]
      (-> (com-loja-save!
           {"g" {"ash" {"moedas" 10}}}
           nil
           (fn [chave valor]
             (swap! ordem conj :salvar)
             (swap! armazenamento/confirmados assoc chave valor)
             (p/delay 5 nil))
           (fn []
             (let [pa (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)
                   pb (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)]
               (p/let [a pa b pb]
                 (is (true? (:durable a)))
                 (is (true? (:idempotent b)))
                 (is (= 1 (count (filter #{:salvar} @ordem))))
                 (is (= 10 (get (economia-save "g" "ash" "save-A") "moedas")))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(deftest flags-desligadas-nao-migram-nem-exigem-vinculo
  (async done
    (let [flags [config/save-transitions-enabled? config/canonical-trainer-enabled?]
          antes @loja/contas
          antes-salvar armazenamento/salvar!
          gravacoes (atom 0)]
      (set! config/save-transitions-enabled? false)
      (set! config/canonical-trainer-enabled? false)
      (reset! loja/contas {"g" {"ash" {"moedas" 30}}})
      (set! armazenamento/salvar!
            (fn [& _] (swap! gravacoes inc) (p/resolved nil)))
      (-> (p/let [r (loja/garantir-vinculo-duravel! "g" "ash" "save-A" 1)]
            (is (true? (:disabled r)))
            (is (= 0 @gravacoes))
            (is (nil? (get-in @loja/contas ["g" "ash" "schema-version-loja"])))
            (is (not (loja/vinculo-duravel? "g" "ash" "save-A"))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally (fn []
                       (reset! loja/contas antes)
                       (set! armazenamento/salvar! antes-salvar)
                       (set! config/save-transitions-enabled? (nth flags 0))
                       (set! config/canonical-trainer-enabled? (nth flags 1))
                       (done)))))))
