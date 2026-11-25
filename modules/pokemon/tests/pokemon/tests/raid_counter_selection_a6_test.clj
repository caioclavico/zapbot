(ns pokemon.tests.raid-counter-selection-a6-test
  "Seleção auto de counter em raid — tipagem, imunidade, revide, fallback."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-boss :as boss]
            [pokemon.domain.raid-counter-selection :as sel]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- mon
  [{:keys [idx tipos hp-atual hp nivel golpes ataque defesa atq-esp def-esp raridade]
    :or {hp-atual 80 hp 100 nivel 20 raridade "comum"}}]
  {:collection-index (long idx)
   :tipos (vec (map str tipos))
   :hp-atual (long hp-atual)
   :hp (long hp)
   :nivel (long nivel)
   :raridade raridade
   :ataque (long (or ataque 50))
   :defesa (long (or defesa 50))
   :atq-esp (long (or atq-esp 50))
   :def-esp (long (or def-esp 50))
   :veloc 50
   :golpes (vec golpes)})

(defn- raid-chefe
  [{:keys [tipos golpes defesa def-esp hp]
    :or {defesa 70 def-esp 70 hp 400}}]
  {:chefe {:tipos (vec (map str tipos))
           :golpes (vec golpes)
           :defesa (long defesa)
           :def-esp (long def-esp)
           :ataque 80 :atq-esp 80 :hp (long hp)}
   :hp-chefe (long hp)})

(def ^:private golpe-fisico
  {:nome-exibicao "Hit" :tipo "normal" :poder 40 :classe :fisico})
(def ^:private golpe-agua
  {:nome-exibicao "Surf" :tipo "water" :poder 80 :classe :especial})
(def ^:private golpe-grama
  {:nome-exibicao "Razor Leaf" :tipo "grass" :poder 55 :classe :especial})
(def ^:private golpe-eletrico
  {:nome-exibicao "Thunder" :tipo "electric" :poder 90 :classe :especial})
(def ^:private golpe-terra
  {:nome-exibicao "Earthquake" :tipo "ground" :poder 90 :classe :fisico})
(def ^:private golpe-fogo
  {:nome-exibicao "Flamethrower" :tipo "fire" :poder 80 :classe :especial})
(def ^:private golpe-gelo
  {:nome-exibicao "Ice Beam" :tipo "ice" :poder 80 :classe :especial})
(def ^:private golpe-dragao
  {:nome-exibicao "Dragon Pulse" :tipo "dragon" :poder 80 :classe :especial})
(def ^:private golpe-fada
  {:nome-exibicao "Dazzling Gleam" :tipo "fairy" :poder 80 :classe :especial})
(def ^:private golpe-fantasma
  {:nome-exibicao "Shadow Ball" :tipo "ghost" :poder 80 :classe :especial})
(def ^:private golpe-status
  {:nome-exibicao "Growl" :tipo "normal" :poder 0 :classe :status})

(defn- pick [raid colecao]
  (sel/pick-best-collection-index raid colecao))

