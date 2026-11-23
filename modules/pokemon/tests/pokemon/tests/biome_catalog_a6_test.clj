(ns pokemon.tests.biome-catalog-a6-test
  "Catálogo fixo de 6 biomas e 6 climas + céu limpo noturno.
   Sem fork de gameplay por chatId — eventos temáticos é que podem ser por chat."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mundo :as mundo])
  (:import [java.time Instant LocalDate]))

(def ^:private repo-root
  (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(defn- instante-noite-ensolarado
  "Noite cujo sorteio cru cairia em ensolarado (índice 0) e vira céu limpo."
  []
  (let [base (LocalDate/parse "2026-01-01")
        dia (first (filter #(zero? (mod (+ (.toEpochDay %) 3) 6))
                           (map #(.plusDays base (long %)) (range 18))))]
    (Instant/parse (str dia "T22:00:00-03:00"))))

(defn- instante-dia-ensolarado
  []
  (let [base (LocalDate/parse "2026-01-01")
        ;; Dia = índice 1; (epochDay + 1) mod 6 = 0 ⇒ epochDay mod 6 = 5
        dia (first (filter #(= 5 (mod (.toEpochDay %) 6))
                           (map #(.plusDays base (long %)) (range 18))))]
    (Instant/parse (str dia "T12:00:00-03:00"))))

(deftest catalogo-nao-inventa-bioma-nem-clima
  (is (= 6 (count mundo/areas)))
  (is (= ["floresta" "praia" "caverna" "cidade" "lago" "vulcao"]
         (mapv :id mundo/areas)))
  (is (= 6 (count mundo/climas)))
  (is (= ["ensolarado" "chuvoso" "nublado" "ventania" "neve" "nevoeiro"]
         (mapv :id mundo/climas)))
  (is (= "ceu-limpo" (:id (mundo/obter-clima "ceu-limpo"))))
  (is (not (some #(= "ceu-limpo" (:id %)) mundo/climas))))

(deftest areas-parametrizadas-acento-caixa-espaco-composto-alias
  (doseq [[id variantes] [["floresta" ["floresta" "FLORESTA" "Floresta Verde" "  floresta   verde  "
                                       "floresta-verde" "verde"]]
                          ["praia" ["praia" "Praia Azul" "praia_azul" "azul" "  AZUL  "]]
                          ["caverna" ["caverna" "Caverna Rochosa" "caverna-rochosa" "rochosa"]]
                          ["cidade" ["cidade" "Cidade Pokémon" "cidade-pokemon" "pokemon"]]
                          ["lago" ["lago" "Lago Encantado" "lago_encantado" "encantado"]]
                          ["vulcao" ["vulcao" "vulcão" "Vulcão Rubro" "vulcao-rubro" "  rubro  "]]]]
    (testing id
      (doseq [texto variantes]
        (is (= id (:id (mundo/obter-area texto))) texto)))))

(deftest climas-parametrizados-acento-caixa-espaco-composto
  (doseq [[id variantes] [["ensolarado" ["ensolarado" "ENSOLARADO" "  Ensolarado  "]]
                          ["chuvoso" ["chuvoso" "Chuvoso"]]
                          ["nublado" ["nublado" "Nublado"]]
                          ["ventania" ["ventania" "Ventania"]]
                          ["neve" ["neve" "Neve"]]
                          ["nevoeiro" ["nevoeiro" "Nevoeiro"]]
                          ["ceu-limpo" ["ceu-limpo" "ceu limpo" "Céu limpo" "céu-limpo"
                                        "  Céu   Limpo  "]]]]
    (testing id
      (doseq [texto variantes]
        (is (= id (:id (mundo/obter-clima texto))) texto))))
  (doseq [desconhecido ["deserto" "tempestade" "granizo" "magma" "atlantida" ""]]
    (is (nil? (mundo/obter-area desconhecido)) desconhecido)
    (is (nil? (mundo/obter-clima desconhecido)) desconhecido))
  ;; ensolarado continua sendo o clima diurno; não vira céu limpo no lookup.
  (is (= "ensolarado" (:id (mundo/obter-clima "ensolarado")))))

(deftest noite-ensolarado-vira-ceu-limpo-dia-permanece
  (let [noite (mundo/resumo (instante-noite-ensolarado))
        dia (mundo/resumo (instante-dia-ensolarado))]
    (is (= "Noite" (get-in noite [:periodo :nome])))
    (is (= "ceu-limpo" (get-in noite [:clima :id])))
    (is (= "Céu limpo" (get-in noite [:clima :nome])))
    (is (not= "ensolarado" (get-in noite [:clima :id])))
    (is (= "Dia" (get-in dia [:periodo :nome])))
    (is (= "ensolarado" (get-in dia [:clima :id])))
    (is (= "Ensolarado" (get-in dia [:clima :nome])))))

(deftest area-desconhecida-difere-de-indisponivel
  (let [instant (Instant/parse "2026-10-17T15:00:00-03:00")
        mundo (mundo/resumo instant)
        fechada (first (:areas-indisponiveis mundo))
        desconhecida (try
                       (hunt/montar-plano {:area-pedida "deserto"
                                           :instant instant
                                           :trainer-level 1
                                           :roll-tipos 0 :roll-surto 99})
                       nil
                       (catch Exception e e))
        fechada-ex (try
                     (hunt/montar-plano {:area-pedida (:id fechada)
                                         :instant instant
                                         :trainer-level 1
                                         :roll-tipos 0 :roll-surto 99})
                     nil
                     (catch Exception e e))
        aberta (hunt/montar-plano {:area-pedida "verde"
                                   :instant instant
                                   :trainer-level 1
                                   :roll-tipos 90 :roll-surto 99})]
    (is (= "Dia" (get-in mundo [:periodo :nome])))
    (is (= :area_unknown (err/error-code desconhecida)))
    (is (= "deserto" (:pedido (ex-data desconhecida))))
    (is (= :area_unavailable (err/error-code fechada-ex)))
    (is (= (:id fechada) (:area-id (ex-data fechada-ex))))
    (is (string? (:resumo-texto (ex-data fechada-ex))))
    (is (= "floresta" (get-in aberta [:area :id])))))

(deftest biomas-sem-fork-de-chat-id
  (let [mundo (slurp (io/file repo-root "modules/pokemon/domain/pokemon/domain/mundo.clj"))
        hunt (slurp (io/file repo-root "modules/pokemon/domain/pokemon/domain/hunt.clj"))]
    (is (not (re-find #"chat-id|chatId" mundo)))
    (is (not (re-find #"chat-id|chatId" hunt)))
    (is (str/includes? mundo "area-aliases"))
    (is (str/includes? hunt ":area_unknown"))
    (is (str/includes? hunt ":area_unavailable"))))
