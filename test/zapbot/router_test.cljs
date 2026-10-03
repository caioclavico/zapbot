(ns zapbot.router-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [zapbot.bloqueio :as bloqueio]
            [zapbot.config :as config]
            [zapbot.pokemon-http :as pokemon]
            [zapbot.router :as router]))

(defn- mensagem [corpo]
  #js {:body corpo :from "chat@c.us"})

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
