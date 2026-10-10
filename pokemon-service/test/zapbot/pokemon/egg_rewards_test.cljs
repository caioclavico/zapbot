(ns zapbot.pokemon.egg-rewards-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [pokemon-service.adventure-fixture :as fixture]
            [zapbot.pokemon.ovos-test :refer [concluir falha]]
            [zapbot.pokemon.egg-rewards :as rewards]
            [zapbot.pokemon.adventure :as adventure]
            [zapbot.pokemon.ovos :as ovos]
            [zapbot.pokemon.adventure-catalog :as catalog]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]
            [zapbot.armazenamento :as storage]
            [zapbot.adventure-store :as store]
            [zapbot.config :as config]
            [zapbot.rank :as rank]))

(defn ativar-incubar [cid pid]
  (p/let [a (adventure/atividade-ativar! cid pid "welcome")
          _ (adventure/atividade-incubar! cid pid "start" (:egg-id (first (:eggs a))))]
    a))

(defn publicar [cid pid evento]
  (treinador/registrar-evento-atividade! cid pid evento)
  (p/let [_ (storage/aguardar-todas!)]
    (core/enfileirar-operacao-interna! cid (fn [] (adventure/drenar-eventos! cid)))))

(deftest probabilidades-condicionais-e-configuraveis
  (let [p rewards/probabilidades-padrao]
    (is (= :comum (rewards/categoria :capture [9.99 99] p)))
    (is (nil? (rewards/categoria :capture [10 0] p)))
    (is (= :epico (rewards/categoria :pvp-win [2.99 99] p)))
    (is (= :raro (rewards/categoria :pvp-win [3 19.99] p)))
    (is (nil? (rewards/categoria :pvp-win [3 20] p)))
    (is (nil? (rewards/categoria :battle [0 0] p)))
    (is (= :raro (rewards/categoria :pvp-win [99 99] (assoc p :pvp-rare 100))))
    (is (thrown? js/Error (rewards/validar-probabilidades! (assoc p :capture-common 101))))))

(deftest inicial-uma-vez-mesmo-com-ids-distintos-e-concorrencia
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [_ (p/all [(adventure/atividade-ativar! "g" "p" "a")
                         (adventure/atividade-ativar! "g" "p" "b")])
                _ (adventure/atividade-ativar! "g" "p" "c")
                a (adventure/atividade-consultar! "g" "p")]
          (is (= 1 (count (:eggs a))))
          (is (:welcome-granted? a))))) done)))

(deftest pontos-recompensa-replay-inventario-cheio-e-nao-retroativo
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (set! config/egg-probabilities {:capture-common 100 :pvp-epic 100 :pvp-rare 100})
        (p/let [_ (ativar-incubar "g" "p")
                _ (publicar "g" "p" (rewards/evento "old" :capture (dec @now)))
                _ (publicar "g" "p" (rewards/evento "capture" :capture @now))
                a (adventure/atividade-consultar! "g" "p")
                _ (publicar "g" "p" (rewards/evento "capture" :capture @now))
                b (adventure/atividade-consultar! "g" "p")
                _ (reduce (fn [anterior i]
                            (p/let [_ anterior]
                              (publicar "g" "p" (rewards/evento (str "fill-" i) :capture @now))))
                          (p/resolved nil) (range 12))
                cheio (adventure/atividade-consultar! "g" "p")]
          (is (= 15 (get-in a [:eggs 0 :points])))
          (is (= (:eggs a) (:eggs b)))
          (is (<= (count (remove (fn [o] (= :hatched (:state o))) (:eggs cheio))) 9))
          (is (= 1 (count (treinador/equipe "g" "p"))))
          (is (empty? (treinador/eventos-atividade "g")))))) done)))

(deftest temporarias-tres-usos-bonus-e-limite
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (set! config/egg-probabilities {:capture-common 100 :pvp-epic 0 :pvp-rare 0})
        (p/let [_ (adventure/atividade-ativar! "g" "p" "welcome")
                _ (publicar "g" "p" (rewards/evento "egg-a" :capture @now))
                _ (publicar "g" "p" (rewards/evento "egg-b" :capture @now))
                _ (adventure/conceder-incubadora! "g" "p" "extra" :extra)
                _ (adventure/conceder-incubadora! "g" "p" "extra" :extra)
                _ (adventure/conceder-incubadora! "g" "p" "special" :especial)
                _ (falha (fn [] (adventure/conceder-incubadora! "g" "p" "another" :extra)) :incubator-limit)
                a (adventure/atividade-consultar! "g" "p")
                _ (reduce (fn [anterior e]
                            (p/let [_ anterior]
                              (adventure/atividade-incubar! "g" "p" (str "start-" (:egg-id e)) (:egg-id e))))
                          (p/resolved nil) (:eggs a))
                _ (publicar "g" "p" (rewards/evento "battle" :battle @now))
                b (adventure/atividade-consultar! "g" "p")
                _ (is (= [10 10 12.5] (mapv :points (:eggs b))))
                _ (adventure/atividade-pontos! "g" "p" "finish" 100)
                c (adventure/atividade-consultar! "g" "p")
                replay (adventure/conceder-incubadora! "g" "p" "extra" :extra)]
          (is (= [nil 2 2] (mapv :uses (:incubators replay))))
          (is (= [nil 2 2] (mapv :uses (:incubators c))))
          (is (every? (fn [i] (nil? (:active-egg-id i))) (:incubators c)))
          (is (= 3 (count (treinador/equipe "g" "p"))))
          (is (= 3 (count (:incubators c))))))) done)))

