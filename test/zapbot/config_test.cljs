(ns zapbot.config-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.config :as config]))

(deftest grupos-restritos-por-padrao
  (with-redefs [config/staging-like? true
                config/staging-bootstrap-group? false
                config/allow-all-groups? false
                config/dev-group-id "teste@g.us"]
    (is (config/chat-autorizado? "teste@g.us"))
    (is (not (config/chat-autorizado? "outro@g.us")))
    (doseq [id [nil "" "  "]]
      (with-redefs [config/dev-group-id id]
        (is (not (config/chat-autorizado? "outro@g.us")))))))

(deftest opt-in-libera-grupos-sem-liberar-conversas-privadas
  (doseq [dev-id [nil "" "teste@g.us"]]
    (with-redefs [config/staging-like? true
                  config/staging-bootstrap-group? false
                  config/allow-all-groups? true
                  config/dev-group-id dev-id]
      (is (config/chat-autorizado? "teste@g.us"))
      (is (config/chat-autorizado? "outro@g.us"))
      (doseq [cid [nil "" "123@c.us" "status@broadcast" "grupo@g.us.invalido"]]
        (is (not (config/chat-autorizado? cid)))))))

(deftest bootstrap-tem-prioridade-sobre-opt-in
  (doseq [allow? [false true]]
    (with-redefs [config/staging-like? true
                  config/staging-bootstrap-group? true
                  config/allow-all-groups? allow?
                  config/dev-group-id "teste@g.us"]
      (is (not (config/chat-autorizado? "teste@g.us")))
      (is (not (config/chat-autorizado? "outro@g.us"))))))

(deftest producao-preserva-comportamento
  (doseq [allow? [false true]]
    (with-redefs [config/staging-like? false
                  config/staging-bootstrap-group? false
                  config/allow-all-groups? allow?]
      (is (config/chat-autorizado? "outro@g.us"))
      (is (config/chat-autorizado? "123@c.us")))))

(deftest grupos-liberados-preservam-allowlist-de-usuarios
  (with-redefs [config/staging-like? true
                config/staging-bootstrap-group? false
                config/allow-all-groups? true
                config/staging-allowed-users #{"55119999"}]
    (is (config/chat-autorizado? "outro@g.us"))
    (is (config/usuario-autorizado? "55119999@c.us"))
    (is (not (config/usuario-autorizado? "55118888@c.us")))
    (is (not (config/usuario-autorizado? nil)))))

(deftest flag-desativada-por-padrao-e-opt-in-explicito
  (let [anterior (aget js/process.env "ALLOW_ALL_GROUPS")]
    (try
      (js-delete js/process.env "ALLOW_ALL_GROUPS")
      (is (false? (config/env-flag-on? "ALLOW_ALL_GROUPS")))
      (doseq [[valor esperado] [["true" true] [" TRUE " true]
                                ["false" false] ["" false] ["invalid" false]]]
        (aset js/process.env "ALLOW_ALL_GROUPS" valor)
        (is (= esperado (config/env-flag-on? "ALLOW_ALL_GROUPS"))))
      (finally
        (if (nil? anterior)
          (js-delete js/process.env "ALLOW_ALL_GROUPS")
          (aset js/process.env "ALLOW_ALL_GROUPS" anterior))))))
