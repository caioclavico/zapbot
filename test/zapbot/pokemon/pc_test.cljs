(ns zapbot.pokemon.pc-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.ginasios :as ginasios]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.ajuda :as ajuda]))

(defn registro [id]
  {"id-pokemon" (str id) "nome" (str "Pokemon " id) "nivel" 5
   "hp" 80 "hp-atual" 40 "status" "queimado" "xp-desde-nivel" 3
   "ataque" 60 "defesa" 60 "atq-esp" 60 "def-esp" 60 "veloc" 60
   "tipos" ["normal"] "golpes" [] "item" "restos"})

(defn sem-gravacao [& _] (js/Promise.resolve nil))

(deftest migracao-preserva-todos-os-registros-e-escalacoes
  (let [registros (mapv registro (range 32))
        estado (atom {"chat" {"ash" {"equipe" registros "ativo" 31
                                      "favorito" {"id" "30"}
                                      "time-ginasio" [0 1 2] "liga" "iniciante"
                                      "times-liga" {"iniciante" [3 4 5]}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (treinador/migrar-pc! "chat" "ash")
      (let [migrado @estado eq (treinador/equipe "chat" "ash") pc (treinador/pc "chat" "ash")]
        (is (= 6 (count eq)))
        (is (= 26 (count pc)))
        (is (= (set registros) (set (concat eq pc))))
        (is (= "31" (get (get eq (treinador/indice-ativo "chat" "ash")) "id-pokemon")))
        (is (some #(= "30" (get % "id-pokemon")) eq))
        (is (= ["0" "1" "2"] (get-in @estado ["chat" "ash" "times-prontos" "ginasio anterior" "pokemons"])))
        (is (= ["3" "4" "5"] (get-in @estado ["chat" "ash" "times-prontos" "liga iniciante anterior" "pokemons"])))
        (treinador/migrar-pc! "chat" "ash")
        (is (= migrado @estado))))))

(deftest expansao-debita-preco-progressivo-e-nao-cobra-sem-saldo
  (let [estado (atom {"chat" {"ash" {"moedas" 1200}}})]
    (with-redefs [loja/contas estado armazenamento/salvar! sem-gravacao]
      (is (= 26 (loja/capacidade-pokemon "chat" "ash")))
      (doseq [[preco capacidade] [[200 36] [400 46] [600 56]]]
        (is (= {:status :ok :preco preco :capacidade capacidade}
               (loja/comprar-espaco-pc! "chat" "ash"))))
      (is (= 0 (loja/moedas "chat" "ash")))
      (is (= {:status :sem-moedas :preco 800} (loja/comprar-espaco-pc! "chat" "ash")))
      (is (= 56 (loja/capacidade-pokemon "chat" "ash"))))))

(deftest movimentacoes-preservam-dados-e-limite-da-equipe
  (let [eq (mapv registro (range 6)) r (registro 6)
        estado (atom {"chat" {"ash" {"equipe" eq "pc" [r] "ativo" 2 "pc-migrado" true
                                      "time-ginasio" [0 1 2] "times-liga" {"iniciante" [0 1 2]}}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (nil? (treinador/mover-pc! "chat" "ash" :retirar 0 nil)))
      (is (true? (treinador/mover-pc! "chat" "ash" :trocar 0 1)))
      (is (= r (get (treinador/equipe "chat" "ash") 1)))
      (is (= (eq 1) (first (treinador/pc "chat" "ash"))))
      (is (= [0 nil 2] (treinador/time-ginasio "chat" "ash")))
      (is (treinador/mover-pc! "chat" "ash" :depositar 0 nil))
      (is (= 1 (treinador/indice-ativo "chat" "ash")))
      (is (treinador/mover-pc! "chat" "ash" :retirar 0 nil))
      (is (= 6 (count (treinador/equipe "chat" "ash"))))
      (is (= (set (conj eq r)) (set (concat (treinador/equipe "chat" "ash") (treinador/pc "chat" "ash")))))
      (is (nil? (treinador/mover-pc! "chat" "ash" :depositar 99 nil))))))

(deftest retornos-e-doacoes-vao-ao-pc-sem-exceder-seis
  (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 6))
                                      "enfermaria" [{"pokemon" (registro 8) "pronto-em" 0}]}}})]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (= :pc (:destino (treinador/receber-doacao! "chat" "ash" (registro 7)))))
      (treinador/recolher-curados! "chat" "ash")
      (treinador/receber-retorno-ginasio! "chat" "ash" (assoc (registro 9) "hp-atual" 0) 9)
      (let [[doado curado defensor] (treinador/pc "chat" "ash")]
        (is (= (registro 7) doado))
        (is (= 80 (get curado "hp-atual")))
        (is (nil? (get curado "status")))
        (is (= 6 (get defensor "nivel")))
        (is (= 3 (get defensor "xp-desde-nivel")))
        (is (= 0 (get defensor "hp-atual")))
        (is (= 6 (count (treinador/equipe "chat" "ash"))))
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
        (is (str/includes? texto "Termine a batalha"))
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

(deftest captura-com-equipe-cheia-guarda-no-pc-sem-perder-dados
  (let [estado (atom {"chat" {"ash" {"equipe" (mapv registro (range 6))}}})
        [pokemon hp status] (treinador/registro->pokemon (registro 20))]
    (with-redefs [treinador/contas estado armazenamento/salvar! sem-gravacao]
      (is (= {:destino :pc :indice 0} (treinador/adicionar-pokemon! "chat" "ash" pokemon hp status)))
      (is (= 6 (count (treinador/equipe "chat" "ash"))))
      (let [r (first (treinador/pc "chat" "ash"))]
        (is (= "Pokemon 20" (get r "nome")))
        (is (= 40 (get r "hp-atual")))
        (is (= "queimado" (get r "status")))
        (is (= "restos" (get r "item")))))))

(deftest retorno-real-do-ginasio-vai-ao-pc-e-mantem-xp
  (let [anterior {"pid" "brock" "nome" "Brock" "time" (mapv registro [10 11 12]) "desde" 0}
        estado (atom {"chat" {"ash" {"equipe" (mapv registro [0 1 2]) "ativo" 0}
                              "brock" {"equipe" (mapv registro (range 20 26)) "ativo" 0}}})]
    (with-redefs [treinador/contas estado loja/contas (atom {})
                  ginasios/ocupacoes (atom {"chat" {"pedra" anterior}})
                  ginasios/estatisticas (atom {}) armazenamento/salvar! sem-gravacao]
      (is (some? (ginasios/ocupar! "chat" "pedra" anterior "ash" "Ash" [0 1 2] (* 60 60 1000))))
      (is (= 6 (count (treinador/equipe "chat" "brock"))))
      (is (= 3 (count (treinador/pc "chat" "brock"))))
      (is (every? #(= 0 (get % "hp-atual")) (treinador/pc "chat" "brock")))
      (is (every? #(= 5 (get % "xp-desde-nivel")) (treinador/pc "chat" "brock")))
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
                     (is (str/includes? texto "PC do Centro"))
                     (is (= antes @estado))
                     (done)))
            (.catch (fn [erro] (is false (str erro)) (done))))))))


(deftest doacao-revalida-capacidade-e-entrega-no-pc
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
                    (is (= 6 (count (treinador/equipe cid "misty"))))
                    (is (= 26 (core/ocupacao-pokemon cid "misty")))
                    (is (= doado (last (treinador/pc cid "misty"))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (if anterior (swap! treinador/contas assoc cid anterior)
                           (swap! treinador/contas dissoc cid))
                       (done)))))))
