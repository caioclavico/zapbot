(ns pokemon.tests.raid-lobby-fase2-test
  "Fase 2 — lobby cooperativo: domínio + Datomic (idempotência/concorrência/expiração)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-lobby-f2")
(def ^:private storage-rel ".datomic-local/raid-lobby-f2")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(def ^:private mon
  ;; nv.8 → liga iniciante do ginásio pedra (1–10)
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 8 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil))

;;;; Domínio puro

(deftest constantes-lobby-fase2
  (is (= (* 5 60 1000) raid/duracao-inscricoes))
  (is (= 5 raid/max-participantes))
  (is (= 1 raid/min-participantes)))

(deftest boss-do-ginasio-pedra
  (let [b (raid/boss-do-ginasio "pedra")]
    (is (= "pedra" (:ginasio b)))
    (is (= "iniciante" (:liga b)))
    (is (= "Onix" (get-in b [:chefe :nome])))
    (is (pos? (get-in b [:chefe :hp])))))

(deftest boss-ginasio-desconhecido
  (is (= :raid_gym_unknown
         (try (raid/boss-do-ginasio "xyz")
              (catch Exception ex (err/error-code ex))))))

(deftest dominio-criar-e-bloquear-duplicata-logica
  (let [boss (:chefe (raid/boss-do-ginasio "pedra"))
        r (raid/nova-raid {:raid-id "r1" :chat-id "c1" :ginasio "pedra"
                           :nome-ginasio "Pedra" :nivel-ginasio 10
                           :liga "iniciante" :chefe boss :agora 1000
                           :criador "ash"})]
    (is (= "inscricoes" (:fase r)))
    (is (= (+ 1000 raid/duracao-inscricoes) (:expira r)))
    (is (= 5 (:max-jogadores r)))
    (is (raid/lobby-ou-combate-vivo? r 1001))
    (is (not (raid/lobby-ou-combate-vivo? r (+ 1000 raid/duracao-inscricoes))))))

(deftest dominio-inscricao-idempotente-e-limite
  (let [boss (:chefe (raid/boss-do-ginasio "pedra"))
        base (raid/nova-raid {:raid-id "r1" :chat-id "c1" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "ash"})
        join (fn [r pid]
               (raid/entrar r {:player-id pid :nome pid :save-id "a"
                               :fence-generation 0 :pokemon mon :collection-index 0}
                            1001))
        r1 (join base "ash")
        r1b (join r1 "ash")]
    (is (= 1 (count (:ordem r1b))))
    (is (= r1 r1b))
    (let [full (reduce join r1b ["b" "c" "d" "e"])]
      (is (= 5 (count (:ordem full))))
      (is (= :raid_full
             (try (join full "f")
                  (catch Exception ex (err/error-code ex))))))))

(deftest dominio-expirar-vazio-cancela-e-com-inscritos-inicia
  "Mínimo real=1: 0 inscritos → cancelada; 1+ → combate."
  (let [boss (:chefe (raid/boss-do-ginasio "pedra"))
        base (raid/nova-raid {:raid-id "r1" :chat-id "c1" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "ash"})
        join (fn [r pid]
               (raid/entrar r {:player-id pid :nome pid :save-id "a"
                               :fence-generation 0 :pokemon mon :collection-index 0}
                            1001))
        vazio (raid/expirar-inscricoes base (+ 1000 raid/duracao-inscricoes))
        sozinho (join base "ash")
        solo (raid/expirar-inscricoes sozinho (+ 1000 raid/duracao-inscricoes))
        duo (-> base (join "ash") (join "misty"))
        combate (raid/expirar-inscricoes duo (+ 1000 raid/duracao-inscricoes))]
    (is (= :cancelada (:acao vazio)))
    (is (= "cancelada" (get-in vazio [:raid :fase])))
    (is (= :combate (:acao solo)))
    (is (= "combate" (get-in solo [:raid :fase])))
    (is (= :combate (:acao combate)))
    (is (= "combate" (get-in combate [:raid :fase])))
    (is (= 440 (get-in combate [:raid :hp-chefe])))))

