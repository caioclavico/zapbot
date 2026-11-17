(ns pokemon.domain.species-hp-freeze
  "Política de congelamento de combates durante backfill Species-HP.

  Pura / documental para ensaio. Em produção o freeze operacional usa o
  maintenance-gate existente da bridge (enter → draining → quiescent) —
  este namespace classifica operações e define o checklist; NÃO altera o gate live."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def freeze-version "species-hp-freeze-v1")

(def hp-mutating-ops
  "Ops da bridge que podem alterar HP de saves (bloquear durante backfill)."
  #{:encounter-capture :battle-start :battle-turn :battle-leave
    :hunt-start :gym-challenge :gym-heal
    :raid-join :raid-start :raid-attack :raid-capture
    :pvp-open :pvp-accept :pvp-turn
    :joy-send :joy-collect
    :pokemon-evolve :pokemon-set-active
    :item-use :egg-hatch
    :recovery-import})

(def read-safe-ops
  "Ops de leitura / status permitidas sob freeze (não mutam HP)."
  #{:trainer-read :collection-read :active-save-read :saves-list
    :missions-read :gyms-read :raid-read :encounter-read
    :joy-read :shop-read :inventory-read :adventure-read
    :pvp-active-read :onboarding-read :operation-get
    :events-pending :profile-friendship :profile-titles
    :named-teams-read :team-csv-export :professor-cards
    :platform-blocks :platform-admins})

(defn normalize-op
  [op]
  (cond
    (keyword? op) op
    (string? op) (keyword (str/replace (str/lower-case op) #"_" "-"))
    :else nil))

(defn hp-mutating-op?
  "True se a op pode modificar HP/coleção durante backfill."
  [op]
  (contains? hp-mutating-ops (normalize-op op)))

(defn allowed-during-freeze?
  "Sob freeze estrito: só leituras classificadas. Ops desconhecidas → bloqueadas."
  [op]
  (let [k (normalize-op op)]
    (boolean (and k (contains? read-safe-ops k) (not (hp-mutating-op? k))))))

(defn freeze-plan
  "Checklist operacional (não executa). Compatível com maintenance-gate."
  []
  {:version freeze-version
   :steps
   [{:id :enter-maintenance
     :action "bridge maintenance enter (draining→quiescent)"
     :blocks "todas as writes da bridge via acquire! fail-closed"}
   {:id :drain-active
     :action "aguardar active-requests=0; não matar TX em voo"}
   {:id :block-node-commands
     :action "Node respeita 503 maintenance_active; não iniciar hunt/raid/pvp/gym"}
   {:id :verify-no-active-combat
     :action "query RO battle/active? e raid fases lobby|combate|captura = 0"}
   {:id :schema-035
     :action "aplicar schema 035 uma vez (GO separado)"}
   {:id :batch-migrate
     :action "lotes idempotentes species-hp-migrate"}
   {:id :verify
     :action "agregados v1 + quarantine + amostra faint/partial"}
   {:id :resume
     :action "maintenance resume só após quiescent + verify"}]
   :hp-mutating-ops hp-mutating-ops
   :read-safe-ops read-safe-ops
   :notes
   ["Não modificar o maintenance gate de produção nesta tarefa."
    "Freeze = reuse do gate existente; este ns só classifica ops."
    "Efeitos pendentes (events-pending) podem ser ack após reopen se não mutarem HP."]})

(defn assert-disjoint-classification
  "Invariante de teste: nenhum op em ambos os conjuntos."
  []
  (empty? (set/intersection hp-mutating-ops read-safe-ops)))
