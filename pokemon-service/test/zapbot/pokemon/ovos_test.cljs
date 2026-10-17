(ns zapbot.pokemon.ovos-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [clojure.string :as str]
            [pokemon-service.adventure-fixture :as fixture]
            [zapbot.armazenamento :as storage]
            [zapbot.config :as config]
            [zapbot.adventure-store :as store]
            [zapbot.pokemon.adventure :as adventure]
            [zapbot.pokemon.adventure-catalog :as catalog]
            [zapbot.pokemon.ovos :as ovos]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]))

(defn concluir [trabalho done]
  (-> trabalho (p/catch #(is false (str % " " (ex-data %) " " (.-stack %)))) (p/finally done)))
(defn falha [trabalho code]
  (-> (p/then (p/resolved nil) trabalho)
      (p/then (fn [_] (is false (str "Esperava erro " code))))
      (p/catch (fn [erro] (is (= code (:code (ex-data erro))))))))
(defn abrir [cid pid]
  (p/let [resposta (adventure/ativar! cid pid)
          egg-id (get-in resposta [:operation :egg-id])
          _ (adventure/incubar! cid pid "incubar" egg-id)] egg-id))

(deftest etapa-a-atividade-tempo-pontos-e-incubadoras
  (let [estado (ovos/estado-atividade-inicial)
        ovo (ovos/novo-ovo-atividade "ovo-1" :comum 0)
        estado (ovos/conceder-ovo-atividade estado ovo)
        estado (ovos/incubar-atividade estado "ovo-1" "permanente" 1000)
        estado (ovos/aplicar-pontos-atividade estado "evento-1" 100)
        pronto (first (:eggs estado))]
    (is (= :ready-to-hatch (:state pronto)))
    (is (= 100 (:points pronto)))
    (is (identical? estado (ovos/aplicar-pontos-atividade estado "evento-1" 100)))
    (is (= :hatched (:state (first (:eggs (ovos/concluir-atividade estado "ovo-1" 2000))))))))

(deftest etapa-a-tempo-natural-e-bonus-especial
  (let [estado (-> (ovos/estado-atividade-inicial)
                   (ovos/adicionar-incubadora-atividade "especial" :especial 3 25)
                   (ovos/conceder-ovo-atividade (ovos/novo-ovo-atividade "ovo-2" :raro 0))
                   (ovos/incubar-atividade "ovo-2" "especial" 1000)
                   (ovos/aplicar-pontos-atividade "atividade" 200))
        parcial (first (:eggs estado))
        pronto (ovos/avancar-tempo-atividade estado (* 6 60 60 1000 1))]
    (is (= 250 (:points parcial)))
    (is (= :ready-to-hatch (:state parcial)))
    (is (= :ready-to-hatch (:state (first (:eggs pronto)))))))

(deftest etapa-a-limites-e-usos-temporarios
  (let [estado (-> (ovos/estado-atividade-inicial)
                   (ovos/adicionar-incubadora-atividade "azul" :extra 1 0)
                   (ovos/adicionar-incubadora-atividade "roxa" :especial 1 25))]
    (is (thrown? js/Error (ovos/adicionar-incubadora-atividade estado "outra" :extra 1 0)))
    (let [estado (-> estado
                     (ovos/conceder-ovo-atividade (ovos/novo-ovo-atividade "ovo-3" :comum 0))
                     (ovos/incubar-atividade "ovo-3" "azul" 0)
                     (ovos/aplicar-pontos-atividade "ganho" 100)
                     (ovos/concluir-atividade "ovo-3" 1))]
      (is (empty? (filter #(= "azul" (:incubator-id %)) (:incubators estado)))))))

(deftest transicao-legado-preserva-identidade-e-progresso
  (let [legado {:schema-version 1 :account-id "a" :chat-id "g" :player-id "p"
                :eggs [{:egg-id "legacy-2" :category 2 :goal-mm 2000000
                        :progress-mm 1000000 :state "incubating"
                        :incubated-at 1000 :pool [{:species "eevee"}] :seed "s"}
                       {:egg-id "legacy-5" :category 5 :goal-mm 5000000
                        :progress-mm 0 :state "inventory" :pool [{:species "growlithe"}] :seed "t"}]
                :incubators [{:incubator-id "permanente" :active-egg-id "legacy-2"}]
                :receipts [{:event-id "old-event" :kind "distance"}]}
        novo (ovos/transicionar-legado legado)
        novamente (ovos/transicionar-legado novo)]
    (is (= 2 (:mechanics-version novo)))
    (is (= "legacy-distance-preserved" (:transition-receipt novo)))
    (is (= ["legacy-2" "legacy-5"] (mapv :egg-id (:eggs novo))))
    (is (= [2 5] (mapv :category (:eggs novo))))
    (is (nil? (get-in novo [:eggs 0 :points])))
    (is (= :approval-required (:activity-migration novo)))
    (is (= [1000 nil] (mapv :incubated-at (:eggs novo))))
    (is (= (:receipts legado) (:receipts novo)))
    (is (= novo novamente))))

(deftest transicao-legado-preserva-pronto-sem-nascimento
  (let [legado {:schema-version 1 :eggs [{:egg-id "ready" :category 10
                                          :goal-mm 10000000 :progress-mm 10000000
                                          :state "ready_to_hatch"}]
                :incubators [{:incubator-id "permanente" :active-egg-id "ready"}]}
        novo (ovos/transicionar-legado legado)]
    (is (= 10 (get-in novo [:eggs 0 :category])))
    (is (= "ready_to_hatch" (get-in novo [:eggs 0 :state])))
    (is (nil? (get-in novo [:eggs 0 :resultado])))
    (is (= "ready" (get-in novo [:incubators 0 :active-egg-id])))))

(deftest transicao-persistida-usa-cas-e-e-idempotente
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now db]}]
        (p/let [estado (store/resolver! "g" "p" true @now)
                legado (assoc estado :eggs [{:egg-id "legado" :category 2 :goal-mm 2000000
                                             :progress-mm 1000000 :state "incubating"
                                             :incubated-at 10}])
                _ (swap! db assoc-in [:states (:account-id estado)]
                         {:version (:version estado) :schema_version 1
                          :payload (store/json legado)})
                convertido (store/transicionar-legado! (:account-id estado) @now)
                repetido (store/transicionar-legado! (:account-id estado) @now)]
          (is (= 2 (:mechanics-version convertido)))
          (is (= (:version convertido) (:version repetido)))
          (is (= 1 (count (:eggs repetido))))
          (is (nil? (get-in repetido [:eggs 0 :points])))
          (is (= :approval-required (:activity-migration repetido)))))) done)))

(deftest regras-puras-e-pesos-do-catalogo
  (doseq [categoria [2 5 10]]
    (let [pool (catalog/validar! catalog/distribuicao categoria)
          ovo (ovos/novo-ovo "id" categoria "v1" pool "seed" 0)]
      (is (= (* categoria 1000000) (:goal-mm ovo)))
      (is (= 100 (reduce + (map :weight pool))))
      (is (= (catalog/sortear pool "seed") (catalog/sortear pool "seed")))))
  (is (thrown? js/Error (ovos/novo-ovo "id" 3 "v1" [] "seed" 0)))
  (is (thrown? js/Error (catalog/validar! (assoc-in catalog/distribuicao [:pools 2 0 :weight] 71) 2)))
  (is (thrown? js/Error (catalog/validar! {:version "bad" :pools {2 [{:weight 100 :species ["mewtwo"]}]}} 2))))

(deftest criar-cada-distancia-com-recibo-e-isolamento
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [respostas (p/all (map #(adventure/criar-ovo! (str "grupo" %) "ash@lid" "mesmo-id" %) [2 5 10]))
                novamente (adventure/criar-ovo! "grupo2" "ash@lid" "mesmo-id" 2)]
          (is (= [2 5 10] (mapv #(get-in % [:state :eggs 0 :category]) respostas)))
          (is (= 3 (count (set (map #(get-in % [:state :account-id]) respostas)))))
          (is (= (:operation (first respostas)) (:operation novamente)))
          (is (= 1 (count (get-in novamente [:state :eggs]))))))) done)))

(deftest limite-ovos-nao-consome-moedas-ou-mochila
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [_ (reduce (fn [anterior i] (p/then anterior #(adventure/criar-ovo! "g" "p" (str i) 5)))
                         (p/resolved nil) (range 9))
                _ (falha #(adventure/criar-ovo! "g" "p" "dez" 10) :egg-inventory-full)
                estado (adventure/consultar! "g" "p")]
          (is (= 9 (count (:eggs estado))))
          (is (empty? @treinador/contas))))) done)))

(deftest incubacao-valida-invalida-e-um-slot
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [a (adventure/criar-ovo! "g" "p" "a" 2)
                b (adventure/criar-ovo! "g" "p" "b" 10)
                id (get-in a [:operation :egg-id])
                _ (falha #(adventure/incubar! "outro" "p" "i" id) :egg-not-found)
                _ (falha #(adventure/incubar! "g" "p" "i" "inexistente") :egg-not-found)
                inicio (adventure/incubar! "g" "p" "i" id)
                replay (adventure/incubar! "g" "p" "i" id)
                _ (falha #(adventure/incubar! "g" "p" "outro-i" id) :invalid-incubation)
                _ (falha #(adventure/incubar! "g" "p" "b-i" (get-in b [:operation :egg-id])) :incubator-busy)]
          (is (= (:operation inicio) (:operation replay)))
          (is (= id (get-in inicio [:state :incubators 0 :active-egg-id])))))) done)))

(deftest progresso-parcial-e-replay-com-conteudo-conflitante
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [_ (abrir "g" "p")]
          (let [evento (fixture/caminhada now "andar" 700000)]
            (p/let [a (adventure/aplicar-distancia! "g" "p" evento)
                    b (adventure/aplicar-distancia! "g" "p" evento)
                    _ (falha #(adventure/aplicar-distancia! "g" "p" (assoc evento :distance-mm 900000)) :event-conflict)]
              (is (= 700000 (get-in b [:state :eggs 0 :progress-mm])))
              (is (= (:operation a) (:operation b)))
              (is (empty? (treinador/equipe "g" "p")))))))) done)))

(deftest nascimento-modelo-atual-sem-ivs-sem-recompensa-de-captura
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now db]}]
        (p/let [_ (abrir "g" "p")
                resultado (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 3000000))
                historico (adventure/historico! "g" "p" (store/mes @now))]
          (let [registro (first (treinador/equipe "g" "p")) [pokemon hp status] (treinador/registro->pokemon registro)]
            (is (= 1 (count (treinador/equipe "g" "p"))))
            (is (= 1 (get registro "nivel"))) (is (= hp (:hp pokemon))) (is (nil? status))
            (is (seq (:golpes pokemon))) (is (pos? (:ataque pokemon)))
            (is (nil? (get registro "ivs"))) (is (nil? (get registro "iv")))
            (is (= 0 (treinador/sequencia-capturas "g" "p")))
            (is (empty? (get-in resultado [:state :eggs])))
            (is (= 2000000 (get-in resultado [:operation :credited-mm])))
            (is (= 1 (count historico))) (is (= (get registro "id-pokemon") (:pokemon-id (first historico))))
            (is (= 1 (count (:history @db)))))))) done)))

