(ns zapbot.pokemon.core-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.mundo :as mundo]
            [zapbot.pokemon.treinador :as treinador]
            ["sharp" :as sharp]))

(def pikachu
  {:nome "Pikachu" :tipos ["electric"] :habilidade "static"
   :hp 80 :ataque 70 :defesa 50 :atq-esp 75 :def-esp 60 :veloc 100})

(def geodude
  {:nome "Geodude" :tipos ["rock" "ground"] :habilidade "rock-head"
   :hp 100 :ataque 80 :defesa 100 :atq-esp 30 :def-esp 40 :veloc 20})

(defn jogo-base [x o]
  {:pokemons {:x x :o o}
   :hp {:x (:hp x) :o (:hp o)}
   :status {:x nil :o nil}
   :estagios {:x {} :o {}}
   :defendendo {:x false :o false}})

(deftest efetividade-de-tipos-considera-duplo-tipo-e-habilidade
  (testing "vantagem e resistência"
    (is (= 2 (core/multiplicador-vs-tipos "water" ["fire"] nil)))
    (is (= 0.5 (core/multiplicador-vs-tipos "fire" ["water"] nil))))
  (testing "efeitos de dois tipos são multiplicados"
    (is (= 4 (core/multiplicador-vs-tipos "water" ["rock" "ground"] nil)))
    (is (= 0.25 (core/multiplicador-vs-tipos "fire" ["water" "dragon"] nil))))
  (testing "imunidades de tipo e Levitate anulam o dano"
    (is (zero? (core/multiplicador-vs-tipos "electric" ["ground"] nil)))
    (is (zero? (core/multiplicador-vs-tipos "ground" ["electric"] "levitate")))))

(deftest estagios-alteram-stats-e-respeitam-limites
  (is (= 1.5 (core/multiplicador-estagio 1)))
  (is (= (/ 2 3) (core/multiplicador-estagio -1)))
  (is (= 4 (core/multiplicador-estagio 6)))
  (is (= 0.25 (core/multiplicador-estagio -6)))
  (let [jogo (jogo-base pikachu geodude)
        aumentado (core/aplicar-alteracoes jogo :x [{:atributo :ataque :estagios 10}])
        reduzido (core/aplicar-alteracoes aumentado :x [{:atributo :ataque :estagios -20}])]
    (is (= 6 (get-in aumentado [:estagios :x :ataque])))
    (is (= -6 (get-in reduzido [:estagios :x :ataque])))))

(deftest dano-de-status-e-cura-de-restos
  (is (= 10 (core/dano-por-status :queimado 160)))
  (is (= 20 (core/dano-por-status :envenenado 160)))
  (is (= 1 (core/dano-por-status :queimado 5)))
  (is (zero? (core/dano-por-status :paralisado 160)))
  (let [portador (assoc pikachu :hp 160 :item "restos")
        jogo (assoc-in (jogo-base portador geodude) [:hp :x] 100)
        [curado cura] (core/aplicar-restos jogo :x)]
    (is (= 10 cura))
    (is (= 110 (get-in curado [:hp :x]))))
  (let [portador (assoc pikachu :hp 160 :item "restos")
        jogo (-> (jogo-base portador geodude)
                 (assoc-in [:hp :x] 100)
                 (assoc-in [:status :x] :envenenado))
        [final texto] (core/aplicar-fim-de-turno jogo :x)]
    (is (= 90 (get-in final [:hp :x])))
    (is (str/includes? texto "Restos recuperou 10 HP"))
    (is (str/includes? texto "sofreu 20 de dano"))))

(deftest intimidacao-reduz-o-ataque-correto
  (let [intimidador (assoc pikachu :habilidade "intimidate")
        [resultado mensagem] (core/aplicar-intimidacao (jogo-base intimidador geodude))]
    (is (= 53 (get-in resultado [:pokemons :o :ataque])))
    (is (= (:ataque intimidador) (get-in resultado [:pokemons :x :ataque])))
    (is (str/includes? mensagem "intimidou")))
  (let [x (assoc pikachu :habilidade "intimidate")
        o (assoc geodude :habilidade "intimidate")
        [resultado _] (core/aplicar-intimidacao (jogo-base x o))]
    (is (= 47 (get-in resultado [:pokemons :x :ataque])))
    (is (= 53 (get-in resultado [:pokemons :o :ataque])))))

(deftest habilidades-de-hp-baixo-so-impulsionam-o-tipo-correto
  (let [charizard (assoc pikachu :hp 99 :habilidade "blaze")]
    (is (= 1.5 (core/impulso-habilidade charizard 33 "fire")))
    (is (= 1 (core/impulso-habilidade charizard 34 "fire")))
    (is (= 1 (core/impulso-habilidade charizard 20 "flying")))))

(deftest imunidade-produz-ataque-sem-dano
  (let [jogo (jogo-base pikachu geodude)
        golpe {:nome-exibicao "Choque do Trovão" :tipo "electric"
               :classe :especial :poder 40}
        resultado (core/resolver-ataque jogo golpe :x :o false (:hp pikachu))]
    (is (zero? (:dano resultado)))
    (is (false? (:acertou? resultado)))
    (is (str/includes? (:mensagem resultado) "não teve efeito"))))

(deftest bonus-das-bolas-respeita-o-limite
  (is (= 70 (core/chance-com-bola 70 "pokebola")))
  (is (= 88 (core/chance-com-bola 70 "grande-bola")))
  (is (= 95 (core/chance-com-bola 70 "ultra-bola"))))

(deftest barra-de-hp-nao-exibe-valor-negativo
  (is (= "[█████░░░░░] 50/100" (core/barra-hp 50 100)))
  (is (= "[░░░░░░░░░░] 0/100" (core/barra-hp -10 100))))

