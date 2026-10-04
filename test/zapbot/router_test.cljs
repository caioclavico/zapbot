(ns zapbot.router-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [zapbot.apuracao :as apuracao]
            [zapbot.bloqueio :as bloqueio]
            [zapbot.config :as config]
            [zapbot.pokemon-http :as pokemon]
            [zapbot.router :as router]))

(defn- mensagem [corpo]
  #js {:body corpo :from "chat@c.us"})

(deftest apuracao-presidencial-formata-dados-oficiais
  (let [texto (apuracao/formatar-dados
               {:dt "04/10/2026"
                :ht "17:30:00"
                :s {:st "250" :ts "500" :pst "50,00"}
                :carg [{:cd "1"
                        :agr [{:par [{:cand [{:n "13" :nmu "LULA" :vap "1000" :pvap "55,56"}
                                      {:n "22" :nmu "BOLSONARO" :vap "800" :pvap "44,44"}]}]}]}]})]
    (is (str/includes? texto "250/500 (50,00%)"))
    (is (str/includes? texto "*Atualização:* 17:30:00 de 04/10/2026"))
    (is (str/includes? texto "1. *LULA* (13): 1.000 votos — 55,56%"))
    (is (< (.indexOf texto "LULA") (.indexOf texto "BOLSONARO")))))

(deftest apuracao-aceita-grafia-com-e-sem-acentos
  (async done
    (let [chamadas (atom [])
          respostas
          (with-redefs [config/prefix "!"
                        bloqueio/chat-id (fn [_] "chat@c.us")
                        bloqueio/bot-bloqueado? (fn [_] false)
                        bloqueio/comando-bloqueado? (fn [_ _] false)
                        apuracao/buscar-apuracao (fn []
                                                   (swap! chamadas conj true)
                                                   (js/Promise.resolve "apuração atual"))]
            (mapv #(router/processar (mensagem %)) ["!apuração" "!apuracao"]))]
      (-> (js/Promise.all (clj->js respostas))
          (.then (fn [resultados]
                   (is (= ["apuração atual" "apuração atual"] (js->clj resultados)))
                   (is (= 2 (count @chamadas)))
                   (done)))
          (.catch (fn [erro]
                    (is false (str "Falha ao despachar !apuração: " erro))
                    (done)))))))

(deftest pk-treinador-despacha-para-o-comando-pokemon
  (async done
    (let [message (mensagem "!pk treinador")
          chamadas (atom [])]
      (with-redefs [config/prefix "!"
                    bloqueio/chat-id (fn [_] "chat@c.us")
                    bloqueio/bot-bloqueado? (fn [_] false)
                    bloqueio/comando-bloqueado? (fn [_ _] false)
                    pokemon/jogar (fn [recebida argumentos]
                                    (swap! chamadas conj [recebida argumentos])
                                    (js/Promise.resolve :perfil))]
        (-> (router/processar message)
            (.then (fn [resposta]
                     (is (= :perfil resposta))
                     (is (= 1 (count @chamadas)))
                     (is (identical? message (ffirst @chamadas)))
                     (is (= "treinador" (second (first @chamadas))))
                     (done)))
            (.catch (fn [erro]
                      (is false (str "Falha ao despachar !pk treinador: " erro))
                      (done))))))))

(deftest bloqueio-de-pk-consulta-a-chave-pokemon
  (async done
    (let [message (mensagem "!pk treinador")
          chaves-consultadas (atom [])
          despachos (atom 0)]
      (with-redefs [config/prefix "!"
                    bloqueio/chat-id (fn [_] "chat@c.us")
                    bloqueio/bot-bloqueado? (fn [_] false)
                    bloqueio/comando-bloqueado?
                    (fn [_ chave]
                      (swap! chaves-consultadas conj chave)
                      (= "pokemon" chave))
                    pokemon/jogar (fn [_ _]
                                    (swap! despachos inc)
                                    (js/Promise.resolve :nao-deveria-despachar))]
        (-> (router/processar message)
            (.then (fn [resposta]
                     (is (= ["pokemon"] @chaves-consultadas))
                     (is (zero? @despachos))
                     (is (string? resposta))
                     (is (re-find #"!pokemon.*bloqueado" resposta))
                     (done)))
            (.catch (fn [erro]
                      (is false (str "Falha ao verificar o bloqueio de !pk: " erro))
                      (done))))))))

(deftest rotas-pokemon-externas-passam-pelo-adaptador-http
  (async done
    (let [chamadas (atom [])
          respostas
          (with-redefs [config/prefix "!"
                        bloqueio/chat-id (fn [_] "chat@c.us")
                        bloqueio/bot-bloqueado? (fn [_] false)
                        bloqueio/comando-bloqueado? (fn [_ _] false)
                        pokemon/jogar (fn [_ args]
                                        (swap! chamadas conj ["pokemon" args])
                                        (js/Promise.resolve nil))
                        pokemon/executar (fn [_ cmd args]
                                           (swap! chamadas conj [cmd args])
                                           (js/Promise.resolve nil))]
            (mapv #(router/processar (mensagem %))
                  ["!pokemon time 2" "!pk atk 1" "!pokedex pikachu" "!dex 25" "!pdx eevee"
                   "!presente @amigo" "!presentes @amigo" "!missoes semanais resgatar"
                   "!missões resgatar" "!mochila diario" "!loja comprar atadura"
                   "!loja detalhe mt" "!loja"]))]
      (-> (js/Promise.all (clj->js respostas))
          (.then (fn [_]
                   (is (= [["pokemon" "time 2"] ["pokemon" "atk 1"]
                           ["pokedex" "pikachu"] ["pokedex" "25"] ["pokedex" "eevee"]
                           ["pokemon" "presente @amigo"] ["pokemon" "presente @amigo"]
                           ["pokemon" "missoes semanais resgatar"] ["pokemon" "missoes resgatar"]
                           ["mochila" "diario"] ["loja" "comprar atadura"] ["loja" "detalhe mt"]
                           ["loja" ""]] @chamadas))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))
