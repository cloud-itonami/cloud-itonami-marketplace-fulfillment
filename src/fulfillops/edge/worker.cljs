(ns fulfillops.edge.worker
  "The fulfilment actor's Worker — the one that touches robots, and
  therefore the one whose refusals matter most.

  There is no op that actuates hardware. `:dispatch-actions` RELEASES
  gated actions to an operator's driver, and the driver is outside this
  repo entirely. What this host contributes is the gate:

    - every action carries a `kotoba.robotics` safety class;
    - `:handover` — a machine putting a parcel into a person's hands —
      is `:high`, and `marketplace.fulfillment/gate-actions` keeps
      `:needs-sign-off` in a SEPARATE bucket from `:permitted`, so a
      caller that ignores the distinction dispatches nothing rather
      than everything;
    - a robot may only run what it is CERTIFIED for, and certification
      is operator input written through `/robots`. A machine that could
      certify itself for a handover is not gated at all.

  Orders are read from the shared ref, written by `-marketplace-order`.
  A warehouse does not author the order it is picking against."
  (:require [marketplace.edge :as edge]
            [fulfillops.advisor :as advisor]
            [fulfillops.governor :as governor]
            [fulfillops.phase :as phase]
            [fulfillops.store :as store]))

(def ^:private ops
  {:advise      (fn [st req] (advisor/-advise (advisor/mock-advisor) st req))
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal req]
                  (store/commit-record! st {:op (:op proposal)
                                            :task-id (:task-id req)
                                            :value (:value proposal)
                                            :payload (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(defn- ctx [body]
  {:actor-id "fulfillops-edge"
   :phase (get body "phase" 3)
   :now (get body "now" "2026-06-01T00:00:00Z")})

(defn- run [client wants body op patch ref]
  (edge/with-store
    {:client client :wants wants :store-fn store/kotobase-store}
    (fn [st]
      (edge/outcome ref (edge/run ops st (ctx body)
                                  {:op op :task-id (get body "task-id")
                                   :ref ref :patch patch})))))

(defn- register-robot [client body]
  (let [rid (get body "robot-id")]
    (edge/with-store
      {:client client :wants {:robot [rid]} :store-fn store/kotobase-store}
      (fn [st]
        (store/put-robot! st {:robot-id rid
                              :station (get body "station")
                              :certified-for (set (map keyword (get body "certified-for" [])))})
        {:ref rid :disposition "commit" :violations []
         :certified-for (vec (get body "certified-for" []))}))))

;; ───────────────────────── routes ─────────────────────────

(defn- gated [request env f]
  (if-not (edge/authorised? request env)
    (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
    (-> (.json request) (.then #(f (js->clj %))) (.then #(edge/json % 200)))))

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/robots")) (gated request env #(register-robot client %))

    (and (= method "POST") (= path "/tasks"))
    (gated request env
           (fn [b] (run client {:order [(get b "order-id")] :robot :all :task :all}
                        b :plan-tasks
                        {:order-id (get b "order-id") :seller (get b "seller")
                         :station (get b "station") :robot (get b "robot")}
                        (get b "order-id"))))

    (and (= method "POST") (= path "/picks"))
    (gated request env
           (fn [b] (run client {:task [(get b "task-id")] :picks [(get b "task-id")]
                                :order :all}
                        b :record-pick
                        {:task-id (get b "task-id")
                         :picks (mapv (fn [p] {:sku (get p "sku") :qty (get p "qty")})
                                      (get b "picks" []))}
                        (get b "task-id"))))

    (and (= method "POST") (= path "/dispatch"))
    (gated request env
           (fn [b] (run client {:task [(get b "task-id")] :robot :all :dispatch :all
                                :order :all}
                        b :dispatch-actions
                        {:task-id (get b "task-id") :robot (get b "robot")}
                        (get b "task-id"))))

    (and (= method "POST") (= path "/complete"))
    (gated request env
           (fn [b] (run client {:task [(get b "task-id")] :picks [(get b "task-id")]
                                :order :all}
                        b :complete-task {:task-id (get b "task-id")}
                        (get b "task-id"))))

    (and (= method "POST") (= path "/halt"))
    (gated request env
           (fn [b] (run client {:task [(get b "task-id")]}
                        b :halt-task {:task-id (get b "task-id")
                                      :reason (get b "reason")}
                        (get b "task-id"))))

    (and (= method "GET") (= path "/tasks"))
    (-> (edge/read-all client :task)
        (.then (fn [ts]
                 (edge/json {:tasks (mapv (fn [t] {:task-id (:task/id t)
                                                   :order (:task/order t)
                                                   :seller (:task/seller t)
                                                   :status (str (:task/status t))})
                                          ts)}
                            200))))

    ;; /escalations and /ledger, implemented once in marketplace.edge.
    ;; Every high-stakes move in this actor escalates rather than committing
    ;; on a machine's say-so; without a way to READ those, each of those gates
    ;; is a black hole.
    :else (edge/ledger-routes client request env method path :fulfillops)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-marketplace-fulfillment" request env routes))}))