(deftest atalhos-pokemon-sao-expandidos
  (testing "atalhos de batalha"
    (is (= "atacar" (core/expandir-atalho "atk")))
    (is (= "defender" (core/expandir-atalho "def")))
    (is (= "curar" (core/expandir-atalho "cur")))
    (is (= "pocao" (core/expandir-atalho "pot")))
    (is (= "pocao-maxima" (core/expandir-atalho "pmax"))))
  (testing "atalhos de navegação e gerenciamento"
    (is (= "ginasio" (core/expandir-atalho "gin")))
    (is (= "cacar" (core/expandir-atalho "cac")))
    (is (= "time" (core/expandir-atalho "tm")))
    (is (= "pokedex" (core/expandir-atalho "dex")))
    (is (= "pokedex" (core/expandir-atalho "pdx"))))
  (testing "comandos completos permanecem inalterados"
    (is (= "atacar" (core/expandir-atalho "atacar")))
    (is (= "raid" (core/expandir-atalho "raid")))))

(deftest identifica-ataques-que-devem-levar-foto-do-ginasio
  (let [ginasio {:ginasio {:id "pedra"}}
        pvp {:jogadores {:x "a" :o "b"}}]
    (is (true? (core/ataque-ginasio? ginasio "atk 1")))
    (is (true? (core/ataque-ginasio? ginasio "atacar 2")))
    (is (false? (core/ataque-ginasio? ginasio "def 1")))
    (is (false? (core/ataque-ginasio? pvp "atk 1")))))

(deftest identifica-imagens-da-cacada-e-fugas
  (let [caca {:pokemons {:x pikachu :o geodude}}]
    (is (true? (core/ataque-cacada? caca "atk 1")))
    (is (true? (core/ataque-cacada? caca "atacar 2")))
    (is (false? (core/ataque-cacada? caca "def")))
    (is (false? (core/ataque-cacada? nil "atk 1"))))
  (is (true? (core/fuga-selvagem-na-resposta? "💨 Pikachu fugiu durante a batalha")))
  (is (true? (core/fuga-selvagem-na-resposta? "Geodude escapou e sua sequência acabou")))
  (is (false? (core/fuga-selvagem-na-resposta? "O selvagem continua aqui"))))

(deftest arena-da-cacada-tem-grama-e-fumaca-apenas-na-fuga
  (let [normal (core/svg-arena-cacada false)
        fuga (core/svg-arena-cacada true)]
    (is (str/includes? normal "id='grama'"))
    (is (not (str/includes? normal "fill-opacity='.92'")))
    (is (str/includes? fuga "id='grama'"))
    (is (str/includes? fuga "fill-opacity='.92'"))))

(deftest arena-da-cacada-muda-o-cenario-conforme-o-bioma
  (doseq [id ["floresta" "praia" "caverna" "cidade" "lago" "vulcao"]]
    (let [svg (core/svg-arena-cacada
               {:bioma {:id id :ceu "#abcdef" :chao "#123456"}} false)]
      (is (str/includes? svg (str "id='cenario-" id "'")) id)))
  (is (not= (core/svg-arena-cacada {:bioma {:id "praia"}} false)
            (core/svg-arena-cacada {:bioma {:id "caverna"}} false)))
  (testing "aceita bioma restaurado, nome completo e palavra-chave"
    (is (= "vulcao" (core/id-cenario-bioma {"nome" "Vulcão Rubro"})))
    (is (= "floresta" (core/id-cenario-bioma :floresta)))
    (is (str/includes?
         (core/svg-arena-cacada {:bioma {"id" "lago"}} false)
         "id='cenario-lago'"))))

(deftest pokebolas-de-captura-tem-cores-e-estados-visuais
  (is (= "#dc2626" (core/cor-bola "pokebola")))
  (is (= "#2563eb" (core/cor-bola "grande-bola")))
    (is (= "#111827" (core/cor-bola "ultra-bola")))
    (let [aberta (core/svg-bola-captura "grande-bola" false false)
        comum (core/svg-bola-captura "pokebola" false false)
        ultra (core/svg-bola-captura "ultra-bola" false false)
        fechada (core/svg-bola-captura "ultra-bola" true false)
        fuga (core/svg-bola-captura "pokebola" false true)]
    (is (str/includes? aberta "fill='#1f2937'"))
    (is (str/includes? comum "cx='380' cy='150' rx='116' ry='82' fill='#dc2626'"))
    (is (str/includes? aberta "transform='translate(0 34)'"))
    (is (str/includes? aberta "cx='380' cy='150' rx='116' ry='82' fill='#2563eb'"))
    (is (str/includes? ultra "cx='380' cy='150' rx='116' ry='82' fill='#111827'"))
    (is (str/includes? aberta "fill='#93c5fd' fill-opacity='.58'"))
    (is (str/includes? aberta "cx='380' cy='118' r='35' fill='#0f172a'"))
    (is (str/includes? aberta "cx='380' cy='118' r='13' fill='#f8fafc'"))
    (is (str/includes? aberta "M350 247A30 24"))
    (is (not (str/includes? aberta "cx='380' cy='248' r='28' fill='#f8fafc'")))
    (is (not (str/includes? aberta "cx='319' cy='146'")))
    (is (not (str/includes? aberta "M360 250A20")))
    (is (not (str/includes? aberta "#fde047")))
    (is (str/includes? fechada "#fde047"))
    (is (not (str/includes? fechada "fill='#050505'")))
    (is (str/includes? fuga "fill-opacity='.92'"))))

(deftest reconhece-comandos-e-resultados-de-captura
  (is (= "pokebola" (core/bola-do-comando-captura "capturar pokebola")))
  (is (= "grande-bola" (core/bola-do-comando-captura "cap grande")))
  (is (= "ultra-bola" (core/bola-do-comando-captura "capturar ultra-bola")))
  (is (nil? (core/bola-do-comando-captura "capturar invalida")))
  (is (true? (core/captura-concluida? "✅ Pokébola lançada: captura concluída!")))
  (is (true? (core/tentativa-captura-realizada? "💥 A Grande Bola falhou, mas continua aqui!")))
  (is (true? (core/tentativa-captura-realizada? "💨 A Pokébola falhou e Pikachu fugiu!")))
  (is (false? (core/tentativa-captura-realizada? "🎒 Você não tem essa bola."))))

