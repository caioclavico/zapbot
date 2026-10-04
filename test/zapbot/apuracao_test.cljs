(ns zapbot.apuracao-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [zapbot.apuracao :as apuracao]))

(defn- candidato [n nmu votos pvap & [extra]]
  (merge {:n n :nmu nmu :vap votos :pvap pvap :dvt "Válido" :e "n" :st ""} extra))

(defn- dados-cargo
  "Estrutura do TSE com um partido por candidato: [[sigla candidato] ...]."
  [nome vagas pst candidatos]
  {:dt "04/10/2026" :ht "19:29:19" :s {:pst pst}
   :carg [{:nmn nome :nv vagas
           :agr (mapv (fn [[sg c]] {:par [{:sg sg :cand [c]}]}) candidatos)}]})

(deftest presidente-no-brasil-formata-dados-oficiais
  (let [texto (apuracao/formatar-dados
               {:dt "04/10/2026" :ht "17:30:00"
                :s {:st "250" :ts "500" :pst "50,00"}
                :carg [{:cd "1"
                        :agr [{:par [{:sg "PT" :cand [{:n "13" :nmu "LULA" :vap "1000" :pvap "55,56"}]}
                                     {:sg "PL" :cand [{:n "22" :nmu "BOLSONARO" :vap "800" :pvap "44,44"}]}]}]}]})]
    (is (str/includes? texto "250/500 (50,00%)"))
    (is (str/includes? texto "*Atualização:* 17:30:00 de 04/10/2026"))
    (is (str/includes? texto "1. *LULA* (PT 13): 1.000 votos — 55,56%"))
    (is (< (.indexOf texto "LULA") (.indexOf texto "BOLSONARO")))))

(deftest cargo-mostra-vagas-andamento-e-so-os-mais-votados
  (let [texto (apuracao/formatar-cargo
               (dados-cargo "Senador" "2" "88,89"
                            [["PL" (candidato "222" "ANDRÉ DO PRADO" "300" "30,00")]
                             ["PSOL" (candidato "500" "ERIKA" "500" "50,00")]
                             ["NOVO" (candidato "300" "MARINA" "200" "20,00")]])
               "Senador" 2)]
    (is (str/starts-with? texto "*Senador* (2 vagas) — 88,89% das seções · 19:29:19\n"))
    (is (str/includes? texto "1. *ERIKA* (PSOL 500): 500 votos — 50,00%"))
    (is (str/includes? texto "2. *ANDRÉ DO PRADO* (PL 222): 300 votos — 30,00%"))
    (is (not (str/includes? texto "MARINA")))))

(deftest cargo-de-uma-vaga-nao-mostra-contagem-de-vagas
  (let [texto (apuracao/formatar-cargo
               (dados-cargo "Governador" "1" "76,22"
                            [["REPUBLICANOS" (candidato "10" "TARCÍSIO" "11062407" "63,26")]])
               "Governador" 5)]
    (is (str/starts-with? texto "*Governador* — 76,22% das seções"))
    (is (str/includes? texto "1. *TARCÍSIO* (REPUBLICANOS 10): 11.062.407 votos — 63,26%"))))

(deftest eleitos-vem-primeiro-e-sao-marcados
  (let [texto (apuracao/formatar-cargo
               (dados-cargo "Deputado Federal" "70" "100,00"
                            [["PL" (candidato "1" "MAIS VOTADO" "900" "9,00")]
                             ["PT" (candidato "2" "ELEITO" "100" "1,00" {:e "s" :st "Eleito por QP"})]
                             ["MDB" (candidato "3" "SUB JUDICE" "50" "0,50" {:dvt "Anulado sub judice"})]])
               "Deputado Federal" 3)]
    (is (< (.indexOf texto "ELEITO") (.indexOf texto "MAIS VOTADO")))
    (is (str/includes? texto "(PT 2): 100 votos — 1,00% ✅"))
    (is (not (str/includes? texto "(PL 1): 900 votos — 9,00% ✅")))
    (is (str/includes? texto "(MDB 3): 50 votos — 0,50% (sub judice)"))))

(deftest segundo-turno-e-sinalizado
  (let [texto (apuracao/formatar-cargo
               (dados-cargo "Governador" "1" "95,00"
                            [["PT" (candidato "13" "FULANO" "600" "40,00" {:st "2º turno"})]
                             ["PL" (candidato "22" "BELTRANO" "500" "35,00")]])
               "Governador" 5)]
    (is (str/includes? texto "FULANO* (PT 13): 600 votos — 40,00% ➡️ 2º turno"))
    (is (not (str/includes? texto "BELTRANO* (PL 22): 500 votos — 35,00% ➡️")))))

(deftest segundo-turno-do-tse-nao-recebe-selo-de-eleito
  (let [texto (apuracao/formatar-cargo
               (dados-cargo "Governador" "1" "100,00"
                            [["PP" (candidato "11" "CELINA" "825" "49,93" {:e "s" :st "2º turno"})]
                             ["PT" (candidato "13" "LEANDRO" "569" "34,47" {:e "s" :st "2º turno"})]
                             ["NOVO" (candidato "30" "KIKO" "72" "4,36")]])
               "Governador" 5)]
    (is (str/includes? texto "CELINA* (PP 11): 825 votos — 49,93% ➡️ 2º turno"))
    (is (str/includes? texto "LEANDRO* (PT 13): 569 votos — 34,47% ➡️ 2º turno"))
    (is (not (str/includes? texto "✅")))))