(deftest novas-distancias-5-e-10-km-completam-sem-excedente
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (reduce
          (fn [anterior categoria]
            (p/let [_ anterior a (adventure/criar-ovo! "g" "p" (str "grant" categoria) categoria)
                    _ (adventure/incubar! "g" "p" (str "inc" categoria) (get-in a [:operation :egg-id]))
                    r (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now (str "walk" categoria) (* (inc categoria) 1000000)))]
              (is (= (* categoria 1000000) (get-in r [:operation :credited-mm])))
              (is (nil? (get-in r [:state :incubators 0 :active-egg-id])))))
          (p/resolved nil) [5 10]))) done)))

(deftest estoque-cheio-preserva-resultado-e-recupera-ao-liberar-vaga
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now cap]}]
        (reset! cap 0)
        (p/let [_ (abrir "g" "p")
                r (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000))
                estado (store/resolver! "g" "p" false @now)
                fixado (:resultado (first (:eggs estado)))
                _ (adventure/recuperar! "g" "p")
                _ (reset! cap 1)
                _ (adventure/recuperar! "g" "p")
                historia (adventure/historico! "g" "p" (store/mes @now))]
          (is (= "delivery_pending" (get-in r [:state :eggs 0 :state])))
          (is (= 1 (count historia)))
          (is (= (:pokemon-id fixado) (:pokemon-id (first historia))))))) done)))