(deftest fuga-da-captura-mostra-bola-aberta-com-fumaca
    (let [quadro (core/svg-bola-captura "pokebola" false true)]
    (is (str/includes? quadro "fill='#1f2937'"))
    (is (str/includes? quadro "fill-opacity='.92'"))
    (is (not (str/includes? quadro "cy='82' rx='92'")))
    (is (str/includes? quadro "cx='380' cy='118' r='13' fill='#f8fafc'"))
    (is (str/includes? quadro "M350 247A30 24"))
    (is (not (str/includes? quadro "fill='#f97316'")))
    (is (not (str/includes? quadro "M360 250A20")))
    (is (str/includes? quadro "cx='381' cy='50'"))
    (is (str/includes? quadro "M380 22C364 8"))
    (is (not (str/includes? quadro "<image")))))

(deftest permanencia-de-ginasio-formata-tempo-e-xp
  (is (= "59s" (ginasios/formatar-duracao 59000)))
  (is (= "1m 1s" (ginasios/formatar-duracao 61000)))
  (is (= "1h 0m" (ginasios/formatar-duracao (* 60 60 1000))))
  (let [agora (* 3 60 60 1000)
        ocupacao {"desde" 0}]
    (is (= 6 (ginasios/xp-permanencia ocupacao agora))))
  (is (= 24 (ginasios/xp-permanencia {"desde" 0} (* 30 60 60 1000)))))

(deftest xp-grande-processa-varios-niveis-e-preserva-desmaio
  (let [registro (assoc (treinador/pokemon->registro pikachu 0 nil)
                        "nivel" 4 "xp-desde-nivel" 0)
        estado (atom {"chat" {"ash" {"equipe" [registro]}}})]
    (with-redefs [treinador/contas estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))]
      (let [subida (treinador/ganhar-xp-no-indice! "chat" "ash" 0 24)
            atualizado (first (treinador/equipe "chat" "ash"))]
        (is (= 6 (:nivel subida)))
        (is (= 2 (:niveis-subidos subida)))
        (is (= 6 (get atualizado "xp-desde-nivel")))
        (is (= 0 (get atualizado "hp-atual")))
        (is (= {:atual 6 :necessario 9} (treinador/progresso-xp atualizado)))))))

(deftest progresso-legado-acima-de-nove-e-corrigido-ao-carregar
  (let [registro (assoc (treinador/pokemon->registro pikachu 0 nil)
                        "nivel" 5 "xp-desde-nivel" 15)
        contas (treinador/normalizar-xp-contas
                {"chat" {"ash" {"equipe" [registro]}}})
        corrigido (get-in contas ["chat" "ash" "equipe" 0])]
    (is (= 6 (get corrigido "nivel")))
    (is (= 6 (get corrigido "xp-desde-nivel")))
    (is (= 0 (get corrigido "hp-atual")))))

(deftest defensor-volta-do-ginasio-desmaiado
  (let [registro (treinador/pokemon->registro pikachu (:hp pikachu) :veneno)
        derrotado (ginasios/registro-apos-derrota registro)]
    (is (= 0 (get derrotado "hp-atual")))
    (is (nil? (get derrotado "status")))
    (is (= (get registro "hp") (get derrotado "hp")))))

(deftest favorito-volta-da-joy-saudavel-e-fica-ativo
  (let [primeiro (treinador/pokemon->registro pikachu (:hp pikachu) nil)
        favorito (treinador/pokemon->registro geodude 20 nil)
        estado (atom {"chat" {"ash" {"equipe" [primeiro favorito] "ativo" 0
                                      "enfermaria" []}}})]
    (with-redefs [treinador/contas estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))]
      (treinador/definir-favorito! "chat" "ash" 1)
      (is (= 1 (treinador/indice-ativo "chat" "ash")))
      (treinador/enviar-ferido-para-enfermaria! "chat" "ash" 1)
      (swap! estado assoc-in ["chat" "ash" "enfermaria" 0 "pronto-em"] 0)
      (treinador/recolher-curados! "chat" "ash")
      (is (= "Geodude" (get-in @estado ["chat" "ash" "favorito" "nome"])))
      (is (= 1 (treinador/indice-ativo "chat" "ash")))
      (is (= (:hp geodude) (get-in @estado ["chat" "ash" "equipe" 1 "hp-atual"]))))))

(deftest escalacao-nomeada-usa-identidades-sem-reservar-pokemons
  (let [terceiro (assoc pikachu :nome "Raichu")
        registros [(treinador/pokemon->registro pikachu 80 nil)
                   (treinador/pokemon->registro geodude 100 nil)
                   (treinador/pokemon->registro terceiro 80 nil)]
        estado (atom {"chat" {"ash" {"equipe" registros}}})]
    (with-redefs [treinador/contas estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))]
      (is (= 3 (count (treinador/salvar-time-pronto! "chat" "ash" "os fodoes" [0 1 2]))))
      (is (= 3 (count (treinador/equipe "chat" "ash"))))
      (is (= [0 1 2] (treinador/indices-time-pronto "chat" "ash" "os fodoes")))
      (treinador/remover-pokemon! "chat" "ash" 0)
      (is (= [nil 0 1] (treinador/indices-time-pronto "chat" "ash" "os fodoes"))))))

(deftest tentativa-de-ginasio-recompensa-participacao-e-nocautes
  (is (= 3 (core/xp-ginasio-participante false nil 0)))
  (is (= 5 (core/xp-ginasio-participante false nil 2)))
  (is (= 7 (core/xp-ginasio-participante true :primeira 0)))
  (is (= 9 (core/xp-ginasio-participante true :primeira 2)))
  (is (= 5 (core/xp-ginasio-participante true :revanche 1)))
  (is (= 3 (core/xp-ginasio-participante true nil 1))))

