(ns pokemon.application.recovery-stable-id
  "Identidade estável de Pokémon para recuperação A6.4 (pura).

  Não grava Datomic/Cassandra. Não inventa id em produção.
  Propõe IDs sintéticos só quando o núcleo estável é único no escopo
  (chat, player, snapshot). Colisões → :ambiguous (humano)."
  (:require [clojure.string :as str]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.account-shape :as shape]))

(def scheme-version "a64-stable-v1")

(defn- g
  [m k]
  (when (map? m)
    (or (get m k)
        (get m (name k))
        (get m (keyword (name k))))))

(defn- norm-str [v]
  (-> (str (or v "")) str/trim str/lower-case))

(defn- norm-num [v]
  (cond
    (nil? v) 0
    (number? v) (long v)
    (string? v) (try (Long/parseLong v) (catch Exception _ 0))
    :else 0))

(defn stable-core
  "Núcleo determinístico: espécie + shiny + stats de base + habilidade/tipos
   + artefatos de captura. Sem HP atual, XP, nível, golpes ou índice."
  [pokemon]
  (let [p (if (map? pokemon) pokemon {})]
    {:nome (norm-str (or (g p :nome) (g p :especie) (g p :species)))
     :shiny (boolean (or (g p :shiny) (g p :shiny?)))
     :ataque (norm-num (g p :ataque))
     :defesa (norm-num (g p :defesa))
     :atq-esp (norm-num (g p :atq-esp))
     :def-esp (norm-num (g p :def-esp))
     :veloc (norm-num (g p :veloc))
     :hp-max (norm-num (g p :hp))
     :habilidade (norm-str (g p :habilidade))
     :tipos (vec (or (g p :tipos) []))
     :imagem (norm-str (g p :imagem))
     :imagem-shiny (norm-str (g p :imagem-shiny))
     :raridade (norm-str (g p :raridade))
     :item (norm-str (g p :item))
     :altura (norm-num (g p :altura))
     :peso (norm-num (g p :peso))}))

(defn core-digest
  [core]
  (reward/payload-hash {:scheme scheme-version :core core}))

(defn propose-synthetic-id
  "ID sintético versionado. Escopo chat|player — sem índice de ordem.
   Só chamar quando uniqueness já foi comprovada."
  [{:keys [chat-id player-id core]}]
  (let [digest (reward/payload-hash
                {:scheme scheme-version
                 :chat-id chat-id
                 :player-id player-id
                 :core core})]
    (str "a64sid:" scheme-version ":" (subs digest 0 32))))

(defn- located-pokemon
  [account]
  (mapv (fn [entry]
          (let [p (:pokemon entry)]
            {:pokemon p
             :location (:location entry)
             :native-id (let [id (g p :id-pokemon)]
                          (when-not (str/blank? (str id)) (str id)))
             :core (stable-core p)
             :digest (core-digest (stable-core p))}))
        (shape/pokemon-in-location account)))

(defn classify-account
  "Classifica cada Pokémon da conta.

  :native          — já tem id-pokemon
  :synthesizable   — sem id, núcleo único no escopo da conta
  :ambiguous       — sem id (ou com id) mas núcleo compartilhado por ≥2
  :native-collision — ids nativos distintos com mesmo núcleo (sinalizar)

  Nunca associa ambíguos automaticamente."
  [{:keys [chat-id player-id account]}]
  (let [xs (located-pokemon account)
        by-digest (group-by :digest xs)
        rows
        (mapv (fn [row]
                (let [peers (get by-digest (:digest row))
                      n (count peers)
                      unique-core? (= 1 n)
                      native (:native-id row)]
                  (cond
                    (and native unique-core?)
                    (assoc row :status :native :proposed-id native)

                    (and native (not unique-core?))
                    (assoc row :status :native-collision :proposed-id native
                           :peer-count n)

                    (and (nil? native) unique-core?)
                    (assoc row :status :synthesizable
                           :proposed-id (propose-synthetic-id
                                         {:chat-id chat-id
                                          :player-id player-id
                                          :core (:core row)}))

                    :else
                    (assoc row :status :ambiguous :proposed-id nil
                           :peer-count n))))
              xs)
        counts (frequencies (map :status rows))]
    {:scheme scheme-version
     :chat-id chat-id
     :player-id player-id
     :pokemon-count (count rows)
     :status-counts counts
     :rows rows
     :import-ready?
     (and (pos? (count rows))
          (zero? (get counts :ambiguous 0))
          (zero? (get counts :native-collision 0))
          (every? #(contains? #{:native :synthesizable} (:status %)) rows))}))

(defn reorder-invariant?
  "Mesmo conjunto de proposed-id após reordenar equipe/pc/enfermaria."
  [classified-a classified-b]
  (= (sort (map :proposed-id (:rows classified-a)))
     (sort (map :proposed-id (:rows classified-b)))))

(defn cross-snapshot-borrow
  "Sugere emprestar id nativo de `donor` → `target` por núcleo estável.

  Retorna propostas :eligible-borrow | :ambiguous | :unmatched.
  Não aplica; caller decide.
  Se o target tem ≥2 Pokémon com o mesmo núcleo, todos ficam :ambiguous
  (não associar automaticamente exemplares indistinguíveis)."
  [donor-account target-account]
  (let [donors (->> (located-pokemon donor-account)
                    (filter :native-id)
                    (group-by :digest))
        targets (->> (located-pokemon target-account)
                     (remove :native-id))
        target-groups (group-by :digest targets)]
    (mapv (fn [t]
            (let [cands (get donors (:digest t) [])
                  target-peers (count (get target-groups (:digest t)))]
              (cond
                (> target-peers 1)
                {:status :ambiguous
                 :digest (:digest t)
                 :reason :duplicate-core-in-target
                 :peer-count target-peers
                 :core (:core t)}

                (= 1 (count cands))
                {:status :eligible-borrow
                 :digest (:digest t)
                 :borrow-id (:native-id (first cands))
                 :core (:core t)}

                (pos? (count cands))
                {:status :ambiguous
                 :digest (:digest t)
                 :reason :multiple-donors
                 :candidate-count (count cands)
                 :core (:core t)}

                :else
                {:status :unmatched
                 :digest (:digest t)
                 :core (:core t)})))
          targets)))

(defn collision-risk-matrix
  "Resumo de risco a partir de vários classify-account."
  [classified-list]
  (let [all-rows (mapcat :rows classified-list)
        digests (group-by :digest all-rows)
        multi (filter #(> (count (val %)) 1) digests)]
    {:accounts (count classified-list)
     :pokemon (count all-rows)
     :unique-cores (count digests)
     :cores-with-multiplicity (count multi)
     :status-totals (frequencies (map :status all-rows))
     :synthesizable (count (filter #(= :synthesizable (:status %)) all-rows))
     :ambiguous (count (filter #(= :ambiguous (:status %)) all-rows))
     :native (count (filter #(= :native (:status %)) all-rows))
     :import-ready-accounts (count (filter :import-ready? classified-list))}))
