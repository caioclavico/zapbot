(ns zapbot.pokemon.raids-save-test
  "Isolamento de participação/recompensas de raide por save (MVP)."
  (:require [pokemon-service.regression-fixture :as fixture]
            [cljs.test :refer-macros [use-fixtures deftest is]]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.raids :as raids]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.armazenamento :as armazenamento]))

(use-fixtures :once {:before fixture/iniciar! :after fixture/parar!})

(def ^:private pokemon
  {"id-pokemon" "pk" "nome" "Lapras" "nivel" 1 "hp" 100 "hp-atual" 100
   "ataque" 100 "defesa" 100 "atq-esp" 100 "def-esp" 100 "veloc" 50
   "tipos" ["agua"] "raridade" "raro"
   "golpes" [{"classe" "fisico" "poder" 100 "slug" "tackle" "nome-exibicao" "Investida"}]})

(defn- raide-aberta [agora]
  {"id" "raid-save-A" "ginasio" "pedra" "nome-ginasio" "Pedra"
   "nivel-ginasio" 10 "chefe" pokemon "fase" "inscricoes" "liga" "iniciante"
   "ordem" [] "participantes" {} "expira" (+ agora (* 45 60 1000))})

(defn- com-raids-save!
  [estado ctx-atom f]
  (let [flags [config/save-transitions-enabled? config/canonical-trainer-enabled?]
        antes-raids @raids/raids
        antes-loja @loja/contas
        antes-treinador @treinador/contas
        antes-salvar armazenamento/salvar!
        antes-ctx armazenamento/contexto-writer-jogador
        antes-selo armazenamento/selo-save-atual
        antes-exec armazenamento/executar-com-selo-save!]
    (set! config/save-transitions-enabled? true)
    (set! config/canonical-trainer-enabled? true)
    (reset! raids/raids estado)
    (reset! loja/contas {})
    (reset! treinador/contas {})
    (set! armazenamento/salvar! (fn [& _] nil))
    (set! armazenamento/contexto-writer-jogador
          (fn [pid] (when ctx-atom (get @ctx-atom pid))))
    (set! armazenamento/selo-save-atual
          (fn [pid]
            (when-let [ctx (get @ctx-atom pid)]
              {"chat" "chat" "player" pid
               "activeSaveId" (:save-id ctx)
               "fenceGeneration" (:fence-generation ctx)
               "transitionOwner" ""
               "identityHash" (str "hash-" pid)})))
    (set! armazenamento/executar-com-selo-save!
          (fn [_cid pid selo acao]
            (let [antes (get @ctx-atom pid)]
              (swap! ctx-atom assoc pid {:save-id (get selo "activeSaveId")
                                        :fence-generation (get selo "fenceGeneration")
                                        :transition-owner ""})
              (try (acao)
                   (finally
                     (if antes (swap! ctx-atom assoc pid antes)
                         (swap! ctx-atom dissoc pid)))))))
    (try
      (f)
      (finally
        (reset! raids/raids antes-raids)
        (reset! loja/contas antes-loja)
        (reset! treinador/contas antes-treinador)
        (set! armazenamento/salvar! antes-salvar)
        (set! armazenamento/contexto-writer-jogador antes-ctx)
        (set! armazenamento/selo-save-atual antes-selo)
        (set! armazenamento/executar-com-selo-save! antes-exec)
        (set! config/save-transitions-enabled? (nth flags 0))
        (set! config/canonical-trainer-enabled? (nth flags 1))))))

(defn- ctx-a []
  {:save-id "save-A" :fence-generation 1 :transition-owner ""})

(defn- ctx-peer []
  {:save-id "peer-A" :fence-generation 1 :transition-owner ""})

(deftest raid-iniciada-em-a-carimba-save
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "misty" (ctx-a)})]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
       (raids/comando! "chat" "misty" "Misty" ["entrar"] pokemon "iniciante" (+ t 1))
       (is (= "save-A" (get-in @raids/raids ["chat" "participantes" "ash" "save-id"])))
       (is (= 1 (get-in @raids/raids ["chat" "participantes" "ash" "fence-generation"])))
       (is (some? (get-in @raids/raids ["chat" "participantes" "ash" "save-seal"])))
       (is (raids/bloqueia-transicao-save? "chat" "ash" (+ t 2)))
       (is (raids/participando? "chat" "ash"))))))

(deftest troca-bloqueada-durante-participacao-sem-abandonar
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "misty" (ctx-a)})]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
       (raids/comando! "chat" "misty" "Misty" ["entrar"] pokemon "iniciante" (+ t 1))
       (raids/comando! "chat" "ash" "Ash" ["iniciar"] nil nil (+ t 2))
       (is (raids/bloqueia-transicao-save? "chat" "ash" (+ t 3)))
       (is (= "combate" (get-in @raids/raids ["chat" "fase"]))
           "raide não é abandonada para liberar troca")
       (is (contains? (get-in @raids/raids ["chat" "participantes"]) "ash"))))))