(deftest pocao-sem-numero-usa-ativo-e-com-numero-cura-o-escolhido
  (let [ferido (treinador/pokemon->registro pikachu 20 nil)
        outro (treinador/pokemon->registro geodude 30 nil)
        estado (atom {"chat" {"ash" {"equipe" [ferido outro] "ativo" 0}}})]
    (with-redefs [treinador/contas estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))
                  loja/usar-pocao! (fn
                                     ([_ _] 0.4)
                                     ([_ _ _] 0.4))]
      (is (= 0 (core/indice-alvo-pocao "chat" "ash" nil)))
      (is (= 1 (core/indice-alvo-pocao "chat" "ash" "2")))
      (is (str/includes? (core/pocao-fora-de-batalha "chat" "ash" "pocao" "2")
                         "Geodude"))
      (is (= 20 (get-in @estado ["chat" "ash" "equipe" 0 "hp-atual"])))
      (is (> (get-in @estado ["chat" "ash" "equipe" 1 "hp-atual"]) 30)))))

(deftest motivacao-do-ginasio-cai-com-o-tempo-e-enfraquece-defensores
  (let [registro (treinador/pokemon->registro pikachu (:hp pikachu) nil)
        ocupacao {"time" [registro registro registro]
                  "desde" 0
                  "motivacao" [100 70 25]
                  "motivacao-em" 0}
        tres-horas (* 3 60 60 1000)
        trinta-horas (* 30 60 60 1000)
        time-fraco (ginasios/time-defensor ocupacao trinta-horas)]
    (is (= [85 55 20] (ginasios/motivacoes ocupacao tres-horas)))
    (is (= [20 20 20] (ginasios/motivacoes ocupacao trinta-horas)))
    (is (= 20 (:motivacao-ginasio (first time-fraco))))
    (is (= (js/Math.round (* 0.6 (:hp pikachu))) (:hp (first time-fraco))))
    (is (= (js/Math.round (* 0.6 (:ataque pikachu))) (:ataque (first time-fraco))))))

(deftest pocao-recupera-motivacao-e-defesa-vencida-desgasta-o-time
  (let [registro (treinador/pokemon->registro pikachu (:hp pikachu) nil)
        ocupacao {"pid" "lider" "nome" "Ash" "time" [registro registro registro]
                  "desde" 0 "motivacao" [70 100 100] "motivacao-em" 0}
        estado (atom {"chat" {"pedra" ocupacao}})
        consumidas (atom 0)
        agora (* 2 60 60 1000)]
    (with-redefs [ginasios/ocupacoes estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))
                  loja/usar-pocao! (fn
                                     ([_ _] (swap! consumidas inc) 0.4)
                                     ([_ _ _] (swap! consumidas inc) 0.4))]
      (let [invalida (ginasios/usar-pocao! "chat" "lider" "pedra" nil agora)
            resultado (ginasios/usar-pocao! "chat" "lider" "pedra" 0 agora)]
        (is (= :indice-invalido (:status invalida)))
        (is (= :ok (:status resultado)))
        (is (= 60 (:antes resultado)))
        (is (= 100 (:depois resultado)))
        (is (= 1 @consumidas))))
    (let [estado-defesa (atom {"chat" {"pedra" ocupacao}})]
      (with-redefs [ginasios/ocupacoes estado-defesa
                    armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))]
        (is (= [48 78 78]
               (ginasios/desgastar-defesa! "chat" "pedra" ocupacao agora)))))))

(deftest fruta-recupera-motivacao-do-defensor
  (let [registro (treinador/pokemon->registro pikachu (:hp pikachu) nil)
        ocupacao {"pid" "lider" "time" [registro registro registro]
                  "desde" 0 "motivacao" [30 100 100] "motivacao-em" 0}
        estado (atom {"chat" {"pedra" ocupacao}})]
    (with-redefs [ginasios/ocupacoes estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))
                  loja/usar-fruta! (fn [_ _ item] (if (= item "fruta-dourada") 100 20))]
      (let [comum (ginasios/usar-fruta! "chat" "lider" "pedra" 0 0 "fruta")
            dourada (ginasios/usar-fruta! "chat" "lider" "pedra" 0 0 "fruta-dourada")]
        (is (= 50 (:depois comum)))
        (is (= 100 (:depois dourada)))))))

(deftest clima-e-areas-possuem-rotacao-diaria-estavel
  (let [dia "2026-09-17"
        areas (mundo/areas-do-dia dia)
        indisponiveis (mundo/areas-indisponiveis dia)]
    (is (= 3 (count areas)))
    (is (= 3 (count indisponiveis)))
    (is (empty? (set/intersection (set (map :id areas))
                                  (set (map :id indisponiveis)))))
    (is (= areas (mundo/areas-do-dia dia)))
    (is (= (mundo/clima-do-dia dia) (mundo/clima-do-dia dia)))
    (is (every? seq (map :tipos areas)))
    (is (= "praia" (:id (mundo/obter-area "Praia"))))))

(deftest amizade-titulos-e-descobertas-sao-persistentes
  (let [registro (treinador/pokemon->registro pikachu (:hp pikachu) nil)
        estado (atom {"chat" {"ash" {"equipe" [registro]
                                      "vitorias-treinador" 1 "pokedex" {}}}})]
    (with-redefs [treinador/contas estado
                  armazenamento/salvar! (fn [& _] (js/Promise.resolve nil))]
      (is (= 72 (treinador/ganhar-amizade! "chat" "ash" 0 2)))
      (is (= :ok (:status (treinador/selecionar-titulo! "chat" "ash" 1))))
      (treinador/registrar-avistamento! "chat" "ash" pikachu)
      (let [resumo (treinador/resumo-descobertas "chat" "ash")]
        (is (= 1 (:vistos resumo)))
        (is (= 1 (:avistamentos resumo)))))))

