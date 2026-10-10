(ns zapbot.pokemon.pvp-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [clojure.string :as str]
            [promesa.core :as p]
            [pokemon-service.entry :as entry]
            [pokemon-service.regression-fixture :as fixture]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.ajuda :as ajuda]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.pvp :as pvp]
            [zapbot.pokemon.treinador :as treinador]))

(use-fixtures :once {:before fixture/iniciar! :after fixture/parar!})

(def charizard {:nome "Charizard" :nivel 28 :hp 93 :ataque 84 :defesa 78
                :atq-esp 109 :def-esp 85 :veloc 100 :tipos ["fire" "flying"]
                :golpes [{:nome-exibicao "Brasa" :tipo "fire" :classe :especial :poder 40}]})
(defn contexto [pid & [cid]] {:chat-id (or cid "pvp-test") :player-id pid :player-name pid})
(defn concluir [trabalho done]
  (-> trabalho (p/catch #(is false (str %))) (p/finally done)))

(defn com-pvp [executar]
  (let [ativos (atom {"ash" [charizard 71 :envenenado]
                     "misty" [(assoc charizard :nome "Blastoise" :nivel 29) 86 nil]
                     "brock" [(assoc charizard :nome "Onix" :nivel 28) 80 nil]})
        indices (atom {"ash" 2 "misty" 4 "brock" 1})
        agora (atom 1000000) timers (atom []) avisos (atom []) imagens (atom 0)
        jogos @core/jogos cacadas @core/cacadas-selvagens limites @core/limites-turno emissor @core/emitir-evento
        salvar storage/salvar! ativo treinador/pokemon-ativo indice treinador/indice-ativo
        atuais treinador/golpes-atuais? migrar treinador/migrar-colecao!
        recolher treinador/recolher-curados! corrigir treinador/corrigir-ataques-iniciais!
        anuncio core/enviar-anuncio-batalha nome core/nome-de
        liga treinador/liga-selecionada time-liga treinador/time-liga pronto treinador/time-pronto?
        definir treinador/definir-ativo!
        timeout js/setTimeout clear js/clearTimeout clock js/Date.now]
    (set! storage/salvar! (fn [& _] (p/resolved nil)))
    (set! treinador/pokemon-ativo (fn [_ pid] (get @ativos pid)))
    (set! treinador/indice-ativo (fn [_ pid] (get @indices pid 0)))
    (set! treinador/golpes-atuais? (fn [& _] true))
    (set! treinador/liga-selecionada (fn [& _] (throw (js/Error. "PvP consultou liga"))))
    (set! treinador/time-liga (fn [& _] (throw (js/Error. "PvP consultou escalação"))))
    (set! treinador/time-pronto? (fn [& _] (throw (js/Error. "PvP exigiu escalação"))))
    (set! treinador/definir-ativo! (fn [& _] (throw (js/Error. "PvP substituiu ativo automaticamente"))))
    (set! treinador/migrar-colecao! (fn [& _]))
    (set! treinador/recolher-curados! (fn [& _]))
    (set! treinador/corrigir-ataques-iniciais! (fn [& _]))
    (set! core/enviar-anuncio-batalha (fn [& _] (swap! imagens inc) (p/resolved nil)))
    (set! js/Date.now #(deref agora))
    (set! js/setTimeout (fn [callback ms]
                         (let [timer {:callback callback :ms ms}]
                           (swap! timers conj timer) timer)))
    (set! js/clearTimeout (fn [& _]))
    (reset! core/emitir-evento nil)
    (reset! core/jogos {}) (reset! core/cacadas-selvagens {}) (reset! core/limites-turno {})
    (reset! core/emitir-evento (fn [_ resposta] (swap! avisos conj (:texto resposta)) (p/resolved nil)))
    (-> (p/resolved nil)
        (p/then #(executar {:ativos ativos :indices indices :agora agora :timers timers :avisos avisos :imagens imagens}))
        (p/finally (fn []
                     (reset! core/emitir-evento nil)
                     (reset! core/jogos jogos) (reset! core/cacadas-selvagens cacadas)
                     (reset! core/limites-turno limites) (reset! core/emitir-evento emissor)
                     (set! storage/salvar! salvar) (set! treinador/pokemon-ativo ativo)
                     (set! treinador/indice-ativo indice) (set! treinador/golpes-atuais? atuais)
                     (set! treinador/migrar-colecao! migrar) (set! treinador/recolher-curados! recolher)
                     (set! treinador/corrigir-ataques-iniciais! corrigir)
                     (set! core/enviar-anuncio-batalha anuncio) (set! core/nome-de nome)
                     (set! treinador/liga-selecionada liga) (set! treinador/time-liga time-liga)
                     (set! treinador/time-pronto? pronto) (set! treinador/definir-ativo! definir)
                     (set! js/setTimeout timeout) (set! js/clearTimeout clear) (set! js/Date.now clock))))))

(deftest regras-centralizadas-e-limites-inclusivos
  (let [jogo {:pokemons {:x charizard}}]
    (is (= 3 pvp/diferenca-maxima-niveis))
    (is (= 5 pvp/minutos-espera))
    (is (= {:min 25 :max 31} (pvp/faixa-desafio jogo)))
    (doseq [nivel [25 28 31]] (is (pvp/compativel? jogo {:nivel nivel})))
    (doseq [nivel [24 32 35]] (is (not (pvp/compativel? jogo {:nivel nivel}))))
    (is (= 1 (:min (pvp/faixa-niveis {:nivel 1}))))))

(deftest categorias-de-recompensa-nao-exigem-liga-salva
  (doseq [[nivel bola] [[1 "pokebola"] [25 "pokebola"] [26 "grande-bola"]
                       [60 "grande-bola"] [61 "ultra-bola"] [100 "ultra-bola"]]]
    (is (= bola (pvp/bola-premio {:nivel nivel})))))

(deftest abrir-usa-ativo-real-sem-liga-ou-reservas
  (async done
    (concluir (com-pvp
      (fn [{:keys [agora timers]}]
        (p/let [texto (core/jogar (contexto "ash") "")]
          (let [jogo (get @core/jogos "pvp-test")]
            (is (str/includes? texto "Desafio Pokémon"))
            (is (str/includes? texto "25 a 31"))
            (is (str/includes? texto "5 minutos"))
            (is (= charizard (get-in jogo [:pokemons :x])))
            (is (= 71 (get-in jogo [:hp :x])))
            (is (= :envenenado (get-in jogo [:status :x])))
            (is (= 2 (get-in jogo [:indices-ativos :x])))
            (is (nil? (:liga jogo))) (is (nil? (:reservas jogo)))
            (is (= (+ @agora pvp/tempo-espera-ms) (:desafio-expira-em jogo)))
            (is (= pvp/tempo-espera-ms (:ms (last @timers)))))))) done)))

(deftest aceitar-usa-ativo-sem-normalizar-atributos
  (async done
    (concluir (com-pvp
      (fn [{:keys [ativos imagens]}]
        (p/let [_ (core/jogar (contexto "ash") "")
                texto (core/jogar (contexto "misty") "")]
          (let [jogo (get @core/jogos "pvp-test")]
            (is (str/includes? (core/texto-resposta texto) "Batalha iniciada"))
            (is (= "misty" (get-in jogo [:jogadores :o])))
            (is (= (first (get @ativos "misty")) (get-in jogo [:pokemons :o])))
            (is (= charizard (get-in jogo [:pokemons :x])))
            (is (= 4 (get-in jogo [:indices-ativos :o])))
            (is (nil? (:desafio-expira-em jogo)))
            (is (nil? (:reservas jogo))) (is (= 1 @imagens)))))) done)))

(deftest incompativeis-acima-e-abaixo-preservam-desafio-e-prazo
  (async done
    (concluir (com-pvp
      (fn [{:keys [ativos agora]}]
        (p/let [_ (core/jogar (contexto "ash") "")]
          (let [original (get @core/jogos "pvp-test")]
            (swap! agora + 20000)
            (swap! ativos assoc "misty" [(assoc charizard :nome "Dragonite" :nivel 24) 80 nil])
            (p/let [baixo (core/jogar (contexto "misty") "")
                    _ (is (str/includes? baixo "incompatível"))
                    _ (is (= original (get @core/jogos "pvp-test")))
                    _ (swap! ativos assoc "misty" [(assoc charizard :nome "Dragonite" :nivel 36) 80 nil])
                    alto (core/jogar (contexto "misty") "")]
              (is (str/includes? alto "25 e 31"))
              (is (str/includes? alto "Nível atual: 36"))
              (is (= original (get @core/jogos "pvp-test")))))))) done)))

(deftest desafiante-nao-aceita-e-terceiro-nao-cancela
  (async done
    (concluir (com-pvp
      (fn [_]
        (p/let [_ (core/jogar (contexto "ash") "")]
          (let [original (get @core/jogos "pvp-test")]
            (p/let [proprio (core/jogar (contexto "ash") "")
                    saida (core/sair (contexto "misty"))]
              (is (str/includes? proprio "próprio desafio"))
              (is (str/includes? saida "Só o desafiante"))
              (is (= original (get @core/jogos "pvp-test")))))))) done)))

(deftest sem-ativo-e-desmaiado-nao-abrem-desafio
  (async done
    (concluir (com-pvp
      (fn [{:keys [ativos]}]
        (swap! ativos dissoc "ash")
        (p/let [sem (core/jogar (contexto "ash") "")
                _ (is (str/includes? sem "não tem Pokémon ativo"))
                _ (swap! ativos assoc "ash" [charizard 0 nil])
                caido (core/jogar (contexto "ash") "")]
          (is (str/includes? (core/texto-resposta caido) "desmaiou"))
          (is (empty? @core/jogos))))) done)))

(deftest trocar-ativo-apos-recusa-permite-aceitar-mesmo-desafio
  (async done
    (concluir (com-pvp
      (fn [{:keys [ativos]}]
        (p/let [_ (core/jogar (contexto "ash") "")
                _ (swap! ativos assoc "misty" [(assoc charizard :nivel 36) 80 nil])
                recusada (core/jogar (contexto "misty") "")
                _ (is (str/includes? recusada "incompatível"))
                _ (swap! ativos assoc "misty" [(assoc charizard :nivel 25) 80 nil])
                aceita (core/jogar (contexto "misty") "")]
          (is (str/includes? (core/texto-resposta aceita) "Batalha iniciada"))
          (is (= 25 (get-in @core/jogos ["pvp-test" :pokemons :o :nivel])))))) done)))

(deftest duas-aceitacoes-concorrentes-iniciam-uma-unica-batalha
  (async done
    (concluir (com-pvp
      (fn [{:keys [imagens]}]
        (p/let [_ (core/iniciar-ou-entrar-atualizado (contexto "ash"))
                respostas (p/all [(core/iniciar-ou-entrar-atualizado (contexto "misty"))
                                  (core/iniciar-ou-entrar-atualizado (contexto "brock"))])]
          (is (contains? #{"misty" "brock"} (get-in @core/jogos ["pvp-test" :jogadores :o])))
          (is (= 1 @imagens))
          (is (= 1 (count (filter #(str/includes? (core/texto-resposta %) "Batalha iniciada") respostas))))))) done)))

(deftest duas-entradas-identicas-nao-anunciam-duas-vezes
  (async done
    (concluir (com-pvp
      (fn [{:keys [imagens]}]
        (p/let [_ (core/iniciar-ou-entrar-atualizado (contexto "ash"))
                respostas (p/all [(core/iniciar-ou-entrar-atualizado (contexto "misty"))
                                  (core/iniciar-ou-entrar-atualizado (contexto "misty"))])]
          (is (= 1 @imagens))
          (is (= 1 (count (filter #(str/includes? (core/texto-resposta %) "Batalha iniciada") respostas))))))) done)))

(deftest nocaute-no-pvp-1x1-usa-o-finalizador-existente-sem-substituicao
  (async done
    (concluir (com-pvp
      (fn [_]
        (p/let [_ (core/jogar (contexto "ash") "")
                _ (core/jogar (contexto "misty") "")]
          (let [jogo (assoc-in (get @core/jogos "pvp-test") [:hp :o] 0)
                vencedor (atom nil)]
            (with-redefs [core/sincronizar-equipe! (fn [& _])
                          core/finalizar-vitoria (fn [_ _ atualizado marca _]
                                                   (reset! vencedor [marca atualizado]) "vitória")]
              (is (= "vitória" (core/anunciar-vitoria (contexto "ash") "pvp-test" jogo :x "")))
              (is (= :x (first @vencedor)))
              (is (= 1 (get-in (second @vencedor) [:participacao :x 2])))
              (is (= charizard (get-in (second @vencedor) [:pokemons :x])))))))) done)))

(deftest mesmo-jogador-nao-abre-dois-grupos-concorrentes
  (async done
    (concluir (com-pvp
      (fn [_]
        (p/let [_ (p/all [(core/iniciar-ou-entrar-atualizado (contexto "ash" "grupo-a"))
                         (core/iniciar-ou-entrar-atualizado (contexto "ash" "grupo-b"))])]
          (is (= 1 (count @core/jogos)))))) done)))

(deftest jogador-em-outro-combate-nao-pode-aceitar
  (async done
    (concluir (com-pvp
      (fn [_]
        (p/let [_ (core/jogar (contexto "ash") "")
                _ (core/jogar (contexto "misty" "outro-grupo") "")
                resposta (core/jogar (contexto "misty") "")]
          (is (str/includes? resposta "outro grupo"))
          (is (nil? (get-in @core/jogos ["pvp-test" :jogadores :o])))))) done)))

(deftest expiracao-automatica-notifica-sem-penalizar-e-callback-antigo-nao-afeta-novo
  (async done
    (concluir (com-pvp
      (fn [{:keys [timers agora avisos]}]
        (p/let [_ (core/jogar (contexto "ash") "")]
          (let [timer (last @timers)]
            (swap! agora + pvp/tempo-espera-ms)
            (p/let [_ ((:callback timer))
                    _ (is (empty? @core/jogos))
                    _ (is (= 1 (count @avisos)))
                    _ (is (str/includes? (first @avisos) "expirou após 5"))
                    _ (core/jogar (contexto "brock") "")
                    _ ((:callback timer))]
              (is (= "brock" (get-in @core/jogos ["pvp-test" :jogadores :x])))
              (is (= 1 (count @avisos)))))))) done)))

(deftest prazo-nao-renova-em-consulta-ou-restauracao
  (async done
    (concluir (com-pvp
      (fn [{:keys [timers agora]}]
        (p/let [_ (core/jogar (contexto "ash") "")]
          (let [original (get @core/jogos "pvp-test")
                registros (core/serializar-combates @core/jogos @agora)]
            (swap! agora + 60000)
            (let [restaurado (get (core/restaurar-combates registros 30 @agora) "pvp-test")]
              (is (= (:desafio-expira-em original) (:desafio-expira-em restaurado)))
              (core/agendar-limite-turno! "pvp-test" core/jogos restaurado 30 core/expirar-batalha!)
              (is (= (- pvp/tempo-espera-ms 60000) (:ms (last @timers))))))))) done)))

(deftest entrada-iniciada-antes-do-prazo-nao-aceita-depois-de-expirar
  (async done
    (concluir (com-pvp
      (fn [{:keys [agora]}]
        (p/let [_ (core/iniciar-ou-entrar-atualizado (contexto "ash"))]
          (let [liberar (atom nil) espera (js/Promise. (fn [resolve _] (reset! liberar resolve)))]
            (set! core/nome-de (fn [_] espera))
            (let [entrada (core/iniciar-ou-entrar-atualizado (contexto "misty"))]
              (swap! agora + pvp/tempo-espera-ms)
              (@liberar "misty")
              (p/let [texto entrada]
                (is (str/includes? texto "expirou"))
                (is (nil? (get-in @core/jogos ["pvp-test" :jogadores :o]))))))))) done)))

(deftest legado-aberto-ganha-prazo-original-e-partida-3x3-iniciada-permanece
  (let [aberto {:jogadores {:x "ash"} :pokemons {:x charizard} :liga "prata" :reservas {:x [1 2]}}
        convertido (pvp/restaurar-espera aberto 500)
        iniciado (assoc-in aberto [:jogadores :o] "misty")]
    (is (= (+ 500 pvp/tempo-espera-ms) (:desafio-expira-em convertido)))
    (is (nil? (:liga convertido))) (is (nil? (:reservas convertido)))
    (is (= iniciado (pvp/restaurar-espera iniciado 500)))))

(deftest comandos-legados-nao-selecionam-liga-e-ajuda-descreve-pvp
  (async done
    (concluir (com-pvp
      (fn [_]
        (p/let [texto (core/jogar (contexto "ash") "liga prata")]
          (is (str/includes? texto "Pokémon ativo"))
          (is (str/includes? texto "5 minutos"))
          (is (empty? @core/jogos))
          (is (= (ajuda/resposta "ajuda pvp") (ajuda/resposta "ajuda ligas")))))) done)))

(deftest mensagens-duplicadas-usam-recibo-http-sem-reabrir-ou-reiniciar-batalha
  (async done
    (concluir (com-pvp
      (fn [{:keys [imagens]}]
        (let [runtime (js/require "../runtime/service.cjs") dados (atom {}) chamadas (atom 0)
              dominio #js {:registerModule (fn [k] (swap! dados assoc k {})) :isReady (fn [] true)
                            :load (fn [k] (clj->js (get @dados k {})))
                            :store (fn [k v] (swap! dados assoc k (js->clj v)) (p/resolved nil))
                            :reserve (fn [k id v]
                                       (if (get-in @dados [k id]) (p/resolved false)
                                         (do (swap! dados assoc-in [k id] (js->clj v)) (p/resolved true))))
                            :takeEffects (fn [_] #js [])
                            :command (fn [pedido emitir] (swap! chamadas inc) (entry/command pedido emitir))}
              ^js service (new (.-PokemonService runtime) #js {:domain dominio :media #js {} :logger (fn [& _])})
              abrir #js {:requestId "pvp-open" :chatId "pvp-test" :playerId "ash" :playerName "ash" :command "pokemon"}
              aceitar #js {:requestId "pvp-accept" :chatId "pvp-test" :playerId "misty" :playerName "misty" :command "pk"}]
          (p/let [aberturas (p/all [(.execute service abrir) (.execute service abrir)])
                  entradas (p/all [(.execute service aceitar) (.execute service aceitar)])
                  repetida (.execute service aceitar)]
            (is (= (js->clj (first aberturas)) (js->clj (second aberturas))))
            (is (= (js->clj (first entradas)) (js->clj repetida)))
            (is (= 2 @chamadas)) (is (= 1 @imagens))
            (is (= "misty" (get-in @core/jogos ["pvp-test" :jogadores :o]))))))) done)))