(deftest incremento-anterior-futuro-e-invalido-nao-gravam-progresso
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [_ (abrir "g" "p")
                _ (falha #(adventure/aplicar-distancia! "g" "p" {:event-id "antes" :from-ms (- @now 1000) :to-ms @now :distance-mm 100}) :before-incubation)
                _ (falha #(adventure/aplicar-distancia! "g" "p" {:event-id "futuro" :from-ms @now :to-ms (+ @now 300000) :distance-mm 100}) :invalid-interval)
                _ (falha #(adventure/aplicar-distancia! "g" "p" {:event-id "bad" :from-ms @now :to-ms @now :distance-mm -1}) :invalid-distance)
                estado (adventure/consultar! "g" "p")]
          (is (= 0 (:total-credited-mm estado)))))) done)))

(deftest concorrencia-de-ativacao-e-distancia
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [respostas (p/all [(adventure/ativar! "g" "p") (adventure/ativar! "g" "p")])
                _ (is (= 1 (count (get-in (last respostas) [:state :eggs]))))
                _ (adventure/incubar! "g" "p" "inc" (get-in (first respostas) [:operation :egg-id]))]
          (let [a (fixture/caminhada now "a" 900000) b (fixture/caminhada now "b" 1200000)]
            (p/let [_ (p/all [(adventure/aplicar-distancia! "g" "p" a)
                             (adventure/aplicar-distancia! "g" "p" a)
                             (adventure/aplicar-distancia! "g" "p" b)])]
              (is (= 1 (count (treinador/equipe "g" "p"))))))))) done)))