(deftest imagens-estaticas-de-captura-sao-png
  (async done
    (-> (sharp (js/Buffer.from (core/svg-bola-captura "ultra-bola" true false)))
        (.png)
        (.toBuffer)
        (.then (fn [buffer] (.metadata (sharp buffer))))
        (.then (fn [metadados]
                 (is (= "png" (.-format metadados)))
                 (is (= 760 (.-width metadados)))
                 (is (= 400 (.-height metadados)))
                 (done)))
        (.catch (fn [erro]
                  (is false (str "Não conseguiu gerar a imagem de captura: " erro))
                  (done))))))

(deftest menu-de-captura-centraliza-apenas-o-selvagem-derrotado
  (let [batalha (core/layout-imagem-cacada {} false)
        captura (core/layout-imagem-cacada {:aguardando-captura? true} false)]
    (is (true? (:mostrar-meu? batalha)))
    (is (= 165 (:centro-meu batalha)))
    (is (= 595 (:centro-selvagem batalha)))
    (is (false? (:mostrar-meu? captura)))
    (is (= 380 (:centro-selvagem captura)))))

(deftest efeitos-visuais-cobrem-golpes-status-shiny-e-substituicao
  (let [efeitos (core/svg-sobreposicao-batalha
                 "causou queimadura, paralisia, envenenamento, congelamento, dormiu, confusão e entrou na batalha"
                 true {:tipo "fire" :classe :especial})]
    (is (str/includes? efeitos "#f97316"))
    (is (str/includes? efeitos "#fde047"))
    (is (str/includes? efeitos ">Z</text>"))
    (is (str/includes? efeitos "#f472b6"))
    (is (str/includes? efeitos "translate(585 250)"))
    (is (str/includes? efeitos "M555 60")))
  (testing "a substituição do ginásio não desenha Pokébola sobre o defensor"
    (let [com-bola (core/svg-sobreposicao-batalha
                    "Onix entrou na batalha" false nil true)
          sem-bola (core/svg-sobreposicao-batalha
                    "Onix entrou na batalha" false nil false)]
      (is (str/includes? com-bola "translate(585 250)"))
      (is (not (str/includes? sem-bola "translate(585 250)")))))
  (testing "o raio do cabeçalho não cria efeito elétrico"
    (let [inicio (core/svg-sobreposicao-batalha "⚡ *Pokémon* selvagem apareceu" false nil)]
      (is (not (str/includes? inicio "M405 75")))
      (is (not (str/includes? inicio "M380 125")))))
  (testing "o tipo real seleciona um efeito diferente"
    (let [raio (core/svg-sobreposicao-batalha "golpe" false {:tipo "electric"})
          fogo (core/svg-sobreposicao-batalha "golpe" false {:tipo "fire"})]
      (is (str/includes? raio "M405 75"))
      (is (str/includes? raio "fill='#fde047'"))
      (is (str/includes? fogo "fill='#f97316'"))
      (is (str/includes? fogo "fill='#fde047'"))
      (is (not (str/includes? fogo "fill-opacity='.82'"))))
    (is (str/includes? (core/svg-sobreposicao-batalha "golpe" false {:tipo "water"}) "M380 80"))
    (is (str/includes? (core/svg-sobreposicao-batalha "golpe" false {:tipo "grass"}) "<ellipse"))
    (let [psiquico (core/svg-sobreposicao-batalha "golpe" false {:tipo "psychic"})]
      (is (str/includes? psiquico "#e879f9"))
      (is (str/includes? psiquico "M380 210C380 178")))))

(deftest golpes-da-rodada-ficam-lado-a-lado-e-escalam-com-dano
  (let [fraco (core/svg-golpe-posicionado {:tipo "fire" :origem :x :dano 10})
        forte (core/svg-golpe-posicionado {:tipo "fire" :origem :x :dano 90})
        lider (core/svg-golpe-posicionado {:tipo "water" :origem :o :dano 40})
        rodada (core/svg-sobreposicao-batalha
                "rodada" false [{:tipo "fire" :origem :x :dano 20}
                                 {:tipo "water" :origem :o :dano 60}])]
    (is (< (core/escala-visual-dano 10) (core/escala-visual-dano 90)))
    (is (> (core/escala-visual-dano 0) 0.28))
    (is (str/includes? fraco "data-centro='328'"))
    (is (str/includes? lider "data-centro='432'"))
    (is (not= fraco forte))
    (is (not= fraco lider))
    (is (str/includes? rodada "#f97316"))
    (is (str/includes? rodada "#38bdf8"))))

