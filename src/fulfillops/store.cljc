(ns fulfillops.store
  "SSoT for the marketplace fulfillment actor -- the warehouse.

  ## The robotics gap this actor closes

  679 blueprints in this fleet declare `:robotics true`. Only 23 repos
  actually `require` `kotoba.robotics`, and every one of them is heavy
  industry (mining, steel, vehicle assembly, road works). **No
  commerce-side actor wired it at all** -- ISIC 4791's own \"warehouse
  fulfillment scheduling\" contains zero robotics references.

  This actor is the first. It does not make the robotics library do
  anything new; it puts the existing gate where warehouse work actually
  happens.

  Directories, keyed by STRING ids:

    orders      multi-seller orders from `-marketplace-order`. Read-only
                here: this actor picks what the order says, and the
                order actor owns what that is.
    tasks       `marketplace.fulfillment` pick/pack/handover tasks --
                the one thing this actor owns.
    picks       what was ACTUALLY picked per task, which is not the same
                as what was ordered and is the whole reason
                `pick-errors` and `short-picks` exist.
    robots      the fleet on the floor, with the safety classes each is
                certified for. A robot's certification is an operator
                fact, so it lives in the store rather than in code.
    dispatched  actions already handed to a driver -- kept so a
                re-dispatch after a crash cannot double-actuate.

  This store holds no money and drives no motors. `dispatch-actions`
  records that actions were RELEASED to an operator's driver; the driver
  itself is the operator's integration, outside this repo.

  The ledger stays append-only."
  (:require [marketplace.fulfillment :as ff]
            [marketplace.order :as order]
            [marketplace.persist :as persist]))