(deftest temporarias-esgotam-sem-reconcessao-por-replay
  (let [a (-> (ovos/estado-atividade-inicial)
              (ovos/adicionar-incubadora-atividade "extra" :extra 3 0))
        final (reduce (fn [a i]
                        (-> a
                            (ovos/conceder-ovo-atividade (ovos/novo-ovo-atividade (str i) :comum 0))
                            (ovos/incubar-atividade (str i) "extra" 0)
                            (ovos/aplicar-pontos-atividade (str "points-" i) 100)
                            (ovos/concluir-atividade (str i) 1))) a (range 3))]
    (is (= [:permanente] (mapv :kind (:incubators final))))
    (is (= 3 (count (filter (fn [o] (= :hatched (:state o))) (:eggs final)))))))

(deftest missao-diaria-concluida-e-nao-resgate-ou-progresso-parcial
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [_ (ativar-incubar "g" "p")
                _ (loja/registrar-missao! "g" "p" "capturas" 1)
                _ (storage/aguardar-todas!)
                _ (is (empty? (loja/eventos-atividade "g")))
                _ (loja/registrar-missao! "g" "p" "capturas" 1)
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "g")
                _ (loja/registrar-missao! "g" "p" "capturas" 1)
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "g")
                a (adventure/atividade-consultar! "g" "p")]
          (is (= 30 (get-in a [:eggs 0 :points])))
          (is (empty? (loja/eventos-atividade "g")))))) done)))

(deftest captura-real-publica-evento-mas-falha-nao-publica
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now]}]
        (set! config/egg-probabilities {:capture-common 100 :pvp-epic 0 :pvp-rare 0})
        (p/let [_ (ativar-incubar "capture-g" "p")
                registro (core/preparar-registro-nascimento (catalog/especie "pidgey") "starter")
                pokemon (first (treinador/registro->pokemon registro))
                _ (swap! treinador/contas assoc-in ["capture-g" "p" "equipe"] [registro])
                _ (treinador/persistir!)
                _ (loja/premiar-bolas! "capture-g" "p" "pokebola" 4)
                caca {:adventure-id "real-capture" :pid "p" :aguardando-captura? true
                      :nome-treinador "Player" :indice-pokemon 0 :chance-base-captura 95
                      :pokemons {:x pokemon :o (dissoc pokemon :id-pokemon)} :hp {:x (:hp pokemon) :o 0}}
                _ (swap! core/cacadas-selvagens assoc "capture-g" caca)
                resposta (with-redefs [cljs.core/rand-int (fn [_] 0)]
                           (core/capturar-selvagem {:chat-id "capture-g" :player-id "p"} ["pokebola"]))
                _ (is (seq (treinador/eventos-atividade "capture-g")))
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "capture-g")
                a (adventure/atividade-consultar! "capture-g" "p")
                _ (swap! core/cacadas-selvagens assoc "capture-g" (assoc caca :adventure-id "failed"))
                _ (with-redefs [cljs.core/rand-int (fn [_] 99)]
                    (core/capturar-selvagem {:chat-id "capture-g" :player-id "p"} ["pokebola"]))]
          (is (= 15 (get-in a [:eggs 0 :points])))
          (is (= 2 (count (:eggs a))))
          (is (empty? (treinador/eventos-atividade "capture-g")))
          (is (= 2 (count (treinador/equipe "capture-g" "p"))))))) done)))

