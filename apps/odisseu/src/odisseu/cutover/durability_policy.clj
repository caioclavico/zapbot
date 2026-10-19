(ns odisseu.cutover.durability-policy
  "M8.6 — política explícita de durabilidade (optional vs required-strict).
   Default :optional preserva comportamento legado (sem journal = pass-through).
   :required-strict exige journal configurado e fail-closed sob manutenção."
  (:require [clojure.string :as str]
            [odisseu.cutover.recovery :as recovery]
            [pokemon.domain.errors :as err]))

(def modes #{:optional :required-strict})

(defn normalize
  "cfg: {:mode :journal-dir :maint-dir :policy :hooks}
   :policy = política do durable-op (:strict|:best-effort), default :strict."
  [cfg]
  (let [mode (keyword (or (:mode cfg) :optional))]
    (when-not (contains? modes mode)
      (throw (ex-info "durability_invalid_mode" {:mode mode :allowed modes})))
    {:mode mode
     :journal-dir (:journal-dir cfg)
     :maint-dir (:maint-dir cfg)
     :policy (keyword (or (:policy cfg) :strict))
     :hooks (or (:hooks cfg) {})}))

(defn journal-configured?
  [cfg]
  (not (str/blank? (str (:journal-dir (normalize cfg))))))

(defn maintenance?
  [maint-dir]
  (boolean (and maint-dir
                (:maintenance? (recovery/maintenance-status maint-dir)))))

(defn assert-ready!
  "Valida política antes de mutação protegida.
   :optional + sem journal → ok (caller faz pass-through).
   :required-strict + sem journal → :durability_journal_required.
   Qualquer modo + manutenção → :durability_maintenance."
  [cfg]
  (let [c (normalize cfg)]
    (when (and (= :required-strict (:mode c))
               (not (journal-configured? c)))
      (throw (err/error :durability_journal_required
                        {:mode :required-strict
                         :hint "Configure journal-dir ou desabilite required-strict."})))
    (when (and (journal-configured? c)
               (maintenance? (:maint-dir c)))
      (throw (err/error :durability_maintenance
                        {:maint-dir (:maint-dir c)
                         :hint "Reconciliar uncertain antes de novas escritas."})))
    c))
