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
            [zapbot.dicionario :as dicionario]
            [zapbot.traduza :as traduza]
            [zapbot.resumo :as resumo]
            [zapbot.pergunta :as pergunta]
            [zapbot.bola8 :as bola8]
            [zapbot.sorteio :as sorteio]
            [zapbot.velha :as velha]
            [zapbot.naval :as naval]
            [zapbot.adedonha :as adedonha]
            [zapbot.musica :as musica]
            [zapbot.moderacao :as moderacao]
            [zapbot.status :as status]
            [zapbot.quiz :as quiz]
            [zapbot.bloqueio :as bloqueio]
            [zapbot.pokemon :as pokemon]
            [zapbot.pokedex :as pokedex]
            [zapbot.loja :as loja]
            [zapbot.rank :as rank]))

(def ^:private comandos
  [{:emoji "🤡" :uso "piada"              :desc "Conta uma piada aleatória"}
   {:emoji "🧠" :uso "curiosidade"        :desc "Conta uma curiosidade aleatória"}
   {:emoji "📰" :uso "noticias"           :desc "Mostra as últimas notícias"}
   {:emoji "💱" :uso "cotacao [PAR ...]"  :desc "Cotação de moedas (padrão: USD-BRL, EUR-BRL, BTC-BRL)"}
   {:emoji "🌦️" :uso "previsao [cidade]"  :desc "Previsão do tempo (também funciona como !tempo, !clima)"}
   {:emoji "🔮" :uso "horoscopo [signo]"  :desc "Horóscopo do dia (ex.: aries, touro, gemeos, cancer... sem signo, sorteia um)"}
   {:emoji "🎬" :uso "filme [nome]"        :desc "Sinopse e nota de um filme, em inglês/título original (sem nome, sugere um aleatório)"}
   {:emoji "🎥" :uso "filmes <nome>"       :desc "Lista até 10 filmes encontrados com esse nome (sem sinopse/capa)"}
   {:emoji "🏅" :uso "oscar [ano]"         :desc "Filme vencedor do Oscar de Melhor Filme daquele ano + concorrentes (sem ano, sorteia um vencedor)"}
   {:emoji "🎭" :uso "genero [nome|listar]" :desc "Indica um filme popular de um gênero (ex.: acao, terror, comedia; 'listar' mostra as opções)"}   {:emoji "📚" :uso "defina <palavra>"    :desc "Mostra o significado de uma palavra (também funciona como !definir)"}   {:emoji "�🌐" :uso "traduza <frase>"    :desc "Traduz uma frase para português"}
   {:emoji "📝" :uso "resuma"             :desc "Resume as últimas mensagens do chat (também funciona como !resumo, !resumir)"}
   {:emoji "🤔" :uso "pergunta <texto>"   :desc "Faz uma pergunta livre para o tio Odisseu responder com IA"}
   {:emoji "🎱" :uso "bola8 [pergunta]"    :desc "Bola 8 mágica: manda uma imagem e uma resposta aleatória"}
   {:emoji "🎲" :uso "sorteio"            :desc "Sorteia uma pessoa conhecida do chat/grupo"}
   {:emoji "⭕" :uso "velha [1-9|sair]"  :desc "Jogo da velha entre duas pessoas: abra/entre numa partida, jogue numa casa (1-9) ou saia"}
   {:emoji "�" :uso "naval [coordenada|sair]" :desc "Batalha naval entre duas pessoas: abra/entre numa partida e atire numa coordenada (ex.: C4) ou saia"}
   {:emoji "�🔤" :uso "adedonha [parar]" :desc "Sorteia uma letra e categorias pro grupo jogar STOP/adedonha - manda todas as respostas numa mensagem só (uma por linha, na ordem) que o bot pontua sozinho (também funciona como !stop)"}
   {:emoji "🎵" :uso "musica [genero|generos]"   :desc "Indica uma música com link do Spotify (sem gênero, sorteia um; 'generos' lista sugestões)"}
   {:emoji "🚫" :uso "ban"                :desc "Remove quem for mencionado/citado do grupo (apenas admins)"}
   {:emoji "📊" :uso "status"             :desc "Mostra o consumo de CPU, memória, disco e uptime da VM"}
   {:emoji "🧩" :uso "quiz [letra|sair]"  :desc "Pergunta de múltipla escolha: responda com a letra (a/b/c/d) ou cancele com 'sair'"}
   {:emoji "⚡" :uso "pokemon [atacar <1-4>|defender|curar|pocao|sair]" :desc "Batalha Pokémon entre duas pessoas, com Pokémon e golpes reais sorteados via PokeAPI: abra/entre, escolha um golpe pra atacar, defenda/esquive, cure um status ou recupere HP (ver !loja) ou saia"}
   {:emoji "📖" :uso "pokedex [nome|numero]" :desc "Mostra tipo, altura, peso, habilidades e status de um Pokémon em português (sem args, sorteia um)"}
   {:emoji "🏪" :uso "loja [comprar <item>]" :desc "Loja de curas pra status e poção de vida do !pokemon (queimadura/veneno/paralisia/pocao); ganhe moedas vencendo batalhas"}
   {:emoji "🏆" :uso "rank"                :desc "Mostra o rank de pontos desse chat (vitórias em !velha, !naval, !pokemon e !quiz)"}
   {:emoji "🔇" :uso "bloquear [comando|jogos|tudo|listar]" :desc "(admin) Bloqueia um comando, todos os jogos ou o bot inteiro nesse chat"}
   {:emoji "🔊" :uso "desbloquear [comando|jogos|tudo]" :desc "(admin) Libera um comando, todos os jogos ou o bot inteiro nesse chat"}
   {:emoji "🪪" :uso "meuid"              :desc "Mostra seu ID do WhatsApp, pra colocar em ADMIN_NUMBERS no .env"}
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

(defn- despachar [message cmd args]
  (case cmd
    "piada"     (piadas/piada-aleatoria)
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
    "filmes"    (filme/buscar-filmes (str/join " " args))
    "oscar"     (if (seq args)
                  (filme/buscar-oscar message (str/join " " args))
                  (filme/buscar-oscar message))
    "genero"    (filme/buscar-por-genero message (str/join " " args))
    ("defina" "definir") (dicionario/buscar-definicao (str/join " " args))
    "traduza"   (traduza/traduzir-frase (str/join " " args))
    ("resuma" "resumo" "resumir" "resume") (resumo/resumir-chat message)
    "pergunta"  (pergunta/perguntar message (str/join " " args))
    "bola8"     (bola8/jogar message (str/join " " args))
    "sorteio"   (sorteio/sortear message)
    "velha"     (velha/jogar message (str/join " " args))
    "naval"     (naval/jogar message (str/join " " args))
    ("adedonha" "stop") (adedonha/jogar message (str/join " " args))
    "musica"    (if (seq args)
                  (musica/buscar-musica (str/join " " args))
                  (musica/buscar-musica))
    "ban"       (moderacao/banir message)
    "status"    (status/status-vm)
    "quiz"      (quiz/jogar message (str/join " " args))
    "pokemon"   (pokemon/jogar message (str/join " " args))
    ("pokedex" "dex") (pokedex/buscar message (str/join " " args))
    "loja"      (if (and (seq args) (contains? #{"comprar" "comprar:"} (str/lower-case (first args))))
                  (p/resolved (loja/comprar message (str/join " " (rest args))))
                  (p/resolved (loja/ver-loja message)))
    "rank"      (p/resolved (rank/formatar-rank (bloqueio/chat-id message)))
    "meuid"     (p/resolved (str "🪪 Seu ID: " (or (.-author message) (.-from message))
                              "\n\nAdicione esse valor em ADMIN_NUMBERS no .env (separado por vírgula, "
                              "se já tiver outros) pra virar admin do bot."))
    "ajuda"     (p/resolved texto-ajuda)
    (p/resolved (str "❌ Por que invocou um comando que nem o próprio tio "
                      config/bot-name " reconhece? Digite " config/prefix
                      "ajuda e ilumine-se."))))

(defn processar
  "Recebe a mensagem do whatsapp-web.js e retorna uma promise com a resposta
  (string) a ser enviada, ou nil caso a mensagem não seja um comando ou o
  bot/comando esteja bloqueado nesse chat (ver zapbot.bloqueio)."
  [message]
  (let [texto (or (.-body message) "")]
    (when (comando? texto)
      (let [[cmd & args] (tokenizar texto)
            cmd          (-> cmd remover-acentos str/lower-case)
            cid          (bloqueio/chat-id message)]
        (cond
          (contains? #{"bloquear" "desbloquear"} cmd)
          (bloqueio/processar-comando message cmd (str/join " " args))

          (bloqueio/bot-bloqueado? cid)
          nil

          (bloqueio/comando-bloqueado? cid cmd)
          (p/resolved (str "🔇 " config/prefix cmd " está bloqueado nesse chat. Peça a um "
                            "admin para liberar com " config/prefix "desbloquear " cmd "."))

          :else
          (despachar message cmd args))))))
