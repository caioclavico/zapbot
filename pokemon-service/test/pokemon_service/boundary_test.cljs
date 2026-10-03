(ns pokemon-service.boundary-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [pokemon-service.entry :as entry]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.boundary :as boundary]
            [zapbot.pokemon.core :as pokemon]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.pokedex :as pokedex]
            [zapbot.pokemon.loja :as loja]))

(deftest contexto-preserva-identidade-e-nao-hidrata-objetos-do-transporte
  (let [contexto (entry/contexto-neutro
                  #js {:requestId "r1" :chatId "grupo" :playerId "123@lid"
                       :playerName "Ana" :context #js {:mentionedIds #js ["456@c.us"]
                                                       :quotedPlayerId "789@lid"
                                                       :isAdmin true
                                                       :bugTarget #js {:author "b" :text "texto" :source "citada"}}}
                  nil)]
    (is (= "123@lid" (:player-id contexto)))
    (is (= ["456@c.us"] (:mentioned-ids contexto)))
    (is (= "789@lid" (:quoted-player-id contexto)))
    (is (= "Ana" (:player-name contexto)))
    (is (= true (:admin? contexto)))
    (is (= {:autor "b" :corpo "texto" :em nil :origem "citada"} (:bug-target contexto)))
    (is (nil? (:emit! contexto)))))

(deftest anexos-neutros-preservam-bytes-sem-base64
  (async done
    (let [bytes (js/Buffer.from #js [137 80 78 71])
          vistos (atom [])
          contexto {:emit! #(swap! vistos conj %)}]
      (-> (boundary/emitir! contexto (boundary/midia "image/png" bytes "imagem.png") nil
                            #js {:caption "legenda" :mentions #js ["123@lid"]})
          (p/then (fn [_]
                    (let [resposta (first @vistos)
                          js-resposta (clj->js resposta)]
                      (is (= "legenda" (:texto resposta)))
                      (is (= ["123@lid"] (:mentions resposta)))
                      (is (identical? bytes (get-in resposta [:media :buffer])))
                      (is (.isBuffer js/Buffer (.. js-resposta -media -buffer)))
                      (is (nil? (get-in resposta [:media :data]))))
                    (done)))
          (p/catch (fn [erro] (is false (str erro)) (done)))))))

(deftest fachada-preserva-todas-as-familias-de-comandos
  (let [chamadas (atom [])
        contexto {:chat-id "g" :player-id "p"}
        registrar (fn [tipo args] (swap! chamadas conj [tipo args]) (p/resolved nil))]
    (with-redefs [pokemon/jogar (fn [_ args] (registrar :pokemon args))
                  pokedex/buscar (fn [_ args] (registrar :pokedex args))
                  loja/mochila (fn [_ args] (registrar :mochila args))
                  loja/comprar (fn [_ args] (registrar :comprar args))
                  loja/detalhes (fn [args] (registrar :detalhes args))
                  loja/ver-loja-com-imagem (fn [_] (registrar :loja nil))]
      (doseq [comando ["pk treinador" "pokemon tre" "pdx 25" "dex pikachu" "pokedex eevee"
                      "presente @ana" "presentes" "missoes resgatar" "missões"
                      "mochila diario" "loja comprar atadura" "loja detalhe pocao" "loja"]]
        (entry/despachar contexto comando)))
    (is (= [[:pokemon "treinador"] [:pokemon "tre"] [:pokedex "25"]
            [:pokedex "pikachu"] [:pokedex "eevee"] [:pokemon "presente @ana"]
            [:pokemon "presente "] [:pokemon "missoes resgatar"] [:pokemon "missoes "]
            [:mochila "diario"] [:comprar "atadura"] [:detalhes "pocao"] [:loja nil]]
           @chamadas))))

(deftest pendencias-de-remocao-e-troca-sobrevivem-a-serializacao
  (let [salvo (atom nil)
        pendencia {:token "t" :pronto-em 1000 :indice-golpe 1 :nome-golpe "Brasa"
                   :timer #js {}}
        proposta {:a "123@lid" :b "456@c.us" :aceita? true :expira 5000
                  :registro-a {"nome" "Eevee" "hp-atual" 12}}]
    (with-redefs [armazenamento/salvar! (fn [modulo valor] (reset! salvo [modulo valor]))]
      (#'pokemon/persistir-pendencias! "pokemon-remocoes-pendentes" {["g" "123@lid"] pendencia}))
    (is (= {["g" "123@lid"] (dissoc pendencia :timer)}
           (#'pokemon/restaurar-pendencias (second @salvo))))
    (with-redefs [armazenamento/salvar! (fn [modulo valor] (reset! salvo [modulo valor]))]
      (#'pokemon/persistir-pendencias! "pokemon-propostas-troca" {["g" "id"] proposta}))
    (is (= {["g" "id"] proposta}
           (#'pokemon/restaurar-pendencias (second @salvo))))))

(deftest cartao-treinador-produz-png-sem-transporte-ou-rede
  (async done
    (-> (#'pokemon/criar-cartao-treinador "Ana" 1 nil 1)
        (p/then (fn [buffer]
                  (is (.isBuffer js/Buffer buffer))
                  (is (= "89504e470d0a1a0a" (.toString (.subarray buffer 0 8) "hex")))
                  (done)))
        (p/catch (fn [erro] (is false (str erro)) (done))))))

(deftest enfermaria-retoma-por-prazo-persistido-e-nao-duplica-retorno
  (let [antes @treinador/contas
        salvo (atom nil)
        agora-original js/Date.now
        registro {"id-pokemon" "poke-1" "nome" "Pikachu" "hp" 50 "hp-atual" 2
                  "status" "envenenado" "nivel" 12 "xp-desde-nivel" 4}]
    (try
      (reset! treinador/contas {"grupo" {"123@lid" {"equipe" [registro] "ativo" 0}}})
      (set! js/Date.now (fn [] 1000))
      (with-redefs [armazenamento/salvar!
                    (fn [_ valor]
                      (reset! salvo (js->clj (js/JSON.parse (js/JSON.stringify (clj->js valor)))))
                      (p/resolved nil))]
        (treinador/enviar-ferido-para-enfermaria! "grupo" "123@lid" 0)
        (is (= 1801000 (get-in @salvo ["grupo" "123@lid" "enfermaria" 0 "pronto-em"])))
        (reset! treinador/contas {})
        (reset! treinador/contas @salvo)
        (is (empty? (treinador/recolher-curados! "grupo" "123@lid")))
        (set! js/Date.now (fn [] 1801000))
        (is (= 1 (count (treinador/recolher-curados! "grupo" "123@lid"))))
        (is (empty? (treinador/recolher-curados! "grupo" "123@lid")))
        (is (= [(assoc registro "hp-atual" 50 "status" nil)]
               (treinador/equipe "grupo" "123@lid"))))
      (finally
        (set! js/Date.now agora-original)
        (reset! treinador/contas antes)))))
