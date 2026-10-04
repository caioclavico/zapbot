(ns zapbot.apuracao
  "Consulta a apuração oficial da eleição presidencial no TSE."
  (:require [promesa.core :as p]
            [clojure.string :as str]))

(def ^:private url-resultados
  "https://resultados.tse.jus.br/oficial/ele2026/6257/dados/br/br-c0001-e006257-u.jws")

(defn- decodificar-jws [jws]
  (let [[_ payload assinatura] (str/split (str/trim jws) #"\.")]
    (when-not (and payload assinatura)
      (throw (js/Error. "Resposta de apuração inválida.")))
    (js/JSON.parse (.toString (js/Buffer.from payload "base64url") "utf8"))))

(defn- formatar-numero [valor]
  (.toLocaleString (js/Number (or valor 0)) "pt-BR"))

(defn formatar-dados [dados]
  (if-let [cargo (first (filter #(= "1" (:cd %)) (:carg dados)))]
    (let [candidatos (->> (:agr cargo)
                          (mapcat :par)
                          (mapcat :cand)
                          (sort-by #(js/parseInt (or (:vap %) "0") 10) >))
          totalizadas (get-in dados [:s :st])
          total-secoes (get-in dados [:s :ts])]
      (str "🗳️ *Apuração presidencial 2026 — 1º turno*\n\n"
           "*Seções totalizadas:* " (formatar-numero totalizadas) "/"
           (formatar-numero total-secoes) " (" (get-in dados [:s :pst] "0,00") "%)\n"
           "*Atualização:* " (:ht dados) " de " (:dt dados) "\n\n"
           (str/join "\n"
                     (map-indexed
                      (fn [i candidato]
                        (str (inc i) ". *" (or (:nmu candidato) (:nm candidato)) "* ("
                             (:n candidato) "): " (formatar-numero (:vap candidato))
                             " votos — " (or (:pvap candidato) "0,00") "%"))
                      candidatos))))
    "🗳️ O TSE ainda não disponibilizou a apuração presidencial."))

(defn buscar-apuracao []
  (-> (p/let [resposta (js/fetch url-resultados)
              _ (when-not (.-ok resposta)
                  (throw (js/Error. (str "TSE HTTP " (.-status resposta)))))
              texto (.text resposta)
              dados (js->clj (decodificar-jws texto) :keywordize-keys true)]
        (formatar-dados dados))
      (p/catch (fn [erro]
                 (js/console.error "Erro ao buscar apuração do TSE:" erro)
                 "❌ Não consegui buscar a apuração presidencial agora. Tente novamente mais tarde."))))
