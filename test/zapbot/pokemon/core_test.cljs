(ns zapbot.pokemon.core-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ginasios :as ginasios]
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
    (is (= "pocao" (core/expandir-atalho "pot"))))
  (testing "atalhos de navegação e gerenciamento"
    (is (= "ginasio" (core/expandir-atalho "gin")))
    (is (= "cacar" (core/expandir-atalho "cac")))
    (is (= "time" (core/expandir-atalho "tm")))
    (is (= "pokedex" (core/expandir-atalho "dex"))))
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

(deftest pokebolas-de-captura-tem-cores-e-estados-visuais
  (is (= "#dc2626" (core/cor-bola "pokebola")))
  (is (= "#2563eb" (core/cor-bola "grande-bola")))
  (is (= "#111827" (core/cor-bola "ultra-bola")))
  (let [aberta (core/svg-bola-captura "grande-bola" false false)
        fechada (core/svg-bola-captura "ultra-bola" true false)
        fuga (core/svg-bola-captura "pokebola" false true)]
    (is (str/includes? aberta "rotate(-18"))
    (is (not (str/includes? aberta "#fde047")))
    (is (str/includes? fechada "#fde047"))
    (is (not (str/includes? fechada "rotate(-18")))
    (is (str/includes? fuga "fill-opacity='.9'"))))

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
    (is (str/includes? quadro "rotate(-18"))
    (is (str/includes? quadro "fill-opacity='.9'"))
    (is (str/includes? quadro "cx='385' cy='130'"))
    (is (not (str/includes? quadro "<image")))))

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
    (is (= 560 (:centro-selvagem batalha)))
    (is (false? (:mostrar-meu? captura)))
    (is (= 380 (:centro-selvagem captura)))))

(deftest efeitos-visuais-cobrem-golpes-status-shiny-e-substituicao
  (let [efeitos (core/svg-sobreposicao-batalha
                 "causou queimadura, paralisia, envenenamento, congelamento, dormiu, confusão e entrou na batalha"
                 true {:tipo "fire" :classe :especial})]
    (is (str/includes? efeitos "#f97316"))
    (is (str/includes? efeitos "#fde047"))
    (is (str/includes? efeitos "#a855f7"))
    (is (str/includes? efeitos "#67e8f9"))
    (is (str/includes? efeitos ">Z</text>"))
    (is (str/includes? efeitos "#f472b6"))
    (is (str/includes? efeitos "translate(585 250)"))
    (is (str/includes? efeitos "M555 60")))
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
      (is (str/includes? psiquico "M380 210C380 178"))
      (is (str/includes? psiquico "translate(247 76.5) scale(.35)"))
      (is (str/includes? psiquico "translate(266 189.3) scale(.30)")))))

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
    (is (<= gigante 260))))

(deftest classifica-cartoes-dos-eventos-pokemon
  (is (= :nivel (core/tema-evento-da-resposta "" "Pikachu subiu para o nível 12")))
  (is (= :desmaio (core/tema-evento-da-resposta "" "Seu Pokémon desmaiou")))
  (is (= :entrada (core/tema-evento-da-resposta "" "Treinador envia *Eevee*")))
  (is (= :insignia (core/tema-evento-da-resposta "atk 1" "Você venceu o ginásio Pedra")))
  (is (= :raid (core/tema-evento-da-resposta "raid atacar 1" "HP do chefe: 200/440")))
  (is (= :joy (core/tema-evento-da-resposta "joy 1" "A Enfermeira Joy recebeu *Pikachu*")))
  (is (= :missao (core/tema-evento-da-resposta "missoes" "Missões diárias em andamento")))
  (is (= :missao (core/tema-evento-da-resposta "missoes semanais" "Missões semanais")))
  (is (= :missao (core/tema-evento-da-resposta "missoes resgatar" "2 missões resgatadas")))
  (is (= "HP 200/440" (core/detalhe-cartao-evento :raid "HP do chefe: 200/440")))
  (is (= "Nv. 12" (core/detalhe-cartao-evento :nivel "subiu para o nível 12"))))

(deftest cartao-da-joy-usa-imagem-propria
  (async done
    (-> (core/criar-cartao-evento :joy nil "A Enfermeira Joy recebeu Pikachu")
        (.then (fn [buffer]
                 (is (> (.-length buffer) 10000))
                 (done)))
        (.catch (fn [erro]
                  (is false (str "Não conseguiu carregar a imagem da Joy: " erro))
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

(deftest imagem-do-ginasio-compoe-os-tres-defensores
  (async done
    (let [sprite (str "data:image/svg+xml;base64,"
                      (.toString (js/Buffer.from
                                  "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'><circle cx='16' cy='16' r='14' fill='red'/></svg>")
                                 "base64"))
          pokemons [{:imagem sprite :altura 0.3}
                    {:imagem sprite :altura 1.0}
                    {:imagem sprite :altura 2.1}]]
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
