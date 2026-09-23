(ns zapbot.pokemon.pc-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ajuda :as ajuda]
            ["sharp" :as sharp]))

(defn registro [id]
  {"id-pokemon" (str id) "nome" (str "Pokemon " id) "nivel" 5
   "hp" 80 "hp-atual" 40 "status" "queimado" "xp-desde-nivel" 3
   "ataque" 60 "defesa" 60 "atq-esp" 60 "def-esp" 60 "veloc" 60
   "tipos" ["normal"] "golpes" [] "item" "restos"})

(defn sem-gravacao [& _] (js/Promise.resolve nil))

(deftest migracao-preserva-todos-os-registros-e-escalacoes
  (let [registros (mapv registro (range 32))
        salvo {"nome" "antigo" "pokemons" ["6" "10" "30"]}
        estado (atom {"chat" {"ash" {"equipe" (subvec registros 0 6)
                                      "pc" (subvec registros 6) "ativo" 5
                                      "favorito" {"id" "30"} "pc-migrado" true
                                      "times-prontos" {"antigo" salvo}
                                      "time-ginasio" [0 1 2] "liga" "iniciante"
                                      "times-liga" {"iniciante" [3 4 5]}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (treinador/migrar-colecao! "chat" "ash")
      (let [migrado @estado]
        (is (= registros (treinador/equipe "chat" "ash")))
        (is (empty? (treinador/pc "chat" "ash")))
        (is (= 5 (treinador/indice-ativo "chat" "ash")))
        (is (= [0 1 2] (treinador/time-ginasio "chat" "ash")))
        (is (= [3 4 5] (treinador/time-liga "chat" "ash" "iniciante")))
        (is (= "30" (get (treinador/favorito "chat" "ash") "id")))
        (is (= salvo (get-in @estado ["chat" "ash" "times-prontos" "antigo"])))
        (treinador/migrar-colecao! "chat" "ash")
        (is (= migrado @estado))))))

(deftest migracao-de-conta-anterior-ao-pc-nao-corta-excedentes
  (let [registros (mapv registro (range 80))
        estado (atom {"chat" {"ash" {"equipe" registros "ativo" 79}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (treinador/migrar-colecao! "chat" "ash")
      (is (= registros (treinador/equipe "chat" "ash")))
      (is (= 79 (treinador/indice-ativo "chat" "ash")))
      (is (= 80 (treinador/quantidade-guardada "chat" "ash"))))))

(deftest expansao-debita-preco-fixo-e-nao-cobra-sem-saldo
  (let [estado (atom {"chat" {"ash" {"moedas" 600}}})]
    (with-redefs [loja/contas estado armazenamento/salvar! sem-gravacao]
      (is (= 26 (loja/capacidade-pokemon "chat" "ash")))
      (doseq [[preco capacidade] [[200 76] [200 126] [200 176]]]
        (is (= 200 (loja/preco-expansao-pc "chat" "ash")))
        (is (= {:status :ok :preco preco :capacidade capacidade}
               (loja/comprar-espaco-pc! "chat" "ash")))
        (is (= capacidade (loja/capacidade-pokemon "chat" "ash"))))
      (is (= 0 (loja/moedas "chat" "ash")))
      (is (= {:status :sem-moedas :preco 200} (loja/comprar-espaco-pc! "chat" "ash")))
      (is (= 176 (loja/capacidade-pokemon "chat" "ash"))))))

(deftest retornos-e-doacoes-vao-a-colecao-sem-cortar-excedentes
  (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 6))
                                      "enfermaria" [{"pokemon" (registro 8) "pronto-em" 0}]}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (= :equipe (:destino (treinador/receber-doacao! "chat" "ash" (registro 7)))))
      (treinador/recolher-curados! "chat" "ash")
      (treinador/receber-retorno-ginasio! "chat" "ash" (assoc (registro 9) "hp-atual" 0) 9)
      (let [[doado curado defensor] (drop 6 (treinador/equipe "chat" "ash"))]
        (is (= (registro 7) doado))
        (is (= 80 (get curado "hp-atual")))
        (is (nil? (get curado "status")))
        (is (= 6 (get defensor "nivel")))
        (is (= 3 (get defensor "xp-desde-nivel")))
        (is (= 0 (get defensor "hp-atual")))
        (is (= 9 (count (treinador/equipe "chat" "ash"))))
        (is (= 9 (treinador/quantidade-guardada "chat" "ash")))))))

(deftest estoque-inclui-joy-e-ginasios-e-bloqueia-antes-da-bola
  (async done
    (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 6))
                                        "pc" (mapv registro (range 6 22))
                                        "enfermaria" [{"pokemon" (registro 22)}]}}})
          consumidas (atom 0)
          caca {:pid "ash" :aguardando-captura? true :tentativas-captura 0}]
      (with-redefs [treinador/contas estado loja/contas (atom {})
                    ginasios/liderados (fn [_ _] [["pedra" {"time" (mapv registro [23 24 25])}]])
                    core/cacadas-selvagens (atom {"chat" caca})
                    loja/consumir-bola! (fn [_ _ _] (swap! consumidas inc) true)]
        (is (= 26 (core/ocupacao-pokemon "chat" "ash")))
        (is (not (core/cabe-pokemon? "chat" "ash")))
        (-> (core/capturar-selvagem #js {:from "chat" :author "ash"} ["pokebola"])
            (.then (fn [texto]
                     (is (str/includes? texto "Estoque Pokémon cheio"))
                     (is (= 0 @consumidas))
                     (done)))
            (.catch (fn [erro] (is false (str erro)) (done))))))))

