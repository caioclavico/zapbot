(ns pokemon.domain.shop
  "Catálogo e regras de compra/uso — espelha loja.cljs / pokemon-service."
  (:require [clojure.string :as str]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]))

(def ^:private catalogo-venda
  "Itens com :preco — únicos compráveis na loja."
  {"cartao-presente" {:nome "Cartão de presente" :emoji "🎁" :preco 20
                      :descricao "Presente para outro treinador (fluxo presente ainda bloqueado no JVM)."}
   "mochila" {:nome "Expansão de Mochila" :emoji "🎒" :preco 200 :expansao? true
              :descricao "Aumenta a capacidade da mochila em +50 (contador expansoes-mochila-50)."}
   "mt" {:nome "MT de Ataque" :emoji "💿" :preco 200
         :descricao "Aprende golpe (uso MT ainda bloqueado no JVM)."}
   "atadura" {:nome "Atadura" :emoji "🔥" :preco 15 :status "queimado"
              :descricao "Remove queimadura — !pk curar."}
   "antidoto" {:nome "Antídoto" :emoji "☠️" :preco 15 :status "envenenado"
               :descricao "Remove envenenamento — !pk curar."}
   "paralisia" {:nome "Cura de Paralisia" :emoji "⚡" :preco 15 :status "paralisado"
                :descricao "Remove paralisia — !pk curar."}
   "despertar" {:nome "Despertar" :emoji "💤" :preco 15 :status "adormecido"
                :descricao "Acorda o Pokémon — !pk curar."}
   "degelo" {:nome "Antigelo" :emoji "🧊" :preco 15 :status "congelado"
             :descricao "Descongela — !pk curar."}
   "persim" {:nome "Baya Caquic" :emoji "💫" :preco 15 :status "confuso"
             :descricao "Remove confusão — !pk curar."}
   "pocao" {:nome "Poção de Vida" :emoji "🧪" :preco 20 :cura-hp 0.4
            :descricao "Recupera 40% do HP — !pk pocao."}
   "pocao-maxima" {:nome "Poção Máxima" :emoji "💖" :preco 60 :cura-hp 1.0
                   :descricao "Restaura todo o HP — !pk pocao-maxima."}
   "fruta" {:nome "Fruta Frambo" :emoji "🍓" :preco 10 :motivacao 20
            :descricao "Motivação de ginásio (+20) — !pk gin potenciar."}
   "restos" {:nome "Restos" :emoji "🍱" :preco 45 :equipavel? true
             :descricao "Equipável (equipar ainda bloqueado no JVM)."}
   "banda" {:nome "Banda Musculosa" :emoji "💪" :preco 40 :equipavel? true
            :descricao "Equipável (equipar ainda bloqueado no JVM)."}
   "oculos" {:nome "Óculos Sábios" :emoji "👓" :preco 40 :equipavel? true
             :descricao "Equipável (equipar ainda bloqueado no JVM)."}
   "faixa-foco" {:nome "Faixa de Foco" :emoji "🥋" :preco 55 :equipavel? true
                 :descricao "Equipável (equipar ainda bloqueado no JVM)."}})

(def ^:private catalogo-info
  "Itens sem preço (informação / obtenção fora da loja)."
  {"pokebola" {:nome "Pokébola" :emoji "🔴" :nao-venda :missoes}
   "grande-bola" {:nome "Grande Bola" :emoji "🔵" :nao-venda :missoes}
   "ultra-bola" {:nome "Ultra Bola" :emoji "🟡" :nao-venda :missoes}
   "fruta-dourada" {:nome "Fruta Frambo Dourada" :emoji "🌟" :nao-venda :diario
                    :motivacao 100}
   "reviver" {:nome "Reviver" :emoji "💎" :nao-venda :missoes}
   "catalisador-evolutivo" {:nome "Catalisador Evolutivo" :emoji "🧬" :nao-venda :missoes}
   "pedra-agua" {:nome "Pedra da Água" :emoji "💧" :nao-venda :ginasio}
   "pedra-trovao" {:nome "Pedra do Trovão" :emoji "⚡" :nao-venda :ginasio}
   "pedra-fogo" {:nome "Pedra do Fogo" :emoji "🔥" :nao-venda :ginasio}
   "pedra-folha" {:nome "Pedra da Folha" :emoji "🍃" :nao-venda :ginasio}
   "pedra-lua" {:nome "Pedra da Lua" :emoji "🌙" :nao-venda :ginasio}})

