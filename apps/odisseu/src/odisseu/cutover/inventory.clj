(ns odisseu.cutover.inventory
  "Inventário M8.0 — entidades Cassandra → destino Datomic.
   Somente classificação; sem acesso a produção."
  (:require [clojure.set :as set]))

(def platform-keys
  #{"bloqueio" "admins-conhecidos" "enquetes" "quiz-historico"
    "rank" "lembretes" "bugs" "participantes"})

(def pokemon-live-keys
  #{"treinador" "loja" "ginasios" "ginasios-estatisticas" "raids"
    "pokemon-descobertas" "pokedex-cache"
    "pokemon-batalhas-ativas" "pokemon-cacadas-ativas"})

(def pokemon-slot-keys
  #{"pokemon-save-slots" "pokemon-save-slot-index"
    "pokemon-save-global-receipts" "pokemon-playable-save-pointer"
    "pokemon-playable-save-a" "pokemon-playable-save-b"})

(def pokemon-restore-keys
  #{"pokemon-restauracao-journals" "pokemon-restauracao-backups"})

(def node-ephemeral-keys
  #{"pokemon-http-deliveries"})

(def rebuildable-keys
  #{"pokedex-cache"})

(def quiesce-keys
  #{"pokemon-batalhas-ativas" "pokemon-cacadas-ativas"})

(def domain-catalog
  "Cada domínio: destino Datomic, status de migração, notas de cutover."
  [{:id :bloqueio
    :cassandra-key "bloqueio"
    :datomic? true :milestone "M7.1"
    :notes "put-block-state! / Bridge HTTP"}
   {:id :admins
    :cassandra-key "admins-conhecidos"
    :datomic? true :milestone "M7.1"}
   {:id :enquetes
    :cassandra-key "enquetes"
    :datomic? true :milestone "M7.2"
    :notes "Uma enquete aberta por chat"}
   {:id :quiz-historico
    :cassandra-key "quiz-historico"
    :datomic? true :milestone "M7.3"}
   {:id :rank
    :cassandra-key "rank"
    :datomic? true :milestone "M7.4"
    :notes "operation-id estável; não somar snapshots"}
   {:id :lembretes
    :cassandra-key "lembretes"
    :datomic? true :milestone "M7.5"
    :notes "Import só pending; sem poller durante import"}
   {:id :bugs
    :cassandra-key "bugs"
    :datomic? true :milestone "M7.6"}
   {:id :participantes
    :cassandra-key "participantes"
    :datomic? true :milestone "M7.6"
    :notes "Mensagens !resuma ficam só em RAM — não migrar"}
   {:id :pokemon-treinador-loja
    :cassandra-keys ["treinador" "loja"]
    :datomic? true :milestone "M5.0–M5.5"
    :notes "Dois saves A/B; proveniência obrigatória; sem ensure-trainer no import"}
   {:id :pokemon-save-slots
    :cassandra-keys (vec pokemon-slot-keys)
    :datomic? true :milestone "M5.1–M5.3"
    :notes "Históricos Etapa 1; matching 26 vs 119; snapshot 14/10 = evidência, não overwrite"}
   {:id :pokemon-joy
    :cassandra-key "treinador (enfermaria)"
    :datomic? true :milestone "M5.5"
    :notes "Leitos enfermaria-bed; preservar pronto-em/UUID"}
   {:id :ginasios
    :cassandra-keys ["ginasios" "ginasios-estatisticas"]
    :datomic? true :milestone "M3/M4 gym"
    :notes "WIP ginásio store.clj separado do cutover M8"}
   {:id :raids
    :cassandra-key "raids"
    :datomic? true :milestone "M3.6/M4.9"
    :notes "Estado ativo; quiescência recomendada"}
   {:id :pokedex-cache
    :cassandra-key "pokedex-cache"
    :datomic? false :status :rebuildable
    :notes "Cache PokeAPI — regenerável; opcional no cutover"}
   {:id :batalhas-cacadas-ativas
    :cassandra-keys ["pokemon-batalhas-ativas" "pokemon-cacadas-ativas"]
    :datomic? false :status :quiesce-required
    :notes "Estado em voo; freeze writers antes do snapshot"}
   {:id :http-deliveries
    :cassandra-key "pokemon-http-deliveries"
    :datomic? false :status :ephemeral
    :notes "Staging M7.7 confirma em memória; não migrar"}
   {:id :restauracao
    :cassandra-keys (vec pokemon-restore-keys)
    :datomic? false :status :decision-required
    :notes "Journals de restore — não copiar como save ativo"}])

(defn classify-module-keys
  "Classifica chaves presentes num dump {:modules …} ou estado_particionado."
  [module-keys]
  (let [ks (set (map str module-keys))
        known (set/union platform-keys pokemon-live-keys pokemon-slot-keys
                         pokemon-restore-keys node-ephemeral-keys)
        platform (set/intersection ks platform-keys)
        pokemon-core (set/intersection ks
                                       (set/difference
                                        (set/union pokemon-live-keys pokemon-slot-keys)
                                        (set/union rebuildable-keys quiesce-keys)))
        rebuildable (set/intersection ks rebuildable-keys)
        quiesce (set/intersection ks quiesce-keys)
        ephemeral (set/intersection ks node-ephemeral-keys)
        restore (set/intersection ks pokemon-restore-keys)
        unknown (set/difference ks known)]
    {:platform (sort platform)
     :pokemon (sort pokemon-core)
     :rebuildable (sort rebuildable)
     :quiesce-required (sort quiesce)
     :ephemeral (sort ephemeral)
     :restore (sort restore)
     :unknown (sort unknown)
     :missing-platform (sort (set/difference platform-keys platform))
     :datomic-ready (sort (set/union platform pokemon-core))
     :decision-required (sort (set/union unknown restore ephemeral rebuildable quiesce))}))

(defn modules-from-dump
  [dump]
  (or (when (map? (:modules dump)) (keys (:modules dump)))
      (->> (:estado_particionado dump)
           (map :modulo)
           (map str)
           distinct)))

(defn inventory-dump
  "Inventário de um dump sintético/autorizado offline."
  [dump]
  (let [keys* (modules-from-dump dump)
        classified (classify-module-keys keys*)]
    {:meta (:meta dump)
     :module-keys (sort (map str keys*))
     :classified classified
     :catalog domain-catalog
     :gaps
     {:missing-platform-in-dump (:missing-platform classified)
      :unknown-modules (:unknown classified)
      :decision-required (:decision-required classified)}
     :at (str (java.time.Instant/now))}))