(defn- jws [dados]
  (str "cabecalho."
       (.toString (js/Buffer.from (js/JSON.stringify (clj->js dados)) "utf8") "base64url")
       ".assinatura"))

(defn- resposta-ok [dados]
  #js {:ok true :status 200 :text (fn [] (js/Promise.resolve (jws dados)))})

(defn- codigo-do-cargo [url]
  (second (re-find #"-c(\d{4})-e" url)))

(def ^:private nomes-por-codigo
  {"0001" "Presidente" "0003" "Governador" "0005" "Senador"
   "0006" "Deputado Federal" "0007" "Deputado Estadual" "0008" "Deputado Distrital"})

(defn- dados-do-url [url]
  (let [nome (nomes-por-codigo (codigo-do-cargo url))]
    (-> (dados-cargo nome "1" "50,00" [["PL" (candidato "1" (str "CAND " nome) "10" "100,00")]])
        (assoc-in [:carg 0 :cd] "1"))))

(defn- com-fetch [fetch-falso acao]
  (let [original js/global.fetch]
    (set! js/global.fetch fetch-falso)
    (-> (acao)
        (.finally (fn [] (set! js/global.fetch original))))))

(def ^:private base "https://resultados.tse.jus.br/oficial/ele2026/")

(deftest estado-busca-presidente-e-cargos-estaduais-nas-eleicoes-certas
  (async done
    (let [urls (atom [])]
      (-> (com-fetch (fn [url & _]
                       (swap! urls conj url)
                       (js/Promise.resolve (resposta-ok (dados-do-url url))))
                     #(apuracao/buscar-apuracao " Sp "))
          (.then (fn [texto]
                   (is (= #{(str base "6257/dados/sp/sp-c0001-e006257-u.jws")
                            (str base "6259/dados/sp/sp-c0003-e006259-u.jws")
                            (str base "6259/dados/sp/sp-c0005-e006259-u.jws")
                            (str base "6259/dados/sp/sp-c0006-e006259-u.jws")
                            (str base "6259/dados/sp/sp-c0007-e006259-u.jws")}
                          (set @urls)))
                   (is (str/includes? texto "Apuração 2026 — São Paulo (SP)"))
                   (let [posicoes (map #(.indexOf texto %)
                                       ["*Presidente*" "*Governador*" "*Senador*"
                                        "*Deputado Federal*" "*Deputado Estadual*"])]
                     (is (every? #(>= % 0) posicoes))
                     (is (= posicoes (sort posicoes))))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest distrito-federal-usa-deputado-distrital
  (async done
    (let [urls (atom [])]
      (-> (com-fetch (fn [url & _]
                       (swap! urls conj url)
                       (js/Promise.resolve (resposta-ok (dados-do-url url))))
                     #(apuracao/buscar-apuracao "DF"))
          (.then (fn [texto]
                   (is (some #(str/includes? % "df-c0008-e006259") @urls))
                   (is (not-any? #(str/includes? % "-c0007-") @urls))
                   (is (str/includes? texto "*Deputado Distrital*"))
                   (is (not (str/includes? texto "*Deputado Estadual*")))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest cargo-indisponivel-nao-derruba-o-resto
  (async done
    (-> (com-fetch (fn [url & _]
                     (js/Promise.resolve
                      (if (= "0005" (codigo-do-cargo url))
                        #js {:ok false :status 404}
                        (resposta-ok (dados-do-url url)))))
                   #(apuracao/buscar-apuracao "rj"))
        (.then (fn [texto]
                 (is (str/includes? texto "*Senador* — indisponível no momento."))
                 (is (str/includes? texto "CAND Governador"))
                 (is (str/includes? texto "CAND Deputado Federal"))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest sem-nenhum-dado-retorna-erro-amigavel
  (async done
    (-> (com-fetch (fn [& _] (js/Promise.reject (js/Error. "sem rede")))
                   #(apuracao/buscar-apuracao "mg"))
        (.then (fn [texto]
                 (is (str/starts-with? texto "❌ Não consegui buscar a apuração de Minas Gerais"))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest uf-invalida-nao-consulta-o-tse
  (async done
    (let [chamadas (atom 0)]
      (-> (com-fetch (fn [& _]
                       (swap! chamadas inc)
                       (js/Promise.reject (js/Error. "nao deveria consultar")))
                     #(apuracao/buscar-apuracao "xx"))
          (.then (fn [texto]
                   (is (str/includes? texto "UF inválida"))
                   (is (zero? @chamadas))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest sem-uf-ou-com-br-consulta-so-o-presidente-nacional
  (async done
    (let [urls (atom [])
          fetch-falso (fn [url & _]
                        (swap! urls conj url)
                        (js/Promise.resolve (resposta-ok (dados-do-url url))))]
      (-> (com-fetch fetch-falso #(apuracao/buscar-apuracao nil))
          (.then (fn [sem-uf]
                   (is (str/includes? sem-uf "Apuração presidencial 2026"))
                   (is (str/includes? sem-uf "CAND Presidente"))
                   (com-fetch fetch-falso #(apuracao/buscar-apuracao "BR"))))
          (.then (fn [_]
                   (is (= [(str base "6257/dados/br/br-c0001-e006257-u.jws")
                           (str base "6257/dados/br/br-c0001-e006257-u.jws")]
                          @urls))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))
