(ns pokemon.domain.capacity
  "Capacidade de coleção/mochila — espelha zapbot.pokemon.armazenamento-regras."
  (:require [pokemon.domain.xp :as xp]))

(def capacidade-inicial-pokemon 50)
(def capacidade-inicial-mochila 50)

(def marcos
  {5 {:pokemon 50} 10 {:mochila 50} 15 {:pokemon 50} 20 {:mochila 50}
   25 {:pokemon 50} 30 {:mochila 50} 35 {:pokemon 50} 40 {:mochila 50}
   45 {:pokemon 50} 50 {:mochila 50}})

(defn bonus-por-nivel
  [nivel tipo]
  (reduce + 0 (for [[marco bonus] marcos
                    :when (<= marco (max 1 (long (or nivel 1))))]
                (long (get bonus tipo 0)))))

(defn capacidade-pokemon
  [nivel expansoes capacidade-legada]
  (max (long (or capacidade-legada 0))
       (+ capacidade-inicial-pokemon
          (bonus-por-nivel nivel :pokemon)
          (* 50 (max 0 (long (or expansoes 0)))))))

(defn capacidade-mochila
  [nivel expansoes capacidade-legada]
  (+ (max capacidade-inicial-mochila (long (or capacidade-legada 0)))
     (bonus-por-nivel nivel :mochila)
     (* 50 (max 0 (long (or expansoes 0))))))

(defn nivel-para-capacidade
  "Unifica nível persistido, XP de treinador e componentes (legado)."
  [save]
  (let [from-comp (long (:nivel (xp/perfil-treinador save) 1))
        from-stored (long (:trainer-level save 1))
        from-xp (long (:nivel (xp/progresso-treinador (long (:trainer-xp save 0))) 1))]
    (max 1 from-comp from-stored from-xp)))

(defn capacidade-pokemon-save
  [save]
  (capacidade-pokemon (nivel-para-capacidade save)
                      (:expansoes-pokemon-50 save 0)
                      (:capacidade-pokemon-legada save 0)))

(defn resumo-armazenamento-pokemon
  "Ocupação = tamanho da coleção do save (ativo). cheio? quando ocupação >= capacidade.
   Excedente histórico é preservado (não trunca)."
  [colecao-count save]
  (let [ocup (long colecao-count)
        cap (long (capacidade-pokemon-save save))
        excesso (max 0 (- ocup cap))
        livres (max 0 (- cap ocup))]
    {:ocupacao ocup
     :capacidade cap
     :livres livres
     :excesso excesso
     :cheio? (>= ocup cap)
     :expansoes-pokemon-50 (long (:expansoes-pokemon-50 save 0))
     :nivel (nivel-para-capacidade save)}))

(defn colecao-cheia?
  [colecao-count save]
  (boolean (:cheio? (resumo-armazenamento-pokemon colecao-count save))))

(defn cabe-pokemon?
  "Sem ginásios neste recorte: ocupação = tamanho da coleção.
   Bloqueia novas aquisições quando ocupação >= capacidade."
  [colecao-count save]
  (not (colecao-cheia? colecao-count save)))
