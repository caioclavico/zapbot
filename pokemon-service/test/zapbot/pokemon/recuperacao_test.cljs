(ns zapbot.pokemon.recuperacao-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.recuperacao :as recuperacao]))

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
              (is (re-find #"restauração ainda" bloqueado))
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