(deftest cas-concorrente-sem-perda-de-atualizacao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [estado (store/resolver! "g" "p" true @now)
                _ (p/all [(store/alterar! (:account-id estado) #(update % :total-credited-mm + 1) @now)
                          (store/alterar! (:account-id estado) #(update % :total-credited-mm + 2) @now)])
                final (store/ler! (:account-id estado))]
          (is (= 3 (:total-credited-mm final)))))) done)))

(deftest catalogo-configuravel-fixado-no-ovo
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (let [cfg {:version "only-eevee" :pools {2 [{:weight 100 :species ["eevee"]}]}}]
          (p/let [a (adventure/criar-ovo! "g" "p" "custom" 2 cfg)
                  _ (adventure/incubar! "g" "p" "i" (get-in a [:operation :egg-id]))
                  _ (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "andar" 2000000))
                  h (adventure/historico! "g" "p" (store/mes @now))]
            (is (= "eevee" (:species (first h))))
            (is (= "only-eevee" (:catalog-version (first h)))))))) done)))

(deftest falha-apos-cas-de-distancia-nao-incrementa-no-replay
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (p/let [_ (abrir "g" "p")]
          (let [evento (fixture/caminhada now "parcial" 800000)]
            (reset! fault {:op :state-cas :after? true :match #(str/includes? (second %) "800000")})
            (p/let [_ (falha #(adventure/aplicar-distancia! "g" "p" evento) :result-unknown)
                    r (adventure/aplicar-distancia! "g" "p" evento)]
              (is (= 800000 (get-in r [:state :eggs 0 :progress-mm])))))))) done)))

(deftest cas-aplicado-resposta-perdida-e-leitura-serial-evitam-reentrega
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (p/let [_ (abrir "g" "p")]
          (let [evento (fixture/caminhada now "meta" 2000000)]
            (reset! fault {:op :trainer-cas :after? true})
            (p/let [_ (-> (adventure/aplicar-distancia! "g" "p" evento) (p/catch (fn [_] nil)))
                    ;; O resultado CAS foi provado por leitura SERIAL; o
                    ;; bloqueio temporário deve ter sido removido.
                    _ (is (empty? @storage/partes-bloqueadas))
                    permitida (core/enfileirar-operacao-interna! "g" (fn [] :admitida))
                    _ (adventure/recuperar! "g" "p")
                    _ (adventure/aplicar-distancia! "g" "p" evento)
                    h (adventure/historico! "g" "p" (store/mes @now))]
              (is (= :admitida permitida))
              (is (empty? @storage/partes-bloqueadas))
              (is (= 1 (count (treinador/equipe "g" "p")))) (is (= 1 (count h)))))))) done)))

(deftest falha-antes-da-entrega-recupera-uma-vez
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (p/let [_ (abrir "g" "p")]
          (reset! fault {:op :trainer-cas})
          (p/let [_ (-> (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000)) (p/catch (fn [_] nil)))
                  _ (adventure/recuperar! "g" "p")
                  _ (adventure/recuperar! "g" "p")]
            (is (= 1 (count (treinador/equipe "g" "p")))))))) done)))