(deftest dominio-cancelar-criador
  (let [boss (:chefe (raid/boss-do-ginasio "pedra"))
        r (raid/nova-raid {:raid-id "r1" :chat-id "c1" :ginasio "pedra"
                           :liga "iniciante" :chefe boss :agora 1000
                           :criador "ash"})
        c (raid/cancelar r "ash" 1001)]
    (is (= "cancelada" (:fase c)))
    (is (= :raid_not_creator
           (try (raid/cancelar r "misty" 1001)
                (catch Exception ex (err/error-code ex)))))))

;;;; Integração Datomic

(deftest abrir-por-ginasio-e-status
  (let [[s _] (fresh! "f2-open")
        chat "chat-open"]
    (boot! s chat "ash")
    (let [o (tx/open-raid!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "open-1" :ginasio "pedra" :agora-ms 1000})]
      (is (= "inscricoes" (get-in o [:raid :fase])))
      (is (= "pedra" (get-in o [:raid :ginasio])))
      (is (= "iniciante" (get-in o [:raid :liga])))
      (is (= "Onix" (get-in o [:raid :chefe :nome]))))
    (is (= :raid_chat_busy
           (try
             (tx/open-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "open-2" :ginasio "agua" :agora-ms 1001})
             (catch Exception ex (err/error-code ex)))))))

(deftest inscricao-duplicada-idempotente-e-lotacao
  (let [[s _] (fresh! "f2-join")
        chat "chat-j"]
    (doseq [p ["ash" "m1" "m2" "m3" "m4" "m5"]]
      (boot! s chat p))
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (let [j1 (tx/join-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "j1" :collection-index 0 :nome "Ash"
                 :agora-ms 1001})
          j1b (tx/join-raid!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "j1b" :collection-index 0 :nome "Ash"
                  :agora-ms 1002})]
      (is (= 1 (get-in j1 [:raid :n-participantes])))
      (is (true? (:duplicate j1b)))
      (is (= 1 (get-in j1b [:raid :n-participantes]))))
    (doseq [[p op] [["m1" "jm1"] ["m2" "jm2"] ["m3" "jm3"] ["m4" "jm4"]]]
      (tx/join-raid!
       s {:chat-id chat :player-id p :save-id "a"
          :fence-generation (fence s chat p)
          :operation-id op :collection-index 0 :nome p :agora-ms 1010}))
    (is (= :raid_full
           (try
             (tx/join-raid!
              s {:chat-id chat :player-id "m5" :save-id "a"
                 :fence-generation (fence s chat "m5")
                 :operation-id "jm5" :collection-index 0 :nome "m5"
                 :agora-ms 1020})
             (catch Exception ex (err/error-code ex)))))))

(deftest cancelar-e-abrir-de-novo
  (let [[s _] (fresh! "f2-cancel")
        chat "chat-c"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o1" :ginasio "pedra" :agora-ms 1000})
    (let [c (tx/cancel-raid!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "c1" :agora-ms 1001})]
      (is (= "cancelada" (get-in c [:raid :fase]))))
    (let [o2 (tx/open-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "o2" :ginasio "agua" :agora-ms 1002})]
      (is (= "agua" (get-in o2 [:raid :ginasio]))))))

