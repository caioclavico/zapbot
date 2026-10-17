(ns pokemon.tests.adventure-m37-test
  "M3.7 — Adventure Sync (ovos) + Enfermeira Joy."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.adventure :as adv]
            [pokemon.application.collection :as collection]
            [pokemon.application.joy :as joy-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.joy :as joy]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(def ^:private system "odisseu-m37-tests")
(def ^:private storage-rel ".datomic-local/m37-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (adv/ensure-account! s chat player))

(def ^:private mon
  {:nome "Rattata" :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20 :veloc 40
   :tipos ["normal"] :nivel 5
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
   :hp-atual 10 :status "envenenado"})

(deftest equivalencia-constantes
  (is (= 9 eggs/limite-inventario))
  (is (= (* 2 60 60 1000) (get-in eggs/categorias [:comum :tempo-ms])))
  (is (= 100 (get-in eggs/categorias [:comum :pontos])))
  (is (= 30 joy/tempo-tratamento-minutos)))

(deftest incubacao-e-nascimento
  (let [s (fresh-store! "e-hatch")
        chat "chat-e1"
        player "ash"
        t0 1000000]
    (boot! s chat player)
    (adv/grant-egg!
     s chat player
     {:operation-id "grant-1" :egg-id "egg-1" :category :comum
      :species "Pichu" :agora t0})
    (let [inc (adv/incubate!
               s chat player
               {:operation-id "inc-1" :egg-id "egg-1"
                :incubator-id "permanente" :agora t0})
          due (get-in inc [:egg :due-at])]
      (is (= :incubating (get-in inc [:egg :state])))
      (is (= "a" (get-in inc [:egg :save-id])))
      (is (= (+ t0 (* 2 60 60 1000)) due))
      (let [h (adv/hatch!
               s chat player
               {:operation-id "hatch-1" :egg-id "egg-1" :hatch-id "h1"
                :agora due})
            col (ports/list-collection s chat player "a")]
        (is (false? (:duplicate h)))
        (is (= "Pichu" (:species (first col))))
        (is (= 1 (:nivel (first col))))
        (is (= :hatched (:state (adv/get-egg s "egg-1"))))))))

(deftest reinicio-antes-e-depois-do-prazo
  (let [db "e-persist"
        chat "chat-e2"
        player "misty"
        t0 2000000]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (adv/grant-egg!
       s chat player
       {:operation-id "g2" :egg-id "egg-p" :category :comum
        :species "Togepi" :agora t0})
      (adv/incubate!
       s chat player
       {:operation-id "i2" :egg-id "egg-p" :agora t0}))
    ;; reinício antes do prazo
    (let [s (reopen-store db)
          egg (adv/get-egg s "egg-p")]
      (is (= :incubating (:state egg)))
      (is (= (+ t0 (* 2 60 60 1000)) (:due-at egg)))
      (let [code (try
                   (adv/hatch! s chat player
                               {:operation-id "early" :egg-id "egg-p"
                                :hatch-id "hx" :agora (inc t0)})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :egg_not_ready code))))
    ;; depois do prazo + reopen
    (let [s (reopen-store db)
          due (:due-at (adv/get-egg s "egg-p"))
          h (adv/hatch! s chat player
                        {:operation-id "late" :egg-id "egg-p"
                         :hatch-id "h-late" :agora due})]
      (is (false? (:duplicate h)))
      (is (= 1 (count (ports/list-collection s chat player "a")))))))

(deftest retry-mesmo-nascimento
  (let [s (fresh-store! "e-idem")
        chat "chat-e3"
        player "brock"
        t0 3000000
        due (+ t0 (* 2 60 60 1000))]
    (boot! s chat player)
    (adv/grant-egg!
     s chat player
     {:operation-id "g3" :egg-id "egg-i" :category :comum
      :species "Cleffa" :agora t0})
    (adv/incubate!
     s chat player
     {:operation-id "i3" :egg-id "egg-i" :agora t0})
    (let [req {:operation-id "hatch-once" :egg-id "egg-i" :hatch-id "h-once"
               :agora due}
          r1 (adv/hatch! s chat player req)
          r2 (adv/hatch! s chat player req)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 1 (count (ports/list-collection s chat player "a")))))))

(deftest a-b-a-durante-incubacao
  (let [s (fresh-store! "e-aba")
        chat "chat-e4"
        player "leaf"
        t0 4000000
        due (+ t0 (* 2 60 60 1000))]
    (boot! s chat player)
    (adv/grant-egg!
     s chat player
     {:operation-id "g4" :egg-id "egg-aba" :category :comum
      :species "Igglybuff" :agora t0})
    (adv/incubate!
     s chat player
     {:operation-id "i4" :egg-id "egg-aba" :agora t0})
    ;; A → B: hatch no B deve falhar
    (trainer/switch-save! s chat player "b")
    (let [code (try
                 (adv/hatch! s chat player
                             {:operation-id "h-b" :egg-id "egg-aba"
                              :hatch-id "hb" :agora due})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :egg_save_mismatch code)))
    ;; B → A: writer fresco no A entrega
    (trainer/switch-save! s chat player "a")
    (let [h (adv/hatch! s chat player
                        {:operation-id "h-a" :egg-id "egg-aba"
                         :hatch-id "ha" :agora due})]
      (is (false? (:duplicate h)))
      (is (= 1 (count (ports/list-collection s chat player "a"))))
      (is (= 0 (count (ports/list-collection s chat player "b")))))))

