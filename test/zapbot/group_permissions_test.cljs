(ns zapbot.group-permissions-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [zapbot.config :as config]
            [zapbot.bloqueio :as bloqueio]))

(deftest liberar-grupos-preserva-admins-configurados
  (async done
    (let [resultado
          (with-redefs [config/allow-all-groups? true
                        config/admin-numbers #{"admin@c.us"}]
            (bloqueio/autorizado? #js {:from "outro@g.us" :author "admin@c.us"}))]
      (-> resultado
          (.then #(is (true? %)))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest liberar-grupos-nao-permite-bloqueio-por-membro-comum
  (async done
    (let [grupo #js {:participants #js [#js {:id #js {:_serialized "membro@c.us"
                                                                   :user "membro"}
                                                   :isAdmin false :isSuperAdmin false}]}
          message #js {:from "outro@g.us" :author "membro@c.us"
                       :getChat #(js/Promise.resolve grupo)
                       :getContact #(js/Promise.resolve #js {:id #js {:_serialized "membro@c.us"}})}
          resultado
          (with-redefs [config/staging-like? true
                        config/staging-bootstrap-group? false
                        config/allow-all-groups? true
                        config/admin-numbers #{}]
            (is (config/chat-autorizado? "outro@g.us"))
            (bloqueio/processar-comando message "bloquear" "tudo"))]
      (-> resultado
          (.then #(is (= "🚫 Você precisa ser administrador do grupo para usar esse comando." %)))
          (.catch #(is false (str %)))
          (.finally done)))))