(deftest pc-nao-move-pokemon-durante-batalha
  (let [estado (atom {"chat" {"ash" {"equipe" [(registro 0)] "pc" [(registro 1)]}}})]
    (with-redefs [treinador/contas estado
                  core/jogos (atom {"chat" {:jogadores {:x "ash" :o "lider-ginasio"}}})
                  core/cacadas-selvagens (atom {}) loja/contas (atom {})
                  ginasios/liderados (fn [_ _] [])]
      (let [antes @estado texto (core/comando-pc #js {:from "chat" :author "ash"} ["trocar" "1" "1"])]
        (is (str/includes? texto "Agora todos os Pokémon"))
        (is (= antes @estado)))))
  (is (= (ajuda/resposta "pc ajuda") (ajuda/resposta "centro ajuda")))
  (is (str/includes? (ajuda/resposta "pc ajuda") "200 moedas")))

(deftest pc-preserva-estado-apos-recarregar-e-nao-libera-outro-inicial
  (let [estado (atom {"chat" {"ash" {"equipe" [] "pc" [(registro 1)] "ativo" 0
                                      "pc-migrado" true "times-prontos" {"meu time" {"pokemons" ["1"]}}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (false? (treinador/inicial-disponivel? "chat" "ash")))
      (let [antes @estado
            recarregado (treinador/normalizar-xp-contas
                          (js->clj (js/JSON.parse (js/JSON.stringify (clj->js antes)))))]
        (is (= antes recarregado)))))
  (is (nil? (core/indice-pc "1abc" 10)))
  (is (nil? (core/indice-pc "0" 10)))
  (is (= 9 (core/indice-pc "10" 10))))

(deftest captura-acrescenta-a-colecao-sem-limite-de-seis
  (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 6))}}})
        [pokemon hp status] (treinador/registro->pokemon (registro 20))]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (= {:destino :equipe :indice 6} (treinador/adicionar-pokemon! "chat" "ash" pokemon hp status)))
      (is (= 7 (count (treinador/equipe "chat" "ash"))))
      (let [r (last (treinador/equipe "chat" "ash"))]
        (is (= "Pokemon 20" (get r "nome")))
        (is (= 40 (get r "hp-atual")))
        (is (= "queimado" (get r "status")))
        (is (= "restos" (get r "item")))))))

