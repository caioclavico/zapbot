(ns pokemon-service.save-slots
  "Snapshots históricos imutáveis e a ativação jogável (quando as flags de
  transição estão ligadas). Sem flags, import/listagem não escrevem no
  treinador ativo; ativar-save! é no-op desabilitado."
  (:require [promesa.core :as p]
            [zapbot.armazenamento :as storage]
            [zapbot.config :as config]))

(defonce store (atom nil))

(defn initialize! []
  (let [module (js/require "../runtime/save-slot-store.cjs")]
    (reset! store (new (.-SaveSlotStore module) storage/save-slot-executar! config/cassandra-keyspace))))

(defn- parse-json [value]
  (try (js/JSON.parse value)
       (catch :default _ (throw (js/Error. "source_unavailable")))))

(defn selecionar-origem
  "Seleciona uma conta histórica pelo par autenticado grupo/jogador, sem
  devolver nem procurar registros de outras partições."
  [dados cid pid versao]
  (let [registros (case versao
                    "1" (.-particionado dados)
                    "2" (.-antigo dados)
                    nil)]
      (when-not (and registros (= 1 (alength registros)))
      (throw (js/Error. "source_unavailable")))
      (let [documento (parse-json (aget registros 0))
          conta (case versao
                  "1" (aget documento pid)
                  "2" (when-let [grupo (aget documento cid)] (aget grupo pid))
                  nil)]
      (when-not (and conta (= "object" (js* "typeof ~{}" conta)) (not (js/Array.isArray conta)))
        (throw (js/Error. "source_unavailable")))
      conta)))

(defn importar-legado!
  "Copia V1 ou V2 para um slot ainda inexistente. A origem vem da consulta
  autenticada ao legado; o treinador ativo não é alterado."
  [cid pid slot-id versao]
  (if-not @store
    (p/rejected (js/Error. "save_slots_unavailable"))
    (if-not (contains? #{"1" "2"} versao)
      (p/rejected (js/Error. "invalid_slot_source"))
      (p/then
       (storage/consultar-treinador-legado! cid)
       (fn [dados]
         (let [account (selecionar-origem (clj->js dados) cid pid versao)
               source (if (= versao "1")
                        #js {:kind "legacy_estado_particionado" :version "1"}
                        #js {:kind "legacy_estado" :version "2"})]
           (p/then
            (storage/restauracao-ler-grupo! cid)
            (fn [grupo]
              (.importValidated @store #js {:chat cid :player pid :slotId slot-id
                                            :source source :account account
                                            :currentAccount (aget grupo pid)})))))))))

(defn listar [cid pid]
  (if @store (.list @store cid pid)
      (p/rejected (js/Error. "save_slots_unavailable"))))

(defn metadados [cid pid slot-id]
  (if @store (.metadata @store cid pid slot-id)
      (p/rejected (js/Error. "save_slots_unavailable"))))

(defn ativar-save!
  "Ativa o slot e hidrata automaticamente o jogador nos atoms (peers intactos).
  Com SAVE_TRANSITIONS_ENABLED=false retorna disabled sem alterar memória."
  [cid pid slot-id operation-id]
  (storage/ativar-save-jogavel! cid pid slot-id operation-id))

(defn hidratar-jogador!
  "Revalida ponteiro/hash e reinstala só esse jogador. Idempotente."
  [cid pid]
  (storage/hidratar-jogador-ativo! cid pid))