(deftest falha-no-historico-e-doacao-nao-reconcedem-pokemon
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault db]}]
        (p/let [_ (abrir "g" "p")]
          (reset! fault {:op :history-write})
          (p/let [_ (-> (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000)) (p/catch (fn [_] nil)))
                  _ (is (= 1 (count (treinador/equipe "g" "p"))))
                  _ (swap! treinador/contas assoc-in ["g" "p" "equipe"] [])
                  _ (storage/salvar! "treinador" @treinador/contas)
                  _ (adventure/recuperar! "g" "p")]
            (is (empty? (treinador/equipe "g" "p"))))
          (is (= 1 (count (:history @db))))))) done)))

(deftest snapshot-de-outro-chat-nao-apaga-nascimento
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now after-cas db]}]
        (p/let [_ (abrir "g" "p")]
          (reset! after-cas #(treinador/receber-registro! "outro" "q" {"id-pokemon" "outro" "nome" "Teste" "nivel" 1}))
          (p/let [_ (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000))
                  _ (storage/aguardar-todas!)]
            (is (= 1 (count (get-in (js->clj (js/JSON.parse (get-in @db [:legacy ["treinador" "g"]]))) ["p" "equipe"]))))
            (is (= 1 (count (get-in (js->clj (js/JSON.parse (get-in @db [:legacy ["treinador" "outro"]]))) ["q" "equipe"])))))))) done)))

(deftest flag-e-read-only-falham-sem-gravacoes
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries]}]
        (set! config/adventure-enabled? false)
        (p/let [_ (falha #(adventure/ativar! "g" "p") :disabled)
                _ (set! config/adventure-enabled? true)
                _ (set! config/read-only? true)
                _ (-> (p/then (p/resolved nil) #(adventure/ativar! "g" "p"))
                      (p/catch (fn [erro] (is (str/includes? (.-message erro) "READ_ONLY")))))]
          (is (empty? @queries))))) done)))

(deftest binding-e-inicializacao-interrompidos-nao-duplicam-boas-vindas
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [fault db]}]
        (reset! fault {:op :binding-insert :after? true})
        (p/let [_ (-> (adventure/ativar! "g" "p") (p/catch (fn [_] nil)))
                _ (reset! fault {:op :state-insert :after? true})
                _ (-> (adventure/ativar! "g" "p") (p/catch (fn [_] nil)))
                a (adventure/ativar! "g" "p")
                b (adventure/ativar! "g" "p")]
          (is (= 1 (count (:bindings @db)))) (is (= 1 (count (:states @db))))
          (is (= 1 (count (get-in b [:state :eggs]))))
          (is (= (:operation a) (:operation b)))))) done)))

(deftest reinicializacao-reconcilia-entrega-incerta-antes-da-admissao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault db]}]
        (p/let [_ (abrir "g" "p")]
          (reset! fault {:op :trainer-cas :after? true})
          (p/let [_ (-> (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000)) (p/catch (fn [_] nil)))
                  ;; A resposta da aplicação CAS pode se perder; primeiro
                  ;; comprovar diretamente o snapshot durável antes do restart.
                  raw (get-in @db [:legacy ["treinador" "g"]])
                  _ (is (some? raw) "CAS do treinador deve deixar linha durável")
                  _ (is (= 1 (count (get-in (js->clj (js/JSON.parse raw)) ["p" "equipe"])))
                        "A linha durável deve conter o Pokémon e o recibo")
                  ;; Simula perda dos guards/cache no restart; banco permanece.
                  _ (reset! storage/partes-bloqueadas {})
                  _ (reset! treinador/contas {})
                  ;; O processo real hidrata os módulos do treinador antes de
                  ;; iniciar a reconciliação Adventure.
                  _ (storage/recuperar-treinador! "g")
                  _ (adventure/preparar-inicializacao!)
                  _ (is (not (adventure/disponivel?)))
                  _ (adventure/inicializar!)
                  h (adventure/historico! "g" "p" (store/mes @now))]
            (is (adventure/disponivel?))
            (is (= 1 (count (treinador/equipe "g" "p")))) (is (= 1 (count h))))))) done)))

