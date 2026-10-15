(ns zapbot.pokemon.recuperacao-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.recuperacao :as recuperacao]
            [zapbot.pokemon.recuperacao-cartao :as cartao]))

(def cid "123@g.us")
(def pid "456@lid")
(def save {"equipe" [{"nome" "Pikachu"}] "pc" [] "ginasios" {"pedra" "dia"}})
(defn json [x] (js/JSON.stringify (clj->js x)))

(deftest identidade-exata-e-isolamento
  (doseq [id ["456@lid" "456@c.us"]]
    (let [dados {:particionado [(json {id save})] :antigo []}]
      (is (= :encontrado (:status (recuperacao/localizar dados cid id))))
      (is (= :ausente (:status (recuperacao/localizar dados cid "999@lid"))))))
  (is (= :ausente (:status (recuperacao/localizar
                            {:antigo [(json {"outro@g.us" {pid save}})]} cid pid))))
  (is (= :ausente (:status (recuperacao/localizar
                            {:particionado [(json {"456@c.us" save})]} cid pid)))))

(deftest formatos-conflitantes-invalidos-e-iguais
  (is (= :ambiguo (:status (recuperacao/localizar
                            {:particionado [(json {pid save})]
                             :antigo [(json {cid {pid (assoc save "equipe" [])}})]} cid pid))))
  (is (= :encontrado (:status (recuperacao/localizar
                               {:particionado [(json {pid save})]
                                :antigo [(json {cid {pid save}})]} cid pid))))
  (doseq [valor ["broken" "[]" (json {pid {"equipe" "invalid"}})]]
    (is (= :invalido (:status (recuperacao/localizar {:particionado [valor]} cid pid))))))

