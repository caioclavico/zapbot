(ns zapbot.router
  "Interpreta o texto das mensagens recebidas e despacha para o comando certo."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.piadas :as piadas]
            [zapbot.curiosidades :as curiosidades]
            [zapbot.noticias :as noticias]
            [zapbot.cotacao :as cotacao]
            [zapbot.previsao :as previsao]
            [zapbot.horoscopo :as horoscopo]
            [zapbot.filme :as filme]
            [zapbot.traduza :as traduza]
            [zapbot.resumo :as resumo]
            [zapbot.moderacao :as moderacao]))

(def ^:private comandos
  [{:emoji "🤡" :uso "piada"              :desc "Conta uma piada aleatória"}
   {:emoji "🧠" :uso "curiosidade"        :desc "Conta uma curiosidade aleatória"}
   {:emoji "📰" :uso "noticias"           :desc "Mostra as últimas notícias"}
   {:emoji "💱" :uso "cotacao [PAR ...]"  :desc "Cotação de moedas (padrão: USD-BRL, EUR-BRL, BTC-BRL)"}
   {:emoji "🌦️" :uso "previsao [cidade]"  :desc "Previsão do tempo (também funciona como !tempo, !clima)"}
   {:emoji "🔮" :uso "horoscopo [signo]"  :desc "Horóscopo do dia (ex.: aries, touro, gemeos, cancer... sem signo, sorteia um)"}
   {:emoji "🎬" :uso "filme [nome]"        :desc "Sinopse e nota de um filme, em inglês/título original (sem nome, sugere um aleatório)"}
   {:emoji "🌐" :uso "traduza <frase>"    :desc "Traduz uma frase para português"}
   {:emoji "�" :uso "resuma"             :desc "Resume as últimas mensagens do chat"}
   {:emoji "�🚫" :uso "ban"                :desc "Remove quem for mencionado/citado do grupo (apenas admins)"}
   {:emoji "❓" :uso "ajuda"              :desc "Mostra esta mensagem"}])

(defn- formatar-comando [{:keys [emoji uso desc]}]
  (str emoji " *" config/prefix uso "*\n" desc))

(def texto-ajuda
  (str "🤖 *" config/bot-name "*\n"
       "_Seu assistente no grupo!_\n\n"
       (str/join "\n\n" (map formatar-comando comandos))
       "\n\n✨ Digite um comando para começar."))

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
          "curiosidade" (p/resolved (curiosidades/curiosidade-aleatoria))
          "noticias"  (noticias/buscar-noticias)
          "cotacao"   (if (seq args)
                        (cotacao/buscar-cotacoes (map str/upper-case args))
                        (cotacao/buscar-cotacoes))
          ("previsao" "tempo" "clima") (if (seq args)
                                          (previsao/buscar-previsao (str/join " " args))
                                          (previsao/buscar-previsao))
          "horoscopo" (horoscopo/buscar-horoscopo (if (seq args)
                                                    (str/join " " args)
                                                    (horoscopo/signo-aleatorio)))
          "filme"     (if (seq args)
                        (filme/buscar-filme (str/join " " args))
                        (filme/buscar-filme))
          "traduza"   (traduza/traduzir-frase (str/join " " args))
          "resuma"    (resumo/resumir-chat message)
          "ban"       (moderacao/banir message)
          "ajuda"     (p/resolved texto-ajuda)
          (p/resolved (str "❌ Por que invocou um comando que nem o próprio tio "
                            config/bot-name " reconhece? Digite " config/prefix
                            "ajuda e ilumine-se.")))))))
