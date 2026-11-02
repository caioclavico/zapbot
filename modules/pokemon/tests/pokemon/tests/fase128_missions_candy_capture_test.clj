(ns pokemon.tests.fase128-missions-candy-capture-test
  "Fase 12.8 — missões (aliases/resumo/recompensas), doces, captura por raridade,
   clima PT, eventos e Frambo na caça (domínio)."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.events :as events]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.mission-rewards :as rewards]
            [pokemon.domain.mission-suggest :as suggest]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.profile :as profile]
            [pokemon.domain.raid-gym-theme :as gym])
  (:import [java.time Instant]))

(deftest aliases-missoes-normalizam-acento-e-numero
  (doseq [[raw scope] [["diaria" "daily"] ["diárias" "daily"] ["DIARIAS" "daily"]
                       ["semanal" "weekly"] ["semanais" "weekly"] ["SEMANAIS" "weekly"]
                       ["evento" "event"] ["eventos" "event"] ["" "all"] ["todas" "all"]]]
    (is (= scope (suggest/normalize-scope raw)) raw)))

(deftest resumo-sugere-ate-duas-pendentes-por-periodo
  (let [snap (catalog/snapshot {} {} {} 1
                               (Instant/parse "2026-11-02T12:00:00Z")
                               "America/Sao_Paulo")
        sum (:summary snap)]
    (is (= 4 (:daily-total sum)))
    (is (= 0 (:daily-done sum)))
    (is (<= (count (:daily-suggested sum)) 2))
    (is (<= (count (:weekly-suggested sum)) 2))
    (is (every? #(contains? #{"available" "in_progress"} (:estado %))
                (concat (:daily-suggested sum) (:weekly-suggested sum))))))

(deftest ranking-prioriza-quase-concluidas
  (let [ms [{:id "a" :meta 10 :progresso 9 :estado "in_progress" :pokebolas 1}
            {:id "b" :meta 2 :progresso 0 :estado "available" :pokebolas 1}
            {:id "c" :meta 1 :progresso 1 :estado "completed" :pokebolas 1}]
        ranked (suggest/rank-pending ms)]
    (is (= ["a" "b"] (mapv :id ranked)))
    (is (= ["a"] (mapv :id (suggest/suggest ms 1))))))

(deftest recompensas-diversificadas-incluem-fruta-e-presente
  (is (pos? (:frutas (first catalog/daily))))
  (is (pos? (:presentes-item (nth catalog/daily 2))))
  (let [items (rewards/base-items (nth catalog/daily 2))]
    (is (= 1 (get items "ultra-bola")))
    (is (= 1 (get items "cartao-presente")))))

(deftest doces-renomeacao-preserva-saldos-e-familias
  (is (= "pidgey" (profile/family "Pidgeotto")))
  (is (= "charmander" (profile/family "charizard")))
  (is (= "eevee" (profile/family "vaporeon")))
  (is (= "Doce Charmander" (profile/candy-label "charizard")))
  (is (= "Doces Eevee" (profile/candy-label-plural "eevee" 12)))
  (is (= {"dratini" 3} (events/grant-candy-map {"dragonite" 3}))))

(deftest clima-traduz-18-tipos
  (doseq [t (keys gym/type-venues)]
    (is (string? (mundo/tipo-label-pt t))))
  (let [texto (mundo/texto-resumo (Instant/parse "2026-11-02T15:00:00Z"))]
    (is (re-find #"favorece" texto))
    (is (not (re-find #"\bfire\b" texto)))
    (is (not (re-find #"\bwater\b" texto)))))

(deftest clima-influencia-sorteio-real
  (let [bioma {:tipos #{"normal"}}
        clima {:tipos #{"water" "electric"}}
        rolls-clima (count (filter #(= #{"water" "electric"}
                                       (hunt/resolver-tipos-encontro bioma clima %))
                                   (range 100)))
        ev [(assoc (events/by-id "festival-dragoes")
                   :inicio "2026-11-01T00:00:00Z"
                   :fim "2026-12-01T00:00:00Z")]
        ;; força ativo via status-at — janela cobre "agora" do teste se usarmos active-events
        ]
    (is (= 35 rolls-clima) "35% clima sem evento")
    (let [bias (events/type-bias-chance
                [(assoc (events/by-id "festival-dragoes") :bias-tipos 20)])
          rolls-evt (count
                     (filter #(= #{"dragon"}
                                 (hunt/resolver-tipos-encontro
                                  bioma clima %
                                  [(assoc (events/by-id "festival-dragoes")
                                          :bias-tipos 20)]))
                             (range 100)))]
      (is (= 20 bias))
      (is (= 20 rolls-evt)))))

(deftest captura-raridade-ordenada-e-frambo
  (let [comum (capture/chance-captura {:raridade "comum" :hp 50 :ataque 50 :defesa 50
                                       :atq-esp 50 :def-esp 50 :veloc 50})
        lend (capture/chance-captura {:raridade "lendario" :hp 50 :ataque 50 :defesa 50
                                      :atq-esp 50 :def-esp 50 :veloc 50})
        mit (capture/chance-captura {:raridade "mitico" :hp 50 :ataque 50 :defesa 50
                                     :atq-esp 50 :def-esp 50 :veloc 50})]
    (is (<= 75 comum 90))
    (is (<= 5 lend 12))
    (is (<= 3 mit 8))
    (is (> comum lend))
    (is (>= lend mit)))
  (let [base 40
        sem (capture/chance-com-bola-frambo base "pokebola" false)
        com (capture/chance-com-bola-frambo base "pokebola" true)]
    (is (= 40 sem))
    (is (= 60 com))
    (is (<= com 95)))
  (let [painel (capture/chances-painel 40 true)]
    (is (= 60 (get painel "pokebola")))
    (is (= 90 (get painel "grande-bola")))
    (is (= 95 (get painel "ultra-bola")))))

(deftest captura-comuns-mais-faceis-que-lendarios-pos-batalha
  (let [c (capture/chance-base-pos-batalha
           {:raridade "comum" :hp 40 :ataque 40 :defesa 40 :atq-esp 40 :def-esp 40 :veloc 40}
           nil 0)
        l (capture/chance-base-pos-batalha
           {:raridade "lendario" :hp 100 :ataque 100 :defesa 100 :atq-esp 100 :def-esp 100 :veloc 100}
           nil 0)]
    (is (> (capture/chance-com-bola c "pokebola")
           (capture/chance-com-bola l "pokebola")))))

(deftest eventos-catalogo-tematico-sem-doce-generico
  (is (= 7 (count events/catalog)))
  (doseq [ev events/catalog]
    (is (seq (:tipos-favorecidos ev)))
    (is (seq (:familias-doce ev)))
    (is (every? string? (:familias-doce ev)))
    (is (nil? (some #(re-find #"^(dragon|water|fire)$" %) (:familias-doce ev))))
    (is (= "inactive" (events/status-at ev (Instant/now))) (:id ev)))
  (is (false? (events/thematic-events-enabled?))
      "12.8.0: flag off por padrão")
  (is (empty? (events/active-events (Instant/now)))
      "12.8.0: nenhum evento temático ativo sem flag+janela")
  ;; Caça de produção (3 args) ignora catálogo mesmo se alguém setar janelas no EDN.
  (let [bioma {:tipos #{"normal"}}
        clima {:tipos #{"water"}}]
    (is (= 35 (count (filter #(= #{"water"} (hunt/resolver-tipos-encontro bioma clima %))
                             (range 100)))))))