(deftest retorno-real-do-ginasio-vai-a-colecao-e-mantem-xp
  (let [anterior {"pid" "brock" "nome" "Brock" "time" (mapv registro [10 11 12]) "desde" 0}
        estado (atom {"chat" {"ash" {"equipe" (mapv registro [0 1 2]) "ativo" 0}
                              "brock" {"equipe" (mapv registro (range 20 26)) "ativo" 0}}})]
    (with-redefs [treinador/contas estado loja/contas (atom {})
                  ginasios/ocupacoes (atom {"chat" {"pedra" anterior}})
                  ginasios/estatisticas (atom {}) armazenamento/salvar! sem-gravacao]
      (is (some? (ginasios/ocupar! "chat" "pedra" anterior "ash" "Ash" [0 1 2] (* 60 60 1000))))
      (is (= 9 (count (treinador/equipe "chat" "brock"))))
      (is (empty? (treinador/pc "chat" "brock")))
      (is (every? #(= 0 (get % "hp-atual")) (drop 6 (treinador/equipe "chat" "brock"))))
      (is (every? #(= 5 (get % "xp-desde-nivel")) (drop 6 (treinador/equipe "chat" "brock"))))
      (is (= 0 (count (treinador/equipe "chat" "ash"))))
      (is (= 3 (core/ocupacao-pokemon "chat" "ash"))))))

(deftest estoque-cheio-bloqueia-cacada-antes-do-cooldown
  (async done
    (let [estado (atom {"chat" {"ash" {"equipe" [(registro 0)] "pc" (mapv registro (range 1 26))}}})]
      (with-redefs [treinador/contas estado loja/contas (atom {})
                    ginasios/liderados (fn [_ _] [])
                    treinador/pode-cacar? (fn [_ _] (throw (js/Error. "Consultou cooldown antes de verificar estoque")))]
        (-> (core/cacar #js {:from "chat" :author "ash"} [])
            (.then (fn [texto]
                     (is (str/includes? texto "Estoque Pokémon cheio"))
                     (is (nil? (get-in @estado ["chat" "ash" "ultima-cacada"])))
                     (done)))
            (.catch (fn [erro] (is false (str erro)) (done))))))))

(deftest comando-pc-adia-migracao-enquanto-batalha-usa-indices
  (async done
    (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 9)) "ativo" 8}}})
          antes @estado]
      (with-redefs [treinador/contas estado loja/contas (atom {})
                    core/jogos (atom {"chat" {:jogadores {:x "ash" :o "lider-ginasio"}}})
                    core/cacadas-selvagens (atom {})
                    ginasios/liderados (fn [_ _] [])]
        (-> (core/jogar-comando #js {:from "chat" :author "ash"} "pc")
            (.then (fn [texto]
                     (is (str/includes? (:texto texto) "Sua coleção"))
                     (is (= antes @estado))
                     (done)))
            (.catch (fn [erro] (is false (str erro)) (done))))))))