(deftest rodada-do-ginasio-remove-estado-intermediario
  (is (= "Ataque do treinador"
         (core/sem-estado-intermediario "Ataque do treinador\n\n🐾 Ash - Pikachu")))
  (is (= 37 (core/dano-da-resposta "causou 37 de dano em Onix!")))
  (testing "respostas estruturadas aninhadas nunca viram [object Object]"
    (is (= "Ataque resolvido" (core/texto-resposta {:texto {:texto "Ataque resolvido"}})))
    (is (= "Ataque do líder"
           (core/texto-resposta #js {:texto #js {:texto "Ataque do líder"}})))))

(deftest vez-automatica-do-lider-nao-expoe-golpes-do-npc
  (let [golpe-secreto {:nome-exibicao "Golpe secreto do NPC" :tipo "rock" :classe :fisico}
        jogo (assoc (jogo-base (assoc pikachu :golpes [golpe-secreto])
                               (assoc geodude :golpes [golpe-secreto]))
                    :nomes {:x "Ash" :o "Brock"}
                    :jogadores {:x "551199999999" :o "lider-ginasio"}
                    :vez :o
                    :ginasio {:id "pedra"})
        texto (core/mensagem-estado jogo)]
    (is (str/includes? texto "Vez do líder"))
    (is (str/includes? texto "ele responderá automaticamente"))
    (is (not (str/includes? texto "Golpe secreto do NPC")))
    (is (not (str/includes? texto "escolha um golpe")))))

(deftest efeito-visual-usa-o-golpe-escolhido
  (let [golpes [{:nome-exibicao "Choque" :tipo "electric" :classe :especial}
                 {:nome-exibicao "Folha" :tipo "grass" :classe :fisico}]
        jogo {:vez :x :pokemons {:x (assoc pikachu :golpes golpes)}}]
    (is (= {:nome-exibicao "Choque" :tipo "electric" :classe :especial}
           (core/golpe-do-comando jogo :x "atk 1")))
    (is (= "grass" (:tipo (core/golpe-do-comando jogo :x "atacar 2"))))
    (is (nil? (core/golpe-do-comando jogo :x "def")))))

(deftest tamanho-visual-respeita-a-altura-da-especie
  (let [inseto (core/tamanho-visual-pokemon {:altura 0.3} 260)
        medio  (core/tamanho-visual-pokemon {:altura 1.0} 260)
        grande (core/tamanho-visual-pokemon {:altura 2.1} 260)
        gigante (core/tamanho-visual-pokemon {:altura 8.8} 260)]
    (is (< inseto medio grande gigante))
    (is (<= 130 inseto))
    (is (<= gigante 260))
    (is (> (core/tamanho-visual-pokemon {:altura 1.0} core/tamanho-sprite-cacada)
           medio))))

(deftest classifica-cartoes-dos-eventos-pokemon
  (is (= :nivel (core/tema-evento-da-resposta "" "Pikachu subiu para o nível 12")))
  (is (= :desmaio (core/tema-evento-da-resposta "" "Seu Pokémon desmaiou")))
  (is (= :entrada (core/tema-evento-da-resposta "" "Treinador envia *Eevee*")))
  (is (= :insignia (core/tema-evento-da-resposta "atk 1" "Você venceu o ginásio Pedra")))
  (is (= :raid (core/tema-evento-da-resposta "raid atacar 1" "HP do chefe: 200/440")))
  (is (= :joy (core/tema-evento-da-resposta "joy 1" "A Enfermeira Joy recebeu *Pikachu*")))
  (is (= :joy-tratando (core/tema-evento-da-resposta
                         "joy" "Escolha quem a Enfermeira Joy deve atender")))
  (is (= :hospital (core/tema-evento-da-resposta
                    "joy" "Seu time já está saudável; a Enfermeira Joy não precisa atender ninguém agora.")))
  (is (= :hospital (core/tema-evento-da-resposta
                    "hospital" "Seu time já está saudável; a Enfermeira Joy não precisa atender ninguém agora.")))
  (is (= :joy-tratando (core/tema-evento-da-resposta
                         "joy 1" "Os Pokémon escolhidos já estão saudáveis. A Enfermeira Joy ainda pode atender outro Pokémon ferido do seu time.")))
  (is (= :hospital (core/tema-evento-da-resposta
                    "enfermaria" "Escolha primeiro seu Pokémon inicial.")))
  (is (nil? (core/tema-evento-da-resposta
             "joy" "Você não pode enviar Pokémon para a Enfermeira Joy durante uma batalha.")))
  (is (= :missao (core/tema-evento-da-resposta "missoes" "Missões diárias em andamento")))
  (is (= :missao (core/tema-evento-da-resposta "missoes semanais" "Missões semanais")))
  (is (= :missao (core/tema-evento-da-resposta "missoes resgatar" "2 missões resgatadas")))
  (is (= "HP 200/440" (core/detalhe-cartao-evento :raid "HP do chefe: 200/440")))
  (is (= "Nv. 12" (core/detalhe-cartao-evento :nivel "subiu para o nível 12"))))

(deftest cartao-de-evolucao-mostra-as-duas-formas
  (let [svg (core/svg-cartao-evolucao {:nome-antigo "Pichu" :nome-novo "Pikachu"})]
    (is (str/includes? svg "Pichu"))
    (is (str/includes? svg "Pikachu"))
    (is (str/includes? svg "cx='185'"))
    (is (str/includes? svg "cx='575'")))
  (async done
    (let [sprite (str "data:image/svg+xml;base64,"
                      (.toString (js/Buffer.from
                                  "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'><circle cx='16' cy='16' r='14' fill='yellow'/></svg>")
                                 "base64"))]
      (-> (core/criar-cartao-evolucao {:nome-antigo "Pichu" :nome-novo "Pikachu"
                                       :imagem-antiga sprite :imagem sprite})
          (.then (fn [buffer]
                   (is (> (.-length buffer) 10000))
                   (done)))
          (.catch (fn [erro]
                    (is false (str "Não conseguiu compor as duas formas da evolução: " erro))
                    (done)))))))

(deftest cartoes-da-joy-tratando-e-do-hospital-usam-imagens-distintas-no-tamanho-padrao
  (async done
    (-> (js/Promise.all
         #js [(core/criar-cartao-evento :joy nil "A Enfermeira Joy recebeu Pikachu")
              (core/criar-cartao-evento :joy-tratando nil "Escolha quem a Enfermeira Joy deve atender")
              (core/criar-cartao-evento :hospital nil "Seu time já está saudável")])
        (.then (fn [buffers]
                 (js/Promise.all
                  #js [(.metadata (sharp (aget buffers 0)))
                       (.metadata (sharp (aget buffers 1)))
                       (.metadata (sharp (aget buffers 2)))])))
        (.then (fn [metadados]
                 (doseq [info (array-seq metadados)]
                   (is (= "png" (.-format info)))
                   (is (= 760 (.-width info)))
                   (is (= 400 (.-height info))))
                 (-> (js/Promise.all
                      #js [(core/criar-cartao-evento :joy nil "recebido")
                           (core/criar-cartao-evento :joy-tratando nil "tratando")
                           (core/criar-cartao-evento :hospital nil "saudável")])
                     (.then (fn [buffers]
                              (is (not (.equals (aget buffers 0) (aget buffers 1))))
                              (is (not (.equals (aget buffers 1) (aget buffers 2))))
                              (done))))))
        (.catch (fn [erro]
                  (is false (str "Não conseguiu carregar as três imagens da Joy/hospital: " erro))
                  (done))))))

(deftest cartao-das-missoes-usa-imagem-do-professor
  (async done
    (-> (core/criar-cartao-evento :missao nil "Missões diárias")
        (.then (fn [buffer]
                 (is (> (.-length buffer) 10000))
                 (done)))
        (.catch (fn [erro]
                  (is false (str "Não conseguiu carregar a imagem do professor: " erro))
                  (done))))))

(deftest cartao-do-treinador-mostra-ash-e-pokemon-ativo
  (let [svg (core/svg-cartao-treinador "Ash" 7 pikachu 1)
        sem-desenho (core/svg-cartao-treinador "Ash" 7 pikachu 1 false)]
    (is (str/includes? svg "fundo-treinador"))
    (is (str/includes? svg "stop-color='#dc2626'"))
    (is (str/includes? svg "fill='#ef4444'"))
    (is (not (str/includes? svg "<rect x='338'")))
    (is (not (str/includes? svg "<rect x='356'")))
    (is (not (str/includes? svg "Pokémon ativo")))
    (is (not (str/includes? svg "Ash • Nv. 7")))
    (is (str/includes? svg "M257 119L305 129"))
    (is (not (str/includes? sem-desenho "M257 119L305 129")))))

(deftest batalhas-temporarias-preservam-keywords-e-removem-objetos-de-runtime
  (let [agora 1000000
        golpe {:nome-exibicao "Choque" :tipo "electric" :classe :especial :poder 40}
        pvp {:message #js {:id "nao-serializar"}
             :pokemons {:x (assoc pikachu :golpes [golpe]) :o geodude}
             :jogadores {:x "1@c.us" :o "2@c.us"}
             :hp {:x 70 :o 80} :status {:x :paralisado :o nil} :vez :o}
        ginasio (assoc pvp :ginasio {:id "pedra"} :resultado-derrota (atom nil))
        caca {:message #js {:id "nao-serializar"}
              :pokemons {:x pikachu :o geodude} :hp {:x 70 :o 0}
              :pid "1@c.us" :aguardando-captura? true :tentativas-captura 2}
        registros (core/serializar-combates
                   {"pvp@g.us" pvp "ginasio@g.us" ginasio "caca@g.us" caca} agora)
        batalhas (core/restaurar-combates
                  (select-keys registros ["pvp@g.us" "ginasio@g.us"]) 30 (+ agora 1000))
        cacadas (core/restaurar-combates
                (select-keys registros ["caca@g.us"]) 5 (+ agora 1000))]
    (is (= :o (get-in batalhas ["pvp@g.us" :vez])))
    (is (= :especial (get-in batalhas ["pvp@g.us" :pokemons :x :golpes 0 :classe])))
    (is (= :paralisado (get-in batalhas ["pvp@g.us" :status :x])))
    (is (nil? (get-in batalhas ["pvp@g.us" :message])))
    (is (satisfies? IDeref (get-in batalhas ["ginasio@g.us" :resultado-derrota])))
    (is (true? (get-in cacadas ["caca@g.us" :aguardando-captura?])))
    (is (= 2 (get-in cacadas ["caca@g.us" :tentativas-captura])))
    (is (not (str/includes? (get-in registros ["pvp@g.us" "estado"]) "nao-serializar")))))

(deftest persistencia-temporaria-descarta-estados-perigosos-e-expirados
  (let [base {:pokemons {:x pikachu} :jogadores {:x "1@c.us"}
              :hp {:x 80} :vez :x}
        batalha-completa {:pokemons {:x pikachu :o geodude}
                           :jogadores {:x "1@c.us" :o "2@c.us"}
                           :hp {:x 80 :o 0} :vez :x}
        caca-derrotada {:pokemons {:x pikachu :o geodude}
                        :hp {:x 80 :o 0} :pid "1@c.us"}
        registros (core/serializar-combates
                   {"valido" base
                    "carregando" (assoc base :carregando? true)
                    "finalizando" (assoc base :finalizando? true)
                    "batalha-completa" batalha-completa
                    "caca-sem-captura" caca-derrotada
                    "caca-em-captura" (assoc caca-derrotada :aguardando-captura? true)}
                   1000)
        desconhecido {"versao" 99 "atualizado-em" 1000 "estado" (pr-str base)}]
    (is (= #{"valido" "finalizando" "batalha-completa" "caca-sem-captura" "caca-em-captura"}
           (set (keys registros))))
    (doseq [cid ["finalizando" "batalha-completa" "caca-sem-captura"]]
      (is (true? (get-in registros [cid "terminal"])))
      (is (nil? (get-in registros [cid "estado"]))))
    (is (= #{"valido" "caca-em-captura"}
           (set (keys (core/restaurar-combates registros 5 2000)))))
    (is (empty? (core/restaurar-combates registros 5 301001)))
    (is (empty? (core/restaurar-combates {"x" desconhecido} 5 2000)))
    (is (empty? (core/restaurar-combates
                 {"x" {"versao" 1 "atualizado-em" 1000 "estado" "{:quebrado"}}
                 5 2000)))))

(deftest marcador-terminal-impede-ressurreicao-e-expira
  (let [base {:pokemons {:x pikachu} :jogadores {:x "1@c.us"}
              :hp {:x 80} :vez :x}
        ativo (core/serializar-combates {"chat" base} 1000)
        terminal (core/serializar-combates {} ativo 2000)
        preservado (core/serializar-combates {} terminal 3000)
        expirado (core/serializar-combates {} terminal (+ 2000 (* 24 60 60 1000) 1))]
    (is (true? (get-in terminal ["chat" "terminal"])))
    (is (= terminal preservado))
    (is (empty? (core/restaurar-combates terminal 30 3000)))
    (is (empty? expirado))))

(deftest persistencia-atualiza-somente-o-chat-que-mudou
  (let [base {:pokemons {:x pikachu} :jogadores {:x "1@c.us"}
              :hp {:x 80} :vez :x}
        inicial (core/serializar-combates {"a" base "b" base} 1000)
        alterado (core/serializar-combates
                  {"a" (assoc-in base [:hp :x] 70)
                   "b" (assoc base :message #js {:id "somente-runtime"})}
                  inicial 2000)]
    (is (= 2000 (get-in alterado ["a" "atualizado-em"])))
    (is (= 1000 (get-in alterado ["b" "atualizado-em"])))
    (is (= (get-in inicial ["b" "estado"]) (get-in alterado ["b" "estado"])))))

(deftest dados-do-treinador-cabem-na-legenda-da-imagem
  (let [insignias (mapv #(assoc % :conquistada? true)
                        (treinador/insignias-treinador "chat" "jogador"))
        perfil {:nivel 100 :xp 99999 :xp-insignias 999 :xp-missoes 999
                :pe-ginasios 999 :pe-raids 999 :xp-atual 999 :xp-necessario 999
                :sequencia 999 :recorde 999 :insignias insignias}
        ativo (assoc pikachu :nivel 100)]
    (with-redefs [treinador/insignias-ginasio
                  (fn [_ _] {"pedra" "dia" "agua" "dia" "eletrico" "dia"
                              "planta" "dia" "fogo" "dia"})]
      (let [texto (core/texto-treinador "chat" "jogador" "Ash Ketchum" perfil 999 ativo)]
        (is (<= (count texto) 900))
        (is (str/includes? texto "⚡ Ativo: #999 *Pikachu*"))
        (doseq [{:keys [nome]} insignias]
          (is (str/includes? texto nome)))))))

(deftest candidatos-de-sprite-priorizam-jsdelivr-para-github-raw
  (let [original "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/25.png"]
    (is (= ["https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/official-artwork/25.png"
            original]
           (core/candidatos-url-sprite original)))
    (is (= ["https://exemplo.com/pikachu.png"]
           (core/candidatos-url-sprite "https://exemplo.com/pikachu.png")))))

(deftest cartao-do-treinador-renderiza-pokemon-ativo
  (async done
    (let [sprite (str "data:image/svg+xml;base64,"
                      (.toString (js/Buffer.from
                                  "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'><circle cx='32' cy='32' r='28' fill='gold'/></svg>")
                                 "base64"))
          ativo (assoc pikachu :imagem sprite)]
      (-> (core/criar-cartao-treinador "Ash" 7 ativo 1)
          (.then (fn [buffer]
                   (is (> (.-length buffer) 10000))
                   (done)))
          (.catch (fn [erro]
                    (is false (str "Não conseguiu gerar o cartão do treinador: " erro))
                    (done)))))))

(deftest cartao-do-treinador-sobrevive-a-sprite-indisponivel
  (async done
    (let [tentativas (atom 0)
          ativo (assoc pikachu :imagem "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png")]
      (with-redefs [core/sprite-pokemon-treinador
                    (fn [_]
                      (swap! tentativas inc)
                      (js/Promise.reject (js/Error. "sprite indisponível")))]
        (-> (core/criar-cartao-treinador "Ash" 7 ativo 1)
            (.then (fn [buffer]
                     (is (= 1 @tentativas))
                     (.metadata (sharp buffer))))
            (.then (fn [metadados]
                     (is (= "png" (.-format metadados)))
                     (is (= 760 (.-width metadados)))
                     (is (= 400 (.-height metadados)))
                     (done)))
            (.catch (fn [erro]
                      (is false (str "O cartão não usou o fallback quando o sprite falhou: " erro))
                      (done))))))))

(deftest ataques-pvp-tambem-recebem-arena-visual
  (let [pvp {:jogadores {:x "a" :o "b"}}
        espera {:jogadores {:x "a"}}
        ginasio {:ginasio {:id "pedra"} :jogadores {:x "a" :o "lider"}}]
    (is (true? (core/ataque-pvp? pvp "atk 1")))
    (is (false? (core/ataque-pvp? espera "atk 1")))
    (is (false? (core/ataque-pvp? ginasio "atk 1")))))

(deftest moldura-do-time-de-ginasio-tem-tres-espacos
  (let [svg (core/svg-time-ginasio)]
    (is (str/includes? svg "M25 270V105"))
    (is (str/includes? svg "M260 270V105"))
    (is (str/includes? svg "M495 270V105"))
    (is (str/includes? svg "TIME DO GINÁSIO"))))

(deftest marcador-do-ginasio-mostra-e-esvazia-a-motivacao
  (let [cheio (core/svg-marcador-motivacao 100)
        baixo (core/svg-marcador-motivacao 20)]
    (is (str/includes? cheio "100%"))
    (is (str/includes? baixo "20%"))
    (is (str/includes? cheio "height='50'"))
    (is (str/includes? baixo "height='10'"))
    (is (str/includes? baixo "#64748b"))))

(deftest imagem-do-ginasio-compoe-os-tres-defensores
  (async done
    (let [sprite (str "data:image/svg+xml;base64,"
                      (.toString (js/Buffer.from
                                  "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'><circle cx='16' cy='16' r='14' fill='red'/></svg>")
                                 "base64"))
          pokemons [{:imagem sprite :altura 0.3 :motivacao-ginasio 100}
                    {:imagem sprite :altura 1.0 :motivacao-ginasio 65}
                    {:imagem sprite :altura 2.1 :motivacao-ginasio 20}]]
      (-> (core/criar-imagem-time-ginasio pokemons)
          (.then (fn [buffer]
                   (is (> (.-length buffer) 10000))
                   (done)))
          (.catch (fn [erro]
                    (is false (str "Não conseguiu compor os defensores no ginásio: " erro))
                    (done)))))))

(deftest menu-de-ginasios-omite-pokemons-do-lider
  (with-redefs [ginasios/lider (fn [_ _]
                                 {"nome" "Misty" "desde" (.now js/Date)
                                  "time" [{"nome" "Starmie" "nivel" 30}]})]
    (is (not (str/includes? (core/descricao-lider "chat" {:id "agua"} false)
                            "Time reservado")))
    (is (str/includes? (core/descricao-lider "chat" {:id "agua"}) "Starmie"))))
