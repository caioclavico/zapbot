(ns zapbot.pokemon.navegacao
  (:require [clojure.string :as str]))

(def duracao-menu (* 5 60 1000))
(defonce menus (atom {}))

(defn abrir! [chave opcoes agora]
  ;; Apenas estado transitório; remove sessões inativas ao abrir outro menu.
  (swap! menus #(assoc (into {} (filter (fn [[_ m]] (> (:expira m) agora)) %))
                      chave {:opcoes opcoes :expira (+ agora duracao-menu)})))

(defn limpar! [chave] (swap! menus dissoc chave))

(defn resolver! [chave texto batalha agora]
  (let [texto (str/trim (str/lower-case (or texto "")))
        menu (get @menus chave)]
    (cond
      (or (str/blank? texto) (= texto "menu") (= texto "0"))
      {:menu? true}

      (re-matches #"[0-9]+" texto)
      (cond
        menu (cond
               (<= (:expira menu) agora)
               (do (limpar! chave) {:erro "⌛ Este menu expirou. Digite !pk para abrir novamente."})
               (get (:opcoes menu) texto)
               {:comando (get (:opcoes menu) texto)}
               :else {:erro "❓ Escolha um número mostrado no menu ou use !pk 0 para voltar."})
        batalha {:comando (str batalha " " texto)}
        :else {:erro "❓ Não há opções abertas para você. Digite !pk para ver o menu."})

      :else (do (limpar! chave) {:comando texto}))))

(def opcoes-principais
  {"1" "treinador" "2" "meus" "3" "batalhas" "4" "mochila"
   "5" "centro" "6" "loja" "7" "ajuda"})

(defn texto-menu [titulo linhas prefixo]
  (str titulo "\n\n" (str/join "\n" linhas)
       "\n\nDigite " prefixo "pk <número>. " prefixo "pk 0 abre o menu."
       "\nOs comandos diretos continuam disponíveis. Menu válido por 5 minutos."))