(deftest doacao-revalida-capacidade-e-entrega-na-colecao
  (async done
    (let [cid "teste-doacao-pc"
          anterior (get @treinador/contas cid)
          doado (registro 0)
          mensagem #js {:from cid :author "ash" :mentionedIds #js ["misty"]}]
      (swap! treinador/contas assoc cid
             {"ash" {"equipe" [doado] "ativo" 0 "pc-migrado" true}
              "misty" {"equipe" (mapv registro (range 1 7))
                       "pc" (mapv registro (range 7 27)) "ativo" 0 "pc-migrado" true}})
      (-> (core/doar mensagem "1")
          (p/then (fn [texto]
                    (is (str/includes? texto "destinatário está sem espaço"))
                    (is (= [doado] (treinador/equipe cid "ash")))
                    (is (= 26 (core/ocupacao-pokemon cid "misty")))
                    (swap! treinador/contas update-in [cid "misty" "pc"] pop)
                    (core/doar mensagem "1")))
          (p/then (fn [texto]
                    (is (str/includes? texto "com sucesso"))
                    (is (empty? (treinador/equipe cid "ash")))
                    (is (= 26 (count (treinador/equipe cid "misty"))))
                    (is (= 26 (core/ocupacao-pokemon cid "misty")))
                    (is (= doado (last (treinador/equipe cid "misty"))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (if anterior (swap! treinador/contas assoc cid anterior)
                           (swap! treinador/contas dissoc cid))
                       (done)))))))

(deftest paginas-do-pc-tem-doze-e-preservam-os-numeros-originais
  (let [banco (mapv #(assoc (registro %) "tipos" [(if (odd? %) "fire" "water")]) (range 50))
        segunda (core/pagina-colecao banco ["2"])
        fogo (core/pagina-colecao banco ["2" "fogo"])
        ultima (core/pagina-colecao banco ["3" "fogo"])]
    (is (= 5 (:paginas segunda)))
    (is (= (vec (range 12 24)) (mapv :indice (:entradas segunda))))
    (is (= 25 (:total fogo)))
    (is (= "fogo" (:filtro fogo)))
    (is (= (vec (range 25 49 2)) (mapv :indice (:entradas fogo))))
    (is (= [49] (mapv :indice (:entradas ultima))))
    (doseq [args [["0"] ["-1"] ["6"] ["pagina"] ["pagina" "abc"] ["9007199254740992"]]]
      (is (not (:valida? (core/pagina-colecao banco args))) (str args)))
  (is (empty? (:entradas (core/pagina-colecao [] []))))))

(deftest filtros-do-pc-usam-a-mesma-ordenacao-do-time
  (let [banco (mapv #(assoc (registro %) "shiny" (even? %) "nivel" (inc %) "ataque" (+ 60 %)) (range 30))]
    (doseq [filtro ["shiny" "normal" "nivel 20" "bronze" "pokemon 5" "shiny >" "normal <" "raro"]]
      (let [pagina (core/pagina-colecao banco (str/split filtro #" "))]
        (is (= (vec (take 12 (core/filtrar-time banco filtro))) (:entradas pagina)) filtro)))
    (is (= [19] (mapv :indice (:entradas (core/pagina-colecao banco ["nivel" "20"])))))))

(deftest time-envia-uma-imagem-com-doze-pokemon-e-proxima-pagina
  (async done
    (let [estado (atom {"chat" {"ash" {"equipe" [] "pc-migrado" true
                                        "pc" (mapv #(assoc (registro %) "tipos" ["fire"]) (range 25))}}})]
      (with-redefs [treinador/contas estado loja/contas (atom {})
                    ginasios/liderados (fn [_ _] [])]
        (-> (core/jogar-comando #js {:from "chat" :author "ash"} "tm 2 fogo")
            (p/then (fn [resposta]
                      (is (some? (:media resposta)))
                      (is (nil? (:medias resposta)))
                      (is (str/includes? (:texto resposta) "Página 2/3"))
                      (is (str/includes? (:texto resposta) "Mostrando 12 de 25"))
                      (is (str/includes? (:texto resposta) "!pk tm 3 fogo"))
                      (is (< (count (:texto resposta)) 900))
                      (is (= "colecao-pokemon.png" (.-filename (:media resposta))))
                      (-> (sharp (js/Buffer.from (.-data (:media resposta)) "base64")) (.metadata))))
            (p/then (fn [metadata]
                      (is (= "png" (.-format metadata)))
                      (is (= 1000 (.-width metadata)))
                      (is (= 1370 (.-height metadata)))))
            (p/catch (fn [erro] (is false (str erro))))
            (p/finally done))))))
