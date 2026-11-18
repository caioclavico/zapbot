(ns pokemon.domain.economy
  "Ledger de moedas: histórico vs operacional. Sem I/O.

  Regras:
  - Nunca somar automaticamente V1 + V2 + operacional + histórico unknown.
  - unknown-pending: gastável = coins-operational apenas.
  - Crédito operacional NÃO promove economy-status nem libera histórico.
  - trusted (pré-ledger): bootstrap copia :coins → operacional (continuidade A6).
  - recovery: bootstrap operacional=0; NÃO copia :coins (placeholder/histórico)."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.shop :as shop]))

(def statuses
  #{"trusted" "unknown-pending" "reconciled"})

(def historical-statuses
  #{"unknown" "declared" "reconciled" "rejected" "none"})

(defn economy-status
  "Inferência compatível com saves pré-028."
  [save]
  (or (some-> (:economy-status save) str)
      (if (shop/economia-confiavel? save) "trusted" "unknown-pending")))

(defn historical-status
  [save]
  (or (some-> (:coins-historical-status save) str)
      (if (= "unknown-pending" (economy-status save)) "unknown" "none")))

(defn coins-operational
  [save]
  (long (or (:coins-operational save) 0)))

(defn coins-historical-amount
  "Nil se não declarado/reconciliado."
  [save]
  (when-let [st (historical-status save)]
    (when (contains? #{"declared" "reconciled"} st)
      (long (or (:coins-historical-amount save) 0)))))

(defn ledger-bootstrapped?
  [save]
  (some? (some-> (:economy-status save) str)))

(defn bootstrap-state
  "Estado inicial do ledger sem mutar histórico unknown.
   trusted: operacional = :coins atual (continuidade).
   unknown-pending: operacional = 0; não lê :coins como saldo."
  [save]
  (if (shop/economia-confiavel? save)
    (let [c (long (or (:coins save) 0))]
      {:economy-status "trusted"
       :coins-operational c
       :coins-operational-earned c
       :coins-operational-spent 0
       :coins-historical-status "none"})
    {:economy-status "unknown-pending"
     :coins-operational 0
     :coins-operational-earned 0
     :coins-operational-spent 0
     :coins-historical-status "unknown"}))

(defn with-ledger-defaults
  "Merge bootstrap se ainda não houver economy-status (leitura pura)."
  [save]
  (if (ledger-bootstrapped? save)
    save
    (merge save (bootstrap-state save))))

(defn spendable-coins
  "Saldo comprovadamente gastável. Nunca inclui histórico unknown/pending.
   Pré-bootstrap: trusted usa :coins; unknown-pending → 0."
  [save]
  (let [s (with-ledger-defaults save)
        st (economy-status s)
        op (coins-operational s)]
    (case st
      "unknown-pending" op
      "reconciled" (+ op (long (or (coins-historical-amount s) 0)))
      ;; trusted
      op)))

(defn can-spend?
  [save amount]
  (>= (spendable-coins save) (long amount)))

(defn- safe-add
  [coins delta]
  (let [next (+ (long coins) (long delta))]
    (when (neg? next)
      (throw (err/error :insufficient_coins {:coins coins :delta delta})))
    next))

(defn assert-can-spend!
  "Compra/débito: usa só saldo comprovado. Não promove status.
   Se recovery sem operacional mas com :coins placeholder → economy_unreliable."
  [save amount]
  (let [need (long amount)
        avail (spendable-coins save)
        st (economy-status (with-ledger-defaults save))]
    (cond
      (>= avail need)
      avail

      (and (= st "unknown-pending")
           (zero? avail)
           (pos? (long (or (:coins save) 0))))
      (throw (err/error :economy_unreliable
                        {:reason :historical-not-spendable
                         :coins-placeholder (long (:coins save 0))
                         :coins-operational (coins-operational (with-ledger-defaults save))
                         :need need
                         :message "Saldo histórico unknown-pending não é gastável. Use apenas créditos operacionais A6."}))

      (and (= st "unknown-pending") (zero? avail))
      (throw (err/error :economy_unreliable
                        {:reason :no-proven-balance
                         :need need
                         :message "Sem saldo operacional comprovado neste save de recuperação."}))

      :else
      (throw (err/error :insufficient_coins
                        {:coins avail :need need
                         :economy-status st
                         :coins-operational (coins-operational (with-ledger-defaults save))})))))

(defn apply-operational-delta
  "Aplica delta ao estado bootstrapped. Retorna novo mapa de saldos operacionais."
  [save delta]
  (let [s (with-ledger-defaults save)
        d (long delta)
        op (coins-operational s)
        next (safe-add op d)
        earned (long (or (:coins-operational-earned s) op))
        spent (long (or (:coins-operational-spent s) 0))]
    {:economy-status (economy-status s)
     :coins-operational next
     :coins-operational-earned (if (pos? d) (+ earned d) earned)
     :coins-operational-spent (if (neg? d) (+ spent (- d)) spent)
     :coins-historical-status (historical-status s)
     :operational-before op
     :mirror-legacy-coins? (contains? #{"trusted" "reconciled"} (economy-status s))}))

(defn ledger-key
  [save-key operation-id role]
  (str save-key "|" operation-id "|" (or role "primary")))

(defn assert-not-mixing-ledgers
  []
  {:rule :separate-ledgers
   :never-auto-sum [:v1 :v2 :operational :historical-unknown]
   :operational-does-not-unlock-historical true})
