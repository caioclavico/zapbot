(ns pokemon.migration.match
  "Compara candidatos do inventário aos perfis esperados (26 / 119).
   Nunca escolhe automaticamente sob ambiguidade."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [pokemon.migration.profiles :as profiles]))

(defn- matches-profile?
  [candidate profile]
  (let [count-ok? (= (:pokemon-count profile) (:pokemon-count candidate))
        shiny-ok? (set/subset?
                   (:require-shiny-species profile #{})
                   (:shiny-species candidate #{}))
        id-prefix (:require-shiny-id-prefix profile)
        prefix-ok? (if id-prefix
                     (some #(and % (str/starts-with? (str %) id-prefix))
                           (:shiny-ids candidate))
                     true)
        xp-req (:require-xp-total profile)
        xp-ok? (or (nil? xp-req)
                   (= (long xp-req) (long (or (:xp-total candidate) -1))))]
    (and count-ok? shiny-ok? prefix-ok? xp-ok?)))

(defn- score-reasons
  [candidate profile]
  (cond-> []
    (= (:pokemon-count profile) (:pokemon-count candidate))
    (conj :pokemon-count)
    (set/subset? (:require-shiny-species profile #{})
                 (:shiny-species candidate #{}))
    (conj :shiny-species)
    (let [p (:require-shiny-id-prefix profile)]
      (or (nil? p)
          (some #(and % (str/starts-with? (str %) p)) (:shiny-ids candidate))))
    (conj :shiny-id-prefix)
    (let [xp-req (:require-xp-total profile)]
      (and xp-req (= (long xp-req) (long (or (:xp-total candidate) -1)))))
    (conj :xp-total)))

(defn- dedupe-by-fingerprint
  "Evita ambiuidade artificial quando live + overlay descrevem o mesmo save.
   Agrupa por contagem + IDs (moedas podem divergir entre trenador/loja embutida)."
  [candidates]
  (->> candidates
       (group-by (fn [c]
                   [(:pokemon-count c)
                    (:shiny-ids c)
                    (set (:ids c))]))
       vals
       (mapv (fn [group]
               (or (first (filter #(#{:historical-envelope :active-provenance :pokemon-save-slots}
                                   (:origin %))
                                 group))
                   (first group))))))

(defn match-inventory
  "Retorna relatório de matching. `:auto-selected?` é sempre false.
   `:status` = :resolved | :ambiguous | :unmatched | :blocked-insufficient."
  [inventory]
  (let [profiles profiles/expected-profiles
        candidates (dedupe-by-fingerprint (:candidates inventory))
        per-profile
        (mapv
         (fn [profile]
           (let [hits (->> candidates
                           (filter #(matches-profile? % profile))
                           (mapv (fn [c]
                                   {:candidate-id (:candidate-id c)
                                    :origin (:origin c)
                                    :snapshot-id (:snapshot-id c)
                                    :slot-id (:slot-id c)
                                    :chat-id (:chat-id c)
                                    :player-id (:player-id c)
                                    :pokemon-count (:pokemon-count c)
                                    :shiny-ids (:shiny-ids c)
                                    :moedas (:moedas c)
                                    :content-hash (:content-hash c)
                                    :source-version (:source-version c)
                                    :matched-on (score-reasons c profile)})))]
             {:profile-id (:id profile)
              :label (:label profile)
              :expected-count (:pokemon-count profile)
              :hit-count (count hits)
              :hits hits
              :status (cond
                        (zero? (count hits)) :unmatched
                        (= 1 (count hits)) :unique-candidate
                        :else :ambiguous)}))
         profiles)
        ambiguous? (some #(= :ambiguous (:status %)) per-profile)
        unmatched? (some #(= :unmatched (:status %)) per-profile)
        all-unique? (every? #(= :unique-candidate (:status %)) per-profile)
        cand->profiles (reduce
                        (fn [acc p]
                          (reduce (fn [a h]
                                    (update a (:candidate-id h) (fnil conj []) (:profile-id p)))
                                  acc
                                  (:hits p)))
                        {}
                        per-profile)
        multi-profile (->> cand->profiles
                           (filter (fn [[_ ps]] (> (count (set ps)) 1)))
                           (mapv (fn [[cid ps]] {:candidate-id cid :profiles ps})))
        status (cond
                 (seq multi-profile) :blocked-insufficient
                 ambiguous? :ambiguous
                 unmatched? :unmatched
                 all-unique? :resolved
                 :else :blocked-insufficient)]
    {:status status
     :auto-selected? false
     :profiles per-profile
     :multi-profile-collisions multi-profile
     :suggested-mapping
     (when (= :resolved status)
       (into {}
             (map (fn [p]
                    [(:profile-id p)
                     (select-keys (first (:hits p))
                                  [:candidate-id :snapshot-id :origin
                                   :slot-id :chat-id :player-id :content-hash])])
                  per-profile)))
     :requires-human-confirmation true
     :block-reason
     (case status
       :ambiguous "Mais de um candidato casa com o mesmo perfil; não escolher automaticamente."
       :unmatched "Nenhum candidato satisfaz contagem/shiny exigidos; dados insuficientes."
       :blocked-insufficient "Colisão entre perfis ou evidência insuficiente; não completar por suposição."
       :resolved "Candidatos únicos por perfil — ainda exige confirmação humana + autorização para dados reais."
       nil)
     :note "sourceVersion nunca decide o matching."}))

(defn write-match-report!
  [report path]
  (io/make-parents path)
  (spit path (pr-str report))
  path)

(defn write-match-markdown!
  [report path]
  (io/make-parents path)
  (let [lines
        (concat
         ["# Matching M5.1 — candidatos × históricos esperados"
          ""
          (str "- status: **" (name (:status report)) "**")
          (str "- auto-selected?: `" (:auto-selected? report) "`")
          (str "- " (:block-reason report))
          ""
          "## Por perfil"
          ""]
         (mapcat
          (fn [p]
            (concat
             [(str "### " (:label p) " (`" (name (:profile-id p)) "`)")
              (str "- status: `" (name (:status p)) "` · hits: " (:hit-count p))
              ""]
             (if (seq (:hits p))
               (map (fn [h]
                      (str "- `" (:candidate-id h) "` origin=" (:origin h)
                           " count=" (:pokemon-count h)
                           " shinies=" (pr-str (:shiny-ids h))
                           " coins=" (:moedas h)
                           (when (:source-version h)
                             (str " sourceVersion(ignored)=" (:source-version h)))))
                    (:hits p))
               ["- (nenhum)"])
             [""]))
          (:profiles report))
         ["## Suggested mapping (somente se unique; NÃO aplicar sem confirmação)"
          ""
          "```"
          (pr-str (:suggested-mapping report))
          "```"
          ""])]
    (spit path (str/join "\n" lines))
    path))
