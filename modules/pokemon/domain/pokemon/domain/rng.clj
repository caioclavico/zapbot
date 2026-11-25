(ns pokemon.domain.rng
  "RNG injetável para equivalência/testes. Ausência da chave → sorteio uniforme."
  (:import [java.util.concurrent ThreadLocalRandom]))

(defn roll
  "Retorna inteiro em [0, n). Se `rng` contém `key`, usa esse valor módulo n."
  [rng key n]
  (let [n (long n)]
    (when-not (pos? n)
      (throw (ex-info "roll n must be positive" {:n n :key key})))
    (if (and (map? rng) (contains? rng key))
      (mod (long (get rng key)) n)
      (.nextLong (ThreadLocalRandom/current) n))))
