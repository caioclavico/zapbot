(ns zapbot.armazenamento-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.loja :as loja]))

(defn- fake-client [executar]
  #js {:execute executar :connect (fn [] (p/resolved nil))
       :shutdown (fn [] (p/resolved nil))
       :getState (fn [] #js {:getConnectedHosts (fn [] #js ["fake"])})})

(deftest le-modulo-com-paginacao-e-sem-leitura-geral
  (async done
    (let [consultas (atom [])
          c (fake-client
             (fn [consulta args ^js opcoes]
               (swap! consultas conj [consulta (js->clj args)])
               (p/resolved
                (if (str/includes? consulta "particionado")
                  (if (.-pageState opcoes)
                    #js {:rows #js [#js {:particao "outro-chat" :valor "{\"xp\":2}"}]}
                    #js {:rows #js [#js {:particao "chat" :valor "{\"xp\":1}"}]
                         :pageState "pagina-2"})
                  #js {:rows #js []}))))]
      (-> (armazenamento/carregar-modulo! c "treinador")
          (p/then (fn [[modulo dados]]
                    (is (= "treinador" modulo))
                    (is (= {"chat" {"xp" 1} "outro-chat" {"xp" 2}} dados))
                    (is (= 3 (count @consultas)))
                    (is (every? #(= ["treinador"] (second %)) @consultas))
                    (is (every? #(str/includes? (first %) " WHERE ") @consultas))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest recusa-legado-pendente-sem-escrever-ou-migrar
  (async done
    (let [consultas (atom [])
          c (fake-client
             (fn [consulta _ _]
               (swap! consultas conj consulta)
               (p/resolved
                (if (str/includes? consulta "particionado")
                  #js {:rows #js []}
                  #js {:rows #js [#js {:valor "{\"chat\":{\"ash\":{\"xp\":5}}}"}]}))))]
      (-> (armazenamento/carregar-modulo! c "treinador")
          (p/then (fn [_] (is false "Legado sem marcador precisa bloquear startup.")))
          (p/catch (fn [erro]
                     (is (str/includes? (.-message erro) "sem marcador"))
                     (is (every? #(str/starts-with? % "SELECT ") @consultas))))
          (p/finally done)))))

(deftest reserva-lwt-confirma-cache-e-recupera-valor-ja-reservado
  (async done
    (let [antes [@armazenamento/client @armazenamento/modulos @armazenamento/cache
                 @armazenamento/confirmados @armazenamento/filas-gravacao]
          chamadas (atom 0)
          c (fake-client
             (fn [consulta _ _]
               (is (str/ends-with? consulta "IF NOT EXISTS"))
               (let [primeira? (= 1 (swap! chamadas inc))]
                 (p/resolved #js {:wasApplied (fn [] primeira?)
                                 :first (fn [] #js {:valor "{\"status\":\"processing\"}"})}))))]
      (reset! armazenamento/client c)
      (reset! armazenamento/modulos #{"pokemon-http-requests"})
      (reset! armazenamento/cache {})
      (reset! armazenamento/confirmados {})
      (reset! armazenamento/filas-gravacao {})
      (-> (p/let [aplicado (armazenamento/reservar! "pokemon-http-requests" "request-1" {"status" "processing"})
                  repetido (armazenamento/reservar! "pokemon-http-requests" "request-1" {"status" "outro"})]
            (is (true? aplicado))
            (is (false? repetido))
            (is (= {"status" "processing"}
                   (get (armazenamento/obter "pokemon-http-requests") "request-1"))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally
           (fn []
             (doseq [[destino valor] (map vector
                                          [armazenamento/client armazenamento/modulos armazenamento/cache
                                           armazenamento/confirmados armazenamento/filas-gravacao] antes)]
               (reset! destino valor))
             (done)))))))

(deftest falha-lwt-nao-confirma-cache
  (async done
    (let [antes [@armazenamento/client @armazenamento/modulos @armazenamento/cache]
          c (fake-client (fn [_ _ _] (p/rejected (js/Error. "timeout incerto"))))]
      (reset! armazenamento/client c)
      (reset! armazenamento/modulos #{"pokemon-http-requests"})
      (reset! armazenamento/cache {})
      (-> (armazenamento/reservar! "pokemon-http-requests" "request-incerto" {"status" "processing"})
          (p/then (fn [_] (is false "Timeout de LWT não é confirmação.")))
          (p/catch (fn [_] (is (nil? (armazenamento/obter "pokemon-http-requests")))))
          (p/finally
           (fn []
             (doseq [[destino valor] (map vector [armazenamento/client armazenamento/modulos armazenamento/cache] antes)]
               (reset! destino valor))
             (done)))))))

(deftest hidratacao-de-watch-nao-grava-no-cassandra
  (async done
    (let [antes @armazenamento/hidratando?]
      (reset! armazenamento/hidratando? true)
      (-> (armazenamento/salvar! "pokemon-batalhas-ativas" {"chat" {"terminal" true}})
          (p/then (fn [_] (is (nil? (armazenamento/obter "pokemon-batalhas-ativas")))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (reset! armazenamento/hidratando? antes) (done)))))))

(deftest snapshot-atrasado-de-ginasio-nao-sobrescreve-atualizacao-pendente
  (let [antigo {"grupo" {"arena" {"pid" "a" "desde" 1}}}
        atual {"grupo" {"arena" {"pid" "b" "desde" 2}}}]
    (is (false? (armazenamento/snapshot-canonical-ginasio-vigente?
                 true "ginasios" atual antigo))
        "callback com snapshot antigo não pode ser promovido a escrita nova")
    (is (true? (armazenamento/snapshot-canonical-ginasio-vigente?
                true "ginasios" atual atual)))
    (is (true? (armazenamento/snapshot-canonical-ginasio-vigente?
                false "ginasios" atual antigo))
        "modo legado continua sem alterar seu fluxo")
    (is (true? (armazenamento/snapshot-canonical-ginasio-vigente?
                true "outro-modulo" atual antigo)))))

(deftest snapshot-atrasado-de-treinador-nao-sobrescreve-save-ativo
  (let [antes {"grupo" {"ash" {"moedas" 10 "equipe" [{"id-pokemon" "a" "hp-atual" 20}]}
                         "misty" {"moedas" 7}}}
        depois {"grupo" {"ash" {"moedas" 10 "equipe" [{"id-pokemon" "a" "hp-atual" 3}]}
                          "misty" {"moedas" 7}}}]
    (is (false? (armazenamento/snapshot-canonico-vigente?
                 true false "treinador" depois antes))
        "callback com HP anterior não pode gravar sobre o save que mudou")
    (is (true? (armazenamento/snapshot-canonico-vigente?
                true false "treinador" depois depois)))
    (is (true? (armazenamento/snapshot-canonico-vigente?
                false false "treinador" depois antes))
        "modo legado permanece sem alterar o comportamento existente")))

(deftest executor-de-transicao-aceita-cas-interno-mas-nao-escrita-direta-de-jogo
  (async done
    (let [antes [@armazenamento/client @armazenamento/pronto]
          chamadas (atom [])
          c (fake-client (fn [sql args opts]
                           (swap! chamadas conj [sql (js->clj args) (js->clj opts)])
                           (p/resolved #js {:rows #js [] :wasApplied (fn [] true)})))
          tabela (str config/cassandra-keyspace ".estado_particionado")]
      (reset! armazenamento/client c)
      (reset! armazenamento/pronto true)
      (-> (p/let [_ (armazenamento/save-transition-executar!
                      (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                      #js ["pokemon-save-transition-journal" "identity"] #js {:consistency "SERIAL"})
                  _ (armazenamento/save-transition-executar!
                      (str "INSERT INTO " tabela " (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS")
                      #js ["pokemon-save-transition-locks" "resource" "{}"] #js {})]
            (is (= 2 (count @chamadas)))
            (is (try
                  (armazenamento/save-transition-executar!
                   (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")
                   #js ["{}" "treinador" "group" "{}"] #js {})
                  false (catch :default _ true)))
            (is (try
                  (armazenamento/save-transition-executar!
                   (str "SELECT valor FROM " tabela " WHERE modulo = ? ALLOW FILTERING")
                   #js ["pokemon-save-transition-journal"] #js {})
                  false (catch :default _ true)))
            nil)
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/client (first antes))
                       (reset! armazenamento/pronto (second antes))
                       (done)))))))

(deftest contexto-canonico-e-capturado-no-adaptador-antes-do-dominio
  (async done
    (let [antes [config/canonical-trainer-enabled? @armazenamento/canonical-trainer-store
                 @armazenamento/confirmados]
          chamadas (atom [])
          store #js {:withWriterContext
                     (fn [cid acao grupo novo? pid]
                       (swap! chamadas conj [:contexto cid (js->clj grupo) novo? pid])
                       (acao))}]
      (set! config/canonical-trainer-enabled? true)
      (reset! armazenamento/canonical-trainer-store store)
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" {"xp" 10}}}})
      (-> (p/then (p/resolved nil)
                  (fn [_] (armazenamento/com-contexto-writer-treinador!
                           "g" #(do (swap! chamadas conj [:dominio]) :ok) true "p")))
          (p/then (fn [resultado]
                    (is (= :ok resultado))
                    (is (= [[:contexto "g" {"p" {"xp" 10}} true "p"] [:dominio]] @chamadas))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! config/canonical-trainer-enabled? (first antes))
                       (reset! armazenamento/canonical-trainer-store (second antes))
                       (reset! armazenamento/confirmados (nth antes 2))
                       (done)))))))

(deftest writer-canonico-nao-faz-fallback-quando-contexto-nao-foi-capturado
  (async done
    (let [antes [config/canonical-trainer-enabled? @armazenamento/canonical-trainer-store]
          consultas (atom [])
          client (fake-client (fn [sql & _]
                                (swap! consultas conj sql)
                                (p/resolved #js {:first (fn [] nil)})))]
      (set! config/canonical-trainer-enabled? true)
      (reset! armazenamento/canonical-trainer-store
              #js {:saveGroup (fn [& _] (p/rejected #js {:code "canonical_trainer_context_required"}))})
      (-> (armazenamento/gravar-treinador-grupo! client "g" {"p" {"xp" 1}} {"p" {"xp" 2}})
          (p/then (fn [_] (is false "Sem contexto a escrita deve ser recusada.")))
          (p/catch (fn [erro]
                     (is (= :canonical_trainer_context_required (:code (ex-data erro))))
                     (is (every? #(str/starts-with? % "SELECT ") @consultas))
                     (is (= 1 (count @consultas)))))
          (p/finally (fn []
                       (set! config/canonical-trainer-enabled? (first antes))
                       (reset! armazenamento/canonical-trainer-store (second antes))
                       (done)))))))

(deftest hidratacao-de-saves-jogaveis-permanece-inativa-por-padrao
  (async done
    (let [antes @armazenamento/confirmados]
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" {"moedas" 10}}}})
      (-> (armazenamento/hidratar-saves-ativos!)
          (p/then (fn [resultado]
                    (is (= {:ready false :disabled true} resultado))
                    (is (= {"treinador" {"g" {"p" {"moedas" 10}}}}
                           @armazenamento/confirmados))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados antes)
                       (done)))))))

(defn- conta-save [id & [extra]]
  (merge {"ativo" 0
          "equipe" [{"id-pokemon" id "shiny" (= id "b") "xp-desde-nivel" (if (= id "b") 8 3)
                     "hp-atual" 30}]
          "inventario" {"pokebola" (if (= id "a") 3 9)}
          "moedas" (if (= id "a") 10 70)
          "adventure-entregas" {"rewardA" "a"}}
         extra))

(defn- fake-ativacao [estado-atom slot-atom & [{:keys [falhar-hidratacao? falhar-activate? bloquear-codigo]}]]
  #js {:activate
       (fn [_cid pid target _op]
         (cond
           bloquear-codigo
           (p/rejected (clj->js {:code "save_transition_activity_blocked"
                                 :blocks [{:code bloquear-codigo}]}))
           falhar-activate?
           (p/rejected #js {:code "playable_save_transition_pending"})
           :else
           (let [idempotente? (= @slot-atom target)]
             (when-not idempotente?
               (reset! slot-atom target)
               (swap! estado-atom assoc pid (get @estado-atom (str "slot:" target))))
             (p/resolved #js {:activeSlotId target :idempotent idempotente?}))))
       :hydrateActive
       (fn [_cid pid]
         (cond
           falhar-hidratacao?
           (p/rejected #js {:code "playable_save_hydration_conflict"})
           (= @slot-atom "pending")
           (p/rejected #js {:code "playable_save_transition_pending"})
           :else
           (p/resolved #js {:account (clj->js (get @estado-atom pid))
                            :activeSlotId @slot-atom
                            :generation 2
                            :fenceGeneration 1
                            :stateHash "a"})))})

(defn- com-binder-loja-noop!
  "Ativação unitária sem Cassandra: vínculo de loja é no-op confirmado."
  [f]
  (armazenamento/configurar-binder-loja-save!
   (fn [_cid _pid _save _fence] (p/resolved {:ready true :durable true :test-stub true})))
  (-> (f)
      (p/finally (fn []
                   (armazenamento/configurar-binder-loja-save! loja/garantir-vinculo-duravel!)))))


(deftest ativacao-com-flag-desligada-nao-altera-atoms
  (async done
    (let [antes [@armazenamento/confirmados config/save-transitions-enabled?
                 @armazenamento/contas-bloqueadas-hidratacao
                 @armazenamento/playable-activation-test]
          grupo {"p" (conta-save "a") "peer" {"moedas" 55 "equipe" []}}]
      (set! config/save-transitions-enabled? false)
      (reset! armazenamento/confirmados {"treinador" {"g" grupo}})
      (reset! armazenamento/contas-bloqueadas-hidratacao {})
      (reset! armazenamento/playable-activation-test
              (fake-ativacao (atom {}) (atom "a")))
      (-> (armazenamento/ativar-save-jogavel! "g" "p" "b" "op1")
          (p/then (fn [resultado]
                    (is (= {:ready false :disabled true} resultado))
                    (is (= grupo (get-in @armazenamento/confirmados ["treinador" "g"])))
                    (is (empty? @armazenamento/contas-bloqueadas-hidratacao))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados (nth antes 0))
                       (set! config/save-transitions-enabled? (nth antes 1))
                       (reset! armazenamento/contas-bloqueadas-hidratacao (nth antes 2))
                       (reset! armazenamento/playable-activation-test (nth antes 3))
                       (done)))))))

(deftest ativacao-a-b-a-hidrata-so-o-jogador-e-preserva-peer
  (async done
    (let [antes [@armazenamento/confirmados config/save-transitions-enabled?
                 @armazenamento/contas-bloqueadas-hidratacao
                 @armazenamento/playable-activation-test
                 @armazenamento/cache @armazenamento/geracoes-partes]
          save-a (conta-save "a")
          save-b (conta-save "b")
          peer {"moedas" 55 "equipe" [{"id-pokemon" "peer-mon"}] "inventario" {"pokebola" 1}}
          estado (atom {"p" save-a "peer" peer "slot:a" save-a "slot:b" save-b})
          slot (atom "a")
          geracao-antes (get @armazenamento/geracoes-partes ["treinador" "g"] 0)]
      (set! config/save-transitions-enabled? true)
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" save-a "peer" peer}}})
      (reset! armazenamento/cache {"treinador" {"g" {"p" save-a "peer" peer}}})
      (reset! armazenamento/contas-bloqueadas-hidratacao {})
      (reset! armazenamento/playable-activation-test (fake-ativacao estado slot))
      (-> (com-binder-loja-noop!
           (fn []
             (p/let [b (armazenamento/ativar-save-jogavel! "g" "p" "b" "act_B")
                     _ (is (= "b" (:activeSlotId b)))
                     _ (is (false? (:idempotent b)))
                     grupo-b (get-in @armazenamento/confirmados ["treinador" "g"])
                     _ (is (= "b" (get-in grupo-b ["p" "equipe" 0 "id-pokemon"])))
                     _ (is (true? (get-in grupo-b ["p" "equipe" 0 "shiny"])))
                     _ (is (= 8 (get-in grupo-b ["p" "equipe" 0 "xp-desde-nivel"])))
                     _ (is (= 9 (get-in grupo-b ["p" "inventario" "pokebola"])))
                     _ (is (= 70 (get-in grupo-b ["p" "moedas"])))
                     _ (is (= {"rewardA" "a"} (get-in grupo-b ["p" "adventure-entregas"])))
                     _ (is (= peer (get grupo-b "peer")) "peer intocado após A→B")
                     ;; Progresso em B antes de voltar.
                     _ (swap! estado assoc "p" (assoc (get @estado "p") "moedas" 99
                                                      "equipe" [(assoc (get-in @estado ["p" "equipe" 0])
                                                                  "xp-desde-nivel" 12)]))
                     _ (swap! estado assoc "slot:b" (get @estado "p"))
                     a (armazenamento/ativar-save-jogavel! "g" "p" "a" "act_A")
                     grupo-a (get-in @armazenamento/confirmados ["treinador" "g"])
                     idem (armazenamento/ativar-save-jogavel! "g" "p" "a" "act_A2")]
               (is (= "a" (:activeSlotId a)))
               (is (= "a" (get-in grupo-a ["p" "equipe" 0 "id-pokemon"])))
               (is (= 10 (get-in grupo-a ["p" "moedas"])))
               (is (= 3 (get-in grupo-a ["p" "equipe" 0 "xp-desde-nivel"])))
               (is (= peer (get grupo-a "peer")) "peer intocado após B→A")
               (is (true? (:idempotent idem)))
               (is (= "a" (get-in @armazenamento/confirmados ["treinador" "g" "p" "equipe" 0 "id-pokemon"])))
               (is (< geracao-antes (get @armazenamento/geracoes-partes ["treinador" "g"] 0))
                   "instalação avança geração da partição para invalidar callbacks antigos")
               (is (false? (armazenamento/snapshot-canonico-vigente?
                            true false "treinador"
                            {"g" {"p" save-b "peer" peer}}
                            (get @armazenamento/confirmados "treinador")))
                   "callback do save B não sobrescreve o atom já hidratado em A"))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados (nth antes 0))
                       (set! config/save-transitions-enabled? (nth antes 1))
                       (reset! armazenamento/contas-bloqueadas-hidratacao (nth antes 2))
                       (reset! armazenamento/playable-activation-test (nth antes 3))
                       (reset! armazenamento/cache (nth antes 4))
                       (reset! armazenamento/geracoes-partes (nth antes 5))
                       (done)))))))

(deftest falha-de-hidratacao-bloqueia-jogador-e-preserva-peer
  (async done
    (let [antes [@armazenamento/confirmados config/save-transitions-enabled?
                 @armazenamento/contas-bloqueadas-hidratacao
                 @armazenamento/playable-activation-test
                 @armazenamento/cache]
          save-a (conta-save "a")
          save-b (conta-save "b")
          peer {"moedas" 55 "equipe" []}
          estado (atom {"p" save-a "peer" peer "slot:a" save-a "slot:b" save-b})
          slot (atom "a")
          chamadas-peer (atom 0)]
      (set! config/save-transitions-enabled? true)
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" save-a "peer" peer}}})
      (reset! armazenamento/cache {"treinador" {"g" {"p" save-a "peer" peer}}})
      (reset! armazenamento/contas-bloqueadas-hidratacao {})
      (reset! armazenamento/playable-activation-test
              (fake-ativacao estado slot {:falhar-hidratacao? true}))
      (-> (armazenamento/ativar-save-jogavel! "g" "p" "b" "act_fail")
          (p/then (fn [_] (is false "hidratação deveria falhar")))
          (p/catch (fn [erro]
                     (is (= :playable-save-hydration-blocked (:code (ex-data erro))))
                     (is (= save-a (get-in @armazenamento/confirmados ["treinador" "g" "p"]))
                         "falha não publica o save B em memória")
                     (is (= peer (get-in @armazenamento/confirmados ["treinador" "g" "peer"])))
                     (is (contains? @armazenamento/contas-bloqueadas-hidratacao ["g" "p"]))
                     (try
                       (armazenamento/com-contexto-writer-treinador!
                        "g" #(do (swap! chamadas-peer inc) :no) false "p")
                       (is false "jogador bloqueado deveria ser recusado")
                       (catch :default e
                         (is (= :playable-save-hydration-blocked (:code (ex-data e))))))
                     ;; Peer continua operável.
                     (is (= :ok (armazenamento/com-contexto-writer-treinador!
                                 "g" (fn [] (swap! chamadas-peer inc) :ok) false "peer")))
                     (is (= 1 @chamadas-peer))
                     ;; Instalação de grupo por peer não publica a conta bloqueada.
                     (armazenamento/restauracao-instalar!
                      "g" (clj->js {"p" save-b "peer" (assoc peer "moedas" 56)}))
                     (is (= save-a (get-in @armazenamento/confirmados ["treinador" "g" "p"])))
                     (is (= 56 (get-in @armazenamento/confirmados ["treinador" "g" "peer" "moedas"])))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados (nth antes 0))
                       (set! config/save-transitions-enabled? (nth antes 1))
                       (reset! armazenamento/contas-bloqueadas-hidratacao (nth antes 2))
                       (reset! armazenamento/playable-activation-test (nth antes 3))
                       (reset! armazenamento/cache (nth antes 4))
                       (done)))))))

(deftest hidratacao-nao-publica-enquanto-transicao-pendente
  (async done
    (let [antes [@armazenamento/confirmados config/save-transitions-enabled?
                 @armazenamento/contas-bloqueadas-hidratacao
                 @armazenamento/playable-activation-test]
          save-a (conta-save "a")
          peer {"moedas" 1}
          estado (atom {"p" save-a "peer" peer})
          slot (atom "pending")]
      (set! config/save-transitions-enabled? true)
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" save-a "peer" peer}}})
      (reset! armazenamento/contas-bloqueadas-hidratacao {})
      (reset! armazenamento/playable-activation-test (fake-ativacao estado slot))
      (-> (armazenamento/hidratar-jogador-ativo! "g" "p")
          (p/then (fn [_] (is false "transição pendente deve recusar hidratação")))
          (p/catch (fn [erro]
                     (is (= :playable-save-hydration-blocked (:code (ex-data erro))))
                     (is (= save-a (get-in @armazenamento/confirmados ["treinador" "g" "p"])))
                     (is (= peer (get-in @armazenamento/confirmados ["treinador" "g" "peer"])))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados (nth antes 0))
                       (set! config/save-transitions-enabled? (nth antes 1))
                       (reset! armazenamento/contas-bloqueadas-hidratacao (nth antes 2))
                       (reset! armazenamento/playable-activation-test (nth antes 3))
                       (done)))))))

(deftest executor-de-transicao-permite-lwt-de-ponteiro-jogavel
  (async done
    (let [antes [@armazenamento/client @armazenamento/pronto]
          chamadas (atom [])
          c (fake-client (fn [sql args opts]
                           (swap! chamadas conj [sql (js->clj args)])
                           (p/resolved #js {:rows #js [] :wasApplied (fn [] true)})))
          tabela (str config/cassandra-keyspace ".estado_particionado")]
      (reset! armazenamento/client c)
      (reset! armazenamento/pronto true)
      (-> (p/let [_ (armazenamento/save-transition-executar!
                      (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")
                      #js ["{}" "pokemon-active-save-pointer" "id" "{}"] #js {})]
            (is (= 1 (count @chamadas)))
            (is (try
                  (armazenamento/save-transition-executar!
                   (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")
                   #js ["{}" "treinador" "group" "{}"] #js {})
                  false (catch :default _ true))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/client (first antes))
                       (reset! armazenamento/pronto (second antes))
                       (done)))))))

(deftest escrita-lateral-recusada-na-reserva-reverte-atom-sem-quarentena
  (async done
    (let [destino (atom {"g" {"antes" true}})
          registro-antes (get @armazenamento/registros "raids")
          antes [config/canonical-trainer-enabled?
                 @armazenamento/canonical-trainer-store
                 @armazenamento/confirmados
                 @armazenamento/cache
                 @armazenamento/falhas-gravacao
                 @armazenamento/client
                 @armazenamento/pronto
                 @armazenamento/filas-gravacao]
          store #js {:assertLateralAdmission
                     (fn [_] (p/rejected #js {:code "canonical_trainer_transition_reserved"}))}]
      (set! config/canonical-trainer-enabled? true)
      (reset! armazenamento/canonical-trainer-store store)
      (reset! armazenamento/pronto false)
      (armazenamento/registrar! "raids" destino identity)
      (reset! armazenamento/pronto true)
      (reset! armazenamento/client
              (fake-client (fn [& _]
                             (is false "recusa definitiva não deve tocar o Cassandra")
                             (p/resolved nil))))
      (reset! armazenamento/confirmados {"raids" {"g" {"antes" true}}})
      (reset! armazenamento/cache {"raids" {"g" {"antes" true}}})
      (reset! armazenamento/falhas-gravacao {})
      (reset! armazenamento/filas-gravacao {})
      (reset! destino {"g" {"depois" true}})
      (-> (armazenamento/salvar! "raids" {"g" {"depois" true}})
          ;; salvar! devolve a promise bruta (rejeitada); a fila interna engole
          ;; a falha, mas o chamador ainda vê a recusa definitiva.
          (p/catch (fn [erro]
                     (is (= "canonical_trainer_transition_reserved" (.-code erro)))
                     (is (= {"g" {"antes" true}} @destino)
                         "atom lateral deve voltar ao confirmado")
                     (is (= {"g" {"antes" true}}
                            (get @armazenamento/cache "raids")))
                     (is (nil? (get @armazenamento/falhas-gravacao "raids"))
                         "recusa de reserva não quarentena o módulo")))
          (p/finally (fn []
                       (set! config/canonical-trainer-enabled? (nth antes 0))
                       (reset! armazenamento/canonical-trainer-store (nth antes 1))
                       (reset! armazenamento/confirmados (nth antes 2))
                       (reset! armazenamento/cache (nth antes 3))
                       (reset! armazenamento/falhas-gravacao (nth antes 4))
                       (reset! armazenamento/client (nth antes 5))
                       (reset! armazenamento/pronto (nth antes 6))
                       (reset! armazenamento/filas-gravacao (nth antes 7))
                       (if registro-antes
                         (swap! armazenamento/registros assoc "raids" registro-antes)
                         (swap! armazenamento/registros dissoc "raids"))
                       (done)))))))

(deftest ativacao-recusa-cada-impedimento-e-preserva-save-ativo
  (async done
    (let [antes [@armazenamento/confirmados config/save-transitions-enabled?
                 @armazenamento/playable-activation-test]
          save-a (conta-save "a")
          peer {"moedas" 55}
          impedimentos ["activity_battle_active" "activity_hunt_active" "activity_raid_active"
                        "activity_raid_capture_pending" "activity_trade_pending"
                        "activity_move_offer_pending" "activity_gym_occupied"
                        "activity_joy_pending" "activity_adventure_egg_pending"
                        "activity_adventure_events_pending" "activity_transition_pending"]
          testar (fn testar [restantes]
                   (if (empty? restantes)
                     (p/resolved (count impedimentos))
                     (let [codigo (first restantes)]
                       (reset! armazenamento/playable-activation-test
                               (fake-ativacao (atom {"p" save-a}) (atom "a")
                                              {:bloquear-codigo codigo}))
                       (p/then
                        (p/catch (armazenamento/ativar-save-jogavel! "g" "p" "b" (str "op-" codigo))
                                 (fn [erro]
                                   (is (= "save_transition_activity_blocked" (.-code erro)))
                                   (is (= save-a (get-in @armazenamento/confirmados ["treinador" "g" "p"])))
                                   (is (= peer (get-in @armazenamento/confirmados ["treinador" "g" "peer"])))
                                   true))
                        (fn [_] (testar (rest restantes)))))))]
      (set! config/save-transitions-enabled? true)
      (reset! armazenamento/confirmados {"treinador" {"g" {"p" save-a "peer" peer}}})
      (-> (testar impedimentos)
          (p/then (fn [n] (is (= (count impedimentos) n))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (reset! armazenamento/confirmados (nth antes 0))
                       (set! config/save-transitions-enabled? (nth antes 1))
                       (reset! armazenamento/playable-activation-test (nth antes 2))
                       (done)))))))

(deftest reserva-de-transicao-recusa-nova-atividade-do-jogador
  (async done
    (let [antes [config/save-transitions-enabled? @armazenamento/save-activity-eligibility
                 @armazenamento/save-transition-coordinator]
          gates (atom 0)
          acoes-peer (atom 0)
          gate #js {:assertActivityMayStart
                    (fn [^js args]
                      (swap! gates inc)
                      (if (= (.-player args) "p")
                        (p/rejected (clj->js {:code "save_transition_activity_reserved"
                                              :operationId "op-lock"}))
                        (p/resolved true)))}]
      (set! config/save-transitions-enabled? true)
      (reset! armazenamento/save-activity-eligibility gate)
      (reset! armazenamento/save-transition-coordinator #js {})
      (-> (p/catch (armazenamento/com-contexto-writer-treinador!
                    "g" #(do :no) true "p")
                   (fn [erro]
                     (is (= "save_transition_activity_reserved" (.-code erro)))
                     (is (= 1 @gates))))
          (p/then (fn [_]
                    (armazenamento/com-contexto-writer-treinador!
                     "g" (fn [] (swap! acoes-peer inc) :ok) true "peer")))
          (p/then (fn [resultado]
                    (is (= :ok resultado))
                    (is (= 2 @gates))
                    (is (= 1 @acoes-peer) "peer não é bloqueado pela reserva do jogador")))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! config/save-transitions-enabled? (nth antes 0))
                       (reset! armazenamento/save-activity-eligibility (nth antes 1))
                       (reset! armazenamento/save-transition-coordinator (nth antes 2))
                       (done)))))))
