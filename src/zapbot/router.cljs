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
            [zapbot.pergunta :as pergunta]
            [zapbot.bola8 :as bola8]
            [zapbot.sorteio :as sorteio]
            [zapbot.velha :as velha]
            [zapbot.adedonha :as adedonha]
            [zapbot.musica :as musica]
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
   {:emoji "📝" :uso "resuma"             :desc "Resume as últimas mensagens do chat"}
   {:emoji "🤔" :uso "pergunta <texto>"   :desc "Faz uma pergunta livre para o tio Odisseu responder com IA"}
   {:emoji "🎱" :uso "bola8 [pergunta]"    :desc "Bola 8 mágica: manda uma imagem e uma resposta aleatória"}
   {:emoji "🎲" :uso "sorteio"            :desc "Sorteia uma pessoa conhecida do chat/grupo"}
   {:emoji "⭕" :uso "velha [1-9|sair]"  :desc "Jogo da velha entre duas pessoas: abra/entre numa partida, jogue numa casa (1-9) ou saia"}
   {:emoji "🔤" :uso "adedonha [parar]" :desc "Sorteia uma letra e categorias pro grupo jogar STOP/adedonha (também funciona como !stop)"}
   {:emoji "🎵" :uso "musica [genero]"   :desc "Indica uma música com link do Spotify (sem gênero, sorteia um)"}
   {:emoji "🚫" :uso "ban"                :desc "Remove quem for mencionado/citado do grupo (apenas admins)"}
   {:emoji "❓" :uso "ajuda"              :desc "Mostra esta mensagem"}])

(defn- formatar-comando [{:keys [emoji uso desc]}]
  (str emoji " *" config/prefix uso "*\n" desc))

(def texto-ajuda
  (str "🤖 *" config/bot-name "*\n"
       "_Seu assistente!_\n\n"
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
                        (filme/buscar-filme message (str/join " " args))
                        (filme/buscar-filme message))
          "traduza"   (traduza/traduzir-frase (str/join " " args))
          "resuma"    (resumo/resumir-chat message)
          "pergunta"  (pergunta/perguntar (str/join " " args))
          "bola8"     (bola8/jogar message (str/join " " args))
          "sorteio"   (sorteio/sortear message)
          "velha"     (velha/jogar message (str/join " " args))
          ("adedonha" "stop") (adedonha/jogar message (str/join " " args))
          "musica"    (if (seq args)
                        (musica/buscar-musica (str/join " " args))
                        (musica/buscar-musica))
          "ban"       (moderacao/banir message)
          "ajuda"     (p/resolved texto-ajuda)
          (p/resolved (str "❌ Por que invocou um comando que nem o próprio tio "
                            config/bot-name " reconhece? Digite " config/prefix
                            "ajuda e ilumine-se.")))))))
