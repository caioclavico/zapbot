(ns zapbot.router
  "Interpreta o texto das mensagens recebidas e despacha para o comando certo."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.piadas :as piadas]
            [zapbot.noticias :as noticias]
            [zapbot.cotacao :as cotacao]
            [zapbot.previsao :as previsao]
            [zapbot.horoscopo :as horoscopo]
            [zapbot.moderacao :as moderacao]))

(def texto-ajuda
  (str "🤖 *Comandos disponíveis*\n\n"
       config/prefix "piada - conta uma piada\n"
       config/prefix "noticias - últimas notícias\n"
       config/prefix "cotacao [PAR ...] - cotação de moedas (padrão: USD-BRL EUR-BRL BTC-BRL)\n"
       config/prefix "previsao [cidade] - previsão do tempo\n"
       config/prefix "horoscopo <signo> - horóscopo do dia\n"
       config/prefix "ban - remove a pessoa mencionada/citada do grupo (apenas admins)\n"
       config/prefix "ajuda - mostra esta mensagem"))

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(defn- comando? [texto]
  (str/starts-with? (str/trim texto) config/prefix))

(defn- tokenizar [texto]
  (-> texto str/trim (subs (count config/prefix)) (str/split #"\s+")))

(defn processar
  "Recebe a mensagem do whatsapp-web.js e retorna uma promise com a resposta
  (string) a ser enviada, ou nil caso a mensagem não seja um comando."
  [message]
  (let [texto (or (.-body message) "")]
    (when (comando? texto)
      (let [[cmd & args] (tokenizar texto)
            cmd          (-> cmd remover-acentos str/lower-case)]
        (case cmd
          "piada"     (p/resolved (piadas/piada-aleatoria))
          "noticias"  (noticias/buscar-noticias)
          "cotacao"   (if (seq args)
                        (cotacao/buscar-cotacoes (map str/upper-case args))
                        (cotacao/buscar-cotacoes))
          "previsao"  (if (seq args)
                        (previsao/buscar-previsao (str/join " " args))
                        (previsao/buscar-previsao))
          "horoscopo" (if (seq args)
                        (horoscopo/buscar-horoscopo (str/join " " args))
                        (p/resolved (str "❓ Use: " config/prefix "horoscopo <signo>")))
          "ban"       (moderacao/banir message)
          "ajuda"     (p/resolved texto-ajuda)
          (p/resolved (str "❓ Comando desconhecido. Digite " config/prefix "ajuda para ver os comandos.")))))))