(deftest pvp-real-vitoria-derrota-e-missao
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (set! config/egg-probabilities {:capture-common 0 :pvp-epic 100 :pvp-rare 100})
        (p/let [_ (ativar-incubar "pvp-g" "winner")
                _ (ativar-incubar "pvp-g" "loser")
                registro (core/preparar-registro-nascimento (catalog/especie "pidgey") "starter")
                pokemon (first (treinador/registro->pokemon registro))
                _ (doseq [pid ["winner" "loser"]]
                    (swap! treinador/contas assoc-in ["pvp-g" pid "equipe"] [registro]))
                jogo {:adventure-id "real-pvp" :pvp? true :jogadores {:x "winner" :o "loser"}
                      :nomes {:x "Winner" :o "Loser"} :pokemons {:x pokemon :o pokemon}
                      :hp {:x (:hp pokemon) :o 0} :status {:x nil :o nil}
                      :participacao {:x {0 1} :o {0 0}}}
                _ (with-redefs [rank/pontuar! (fn [& _] nil)]
                    (core/finalizar-vitoria-pvp {:chat-id "pvp-g" :player-id "winner"} "pvp-g" jogo :x ""))
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "pvp-g")
                a (adventure/atividade-consultar! "pvp-g" "winner")
                b (adventure/atividade-consultar! "pvp-g" "loser")]
          (is (= 45 (get-in a [:eggs 0 :points])))
          (is (= 10 (get-in b [:eggs 0 :points])))
          (is (= :epico (get-in a [:eggs 1 :category])))
          (is (= 1 (count (:eggs b))))
          (is (empty? (treinador/eventos-atividade "pvp-g")))
          (is (empty? (loja/eventos-atividade "pvp-g")))))) done)))

(deftest diario-recupera-falha-e-reinicializacao-sem-sorteio-novo
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (set! config/egg-probabilities {:capture-common 100 :pvp-epic 0 :pvp-rare 0})
        (p/let [_ (ativar-incubar "recover-events" "p")
                e (rewards/evento "confirmed-capture" :capture @now)
                _ (treinador/registrar-evento-atividade! "recover-events" "p" e)
                _ (storage/aguardar-todas!)
                _ (reset! fault {:op :state-cas :after? true})
                _ (falha (fn [] (adventure/drenar-eventos! "recover-events")) :result-unknown)
                _ (is (seq (treinador/eventos-atividade "recover-events")))
                _ (adventure/preparar-inicializacao!)
                _ (adventure/inicializar!)
                a (adventure/atividade-consultar! "recover-events" "p")
                _ (publicar "recover-events" "p" e)
                b (adventure/atividade-consultar! "recover-events" "p")]
          (is (= 15 (get-in a [:eggs 0 :points])))
          (is (= (:eggs a) (:eggs b)))
          (is (empty? (treinador/eventos-atividade "recover-events")))))) done)))

(deftest batalha-selvagem-concluida-mas-desistencia-nao-premia
  (async done
    (concluir (fixture/com-banco
      (fn [_]
        (p/let [_ (ativar-incubar "battle-g" "p")
                registro (core/preparar-registro-nascimento (catalog/especie "pidgey") "starter")
                pokemon (first (treinador/registro->pokemon registro))
                _ (swap! treinador/contas assoc-in ["battle-g" "p" "equipe"] [registro])
                caca {:adventure-id "wild-battle" :pid "p" :indice-pokemon 0
                      :pokemons {:x pokemon :o pokemon} :hp {:x (:hp pokemon) :o 0}}
                _ (core/preparar-captura-pos-batalha "battle-g" "p" caca)
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "battle-g")
                a (adventure/atividade-consultar! "battle-g" "p")
                jogo {:adventure-id "cancelled" :jogadores {:x "p"}
                      :hp {:x (:hp pokemon)} :status {:x nil}}
                _ (swap! core/jogos assoc "battle-g" jogo)
                _ (is (core/tentar-encerrar-por-desistencia! "battle-g" jogo))
                _ (storage/aguardar-todas!)
                _ (adventure/drenar-eventos! "battle-g")
                b (adventure/atividade-consultar! "battle-g" "p")]
          (is (= 15 (get-in a [:eggs 0 :points])))
          (is (= (:eggs a) (:eggs b)))))) done)))

(deftest eventos-concorrentes-e-falha-de-persistencia-da-fonte
  (async done
    (concluir (fixture/com-banco
      (fn [{:keys [now fault]}]
        (p/let [_ (ativar-incubar "concurrent" "p")
                e (rewards/evento "battle-result" :battle @now)
                _ (p/all [(publicar "concurrent" "p" e) (publicar "concurrent" "p" e)])
                a (adventure/atividade-consultar! "concurrent" "p")
                _ (is (= 10 (get-in a [:eggs 0 :points])))
                driver @storage/client
                original (.-execute driver)
                _ (aset driver "execute" (fn [sql args options]
                                            (if (= "treinador" (first (js->clj args)))
                                              (p/rejected (js/Error. "Source unavailable"))
                                              (original sql args options))))
                _ (-> (treinador/registrar-evento-atividade! "concurrent" "p" (rewards/evento "failed-source" :capture @now))
                      (p/catch (fn [_] nil)))
                _ (-> (storage/aguardar-todas!) (p/catch (fn [_] nil)))
                _ (-> (adventure/drenar-eventos! "concurrent")
                      (p/then (fn [_] (is false "Fonte não confirmada não pode produzir recompensa")))
                      (p/catch (fn [_] nil)))
                persistido (store/resolver! "concurrent" "p" false @now)
                _ (is (= 10 (get-in persistido [:activity-state :eggs 0 :points])))]
          (is (empty? (treinador/equipe "concurrent" "p")))))) done)))
