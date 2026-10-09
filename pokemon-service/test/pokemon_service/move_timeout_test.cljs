(ns pokemon-service.move-timeout-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.http :as http]
            [zapbot.pokemon.core :as core]))

(def lookup-runtime (js/require "../runtime/move-lookup.cjs"))
(defn move-data [slug tipo poder]
  #js {:name slug :type #js {:name tipo} :power poder
       :damage_class #js {:name "physical"} :names #js [] :stat_changes #js []})
(defn raw-move [slug]
  {:move {:name slug} :version_group_details [{:move_learn_method {:name "level-up"} :level_learned_at 1}]})
(def base {:nome "Teste" :tipos ["normal"] :nivel 1 :hp 100 :ataque 50 :defesa 50
           :atq-esp 50 :def-esp 50 :veloc 50 :imagem nil :moves-brutos [(raw-move "tackle")]})

(defn with-lookups [fetch-fn timeout executar]
  (let [original js/fetch anterior core/consultas-golpes]
    (set! js/fetch fetch-fn)
    (set! core/consultas-golpes (.createMoveLookup lookup-runtime #js {:timeoutMs timeout}))
    (try
      (.finally (js/Promise.resolve (executar))
                (fn [] (set! js/fetch original) (set! core/consultas-golpes anterior)))
      (catch :default erro
        (set! js/fetch original) (set! core/consultas-golpes anterior)
        (js/Promise.reject erro)))))

(deftest golpes-validos-compartilham-http-e-cache-preserva-dados-e-prazo
  (async done
    (let [chamadas (atom 0) prazo (atom nil) fetch-original http/fetch!]
      (set! http/fetch! (fn ([url] (fetch-original url))
                           ([url opcoes] (fetch-original url opcoes))
                           ([url opcoes ms]
                            (reset! prazo ms)
                            (fetch-original url opcoes ms))))
      (-> (with-lookups
            (fn [_ _] (swap! chamadas inc)
              (js/Promise.resolve #js {:ok true :json #(js/Promise.resolve (move-data "tackle" "normal" 40))}))
            1000
            #(p/let [gs (p/all [(core/buscar-golpe "tackle") (core/buscar-golpe "tackle")])
                     cached (core/buscar-golpe "tackle")]
               (is (= 1 @chamadas))
               (is (= 15000 @prazo))
               (is (= (first gs) (second gs) cached))
               (is (= "Investida" (:nome-exibicao cached)))
               (is (= 40 (:poder cached)))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally (fn [] (set! http/fetch! fetch-original) (done)))))))

(deftest falha-parcial-preserva-stab-ordem-e-golpes-validos
  (async done
    (-> (with-lookups
          (fn [url _]
            (let [slug (last (.split url "/"))]
              (js/Promise.resolve
               #js {:ok (not= slug "missing")
                    :json #(if (= slug "broken") (js/Promise.reject (js/Error. "body error"))
                               (js/Promise.resolve (move-data slug (if (= slug "bite") "dark" "normal")
                                                             (if (= slug "bite") 60 40))))})))
          1000
          #(p/let [gs (core/golpes-ordenados (mapv raw-move ["bite" "broken" "tackle" "missing"]) ["normal"] 1)
                   pokemon (core/com-golpes (assoc base :moves-brutos (mapv raw-move ["bite" "tackle"])))]
             (is (= ["tackle" "tackle" "bite"] (mapv :slug gs)))
             (is (= #{"tackle" "bite"} (set (map :slug (:golpes pokemon)))))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))

(deftest headers-ou-corpo-pendentes-terminam-com-fallback-sem-retry
  (async done
    (let [chamadas (atom 0) sinais (atom [])]
      (-> (with-lookups
            (fn [_ opcoes] (swap! chamadas inc) (swap! sinais conj (.-signal opcoes))
              (js/Promise.resolve #js {:ok true :json #(js/Promise. (fn [& _]))}))
            30
            (fn []
              (p/let [pokemon (core/com-golpes base)]
                (is (= "Investida" (get-in pokemon [:golpes 0 :nome-exibicao])))
                (is (every? #(pos? (:poder %)) (:golpes pokemon)))
                (is (= 0 (get-in (core/filas-pendentes) [:move_requests :pending]))))))
          (.then (fn [_]
                   (is (= 1 @chamadas))
                   (is (every? #(.-aborted ^js %) @sinais))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest requisicao-pendente-nao-aprende-golpe-falso
  (async done
    (-> (with-lookups (fn [& _] (js/Promise. (fn [& _]))) 30
                     #(p/let [golpe (core/buscar-golpe "tackle")]
                        (is (nil? golpe))))
        (.catch (fn [erro] (is false (str erro))))
        (.finally done))))