(deftest bootstrap-paginado-nao-hidrata-contas-inteiras-em-cache
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now queries]}]
        (p/let [_ (reduce (fn [p i] (p/then p #(store/resolver! (str "g" i) "p" true @now)))
                         (p/resolved nil) (range 35))
                _ (adventure/inicializar!)]
          (is (= 2 (count (filter #(= :state-scan (:op %)) @queries))))
          (is (nil? (storage/obter "adventure_account_state")))))) done)))

(deftest metadados-schema-e-barreiras-serial-verificadas
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries now]}]
        (p/let [_ (abrir "g" "p")
                _ (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000))]
          (is (some #(= :schema-read (:op %)) @queries))
          (is (every? #(= storage/serial (get-in % [:options :consistency]))
                      (filter #(contains? #{:state-read :binding-read :trainer-read} (:op %)) @queries)))
          (is (not-any? #(re-find #"(?i)^\s*(CREATE|ALTER|DROP|TRUNCATE)\b|ALLOW FILTERING" (:sql %)) @queries))))) done)))

(deftest encerramento-recusa-novas-operacoes-e-drena-nascimento
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now after-cas]}]
        (p/let [_ (abrir "g" "p")]
          (reset! after-cas #(adventure/parar!))
          (p/let [_ (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000))
                  _ (core/aguardar-operacoes!)
                  _ (storage/aguardar-todas!)
                  _ (falha #(adventure/ativar! "g" "p") :not-ready)]
            (is (= 1 (count (treinador/equipe "g" "p")))))))) done)))

(deftest ttl-e-relogio-recuado-nao-reabrem-distancia-antiga
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now db]}]
        (p/let [_ (abrir "g" "p")]
          (let [evento (fixture/caminhada now "antigo" 800000) original @now]
            (p/let [_ (adventure/aplicar-distancia! "g" "p" evento)
                    _ (swap! now + (* 15 86400000))
                    _ (adventure/recuperar! "g" "p")
                    _ (swap! db assoc :receipts {})
                    _ (reset! now original)
                    _ (falha #(adventure/aplicar-distancia! "g" "p" evento) :window-expired)
                    estado (adventure/consultar! "g" "p")]
              (is (= 800000 (:total-credited-mm estado)))))))) done)))

(deftest bootstrap-read-only-nao-projeta-recuperacao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries]}]
        (p/let [_ (adventure/ativar! "g" "p")
                _ (reset! queries [])
                _ (set! config/read-only? true)
                _ (adventure/inicializar!)]
          (is (adventure/disponivel?))
          (is (not-any? #(not (str/starts-with? (:sql %) "SELECT")) @queries))))) done)))

(deftest encerramento-durante-bootstrap-nao-reabre-admissao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries]}]
        (let [driver @storage/client original (.-execute driver)]
          (aset driver "execute"
                (fn [sql args options]
                  (when (and (str/includes? sql "adventure_account_state")
                             (not (str/includes? sql "WHERE")))
                    (adventure/parar!))
                  (original sql args options)))
          (adventure/preparar-inicializacao!)
          (p/let [_ (falha #(adventure/inicializar!) :stopping)
                  _ (falha #(adventure/ativar! "g" "p") :not-ready)]
            (is (not (adventure/disponivel?)))
            (is (every? #(str/starts-with? (:sql %) "SELECT") @queries)))))) done)))

(deftest schema-incompativel-bloqueia-inicializacao-sem-migracao
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [queries]}]
        (let [driver @storage/client original (.-execute driver)]
          (aset driver "execute"
                (fn [sql args options]
                  (if (str/includes? sql "system_schema.columns")
                    (p/resolved (fixture/result [] false))
                    (original sql args options))))
          (adventure/preparar-inicializacao!)
          (p/let [_ (falha #(adventure/inicializar!) :schema-incompatible)]
            (is (not (adventure/disponivel?)))
            (is (empty? @queries)))))) done)))

(deftest versao-de-payload-incompativel-bloqueia-bootstrap
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [db now]}]
        (p/let [estado (store/resolver! "g" "p" true @now)
                _ (swap! db assoc-in [:states (:account-id estado) :schema_version] 2)
                _ (adventure/preparar-inicializacao!)
                _ (falha #(adventure/inicializar!) :schema-incompatible)]
          (is (not (adventure/disponivel?)))))) done)))