(deftest callback-antigo-apos-a-b-e-recusado
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "misty" (ctx-a)})]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
       (raids/comando! "chat" "misty" "Misty" ["entrar"] pokemon "iniciante" (+ t 1))
       (raids/comando! "chat" "ash" "Ash" ["iniciar"] nil nil (+ t 2))
       ;; Simula A→B no writer (troca ainda bloqueada pelo gate; callback atrasado).
       (swap! ctx assoc "ash" {:save-id "save-B" :fence-generation 2 :transition-owner ""})
       (let [r (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil (+ t 3))]
         (is (str/includes? (:texto r) "outro save"))
         (is (= "combate" (get-in @raids/raids ["chat" "fase"])))
         (is (= 0 (get-in @raids/raids ["chat" "participantes" "ash" "dano"] 0))))
       (is (nil? (raids/captura-pendente "chat" "ash" (+ t 4))))))))

(deftest troca-apos-encerramento-e-captura-esgotada
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "misty" (ctx-a)})]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
       (raids/comando! "chat" "misty" "Misty" ["entrar"] pokemon "iniciante" (+ t 1))
       (raids/comando! "chat" "ash" "Ash" ["iniciar"] nil nil (+ t 2))
       (swap! raids/raids assoc-in ["chat" "hp-chefe"] 1)
       (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil (+ t 3))
       (is (= "vitoria" (get-in @raids/raids ["chat" "fase"])))
       (is (raids/bloqueia-transicao-save? "chat" "ash" (+ t 4))
           "captura pendente ainda bloqueia")
       (is (some? (raids/captura-pendente "chat" "ash" (+ t 4))))
       (dotimes [_ 3] (raids/registrar-tentativa! "chat" "ash" false))
       (is (nil? (raids/captura-pendente "chat" "ash" (+ t 5))))
       (is (not (raids/bloqueia-transicao-save? "chat" "ash" (+ t 5)))
           "após esgotar tentativas, troca liberada sem limpar a raide")
       (is (= "vitoria" (get-in @raids/raids ["chat" "fase"])))))))

(deftest recompensa-unica-no-save-de-origem
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "misty" (ctx-a)})
        premios (atom [])]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (with-redefs [loja/premiar-raid!
                     (fn [_cid pid dia]
                       (swap! premios conj [pid dia])
                       50)
                     treinador/premiar-progresso-raid!
                     (fn [_ pid id _] {:xp 6 :pe 6 :pid pid :id id})]
         (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
         (raids/comando! "chat" "misty" "Misty" ["entrar"] pokemon "iniciante" (+ t 1))
         (raids/comando! "chat" "ash" "Ash" ["iniciar"] nil nil (+ t 2))
         (swap! raids/raids assoc-in ["chat" "hp-chefe"] 1)
         (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil (+ t 3))
         (is (= [["ash" "raid-save-A"]] @premios))
         ;; Retry do mesmo golpe após vitória não repremia.
         (raids/comando! "chat" "ash" "Ash" ["atacar" "1"] nil nil (+ t 4))
         (is (= 1 (count @premios))))))))

(deftest isolamento-preserva-outro-jogador
  (let [t (.now js/Date)
        ctx (atom {"ash" (ctx-a) "peer" (ctx-peer)})]
    (com-raids-save!
     {"chat" (raide-aberta t)} ctx
     (fn []
       (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
       (raids/comando! "chat" "peer" "Peer" ["entrar"] pokemon "iniciante" (+ t 1))
       (is (= "save-A" (get-in @raids/raids ["chat" "participantes" "ash" "save-id"])))
       (is (= "peer-A" (get-in @raids/raids ["chat" "participantes" "peer" "save-id"])))
       (swap! ctx assoc "ash" {:save-id "save-B" :fence-generation 2 :transition-owner ""})
       (is (nil? (raids/captura-pendente "chat" "ash" (+ t 2))))
       (is (raids/participando? "chat" "peer"))
       (is (= "peer-A" (get-in @raids/raids ["chat" "participantes" "peer" "save-id"]))
           "peer intocado após callback cruzado de ash")))))

(deftest flags-desligadas-mantem-legado-sem-carimbo
  (let [t (.now js/Date)
        flags [config/save-transitions-enabled? config/canonical-trainer-enabled?]
        antes @raids/raids
        antes-salvar armazenamento/salvar!]
    (set! config/save-transitions-enabled? false)
    (set! config/canonical-trainer-enabled? false)
    (reset! raids/raids {"chat" (raide-aberta t)})
    (set! armazenamento/salvar! (fn [& _] nil))
    (try
      (raids/comando! "chat" "ash" "Ash" ["entrar"] pokemon "iniciante" t)
      (is (nil? (get-in @raids/raids ["chat" "participantes" "ash" "save-id"])))
      (is (nil? (get-in @raids/raids ["chat" "participantes" "ash" "save-seal"])))
      (is (raids/participando? "chat" "ash"))
      (finally
        (reset! raids/raids antes)
        (set! armazenamento/salvar! antes-salvar)
        (set! config/save-transitions-enabled? (nth flags 0))
        (set! config/canonical-trainer-enabled? (nth flags 1))))))