(def status-cura
  {"atadura" "queimado"
   "antidoto" "envenenado"
   "paralisia" "paralisado"
   "despertar" "adormecido"
   "degelo" "congelado"
   "persim" "confuso"})

(def aliases-item
  {"normal" "pokebola" "grande" "grande-bola" "ultra" "ultra-bola"
   "maxima" "pocao-maxima" "maxima-pocao" "pocao-maxima"
   "poção" "pocao" "poção-maxima" "pocao-maxima"
   "óculos" "oculos" "oculos-sabios" "oculos"})

(defn normalizar-item
  [nome]
  (let [chave (-> (or nome "") str/trim str/lower-case
                  (str/replace #"á|à|ã|â" "a")
                  (str/replace #"é|ê" "e")
                  (str/replace #"í" "i")
                  (str/replace #"ó|ô|õ" "o")
                  (str/replace #"ú" "u")
                  (str/replace #"ç" "c")
                  (str/replace #"\s+" "-"))]
    (get aliases-item chave chave)))

(defn item
  [item-id]
  (or (get catalogo-venda item-id)
      (get catalogo-info item-id)))

(defn catalogo-loja
  "Itens à venda, ordenados por preço."
  []
  (->> catalogo-venda
       (map (fn [[id meta]] (assoc meta :item-id id)))
       (sort-by (juxt :preco :item-id))
       vec))

(defn ocupacao-mochila
  [inventory]
  (reduce + 0 (map #(long (:qty % 0)) (or inventory []))))

(defn capacidade-mochila-save
  [save]
  (capacity/capacidade-mochila
   (:trainer-level save 1)
   (:expansoes-mochila-50 save 0)
   (or (:capacidade-mochila-legada save) 50)))

(defn cabe-item?
  [save inventory]
  (< (ocupacao-mochila inventory) (capacidade-mochila-save save)))

(defn validar-compra!
  "Retorna item meta ou lança. Não muta estado."
  [item-id save inventory coins]
  (let [id (normalizar-item item-id)
        meta (get catalogo-venda id)
        info (get catalogo-info id)]
    (cond
      (and (nil? meta) (nil? info))
      (throw (err/error :unknown_shop_item {:item-id id}))

      (nil? meta)
      (throw (err/error :item_not_for_sale
                        {:item-id id :reason (:nao-venda info)}))

      (< (long coins) (long (:preco meta)))
      (throw (err/error :insufficient_coins
                        {:coins coins :preco (:preco meta) :item-id id}))

      (and (not (:expansao? meta)) (not (cabe-item? save inventory)))
      (throw (err/error :bag_full
                        {:ocupacao (ocupacao-mochila inventory)
                         :capacidade (capacidade-mochila-save save)}))

      :else (assoc meta :item-id id))))

(defn cura-status-item
  "Item-id que cura o status dado, ou nil."
  [status]
  (let [st (some-> status str str/lower-case)]
    (some (fn [[id s]] (when (= s st) id)) status-cura)))

(defn aplicar-pocao
  "Novo hp-atual após poção (fração do HP máximo)."
  [pokemon fracao]
  (let [hp-max (long (or (:hp pokemon) (get pokemon "hp") 0))
        hp (long (or (:hp-atual pokemon) (get pokemon "hp-atual") 0))
        ganho (long (Math/floor (* hp-max (double fracao))))
        novo (min hp-max (+ hp ganho))]
    (when (>= hp hp-max)
      (throw (err/error :pokemon_full_hp {:hp hp :hp-max hp-max})))
    {:hp-antes hp :hp-depois novo :hp-max hp-max}))