(deftest comando-consulta-sem-mutar-progresso
  (async done
    (let [consultas (atom [])]
      (with-redefs [storage/consultar-treinador-legado!
                    (fn [chat] (swap! consultas conj chat)
                      (p/resolved {:particionado [(json {pid save})]}))
                    storage/obter (fn [_] {cid {pid save}})
                    storage/salvar! (fn [& _] (throw (js/Error. "Não deve gravar")))
                    core/navegar (fn [& _] (throw (js/Error. "Não deve executar jogo")))]
        (let [pedido (core/jogar {:chat-id cid :player-id pid} "recuperar")]
        (-> (p/let [texto pedido
                    _ (is (re-find #"SAVE ANTIGO" texto))
                    _ (is (re-find #"SAVE ATUAL" texto))
                    _ (is (not (re-find #"456|Pikachu" texto)))
                    _ (is (= [cid] @consultas))
                    bloqueado (core/jogar {:chat-id cid :player-id pid} "recuperar confirmar 999@lid")]
              (is (re-find #"Comando inválido" bloqueado))
              (is (= [cid] @consultas)))
            (p/catch #(is false (str %)))
            (p/finally done)))))))

(deftest ausente-atual-antigo-e-falha-cassandra
  (async done
    (with-redefs [storage/obter (fn [_] {})
                  storage/consultar-treinador-legado! (fn [_] (p/resolved {}))]
      (let [pedido (recuperacao/consultar! {:chat-id cid :player-id pid} [])]
      (-> (p/let [sem-save pedido]
            (is (re-find #"Não encontramos" sem-save)))
          (p/catch #(is false (str %)))
          (p/finally done))))))

(deftest falha-cassandra-nao-expoe-erro
  (async done
    (with-redefs [storage/consultar-treinador-legado!
                  (fn [_] (p/rejected (js/Error. "SECRET-999@lid")))]
      (-> (recuperacao/consultar! {:chat-id cid :player-id pid} [])
          (p/then (fn [texto]
                    (is (re-find #"Não foi possível consultar" texto))
                    (is (not (re-find #"SECRET|999" texto)))))
          (p/finally done)))))

(deftest consultas-sao-selects-por-chave
  (async done
    (let [consultas (atom [])
          driver #js {:execute (fn [sql params options]
                                (swap! consultas conj [sql (js->clj params)])
                                (p/resolved #js {:rows #js []}))}]
      (with-redefs [storage/client (atom driver)]
        (-> (storage/consultar-treinador-legado! cid)
            (p/then (fn [_]
                      (is (= 2 (count @consultas)))
                      (is (= ["treinador" cid] (second (first @consultas))))
                      (is (= ["treinador"] (second (second @consultas))))
                      (doseq [[sql _] @consultas]
                        (is (re-find #"^SELECT valor FROM .*\.legacy_estado" sql))
                        (is (not (re-find #"ALLOW FILTERING|INSERT|UPDATE|DELETE" sql))))))
            (p/catch #(is false (str %)))
            (p/finally done))))))

(deftest somente-antigo-ou-somente-atual
  (async done
    (with-redefs [storage/obter (fn [_] {})
                  storage/consultar-treinador-legado!
                  (fn [_] (p/resolved {:antigo [(json {cid {pid save}})]}))]
      (-> (recuperacao/consultar! {:chat-id cid :player-id pid} [])
          (p/then (fn [texto]
                    (is (re-find #"SAVE ANTIGO" texto))
                    (is (re-find #"Nenhum progresso atual disponível" texto))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest multiplos-treinadores-no-mesmo-grupo
  (let [outra (assoc save "equipe" [{"nome" "SEGREDO-TERCEIRO"} {"nome" "Outro"}])
        dados {:particionado [(json {pid save "999@lid" outra})]
               :antigo [(json {cid {pid save "999@lid" outra}})]}]
    (is (= save (:conta (recuperacao/localizar dados cid pid))))
    (is (= :ausente (:status (recuperacao/localizar dados cid "123@lid"))))
    (is (= :ambiguo (:status (recuperacao/localizar
                              (assoc dados :antigo [(json {cid {pid outra "999@lid" save}})])
                              cid pid))))))

(deftest consulta-intercepta-antes-de-coleta-e-xp
  (async done
    (with-redefs [storage/consultar-treinador-legado! (fn [_] (p/resolved {}))
                  storage/obter (fn [_] {})
                  storage/salvar! (fn [& _] (throw (js/Error. "Gravação indevida")))
                  core/navegar (fn [& _] (throw (js/Error. "Navegação indevida")))
                  core/jogar-comando (fn [& _] (throw (js/Error. "Jogo indevido")))]
      (-> (core/jogar {:chat-id cid :player-id pid :mentioned-ids ["999@lid"]} "recuperar")
          (p/then #(is (re-find #"associado com segurança" %)))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest versoes-divergentes-com-origem-test
  (let [cid "123@g.us"
        pid "456@lid"
        conta-a {"equipe" [{"id" 1}] "pc" []}
        conta-b {"equipe" [{"id" 1} {"id" 2}] "pc" []}
        dados {:grupo cid
               :particionado [(js/JSON.stringify (clj->js {pid conta-a}))]
               :antigo [(js/JSON.stringify (clj->js {cid {pid conta-b}}))]}
        resultado (recuperacao/localizar dados cid pid)]
    (is (= :ambiguo (:status resultado)))
    (is (= 2 (count (:versoes resultado))))
    (is (= ["Particionado"] (get-in resultado [:versoes 0 :origens])))
    (is (= ["Estado completo"] (get-in resultado [:versoes 1 :origens])))
    (is (clojure.string/includes? (recuperacao/resumo-versoes (:versoes resultado))
                       "Pokémon guardados: 2"))))

(deftest versoes-identicas-nao-duplicadas-test
  (let [cid "123@g.us"
        pid "456@lid"
        conta {"equipe" [{"id" 1}] "pc" []}
        dados {:grupo cid
               :particionado [(js/JSON.stringify (clj->js {pid conta}))]
               :antigo [(js/JSON.stringify (clj->js {cid {pid conta}}))]}
        resultado (recuperacao/localizar dados cid pid)]
    (is (= :encontrado (:status resultado)))
    (is (= conta (:conta resultado)))))

(deftest comparacao-legado-sem-escrita-e-identidade
  (async done
    (let [a {"equipe" [{"id-pokemon" "comum" "nome" "Pikachu" "nivel" 4}
                        {"id-pokemon" "so-a" "nome" "Bulbasaur"}
                        {"nome" "SemUUID-A"}
                        {"id-pokemon" "duplicado" "nome" "Duplicado-A1"}
                        {"id-pokemon" "duplicado" "nome" "Duplicado-A2"}]
             "pc" [] "enfermaria" [{"pokemon" {"id-pokemon" "joy" "nome" "Eevee"}}]}
          b {"equipe" [{"id-pokemon" "comum" "nome" "Pikachu" "nivel" 5}
                        {"id-pokemon" "so-b" "nome" "Charmander"}
                        {"nome" "SemUUID-B"}
                        {"id-pokemon" "duplicado" "nome" "Duplicado-B"}]
             "pc" [] "enfermaria" [{"pokemon" {"id-pokemon" "joy" "nome" "Eevee"}}]}
          chamadas (atom [])]
      (let [original-consulta storage/consultar-treinador-legado!
            original-obter storage/obter
            original-salvar storage/salvar!
            original-navegar core/navegar]
        (set! storage/consultar-treinador-legado!
                    (fn [chat]
                      (swap! chamadas conj chat)
                      (p/resolved {:grupo cid
                                   :particionado [(json {pid a "999@lid" {"equipe" [{"nome" "SEGREDO"}]}})]
                                   :antigo [(json {cid {pid b}})]})))
        (set! storage/obter (fn [_] {}))
        (set! storage/salvar! (fn [& _] (throw (js/Error. "Gravação indevida"))))
        (set! core/navegar (fn [& _] (throw (js/Error. "Navegação indevida"))))
        (-> (p/let [v1 (core/jogar {:chat-id cid :player-id pid} "recuperar 1")
                    v2 (core/jogar {:chat-id cid :player-id pid} "recuperar 2")
                    comparacao (core/jogar {:chat-id cid :player-id pid} "recuperar comparar")
                    invalido (core/jogar {:chat-id cid :player-id pid} "recuperar confirmar 999@lid")]
              (is (= "image/jpeg" (get-in v1 [:media :mime])))
              (is (= "image/jpeg" (get-in v2 [:media :mime])))
              (is (pos? (.-length (get-in v1 [:media :buffer]))))
              (is (re-find #"Versão 1" (:texto v1)))
              (is (re-find #"Versão 2" (:texto v2)))
              (is (not (re-find #"restauração ainda não está disponível" (str (:texto v1) (:texto v2)))))
              (is (= "image/jpeg" (get-in comparacao [:media :mime])))
              (is (re-find #"COMPARAÇÃO DOS SAVES" (:texto comparacao)))
              (is (re-find #"V1: 6 • V2: 5" (:texto comparacao)))
              (is (re-find #"Em comum: 2" (:texto comparacao)))
              (is (re-find #"Exclusivos V1: 1" (:texto comparacao)))
              (is (re-find #"Exclusivos V2: 1" (:texto comparacao)))
              (is (not (re-find #"SEGREDO|999@lid" (str v1 v2 (:texto comparacao) invalido))))
              (is (re-find #"Comando inválido" invalido))
              (is (= [cid cid cid] @chamadas)))
            (p/catch #(is false (str %)))
            (p/finally
              (fn []
                (set! storage/consultar-treinador-legado! original-consulta)
                (set! storage/obter original-obter)
                (set! storage/salvar! original-salvar)
                (set! core/navegar original-navegar)
                (done))))))))


(deftest paginacao-legado-somente-leitura
  (async done
    (let [conta-a {"equipe" (vec (for [n (range 1 18)]
                                     {"id-pokemon" (str "a-" n)
                                      "nome" (str "PokemonA-" n)}))
                   "pc" [{"id-pokemon" "a-18" "nome" "PokemonA-18"}]
                   "enfermaria" [{"pokemon" {"id-pokemon" "a-19"
                                              "nome" "PokemonA-19"}}]}
          conta-b {"equipe" [{"id-pokemon" "b-1" "nome" "PokemonB-1"}]
                   "pc" [] "enfermaria" []}
          vazio {"equipe" [] "pc" [] "enfermaria" []}
          chamadas (atom [])
          gravacoes (atom [])
          dados {:grupo cid
                 :particionado [(json {pid conta-a "999@lid" {"equipe" [{"nome" "SEGREDO"}]}})]
                 :antigo [(json {cid {pid conta-b}})]}
          pedidos (with-redefs [storage/consultar-treinador-legado!
                               (fn [chat]
                                 (swap! chamadas conj chat)
                                 (p/resolved dados))
                               storage/obter (fn [_] {})
                               storage/salvar! (fn [& args]
                                                 (swap! gravacoes conj args)
                                                 (throw (js/Error. "Gravação indevida")))]
                    ;; Captura todas as Promises enquanto os mocks estão ativos.
                    ;; Nenhum mock precisa permanecer instalado durante as asserções.
                    (p/all [(recuperacao/consultar! {:chat-id cid :player-id pid} ["1"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "2"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "3"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["2"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["2" "2"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["comparar"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "0"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "-1"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "1.5"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "1000000"])
                            (recuperacao/consultar! {:chat-id cid :player-id pid} ["1" "2" "3"])
                            (recuperacao/consultar! {:chat-id cid :player-id "888@lid"} ["1"])]))]
      (-> pedidos
          (p/then
           (fn [[primeira segunda fora versao-b fora-b comparacao zero negativo fracao enorme extra intruso]]
             (let [p1 (recuperacao/pagina-registros conta-a 1)
                   p2 (recuperacao/pagina-registros conta-a 2)]
               (is (= (mapv #(str "a-" %) (range 1 13))
                      (mapv #(get-in % [:pokemon "id-pokemon"]) (:itens p1))))
               (is (= ["a-13" "a-14" "a-15" "a-16" "a-17" "a-18" "a-19"]
                      (mapv #(get-in % [:pokemon "id-pokemon"]) (:itens p2))))
               (is (= "Enfermaria" (:local (last (:itens p2)))))
               (let [svg (cartao/svg-cartao (:itens p1) "Versão 1" "Página 1/2")]
                 (is (re-find #"PokemonA-1" svg))
                 (is (not (re-find #"PokemonA-16" svg)))))
             (is (= "image/jpeg" (get-in primeira [:media :mime])))
             (is (= "image/jpeg" (get-in segunda [:media :mime])))
             (is (= "image/jpeg" (get-in versao-b [:media :mime])))
             (is (re-find #"Página 1 de 2" (:texto primeira)))
             (is (re-find #"Exibindo 1–12" (:texto primeira)))
             (is (re-find #"!pk recuperar 1 2" (:texto primeira)))
             (is (re-find #"Página 2 de 2" (:texto segunda)))
             (is (re-find #"Exibindo 13–19" (:texto segunda)))
             (is (re-find #"!pk recuperar 1 1" (:texto segunda)))
             (is (not (re-find #"!pk recuperar 1 3" (:texto segunda))))
             (is (re-find #"Página inexistente" fora))
             (is (re-find #"Página 1 de 1" (:texto versao-b)))
             (is (re-find #"Página inexistente" fora-b))
             (is (= "image/jpeg" (get-in comparacao [:media :mime])))
             (is (re-find #"COMPARAÇÃO DOS SAVES" (:texto comparacao)))
             (doseq [resposta [zero negativo fracao enorme extra]]
               (is (re-find #"Comando inválido" resposta)))
             (is (not (re-find #"SEGREDO|PokemonA|PokemonB" intruso)))
             (is (not (re-find #"SEGREDO|999@lid" (str (:texto primeira) (:texto segunda) (:texto versao-b) (:texto comparacao)))))
             (is (= 7 (count @chamadas)))
             (is (every? #(= cid %) @chamadas))
             (is (empty? @gravacoes))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest cartao-legado-sanitiza-svg-e-origem
  (let [svg (cartao/svg-cartao [{:local "PC" :indice 1
                                 :pokemon {"nome" "<script>&danger</script>"
                                           "nivel" 5 "id-pokemon" "id-1"}}]
                               "Versão 1" "Página 1/1")]
    (is (re-find #"&lt;script&gt;&amp;danger" svg))
    (is (not (re-find #"<script>" svg)))
    (is (re-find #"ID do Pokémon" svg)))
  (is (true? (cartao/url-confiavel?
              "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png")))
  (doseq [url ["http://localhost:9042/secret" "https://example.com/sprite.png"
               "https://raw.githubusercontent.com/evil/sprites/master/a.png"]]
    (is (false? (cartao/url-confiavel? url)))))

(deftest pagina-registros-limite-e-preserva-ordem
  (doseq [[total paginas] [[0 1] [1 1] [12 1] [13 2] [24 2] [25 3]]]
    (let [conta {"equipe" (vec (map #(hash-map "id-pokemon" (str "p-" %)) (range total)))
                 "pc" [] "enfermaria" []}
          paginas-reais (map #(recuperacao/pagina-registros conta %)
                             (range 1 (inc paginas)))
          ids (mapcat (fn [pagina]
                        (map (fn [item]
                               (get-in item [:pokemon "id-pokemon"]))
                             (:itens pagina)))
                      paginas-reais)]
      (is (= paginas (:paginas (recuperacao/pagina-registros conta 1))))
      (is (= total (count ids)))
      (is (= (map #(str "p-" %) (range total)) ids))
      (is (nil? (recuperacao/pagina-registros conta (inc paginas)))))))

(deftest renderizacao-nunca-excede-doze-entradas
  (let [entradas (mapv #(hash-map :local "Equipe" :indice (inc %) :pokemon {"nome" (str "Poke-" %)
                                                                                  "id-pokemon" (str "id-" %)})
                        (range 25))
        svg (cartao/svg-cartao entradas "Versão 1" "Página 1/3")]
    (is (= 12 (count (re-seq #"Poke-" svg))))
    (is (not (re-find #"Poke-12" svg)))))
