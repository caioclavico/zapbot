(ns zapbot.pokemon.loja-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [zapbot.pokemon.loja :as loja]))

(def itens-oficiais
  {"revestimento-metalico" "metal-coat"
   "escama-dragao" "dragon-scale"
   "upgrade" "up-grade"
   "protetor" "protector"
   "pedra-rei" "kings-rock"
   "eletrizador" "electirizer"
   "magmarizador" "magmarizer"
   "tecido-ceifador" "reaper-cloth"
   "escama-prisma" "prism-scale"
   "chicote-doce" "whipped-dream"
   "sache-perfumado" "sachet"})

(deftest itens-de-evolucao-possuem-mapeamento-e-sao-equipaveis
  (is (= (set (keys itens-oficiais)) (set loja/itens-evolucao-troca)))
  (doseq [[item pokeapi] itens-oficiais]
    (testing item
      (is (= pokeapi (loja/item-evolucao-pokeapi item)))
      (is (loja/item-equipavel? item))
      (is (true? (:exclusivo-missoes (loja/dados-item item)))))))

(deftest nomes-de-itens-sao-normalizados
  (is (= "grande-bola" (loja/normalizar-item " Grande ")))
  (is (= "ultra-bola" (loja/normalizar-item "ULTRA")))
  (is (= "sache-perfumado" (loja/normalizar-item "Sachê Perfumado"))))

(deftest catalogo-diferencia-itens-equipaveis
  (is (loja/item-equipavel? "revestimento-metalico"))
  (is (loja/item-equipavel? "restos"))
  (is (not (loja/item-equipavel? "pokebola")))
  (is (nil? (loja/item-evolucao-pokeapi "pokebola"))))

(deftest frutas-possuem-recuperacao-de-motivacao
  (is (= 20 (:motivacao (loja/dados-item "fruta"))))
  (is (= 100 (:motivacao (loja/dados-item "fruta-dourada"))))
  (is (= 10 (:preco (loja/dados-item "fruta"))))
  (is (true? (:exclusivo-diario (loja/dados-item "fruta-dourada")))))

(deftest pocoes-possuem-precos-e-curas-diferentes
  (is (= 0.4 (:cura-hp (loja/dados-item "pocao"))))
  (is (= 20 (:preco (loja/dados-item "pocao"))))
  (is (= 1 (:cura-hp (loja/dados-item "pocao-maxima"))))
  (is (= 60 (:preco (loja/dados-item "pocao-maxima"))))
  (is (= "pocao-maxima" (loja/normalizar-item "Máxima"))))

(deftest sequencia-diaria-avanca-ou-reinicia
  (is (= 6 (loja/proxima-sequencia-diaria "2026-09-16" "2026-09-17" 5)))
  (is (= 1 (loja/proxima-sequencia-diaria "2026-09-15" "2026-09-17" 5)))
  (is (= 1 (loja/proxima-sequencia-diaria nil "2026-09-17" nil))))

(deftest catalogo-da-loja-usa-imagem-propria
  (async done
    (let [message #js {:from "chat" :author "jogador"}]
      (-> (loja/ver-loja-com-imagem message)
          (.then (fn [resposta]
                   (is (some? (:media resposta)))
                   (is (str/includes? (:texto resposta) "Loja do tio"))
                   (done)))
          (.catch (fn [erro]
                    (is false (str "Não conseguiu carregar a imagem da loja: " erro))
                    (done)))))))
