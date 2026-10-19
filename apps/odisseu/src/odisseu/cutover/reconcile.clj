(ns odisseu.cutover.reconcile
  "Reconciliação M8.0 entre snapshot antigo (evidência) e novo (candidato a import).
   Nunca soma contadores; nunca promove o snapshot antigo a estado ativo."
  (:require [clojure.set :as set]
            [odisseu.cutover.export :as export]
            [odisseu.platform.infrastructure.migration.compare :as compare]))

(def platform-modules
  ["bloqueio" "admins-conhecidos" "enquetes" "quiz-historico"
   "rank" "lembretes" "bugs" "participantes"])

(defn- leaf-diff
  "Diff raso de mapas normalizados: added/removed/changed."
  [older newer]
  (let [ok (set (keys older))
        nk (set (keys newer))]
    {:added (sort (set/difference nk ok))
     :removed (sort (set/difference ok nk))
     :changed (->> (set/intersection ok nk)
                   (filter #(not= (get older %) (get newer %)))
                   sort
                   vec)}))

(defn- normalize-module
  [mod-name raw]
  (case (str mod-name)
    "bloqueio" (compare/normalize-bloqueio raw)
    "admins-conhecidos" (compare/normalize-admins raw)
    "enquetes" (compare/normalize-enquetes raw)
    "quiz-historico" (compare/normalize-quiz-historico raw)
    "rank" (compare/normalize-rank raw)
    "lembretes" (compare/normalize-lembretes raw)
    "bugs" (compare/normalize-bugs raw)
    "participantes" (compare/normalize-participantes raw)
    (or raw {})))

(defn reconcile-platform
  [older-mods newer-mods]
  (into {}
        (map (fn [mod]
               (let [o (normalize-module mod (get older-mods mod {}))
                     n (normalize-module mod (get newer-mods mod {}))]
                 [mod (assoc (leaf-diff o n)
                             :equivalent? (= o n)
                             :older-count (count o)
                             :newer-count (count n))])))
        platform-modules))

(defn- pokemon-player-stats
  "Extrai métricas por jogador (moedas/XP/coleção/Joy) sem mutar."
  [mods]
  (let [treinador (get mods "treinador" {})
        loja (get mods "loja" {})]
    (into (sorted-map)
          (map (fn [pid]
                 (let [t (or (get treinador pid) {})
                       l (or (get loja pid) {})
                       equipe (or (get t "equipe") [])
                       pc (or (get t "pc") [])
                       joy (or (get t "enfermaria") [])
                       joy-deadlines (mapv #(or (get % "pronto-em")
                                                (get-in % ["pokemon" "pronto-em"]))
                                           (filter map? joy))]
                   [pid {:moedas (long (or (get l "moedas") 0))
                         :inventario (into (sorted-map)
                                           (map (fn [[k v]] [(str k) (long v)])
                                                (or (get l "inventario") {})))
                         :vitorias-treinador (long (or (get t "vitorias-treinador") 0))
                         :pe-ginasios (long (or (get t "pe-ginasios") 0))
                         :pe-raids (long (or (get t "pe-raids") 0))
                         :xp-insignias (long (or (get t "xp-insignias") 0))
                         :xp-missoes (long (or (get t "xp-missoes") 0))
                         :collection-count (+ (count equipe) (count pc))
                         :joy-bed-count (count joy)
                         :joy-deadlines (vec (sort (keep identity joy-deadlines)))
                         :equipe-ids (vec (sort (keep #(get % "id-pokemon") equipe)))}]))
               (set/union (set (keys treinador)) (set (keys loja)))))))

(defn reconcile-pokemon
  [older-mods newer-mods]
  (let [o (pokemon-player-stats older-mods)
        n (pokemon-player-stats newer-mods)
        players (set/union (set (keys o)) (set (keys n)))
        per-player
        (into (sorted-map)
              (map (fn [pid]
                     (let [a (get o pid)
                           b (get n pid)]
                       [pid
                        (cond
                          (and a (nil? b)) {:status :removed-from-newer :older a}
                          (and b (nil? a)) {:status :added-in-newer :newer b}
                          (= a b) {:status :unchanged :stats a}
                          :else
                          {:status :modified
                           :deltas
                           {:moedas [(:moedas a) (:moedas b)]
                            :collection-count [(:collection-count a) (:collection-count b)]
                            :joy-bed-count [(:joy-bed-count a) (:joy-bed-count b)]
                            :vitorias-treinador [(:vitorias-treinador a) (:vitorias-treinador b)]
                            :inventario-changed? (not= (:inventario a) (:inventario b))
                            :joy-deadlines-changed? (not= (:joy-deadlines a) (:joy-deadlines b))
                            :equipe-ids-changed? (not= (:equipe-ids a) (:equipe-ids b))}
                           :older a
                           :newer b})])))
              players)]
    {:players per-player
     :note "Deltas informativos — NÃO somar contadores entre snapshots."}))

(defn reconcile-historical
  "Históricos A/B: identidades distintas; colisão = bloqueio."
  [older newer]
  (let [oh (vec (or (:historical_snapshots older) (:historical-snapshots older) []))
        nh (vec (or (:historical_snapshots newer) (:historical-snapshots newer) []))
        o-ids (set (map :snapshot-id oh))
        n-ids (set (map :snapshot-id nh))
        o-active (or (:active_provenance older) (:active-provenance older))
        n-active (or (:active_provenance newer) (:active-provenance newer))
        ambiguous?
        (or (and o-active n-active
                 (= (:player-id o-active) (:player-id n-active))
                 (= (:chat-id o-active) (:chat-id n-active))
                 (not= (:snapshot-id o-active) (:snapshot-id n-active))
                 ;; mesmo slot alvo com ids diferentes sem decisão humana
                 (= (:target-slot o-active) (:target-slot n-active))
                 (not= (:kind o-active) (:kind n-active)))
            ;; histórico com mesmo snapshot-id mas conteúdo diferente
            (some (fn [id]
                    (let [a (first (filter #(= id (:snapshot-id %)) oh))
                          b (first (filter #(= id (:snapshot-id %)) nh))]
                      (and a b (not= (dissoc a :created-at) (dissoc b :created-at)))))
                  (set/intersection o-ids n-ids)))]
    {:older-snapshot-ids (sort o-ids)
     :newer-snapshot-ids (sort n-ids)
     :added-historical (sort (set/difference n-ids o-ids))
     :removed-historical (sort (set/difference o-ids n-ids))
     :active-older (:snapshot-id o-active)
     :active-newer (:snapshot-id n-active)
     :active-changed? (not= (:snapshot-id o-active) (:snapshot-id n-active))
     :ambiguous-provenance? (boolean ambiguous?)
     :policy "Snapshot antigo (ex. 14/10) = evidência histórica, nunca overwrite automático."
     :block-import? (boolean ambiguous?)}))

(defn- lembrete-status-index
  [mods]
  (into (sorted-map)
        (map (fn [[id info]]
               [(str id)
                {:status (str (or (get info "status") (get info :status) "pending"))
                 :em (long (or (get info "em") (get info :em) 0))
                 :texto (str (or (get info "texto") ""))}]))
        (or (get mods "lembretes") {})))

(defn reconcile-lembretes-lifecycle
  "Detecta pending/enviados(cancelled/completed)/novos entre snapshots."
  [older-mods newer-mods]
  (let [o (lembrete-status-index older-mods)
        n (lembrete-status-index newer-mods)
        all (set/union (set (keys o)) (set (keys n)))]
    (into (sorted-map)
          (map (fn [id]
                 (let [a (get o id)
                       b (get n id)]
                   [id
                    (cond
                      (and a (nil? b)) {:transition :removed :from (:status a)}
                      (and b (nil? a)) {:transition :added :to (:status b)}
                      (= (:status a) (:status b)) {:transition :unchanged :status (:status a)}
                      :else {:transition :status-changed
                             :from (:status a) :to (:status b)})])))
          all)))

(defn- rank-score-events
  "Não soma pontos entre snapshots — lista identidades de efeitos HTTP / jogadores."
  [mods]
  (let [rank (get mods "rank" {})]
    (into (sorted-map)
          (map (fn [[cid players]]
                 [cid
                  (into (sorted-map)
                        (map (fn [[pid info]]
                               [pid
                                {:pontos (long (or (get info "pontos") 0))
                                 :efeitos (sort (keys (or (get info "efeitos-pokemon-http") {})))}])
                             players))]))
          rank)))

(defn reconcile-rank-events
  [older-mods newer-mods]
  (let [o (rank-score-events older-mods)
        n (rank-score-events newer-mods)]
    {:note "Pontuações legítimas repetidas preservam operation-id; NÃO somar snapshots."
     :older o
     :newer n
     :diff (leaf-diff o n)}))

(defn reconcile-dumps
  "Relatório completo older→newer. Bloqueia se proveniência ambígua."
  [older-dump newer-dump]
  (let [older-mods (export/canonicalize-modules older-dump)
        newer-mods (export/canonicalize-modules newer-dump)
        hist (reconcile-historical older-dump newer-dump)
        platform (reconcile-platform older-mods newer-mods)
        pokemon (reconcile-pokemon older-mods newer-mods)
        lembretes (reconcile-lembretes-lifecycle older-mods newer-mods)
        rank (reconcile-rank-events older-mods newer-mods)
        collisions
        (cond-> []
          (:block-import? hist)
          (conj {:code :ambiguous-provenance :detail hist}))]
    {:milestone "M8.0"
     :older-label (get-in older-dump [:meta :label] (get-in older-dump [:meta :snapshot-date]))
     :newer-label (get-in newer-dump [:meta :label] (get-in newer-dump [:meta :snapshot-date]))
     :older-is-evidence-only? true
     :import-candidate :newer
     :platform platform
     :pokemon pokemon
     :lembretes-lifecycle lembretes
     :rank-events rank
     :historical hist
     :collisions collisions
     :block-import? (boolean (seq collisions))
     :go? (not (seq collisions))}))