(deftest falha-de-projecao-aguarda-outras-escritas-antes-de-liberar-fila
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [estado (store/resolver! "g" "p" true @now)]
          (let [driver @storage/client original (.-execute driver)
                liberar (atom nil) iniciou (atom false) terminou (atom false)
                esperado (p/create (fn [resolve _] (reset! liberar resolve)))
                recibo (fn [id] {:event-id id :fingerprint "hash" :kind "grant"
                                :day (store/dia @now) :at @now :result {}})]
            (aset driver "execute"
                  (fn [sql args options]
                    (if (and (str/includes? sql "adventure_sync_receipts")
                             (str/starts-with? sql "INSERT"))
                      (if @iniciou
                        esperado
                        (do (reset! iniciou true) (p/rejected (js/Error. "projeção falhou"))))
                      (original sql args options))))
            (let [trabalho (-> (store/projetar! (assoc estado :receipts [(recibo "a") (recibo "b")]) @now)
                               (p/catch (fn [erro] (is (= "projeção falhou" (.-message erro)))))
                               (p/finally #(reset! terminou true)))]
              (p/let [_ (p/create (fn [resolve _] (js/setTimeout resolve 0)))
                      _ (is (not @terminou))
                      _ (@liberar (fixture/result [] true))
                      _ trabalho]
                (is @terminou))))))) done)))

(deftest nascimento-preserva-indices-do-pc-legado-e-pokemon-ativo
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now db]}]
        (p/let [_ (abrir "g" "p")]
          (let [registro #(core/preparar-registro-nascimento (catalog/especie "eevee") %)
                conta {"equipe" [(registro "antigo1") (registro "antigo2")]
                       "pc" [(registro "antigo3")] "ativo" 2}]
            (swap! db assoc-in [:legacy ["treinador" "g"]] (store/json {"p" conta}))
            (p/let [_ (storage/recuperar-treinador! "g")
                    _ (adventure/aplicar-distancia! "g" "p" (fixture/caminhada now "meta" 2000000))]
              (is (= ["antigo1" "antigo2" "antigo3"]
                     (mapv #(get % "id-pokemon") (take 3 (treinador/equipe "g" "p")))))
              (is (= 4 (count (treinador/equipe "g" "p"))))
              (is (= 2 (get-in @treinador/contas ["g" "p" "ativo"])))))))) done)))

(deftest politica-de-recompensa-e-deterministica
  (is (= 10 (adventure/categoria-recompensa 0 {:epico 3 :raro 0 :comum 0})))
  (is (= 5 (adventure/categoria-recompensa 3 {:epico 3 :raro 20 :comum 0})))
  (is (= 2 (adventure/categoria-recompensa 23 {:epico 3 :raro 20 :comum 10})))
  (is (nil? (adventure/categoria-recompensa 40 {:epico 3 :raro 20 :comum 10})))
  (is (= 10 (adventure/categoria-recompensa -4 {:epico 3 :raro 0 :comum 0})))
  (is (nil? (adventure/categoria-recompensa 180 {:epico 0 :raro 0 :comum 10}))))

(deftest atividade-nascimento-pontos-e-replay
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [estado (adventure/atividade-ativar! "activity-g" "p" "welcome-activity")
                ovo (first (:eggs estado))
                _ (adventure/atividade-incubar! "activity-g" "p" "start" (:egg-id ovo))
                nasceu (adventure/atividade-pontos! "activity-g" "p" "points" 100)
                replay (adventure/atividade-pontos! "activity-g" "p" "points" 100)
                tempo (adventure/atividade-avancar-tempo! "activity-g" "p" (+ @now 7200000))]
          (is (= :hatched (:state (first (:eggs nasceu)))))
          (is (= (:species ovo) (get-in nasceu [:eggs 0 :resultado :species])))
          (is (= 1 (count (treinador/equipe "activity-g" "p"))))
          (is (= (:eggs replay) (:eggs tempo)))
          (is (= 1 (get (first (treinador/equipe "activity-g" "p")) "nivel")))))) done)))

(deftest atividade-nascimento-tempo-persistido
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [estado (adventure/atividade-ativar! "time-g" "p" "welcome-time")
                ovo (first (:eggs estado))
                _ (adventure/atividade-incubar! "time-g" "p" "start" (:egg-id ovo))
                nasceu (adventure/atividade-avancar-tempo! "time-g" "p" (+ @now 7200000))
                _ (adventure/atividade-consultar! "time-g" "p")]
          (is (= :hatched (:state (first (:eggs nasceu)))))
          (is (= 1 (count (treinador/equipe "time-g" "p"))))))) done)))