(deftest chefe-agua-prefere-grama-ou-eletrico
  (let [raid (raid-chefe {:tipos ["water"]
                          :golpes [golpe-agua]
                          :def-esp 60})
        tank (mon {:idx 0 :tipos ["normal"] :nivel 45 :raridade "lendario"
                   :ataque 120 :atq-esp 120 :defesa 100 :def-esp 100
                   :golpes [golpe-fisico]})
        counter (mon {:idx 1 :tipos ["grass"] :nivel 18
                      :atq-esp 70 :golpes [golpe-grama]})
        shock (mon {:idx 2 :tipos ["electric"] :nivel 22
                    :atq-esp 75 :golpes [golpe-eletrico]})]
    (is (contains? #{1 2} (pick raid [tank counter shock]))
        "Counter tipado vence lendário só por stats")))

(deftest chefe-eletrico-ground-imune
  (let [raid (raid-chefe {:tipos ["electric"]
                          :golpes [golpe-eletrico]})
        flyer (mon {:idx 0 :tipos ["flying" "normal"] :atq-esp 90
                   :golpes [golpe-eletrico]})
        digger (mon {:idx 1 :tipos ["ground"] :atq-esp 55
                     :golpes [golpe-terra]})]
    (is (= 1 (pick raid [flyer digger])))))

(deftest chefe-fogo-agua
  (let [raid (raid-chefe {:tipos ["fire"] :golpes [golpe-fogo]})
        brasa (mon {:idx 0 :tipos ["fire"] :atq-esp 100 :golpes [golpe-fogo]})
        squirt (mon {:idx 1 :tipos ["water"] :atq-esp 60 :golpes [golpe-agua]})]
    (is (= 1 (pick raid [brasa squirt])))))

(deftest chefe-dragao-gelo-ou-fada
  (let [raid (raid-chefe {:tipos ["dragon"] :golpes [golpe-dragao]
                          :defesa 80 :def-esp 90})
        same (mon {:idx 0 :tipos ["dragon"] :atq-esp 50 :golpes [golpe-dragao]})
        ice (mon {:idx 1 :tipos ["ice"] :atq-esp 70 :golpes [golpe-gelo]})
        fairy (mon {:idx 2 :tipos ["fairy"] :atq-esp 65 :golpes [golpe-fada]})]
    (is (contains? #{1 2} (pick raid [same ice fairy])))))

(deftest dupla-tipagem-agua-rocha
  (let [raid (raid-chefe {:tipos ["water" "rock"]
                          :golpes [golpe-agua]})
        eletrico (mon {:idx 0 :tipos ["electric"] :atq-esp 80
                       :golpes [golpe-eletrico]})
        grama (mon {:idx 1 :tipos ["grass"] :atq-esp 70
                    :golpes [golpe-grama]})]
    ;; Grama 2××2× vs água/rocha; elétrico 2××0,5× — counter tipado vence.
    (is (= 1 (pick raid [eletrico grama])))))

(deftest imunidade-fantasma-normal
  (let [raid (raid-chefe {:tipos ["ghost"] :golpes [golpe-fantasma]})
        norm (mon {:idx 0 :tipos ["normal"] :ataque 100 :golpes [golpe-fisico]})
        ghost (mon {:idx 1 :tipos ["ghost"] :atq-esp 60 :golpes [golpe-fantasma]})]
    (is (= 1 (pick raid [norm ghost])))))

(deftest desmaiado-e-sem-golpe-ofensivo
  (testing "HP 0 fora"
    (let [raid (raid-chefe {:tipos ["water"]})
          morto (mon {:idx 0 :tipos ["grass"] :hp-atual 0 :golpes [golpe-grama]})
          vivo (mon {:idx 1 :tipos ["electric"] :golpes [golpe-eletrico]})]
      (is (= 1 (pick raid [morto vivo])))))
  (testing "só golpe status"
    (let [raid (raid-chefe {:tipos ["fire"]})
          status-mon (mon {:idx 0 :golpes [golpe-status]})
          hitter (mon {:idx 1 :tipos ["water"] :golpes [golpe-agua]})]
      (is (= 1 (pick raid [status-mon hitter])))))
  (testing "nenhum apto"
    (let [raid (raid-chefe {:tipos ["fire"]})
          bad (mon {:idx 0 :hp-atual 0 :golpes [golpe-status]})]
      (is (thrown? Exception (pick raid [bad]))))))

(deftest empate-deterministico-indice
  (let [raid (raid-chefe {:tipos ["normal"] :golpes [golpe-fisico]})
        a (mon {:idx 3 :tipos ["normal"] :golpes [golpe-fisico]
               :ataque 60 :atq-esp 60})
        b (mon {:idx 1 :tipos ["normal"] :golpes [golpe-fisico]
               :ataque 60 :atq-esp 60})
        c (mon {:idx 7 :tipos ["normal"] :golpes [golpe-fisico]
               :ataque 60 :atq-esp 60})]
    (is (= 1 (pick raid [a b c])))
    (is (= 1 (pick raid [c a b])))))

(deftest fallback-sem-chefe-usa-legacy
  (let [raid {:raid-id "x" :fase "inscricoes"}
        fraco (mon {:idx 0 :golpes [golpe-fisico] :ataque 30 :defesa 30
                    :atq-esp 30 :def-esp 30 :veloc 30 :hp-atual 50})
        forte (mon {:idx 1 :golpes [golpe-fisico] :ataque 90 :defesa 90
                    :atq-esp 90 :def-esp 90 :veloc 90 :hp-atual 90})]
    (is (= 1 (pick raid [fraco forte])))
    (let [cmp (sel/compare-old-vs-new raid [fraco forte])]
      (is (= (:picked cmp) (:legacy-pick cmp))))))

(deftest score-reprodutivel
  (let [raid (raid-chefe {:tipos ["water" "ground"] :golpes [golpe-agua golpe-terra]})
        p (mon {:idx 4 :tipos ["grass"] :golpes [golpe-grama]})]
    (is (= (sel/combat-score raid p) (sel/combat-score raid p)))
    (is (pos? (:combat-score (sel/score-breakdown raid p))))))

(deftest score-considera-precisao-acertos-e-turnos-especiais
  (let [raid {:chefe {:tipos ["water"]} :hp-chefe 1000 :defesa 70 :def-esp 70}
        p (mon {:idx 0 :hp 100 :hp-atual 100 :atq-esp 70 :golpes [golpe-eletrico]})
        dano #(sel/expected-dano-vs-chefe %1 %2 raid)
        seguro (assoc golpe-eletrico :precisao 100)
        base (dano seguro p)]
    (is (= (* base 0.5) (dano (assoc seguro :precisao 50) p)))
    (is (= (* base 3.1) (dano (assoc seguro :min-acertos 2 :max-acertos 5) p)))
    (is (= (* base 0.4) (dano (assoc seguro :familia :preparacao) p)))
    (is (= (* base 0.2) (dano (assoc seguro :familia :preparacao :precisao 50) p)))
    (is (zero? (dano (assoc seguro :familia :preparacao) (assoc p :hp-atual 20))))
    (is (= (* base 0.6) (dano (assoc seguro :familia :recarga) p)))
    (is (= (* base 0.2) (dano (assoc seguro :autodesmaio true) p)))
    (is (zero? (sel/expected-dano-vs-chefe
                (assoc seguro :tipo "normal") p
                (assoc-in raid [:chefe :tipos] ["ghost"]))))))

(deftest score-prioriza-golpe-real-com-multiplos-acertos
  (let [raid (raid-chefe {:tipos ["water"]})
        simples (mon {:idx 0 :tipos ["grass"] :golpes [golpe-grama]})
        multiplo (mon {:idx 1 :tipos ["grass"]
                       :golpes [{:slug "bullet-seed" :tipo "grass" :poder 25
                                 :classe :especial}]})]
    (is (= 1 (pick raid [simples multiplo])))))

(deftest relatório-old-vs-new-agua
  (let [raid (raid-chefe {:tipos ["water"] :golpes [golpe-agua]})
        tank (mon {:idx 0 :tipos ["normal"] :nivel 50 :raridade "lendario"
                   :ataque 110 :atq-esp 90 :golpes [golpe-fisico]})
        vine (mon {:idx 1 :tipos ["grass"] :nivel 15 :atq-esp 65
                   :golpes [golpe-grama]})
        cmp (sel/compare-old-vs-new raid [tank vine])]
    (is (= 1 (:picked cmp)))
    (is (= 0 (:legacy-pick cmp))
        "Legado escolhia o tanque por stats")
    (is (not= (:picked cmp) (:legacy-pick cmp)))))

(deftest revide-real-independente-da-tipagem-defensiva
  "O chefe atualmente revida ceil(HPmax/5), não usa golpes/tipos.
   Contracandidatos idênticos não podem ser ranqueados por ameaça fictícia."
  (let [raid (raid-chefe {:tipos ["water"]
                          :golpes [golpe-agua]})
        normal (mon {:idx 0 :tipos ["normal"] :hp 100 :hp-atual 80
                     :ataque 70 :atq-esp 70 :defesa 70 :def-esp 70
                     :golpes [golpe-fisico]})
        agua (mon {:idx 1 :tipos ["water"] :hp 100 :hp-atual 80
                   :ataque 70 :atq-esp 70 :defesa 70 :def-esp 70
                   :golpes [golpe-fisico]})
        score-normal (sel/score-breakdown raid normal)
        score-agua (sel/score-breakdown raid agua)]
    (is (not= (:worst-boss-threat score-normal)
              (:worst-boss-threat score-agua))
        "A ameaça teórica é distinta, embora o revide real não seja")
    (is (= (:turns-vs-revide score-normal)
           (:turns-vs-revide score-agua)))
    (is (= (:combat-score score-normal)
           (:combat-score score-agua))
        "Score deve refletir o revide real da raid")
    (is (= 0 (pick raid [normal agua]))
        "Desempate estável pelo menor índice")))
(deftest politoed-prefere-dano-efetivo-mesmo-com-menos-hp
  (let [politoed (some #(when (= "politoed" (:species %)) %) (:species (boss/catalog)))
        raid (raid-store/raid->map
              {:raid/id "fixture" :raid/fase "inscricoes" :raid/origem "auto"
               :raid/chefe-edn (pr-str (assoc politoed :golpes [golpe-agua] :def-esp 100))}
              [] {} #(when % (edn/read-string %)) some?)
        blastoise (assoc (mon {:idx 0 :hp 280 :hp-atual 280 :atq-esp 140
                               :tipos ["water"] :golpes [golpe-agua]})
                        :species "blastoise")
        electric (assoc (mon {:idx 1 :hp-atual 15 :atq-esp 55 :tipos ["electric"]})
                        :golpes [{"slug" "thunderbolt" "poder" 90 "tipo" "electric"
                                  "classe" "especial" "precisao" 100}])
        grass (mon {:idx 2 :hp-atual 15 :atq-esp 50 :tipos ["grass"]
                    :golpes [golpe-grama]})
        old-weight (fn [p]
                     (let [{:keys [turns-vs-revide hp-atual]}
                           (sel/score-breakdown raid p)]
                       ;; O cap a zero zerava ambos os termos de dano do score antigo.
                       (+ (* 12 turns-vs-revide) (* 0.02 hp-atual))))]
    (is (= ["water"] (get-in raid [:chefe :tipos])))
    (is (= [65.6 9.3 9.3] (mapv old-weight [blastoise electric grass])))
    (is (= [41000 88000 58900] (mapv #(sel/combat-score raid %) [blastoise electric grass])))
    (is (= 1 (pick raid [blastoise electric grass])))
    (is (= 2 (pick raid [blastoise grass])))
    (is (= 1 (pick raid [blastoise electric])))
    (is (= 2 (pick raid [blastoise (assoc electric :hp-atual 0) grass])))
    (is (= 2 (pick raid [blastoise (assoc electric :golpes [golpe-status]) grass])))
    (is (= 2 (pick raid [blastoise
                         (assoc electric :golpes (conj (vec (repeat 4 golpe-status)) golpe-eletrico))
                         grass]))
        "O quinto golpe não é selecionável no combate")
    (doseq [tipos [[] ["water" "invalid"]]]
      (let [incompleto (assoc-in raid [:chefe :tipos] tipos)]
        (is (true? (:fallback? (sel/compare-old-vs-new incompleto [blastoise electric]))))
        (is (= 0 (pick incompleto [blastoise electric])))))
    (doseq [defesa [nil 0 "inválida"]]
      (let [incompleto (assoc-in raid [:chefe :def-esp] defesa)]
        (is (true? (:fallback? (sel/compare-old-vs-new incompleto [blastoise electric]))))
        (is (= 0 (pick incompleto [blastoise electric])))))))

(deftest politoed-auto-usa-colecao-e-indice-do-save-datomic
  (let [id (str (java.util.UUID/randomUUID))
        client (persistence/client {:system (str "raid-counter-" id)
                                    :storage-dir (str "/tmp/raid-counter-" id)})
        s (store/make-store (persistence/ensure-db! client "isolado"))
        chat "teste" player "ash"
        _ (trainer/bootstrap! s)
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        mons [(assoc (mon {:idx 0 :hp 280 :hp-atual 280 :atq-esp 140
                           :tipos ["water"] :golpes [golpe-agua]})
                     :species "blastoise" :nome "Blastoise")
              (assoc (mon {:idx 1 :hp-atual 15 :atq-esp 55 :tipos ["electric"]
                           :golpes [{:slug "thunderbolt" :poder 90 :tipo "electric"
                                     :classe :especial :precisao 100}]})
                     :species "pikachu" :nome "Pikachu")
              (assoc (mon {:idx 2 :hp-atual 15 :atq-esp 50 :tipos ["grass"]
                           :golpes [golpe-grama]})
                     :species "bulbasaur" :nome "Bulbasaur")]
        _ (doseq [p mons]
            (collection/adicionar-pokemon! s chat player p (:hp-atual p) nil))
        fence (long (:fence-generation (ports/get-player s chat player)))
        chefe (assoc (some #(when (= "politoed" (:species %)) %) (:species (boss/catalog)))
                     :def-esp 100)
        req {:chat-id chat :player-id player :save-id "a" :fence-generation fence}]
    (tx/open-raid! s (merge req {:operation-id "open" :raid-id "politoed"
                                 :ginasio "agua" :chefe chefe :agora-ms 1000}))
    (let [lobby (raid-app/get-raid s "politoed")
          saved (ports/list-collection s chat player "a")
          join (tx/join-raid! s (merge req {:operation-id "join" :raid-id "politoed"
                                           :auto? true :agora-ms 1001}))
          selected (get-in (raid-app/get-raid s "politoed") [:participantes player])]
      (is (= 0 (:hp-chefe lobby)))
      (is (= ["water"] (get-in lobby [:chefe :tipos])))
      (is (= ["blastoise" "pikachu" "bulbasaur"] (mapv :species saved)))
      (is (= [41000 88000 58900] (mapv #(sel/combat-score lobby %) saved)))
      (is (= 1 (:collection-index join) (:collection-index selected)))
      (is (= (:id-pokemon (nth saved 1)) (get-in selected [:pokemon :id-pokemon])))
      (is (= "pikachu" (get-in selected [:pokemon :species]))))
    (let [out (java.io.StringWriter.)
          auto-index (ns-resolve 'pokemon.application.bridge-tx 'raid-auto-index)]
      (binding [*err* out]
        (is (= 0 (auto-index {:chefe {:tipos []}} [(first mons)]))))
      (is (re-find #"raid_auto_counter_fallback" (str out))))))