(deftest expiracao-eager-sem-inscritos-cancela
  (let [[s _] (fresh! "f2-exp-empty")
        chat "chat-e0"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (let [after (raid-app/expire-lobby! s chat (+ 1000 raid/duracao-inscricoes))]
      (is (= "cancelada" (:fase after))))
    (is (nil? (raid-app/active-raid s chat)))))

(deftest expiracao-eager-um-inscrito-inicia
  "Mínimo real=1: um jogador no lobby → combate ao expirar."
  (let [[s _] (fresh! "f2-exp")
        chat "chat-e"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "j" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (let [after (raid-app/expire-lobby! s chat (+ 1000 raid/duracao-inscricoes))]
      (is (= "combate" (:fase after)))
      (is (some? (raid-app/active-raid s chat))))))

(deftest expiracao-eager-com-quorum-inicia-combate
  (let [[s _] (fresh! "f2-exp2")
        chat "chat-e2"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (let [after (raid-app/expire-lobby! s chat (+ 1000 raid/duracao-inscricoes))]
      (is (= "combate" (:fase after)))
      (is (= 440 (:hp-chefe after))))))

(deftest reinicio-recupera-ponteiro
  (let [db-name "f2-restart"
        [s _] (fresh! db-name)
        chat "chat-r"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (let [s2 (reopen! db-name)
          r (raid-app/active-raid s2 chat)]
      (is (= "inscricoes" (:fase r)))
      (is (= "pedra" (:ginasio r))))))

(deftest open-idempotente-por-operation-id
  (let [[s _] (fresh! "f2-idemp")
        chat "chat-i"]
    (boot! s chat "ash")
    (let [req {:chat-id chat :player-id "ash" :save-id "a"
               :fence-generation (fence s chat "ash")
               :operation-id "same-open" :ginasio "pedra" :agora-ms 1000}
          a (tx/open-raid! s req)
          b (tx/open-raid! s req)
          active (raid-app/active-raid s chat)]
      (is (false? (:duplicate a)))
      (is (true? (:duplicate b)))
      (is (= (get-in a [:raid :raid-id]) (:raid-id active)))
      (is (= "inscricoes" (:fase active))))))

;;;; Cenários críticos Fase 2.1

(deftest abertura-concorrente-mesmo-chat
  (let [[s _] (fresh! "f2-race")
        chat "chat-race"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (let [f1 (future
               (try
                 (tx/open-raid!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "race-ash" :ginasio "pedra" :agora-ms 1000})
                 (catch Exception ex {:error (err/error-code ex)})))
          f2 (future
               (try
                 (tx/open-raid!
                  s {:chat-id chat :player-id "misty" :save-id "a"
                     :fence-generation (fence s chat "misty")
                     :operation-id "race-misty" :ginasio "agua" :agora-ms 1000})
                 (catch Exception ex {:error (err/error-code ex)})))
          r1 @f1
          r2 @f2
          oks (filter #(and (map? %) (get-in % [:raid :raid-id])) [r1 r2])
          errs (filter #(and (map? %) (:error %)) [r1 r2])]
      (is (= 1 (count oks)) "exatamente uma abertura vence")
      (is (= 1 (count errs)))
      (is (= :raid_chat_busy (:error (first errs))))
      (is (some? (raid-app/active-raid s chat))))))

(deftest join-idempotente-mesmo-operation-id
  "Mesmo comando (operation-id) duas vezes — receipt impede duplicação."
  (let [[s _] (fresh! "f2-http-dup")
        chat "chat-dup"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o-dup" :ginasio "pedra" :agora-ms 1000})
    (let [req {:chat-id chat :player-id "ash" :save-id "a"
               :fence-generation (fence s chat "ash")
               :operation-id "join-same" :collection-index 0 :nome "Ash"
               :agora-ms 1001}
          a (tx/join-raid! s req)
          b (tx/join-raid! s req)]
      (is (false? (:duplicate a)))
      (is (true? (:duplicate b)))
      (is (= 1 (get-in a [:raid :n-participantes])))
      (is (= 1 (count (:ordem (raid-app/active-raid s chat))))))))

(deftest reinicio-durante-combate
  (let [db-name "f2-restart-combat"
        [s _] (fresh! db-name)
        chat "chat-rc"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "st" :agora-ms 1003})
    (let [s2 (reopen! db-name)
          r (raid-app/active-raid s2 chat)]
      (is (= "combate" (:fase r)))
      (is (= 440 (:hp-chefe r)))
      (is (= 2 (count (:ordem r)))))))

(deftest comando-apos-expiracao-cancelada
  "Sem inscritos o lobby cancela; entrar/iniciar depois → raid_not_found."
  (let [[s _] (fresh! "f2-exp-cmd")
        chat "chat-ec"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    ;; Ninguém entra → ao expirar cancela (mínimo real=1 exige ≥1 inscrito).
    (raid-app/expire-lobby! s chat (+ 1000 raid/duracao-inscricoes))
    (is (nil? (raid-app/active-raid s chat)))
    (is (= :raid_not_found
           (try
             (tx/join-raid!
              s {:chat-id chat :player-id "misty" :save-id "a"
                 :fence-generation (fence s chat "misty")
                 :operation-id "j-late" :collection-index 0 :nome "Misty"
                 :agora-ms 121001})
             (catch Exception ex (err/error-code ex)))))
    (is (= :raid_not_found
           (try
             (tx/start-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "st-late" :agora-ms 121002})
             (catch Exception ex (err/error-code ex)))))))

(deftest criador-sai-do-lobby-e-outro-permanece
  (let [[s _] (fresh! "f2-creator-leave")
        chat "chat-cl"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (tx/leave-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "leave-ash" :agora-ms 1003})
    (let [r (raid-app/active-raid s chat)]
      (is (= ["misty"] (:ordem r)))
      (is (= "ash" (:criador r)))
      ;; Criador fora: não pode iniciar; misty também não (só criador).
      (is (= :not_in_raid
             (try
               (tx/start-raid!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "st-ash" :agora-ms 1004})
               (catch Exception ex (err/error-code ex)))))
      (is (= :raid_not_creator
             (try
               (tx/start-raid!
                s {:chat-id chat :player-id "misty" :save-id "a"
                   :fence-generation (fence s chat "misty")
                   :operation-id "st-m" :agora-ms 1005})
               (catch Exception ex (err/error-code ex))))))))

(deftest inicio-antecipado-com-dois
  (let [[s _] (fresh! "f2-early")
        chat "chat-early"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 1002})
    (let [st (tx/start-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "st" :agora-ms 1003})]
      (is (= "combate" (get-in st [:raid :fase])))
      (is (= 440 (get-in st [:raid :hp-chefe])))
      ;; Lobby não paga recompensa
      (is (false? (boolean (get-in st [:raid :rewards-paid])))))))

(deftest grupos-independentes
  (let [[s _] (fresh! "f2-groups")
        c1 "chat-g1"
        c2 "chat-g2"]
    (boot! s c1 "ash")
    (boot! s c2 "ash")
    (tx/open-raid!
     s {:chat-id c1 :player-id "ash" :save-id "a"
        :fence-generation (fence s c1 "ash")
        :operation-id "o1" :ginasio "pedra" :agora-ms 1000})
    (tx/open-raid!
     s {:chat-id c2 :player-id "ash" :save-id "a"
        :fence-generation (fence s c2 "ash")
        :operation-id "o2" :ginasio "agua" :agora-ms 1000})
    (is (= "pedra" (:ginasio (raid-app/active-raid s c1))))
    (is (= "agua" (:ginasio (raid-app/active-raid s c2))))
    (is (not= (:raid-id (raid-app/active-raid s c1))
              (:raid-id (raid-app/active-raid s c2))))))

(deftest falha-persistencia-open-nao-duplica-ponteiro
  "Segunda abertura rejeitada não altera a raide vencedora (CAS/busy)."
  (let [[s _] (fresh! "f2-fail-open")
        chat "chat-fo"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (let [ok (tx/open-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "ok-open" :ginasio "pedra" :agora-ms 1000})
          rid (get-in ok [:raid :raid-id])]
      (is (= :raid_chat_busy
             (try
               (tx/open-raid!
                s {:chat-id chat :player-id "misty" :save-id "a"
                   :fence-generation (fence s chat "misty")
                   :operation-id "bad-open" :ginasio "fogo" :agora-ms 1001})
               (catch Exception ex (err/error-code ex)))))
      (is (= rid (:raid-id (raid-app/active-raid s chat))))
      (is (= "pedra" (:ginasio (raid-app/active-raid s chat)))))))

(deftest schema-029-atributos-presentes-apos-open
  (let [[s _] (fresh! "f2-schema")
        chat "chat-sch"]
    (boot! s chat "ash")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "o" :ginasio "pedra" :agora-ms 5000})
    (let [r (raid-app/active-raid s chat)]
      (is (= 5000 (:criada-em r)))
      (is (= 1 (:min-jogadores r)))
      (is (= 5 (:max-jogadores r)))
      (is (= (+ 5000 raid/duracao-inscricoes) (:expira r))))))