(deftest atividade-colecao-cheia-e-resposta-cas-perdida
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now cap fault]}]
        (p/let [estado (adventure/atividade-ativar! "recover-g" "p" "welcome")
                ovo (first (:eggs estado))
                _ (adventure/atividade-incubar! "recover-g" "p" "start" (:egg-id ovo))
                _ (reset! cap 0)
                pendente (adventure/atividade-pontos! "recover-g" "p" "points" 100)
                _ (is (= :ready-to-hatch (get-in pendente [:eggs 0 :state])))
                _ (is (empty? (treinador/equipe "recover-g" "p")))
                resultado (get-in pendente [:eggs 0 :resultado])
                _ (reset! cap 50)
                _ (reset! fault {:op :trainer-cas :after? true})
                _ (-> (adventure/atividade-consultar! "recover-g" "p") (p/catch (fn [_] nil)))
                recuperado (adventure/recuperar! "recover-g" "p")
                _ (adventure/atividade-consultar! "recover-g" "p")]
          (is (= resultado (get-in recuperado [:eggs 0 :resultado])))
          (is (= :hatched (get-in recuperado [:eggs 0 :state])))
          (is (= 1 (count (treinador/equipe "recover-g" "p"))))))) done)))

(deftest atividade-concorrencia-e-isolamento
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [a (adventure/atividade-ativar! "ga" "p" "welcome")
                b (adventure/atividade-ativar! "gb" "p" "welcome")
                _ (adventure/atividade-incubar! "ga" "p" "start" (:egg-id (first (:eggs a))))
                _ (p/all [(adventure/atividade-pontos! "ga" "p" "points" 100)
                          (adventure/atividade-pontos! "ga" "p" "points" 100)])]
          (is (= 1 (count (treinador/equipe "ga" "p"))))
          (is (empty? (treinador/equipe "gb" "p")))
          (is (= :inventory (get-in b [:eggs 0 :state])))))) done)))

(deftest atividade-recupera-falhas-de-estado-e-reinicio
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault db]}]
        (reduce
         (fn [anterior depois?]
           (p/let [_ anterior
                   cid (str "fault-" depois?)
                   a (adventure/atividade-ativar! cid "p" "welcome")
                   _ (adventure/atividade-incubar! cid "p" "start" (:egg-id (first (:eggs a))))
                   _ (reset! fault {:op :state-cas :after? depois?})
                   _ (falha (fn [] (adventure/atividade-pontos! cid "p" "points" 100)) :result-unknown)
                   _ (adventure/atividade-pontos! cid "p" "points" 100)
                   _ (adventure/preparar-inicializacao!)
                   _ (adventure/inicializar!)
                   estado (adventure/atividade-consultar! cid "p")]
             (is (= :hatched (get-in estado [:eggs 0 :state])))
             (is (= 1 (count (treinador/equipe cid "p"))))))
         (p/resolved nil) [false true]))) done)))

(deftest atividade-preserva-legado-pendente
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (p/let [estado (store/resolver! "legacy-activity" "p" true @now)
                ovo (ovos/novo-ovo "old" 2 "v1" [] "seed" @now)
                legado (store/alterar! (:account-id estado)
                         (fn [a] (-> a (ovos/conceder ovo) (ovos/incubar "old" @now)
                                     (assoc :activity-migration :approval-required))) @now)
                _ (adventure/atividade-ativar! "legacy-activity" "p" "welcome")
                _ (adventure/atividade-consultar! "legacy-activity" "p")
                lido (store/ler! (:account-id estado))]
          (is (= (:eggs legado) (:eggs lido)))
          (is (= (:incubators legado) (:incubators lido)))
          (is (= (:activity-migration legado) (:activity-migration lido)))
          (is (empty? (treinador/equipe "legacy-activity" "p")))))) done)))

(deftest atividade-resultado-fixado-recuperavel
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [fault]}]
        (reduce
         (fn [anterior depois?]
           (p/let [_ anterior cid (str "result-" depois?)
                   a (adventure/atividade-ativar! cid "p" "welcome")
                   especie (:species (first (:eggs a)))
                   _ (adventure/atividade-incubar! cid "p" "start" (:egg-id (first (:eggs a))))
                   _ (reset! fault {:op :state-cas :after? depois?
                                    :match (fn [args] (str/includes? (second args) "\"resultado\""))})
                   _ (falha (fn [] (adventure/atividade-pontos! cid "p" "points" 100)) :result-unknown)
                   _ (is (empty? (treinador/equipe cid "p")))
                   recuperado (adventure/atividade-consultar! cid "p")
                   _ (adventure/atividade-consultar! cid "p")]
             (is (= especie (get-in recuperado [:eggs 0 :resultado :species])))
             (is (= 1 (count (treinador/equipe cid "p"))))
             (is (= 0 (treinador/sequencia-capturas cid "p")))))
         (p/resolved nil) [false true]))) done)))
