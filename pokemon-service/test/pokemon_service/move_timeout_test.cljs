(ns pokemon-service.move-timeout-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.http :as http]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.raids :as raids]
            [zapbot.armazenamento :as storage]
            [pokemon-service.entry :as entry]))

(def lookup-runtime (js/require "../runtime/move-lookup.cjs"))
(defn move-data [slug tipo poder]
  #js {:name slug :type #js {:name tipo} :power poder
       :damage_class #js {:name "physical"} :names #js [] :stat_changes #js []})
(defn raw-move [slug]
  {:move {:name slug} :version_group_details [{:move_learn_method {:name "level-up"} :level_learned_at 1}]})
(def base {:nome "Teste" :tipos ["normal"] :nivel 1 :hp 100 :ataque 50 :defesa 50
           :atq-esp 50 :def-esp 50 :veloc 50 :imagem nil :moves-brutos [(raw-move "tackle")]})

(defn with-lookups [fetch-fn timeout executar]
  (let [original js/fetch anterior core/consultas-golpes]
    (set! js/fetch fetch-fn)
    (set! core/consultas-golpes (.createMoveLookup lookup-runtime #js {:timeoutMs timeout}))
    (try
      (.finally (js/Promise.resolve (executar))
                (fn [] (set! js/fetch original) (set! core/consultas-golpes anterior)))
      (catch :default erro
        (set! js/fetch original) (set! core/consultas-golpes anterior)
        (js/Promise.reject erro)))))

(deftest golpes-validos-compartilham-http-e-cache-preserva-dados-e-prazo
  (async done
    (let [chamadas (atom 0) prazo (atom nil) fetch-original http/fetch!]
      (set! http/fetch! (fn ([url] (fetch-original url))
                           ([url opcoes] (fetch-original url opcoes))
                           ([url opcoes ms]
                            (reset! prazo ms)
                            (fetch-original url opcoes ms))))
      (-> (with-lookups
            (fn [_ _] (swap! chamadas inc)
              (js/Promise.resolve #js {:ok true :json #(js/Promise.resolve (move-data "tackle" "normal" 40))}))
            1000
            #(p/let [gs (p/all [(core/buscar-golpe "tackle") (core/buscar-golpe "tackle")])
                     cached (core/buscar-golpe "tackle")]
               (is (= 1 @chamadas))
               (is (= 15000 @prazo))
               (is (= (first gs) (second gs) cached))
               (is (= "Investida" (:nome-exibicao cached)))
               (is (= 40 (:poder cached)))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally (fn [] (set! http/fetch! fetch-original) (done)))))))

(deftest falha-parcial-preserva-stab-ordem-e-golpes-validos
  (async done
    (-> (with-lookups
          (fn [url _]
            (let [slug (last (.split url "/"))]
              (js/Promise.resolve
               #js {:ok (not= slug "missing")
                    :json #(if (= slug "broken") (js/Promise.reject (js/Error. "body error"))
                               (js/Promise.resolve (move-data slug (if (= slug "bite") "dark" "normal")
                                                             (if (= slug "bite") 60 40))))})))
          1000
          #(p/let [gs (core/golpes-ordenados (mapv raw-move ["bite" "broken" "tackle" "missing"]) ["normal"] 1)
                   pokemon (core/com-golpes (assoc base :moves-brutos (mapv raw-move ["bite" "tackle"])))]
             (is (= ["tackle" "tackle" "bite"] (mapv :slug gs)))
             (is (= #{"tackle" "bite"} (set (map :slug (:golpes pokemon)))))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest headers-ou-corpo-pendentes-terminam-com-fallback-sem-retry
  (async done
    (let [chamadas (atom 0) sinais (atom [])]
      (-> (with-lookups
            (fn [_ opcoes] (swap! chamadas inc) (swap! sinais conj (.-signal opcoes))
              (js/Promise.resolve #js {:ok true :json #(js/Promise. (fn [& _]))}))
            30
            (fn []
              (p/let [pokemon (core/com-golpes base)]
                (is (= "Investida" (get-in pokemon [:golpes 0 :nome-exibicao])))
                (is (every? #(pos? (:poder %)) (:golpes pokemon)))
                (is (= 0 (get-in (core/filas-pendentes) [:move_requests :pending]))))))
          (.then (fn [_]
                   (is (= 1 @chamadas))
                   (is (every? #(.-aborted ^js %) @sinais))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest requisicao-pendente-nao-aprende-golpe-falso
  (async done
    (-> (with-lookups (fn [& _] (js/Promise. (fn [& _]))) 30
                     #(p/let [golpe (core/buscar-golpe "tackle")]
                        (is (nil? golpe))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest criacao-parcial-persistida-respeita-cooldown-apos-reinicio
  (with-redefs [raids/raids (atom {"c" {"ginasio" "agua" "fase" "inscricoes" "expira" 1 "proxima" 6000}})]
    (is (= 6000 (raids/proxima-aparicao "c" {"proxima" 0 "ultimo-ginasio" "pedra"})))
    ;; A consistent agenda remains authoritative; do not change normal timing.
    (is (= 5000 (raids/proxima-aparicao "c" {"proxima" 5000 "ultimo-ginasio" "agua"})))))

(defn with-raid [fetch-fn prazo executar]
  (let [destinos [core/emitir-evento core/verificando-raides? core/jogos core/cacadas-selvagens
                  raids/raids raids/agendas]
        anteriores (mapv deref destinos)
        buscar core/buscar-pokemon-por-nome imagem core/resposta-cartao-evento
        salvar storage/salvar! aguardar storage/aguardar-todas! encerrar storage/encerrar!
        falha? (atom false) falhar-no-flush (atom nil) flushes (atom 0)
        criacoes (atom 0) envios (atom 0) fechado? (atom false)]
    (set! core/buscar-pokemon-por-nome (fn [_] (p/resolved base)))
    (set! core/resposta-cartao-evento (fn ([_ _ _] (p/resolved {:texto "raid"}))
                                         ([_ _ _ _] (p/resolved {:texto "raid"}))))
    (set! storage/salvar! (fn [modulo _]
                           (when (contains? #{"raids" "raides-agendas"} modulo) (swap! criacoes inc))
                           (p/resolved nil)))
    (set! storage/aguardar-todas!
          (fn []
            (when (= (swap! flushes inc) @falhar-no-flush) (reset! falha? true))
            (if @falha? (p/rejected (js/Error. "persistence outcome uncertain")) (p/resolved nil))))
    (set! storage/encerrar! (fn [] (reset! fechado? true) (p/resolved nil)))
    (doseq [[destino valor] (map vector destinos
                               [(fn [& _] (swap! envios inc) (p/resolved nil)) false {} {} {}
                                {"raid-test" {"proxima" 0}}])]
      (reset! destino valor))
    (-> (with-lookups fetch-fn prazo
          #(executar {:falhar-no-flush falhar-no-flush :falha? falha? :criacoes criacoes
                      :envios envios :fechado? fechado?}))
        (.finally (fn []
                    (doseq [[destino valor] (map vector destinos anteriores)] (reset! destino valor))
                    (set! core/buscar-pokemon-por-nome buscar)
                    (set! core/resposta-cartao-evento imagem)
                    (set! storage/salvar! salvar)
                    (set! storage/aguardar-todas! aguardar)
                    (set! storage/encerrar! encerrar))))))

(deftest desligamento-durante-raide-espera-fallback-filas-e-persistencia
  (async done
    (let [iniciar (atom nil)
          inicio (js/Promise. (fn [resolve _] (reset! iniciar resolve)))]
      (-> (with-raid
            (fn [& _] (@iniciar nil)
              (js/Promise.resolve #js {:ok true :json #(js/Promise. (fn [& _]))}))
            80
            (fn [{:keys [fechado? criacoes envios]}]
              (let [trabalho (core/verificar-raides!) exits (atom []) logs (atom []) timers (atom [])
                    runtime (js/require "../runtime/service.cjs")
                    shutdown (js/require "../runtime/shutdown.cjs")
                    domain #js {:registerModule (fn [& _]) :stopTimers entry/stop-timers
                                :shutdown entry/shutdown :shutdownPending entry/shutdown-pending}
                    service (new (.-PokemonService runtime)
                                 #js {:domain domain :media #js {} :logger (fn [& _])})
                    stop (.createStop shutdown
                           #js {:service service :server #js {:close (fn [])}
                                :exit #(swap! exits conj %)
                                :logger #(swap! logs conj (js->clj (js/JSON.parse %) :keywordize-keys true))
                                :schedule (fn [callback ms] (let [t #js {:fn callback :ms ms :unref (fn [] nil)}]
                                                       (swap! timers conj t) t))
                                :cancel (fn [& _])})]
                (.then inicio
                       (fn [_]
                         (is (= 1 (:game_operations (core/filas-pendentes))))
                         (let [parada (stop "SIGTERM")]
                           (is (false? @fechado?))
                           (is (empty? @exits))
                           (-> (js/Promise.all #js [trabalho parada])
                               (.then (fn [_]
                                        (is (= [0] @exits))
                                        (is (true? @fechado?))
                                        (is (= 2 @criacoes))
                                        (is (= 1 @envios))
                                        (is (= "Investida" (get-in (raids/atual "raid-test") ["chefe" "golpes" 0 "nome-exibicao"])))
                                        (is (= 0 (:game_operations (core/filas-pendentes))))
                                        (is (= 0 (get-in (core/filas-pendentes) [:move_requests :pending])))
                                        (is (some #(and (= "shutdown_stage_completed" (:event %))
                                                        (= "aguardar_operacoes_BANG_" (:stage %))) @logs)))))))))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest persistencia-incerta-nao-anuncia-nem-recria-raide
  (async done
    (-> (with-raid
          (fn [& _] (js/Promise.resolve #js {:ok true :json #(js/Promise.resolve (move-data "tackle" "normal" 40))}))
          1000
          (fn [{:keys [falhar-no-flush criacoes envios]}]
            (reset! falhar-no-flush 2)
            (p/let [_ (core/verificar-raides!)
                    original (get (raids/atual "raid-test") "id")]
              (is (= 2 @criacoes))
              (is (= 0 @envios))
              ;; Simulate another eligible tick, without touching actual storage.
              (swap! raids/raids assoc-in ["raid-test" "expira"] 0)
              (swap! raids/agendas assoc-in ["raid-test" "proxima"] 0)
              (p/let [_ (core/verificar-raides!)]
                (is (= original (get (raids/atual "raid-test") "id")))
                (is (= 2 @criacoes))
                (is (= 0 @envios))))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest guarda-das-raides-nao-libera-antes-de-todas-as-filas-terminarem
  (async done
    (let [original core/enfileirar-jogada anteriores @raids/agendas emissor @core/emitir-evento
          liberar (atom nil) lento (js/Promise. (fn [resolve _] (reset! liberar resolve)))
          chamadas (atom 0)]
      (reset! raids/agendas {"falha" {} "lenta" {}})
      (reset! core/emitir-evento (fn [& _]))
      (set! core/enfileirar-jogada (fn [cid _ & _]
                                   (swap! chamadas inc)
                                   (if (= cid "falha") (js/Promise.reject (js/Error. "test failure")) lento)))
      (let [trabalho (core/verificar-raides!)]
        (js/setImmediate
         (fn []
           (is (true? @core/verificando-raides?))
           (is (nil? (core/verificar-raides!)))
           (is (= 2 @chamadas))
           (@liberar nil)))
        (-> (js/Promise.resolve trabalho)
            (.then (fn [_] (is (false? @core/verificando-raides?))))
            (.catch (fn [erro] (is false (str erro))))
            (.finally (fn []
                        (set! core/enfileirar-jogada original)
                        (reset! raids/agendas anteriores)
                        (reset! core/emitir-evento emissor)
                        (done))))))))