(defprotocol Store
  (order-record [s order-id] "Multi-seller order from -marketplace-order, or nil.")
  (all-order-records [s])
  (task-record [s task-id])
  (all-task-records [s])
  (tasks-for [s order-id seller])
  (picks-for [s task-id] "What was actually picked, [] when nothing yet.")
  (robot-record [s robot-id] "{:robot-id .. :station .. :certified-for #{safety-class ..}}")
  (all-robots [s])
  (dispatched? [s action-id])
  (ledger [s])
  (fulfillment-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (durable? [s] "False for the test-only memory backend.")
  (with-orders [s orders]))

;; ----------------------------- demo data -----------------------------

(defn demo-order []
  (order/order {:id "ord-1" :buyer "buyer-1"
                :lines [{:seller "merchant.alpha" :sku "A1" :name "Cola"
                         :qty 2 :unit-price-minor 600}
                        {:seller "merchant.alpha" :sku "A2" :name "Water"
                         :qty 3 :unit-price-minor 100}
                        {:seller "merchant.beta" :sku "B1" :name "Tea"
                         :qty 1 :unit-price-minor 1100}]}))

(defn demo-data
  "Fixtures covering the happy path and each hard check.

    amr-07  certified :low :medium        -- can pick and pack, NOT hand over
    amr-99  certified :low :medium :high  -- can also hand over, but a
                                             :high action still requires a
                                             human sign-off, which is the
                                             point of the distinction
    amr-01  certified :low only           -- cannot even pick"
  []
  {:orders {"ord-1" (demo-order)}
   :tasks {}
   :picks {}
   :dispatched #{}
   :robots {"amr-07" {:robot-id "amr-07" :station "ST-1"
                      :certified-for #{:none :low :medium}}
            "amr-99" {:robot-id "amr-99" :station "ST-1"
                      :certified-for #{:none :low :medium :high}}
            "amr-01" {:robot-id "amr-01" :station "ST-2"
                      :certified-for #{:none :low}}}})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (order-record [_ id] (get-in @a [:orders id]))
  (all-order-records [_] (sort-by :order/id (vals (:orders @a))))
  (task-record [_ id] (get-in @a [:tasks id]))
  (all-task-records [_] (sort-by :task/id (vals (:tasks @a))))
  (tasks-for [_ oid sel]
    (->> (vals (:tasks @a))
         (filter #(and (= oid (:task/order %)) (= sel (:task/seller %))))
         (sort-by :task/id)
         vec))
  (picks-for [_ tid] (get-in @a [:picks tid] []))
  (robot-record [_ id] (get-in @a [:robots id]))
  (all-robots [_] (sort-by :robot-id (vals (:robots @a))))
  (dispatched? [_ aid] (contains? (:dispatched @a) aid))
  (ledger [_] (:ledger @a))
  (fulfillment-log [_] (:fulfillment-log @a))
  (durable? [_] false)
  (commit-record! [_ record]
    (swap! a update :fulfillment-log conj record)
    (let [{:keys [op value]} record]
      (case op
        :plan-tasks
        (doseq [t (:tasks value)]
          (swap! a assoc-in [:tasks (:task/id t)] t))

        ;; Picks are APPENDED, never replaced: a second pick pass against
        ;; the same task adds to what was already taken off the shelf, and
        ;; overwriting would hide an over-pick that only the total reveals.
        :record-pick
        (swap! a update-in [:picks (:task-id value)] (fnil into []) (:picks value))

        :complete-task
        (swap! a update-in [:tasks (:task-id value)]
               (fn [t] (or (and t (ff/advance-task t :done)) t)))

        :halt-task
        (swap! a update-in [:tasks (:task-id value)]
               (fn [t] (or (and t (ff/advance-task t :halted)) t)))

        :dispatch-actions
        (do (swap! a update :dispatched into (map :action/id (:actions value)))
            (swap! a update-in [:tasks (:task-id value)]
                   (fn [t] (or (and t (ff/advance-task t :in-progress)) t))))

        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-orders [s orders] (when (seq orders) (swap! a assoc :orders orders)) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :fulfillment-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:orders {} :tasks {} :picks {} :dispatched #{}
                            :robots {} :ledger [] :fulfillment-log []}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn sub-order-for
  "The `okaimono` sub-order a task is fulfilling, or nil."
  [s order-id seller]
  (some-> (order-record s order-id) (order/sub-order seller)))

(defn certified-classes
  "The safety classes a robot is certified for. An unknown robot is
  certified for NOTHING -- an unregistered machine on the floor gets no
  benefit of the doubt."
  [s robot-id]
  (or (:certified-for (robot-record s robot-id)) #{}))

(defn gate-for-task
  "Gate a task's actions against the safety classes its OWN robot is
  certified for.

  This is where the store's robot registry becomes load-bearing: the
  permitted set is not an operator-wide constant, it is per-machine.
  A pick that `amr-99` may run unattended is denied outright to
  `amr-01`."
  [s t]
  (ff/gate-actions t (certified-classes s (:task/robot t))))

(defn total-picked
  "Everything picked against a task so far, as `[{:sku .. :qty ..} ..]`.
  Used for the over-pick check, which must see the TOTAL rather than
  just the newest pass."
  [s task-id new-picks]
  (into (vec (picks-for s task-id)) (or new-picks [])))

;; ----------------------------- durable store -----------------------------

(defrecord KotobaseStore [st seed]
  Store
  ;; Orders are READ here and written by -marketplace-order into the
  ;; same ref. A warehouse does not get to author the order it is
  ;; picking against.
  (order-record [_ id] (persist/get-doc (persist/ctx st :order :order/id) id))
  (all-order-records [_] (persist/all-docs (persist/ctx st :order :order/id)))
  (task-record [_ id] (persist/get-doc (persist/ctx st :task :task/id) id))
  (all-task-records [_] (persist/all-docs (persist/ctx st :task :task/id)))
  (tasks-for [this oid sel]
    (->> (all-task-records this)
         (filter #(and (= oid (:task/order %)) (= sel (:task/seller %))))
         (sort-by :task/id)
         vec))
  (picks-for [_ tid]
    (:picks/items (persist/get-doc (persist/ctx st :picks :task/id) tid) []))
  (robot-record [_ id] (persist/get-doc (persist/ctx st :robot :robot-id) id))
  (all-robots [_] (persist/all-docs (persist/ctx st :robot :robot-id)))
  (dispatched? [_ aid]
    (boolean (:dispatched (persist/get-doc (persist/ctx st :dispatch :id) (str aid)))))
  (durable? [_] (not (:persist/memory? st)))
  (ledger [_] (persist/read-events (persist/stream-ctx st :ledger)))
  (fulfillment-log [_] (persist/read-events (persist/stream-ctx st :fulfillment-log)))
  (commit-record! [this record]
    (persist/append-event! (persist/stream-ctx st :fulfillment-log) seed record)
    (let [{:keys [op value]} record
          tctx (persist/ctx st :task :task/id)
          advance! (fn [tid to]
                     (when-let [t (task-record this tid)]
                       (when-let [t' (ff/advance-task t to)]
                         (persist/put-doc! tctx t'))))]
      (case op
        :plan-tasks
        (doseq [t (:tasks value)] (persist/put-doc! tctx t))

        ;; Picks are APPENDED, never replaced: a second pick pass against
        ;; the same task adds to what was already taken off the shelf,
        ;; and overwriting would hide an over-pick that only the total
        ;; reveals.
        :record-pick
        (persist/put-doc! (persist/ctx st :picks :task/id)
                          {:task/id (:task-id value)
                           :picks/items (into (vec (picks-for this (:task-id value)))
                                              (:picks value))})

        :complete-task (advance! (:task-id value) :done)
        :halt-task     (advance! (:task-id value) :halted)

        :dispatch-actions
        (do (doseq [a (:actions value)]
              (persist/put-doc! (persist/ctx st :dispatch :id)
                                {:id (str (:action/id a)) :dispatched true}))
            (advance! (:task-id value) :in-progress))

        nil))
    record)
  (append-ledger! [_ fact]
    (persist/append-event! (persist/stream-ctx st :ledger) seed fact))
  (with-orders [this orders]
    (doseq [o (vals orders)] (persist/put-doc! (persist/ctx st :order :order/id) o))
    this))

(defn kotobase-store
  "A durable store over a HOST-INJECTED database API. Throws when the
  host has not wired one, per
  `:policy/fail-closed-without-host-injection`."
  [{:keys [db-api seq-fn]}]
  (->KotobaseStore (persist/store {:db-api db-api :actor "fulfillops"})
                   (or seq-fn (let [n (atom 0)] #(swap! n inc)))))

(defn put-robot!
  "Register a robot and what it is CERTIFIED for.

  Operator input. This actor reads the certification to gate an action;
  it never grants one, because a machine that could certify itself for a
  handover is not gated at all."
  [s robot]
  (persist/put-doc! (persist/ctx (:st s) :robot :robot-id) robot)
  robot)