(deftest callback-geracao-antiga
  (let [s (fresh-store! "e-stale")
        chat "chat-e5"
        player "red"
        t0 5000000
        due (+ t0 (* 2 60 60 1000))]
    (boot! s chat player)
    (adv/grant-egg!
     s chat player
     {:operation-id "g5" :egg-id "egg-st" :category :comum
      :species "Tyrogue" :agora t0})
    (adv/incubate!
     s chat player
     {:operation-id "i5" :egg-id "egg-st" :agora t0})
    (let [w (writer/freeze-writer s chat player)]
      (trainer/switch-save! s chat player "b")
      (let [code (try
                   (adv/hatch! s w
                               {:operation-id "stale-h" :egg-id "egg-st"
                                :hatch-id "hs" :agora due})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code))))))

(deftest joy-cura-e-reinicio
  (let [db "j-persist"
        chat "chat-j1"
        player "joy"
        t0 6000000]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (collection/adicionar-pokemon! s chat player mon 10 "envenenado")
      (let [r (joy-app/send!
               s chat player
               {:operation-id "send-1" :idx 0 :agora t0})]
        (is (= (+ t0 joy/tempo-tratamento-ms) (:pronto-em r)))
        (is (= 0 (count (ports/list-collection s chat player "a"))))
        (is (= 1 (count (joy-app/list-beds s chat player "a"))))))
    (let [s (reopen-store db)
          beds (joy-app/list-beds s chat player "a")]
      (is (= 1 (count beds)))
      (let [code (try
                   (joy-app/collect!
                    s chat player
                    {:operation-id "early-c" :agora (inc t0)})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :joy_nothing_ready code)))
      (let [c (joy-app/collect!
               s chat player
               {:operation-id "collect-1"
                :agora (+ t0 joy/tempo-tratamento-ms)})
            col (ports/list-collection s chat player "a")]
        (is (= 1 (:n c)))
        (is (= 1 (count col)))
        (is (= (:hp (first col)) (:hp-atual (first col))))
        (is (nil? (:status (first col))))
        (is (= 0 (count (joy-app/list-beds s chat player "a"))))))))

(deftest joy-isolamento-saves
  (let [s (fresh-store! "j-iso")
        chat "chat-j2"
        player "blue"
        t0 7000000]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player mon 5 "queimado")
    (joy-app/send! s chat player {:operation-id "sa" :idx 0 :agora t0})
    (trainer/switch-save! s chat player "b")
    (is (= 0 (count (joy-app/list-beds s chat player "b"))))
    (is (= 1 (count (joy-app/list-beds s chat player "a"))))
    (trainer/switch-save! s chat player "a")
    (joy-app/collect!
     s chat player
     {:operation-id "ca" :agora (+ t0 joy/tempo-tratamento-ms)})
    (is (= 1 (count (ports/list-collection s chat player "a"))))))

(deftest concorrencia-hatch-isolada
  (let [s (fresh-store! "e-conc")
        chat "chat-e6"
        t0 8000000
        due (+ t0 (* 2 60 60 1000))
        barrier (CyclicBarrier. 2)
        latch (CountDownLatch. 2)
        results (atom {})]
    (boot! s chat "p1")
    (boot! s chat "p2")
    (doseq [p ["p1" "p2"]]
      (adv/grant-egg!
       s chat p
       {:operation-id (str "g-" p) :egg-id (str "egg-" p)
        :category :comum :species "Magikarp" :agora t0})
      (adv/incubate!
       s chat p
       {:operation-id (str "i-" p) :egg-id (str "egg-" p) :agora t0}))
    (doseq [p ["p1" "p2"]]
      (.start
       (Thread.
        (fn []
          (try
            (.await barrier 5 TimeUnit/SECONDS)
            (swap! results assoc p
                   (adv/hatch!
                    s chat p
                    {:operation-id (str "h-" p) :egg-id (str "egg-" p)
                     :hatch-id (str "hh-" p) :agora due}))
            (catch Exception ex
              (swap! results assoc p {:error (err/error-code ex)}))
            (finally
              (.countDown latch)))))))
    (is (.await latch 15 TimeUnit/SECONDS))
    (is (every? #(false? (:duplicate (val %))) @results))
    (is (= 1 (count (ports/list-collection s chat "p1" "a"))))
    (is (= 1 (count (ports/list-collection s chat "p2" "a"))))))
