(ns fulfillops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [fulfillops.advisor :as advisor]
            [fulfillops.governor :as governor]
            [fulfillops.store :as store]
            [marketplace.fulfillment :as ff]))

(def ctx {:actor-id "fulfill-actor" :phase 3})

(defn- db [] (store/seed-db))

(defn- advise [st op & [patch]]
  (advisor/-advise (advisor/mock-advisor) st {:op op :patch (or patch {})}))

(defn- check [st op & [patch]]
  (governor/check {:op op} ctx (advise st op patch) st))

(defn- planned
  "A store with alpha's three tasks planned onto `robot`."
  ([] (planned "amr-07"))
  ([robot]
   (let [st (db)
         p (advise st :plan-tasks {:order-id "ord-1" :seller "merchant.alpha"
                                   :station "ST-1" :robot robot})]
     (store/commit-record! st {:op :plan-tasks :value (:value p)})
     st)))

(defn- tid [kind] (str "ff.ord-1.merchant.alpha." (name kind)))

;; ───────────────────── planning reads the order ─────────────────────

(deftest planning-builds-tasks-from-the-buyers-own-lines
  (let [st (db)
        p (advise st :plan-tasks {:order-id "ord-1" :seller "merchant.alpha"
                                  :station "ST-1" :robot "amr-07"})
        v (governor/check {:op :plan-tasks} ctx p st)]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (= [:pick :pack :handover] (mapv :task/kind (get-in p [:value :tasks]))))
    (is (= 2 (count (:task/lines (first (get-in p [:value :tasks]))))) "A1 and A2")))

(deftest planning-against-an-unknown-target-is-a-hard-block
  (doseq [[patch expected]
          [[{:order-id "ord-nope" :seller "merchant.alpha" :robot "amr-07"} :order-unknown]
           [{:order-id "ord-1" :seller "merchant.nobody" :robot "amr-07"} :seller-not-on-order]
           [{:order-id "ord-1" :seller "merchant.alpha" :robot "amr-ghost"} :robot-unknown]]]
    (let [v (check (db) :plan-tasks patch)]
      (is (true? (:hard? v)) (pr-str patch))
      (is (some #{expected} (mapv :rule (:violations v))) (pr-str patch)))))

(deftest an-unregistered-robot-gets-no-benefit-of-the-doubt
  (is (= #{} (store/certified-classes (db) "amr-ghost"))))

;; ───────────────────── the over-pick guard ─────────────────────

(deftest picking-exactly-the-order-is-clean
  (let [st (planned)
        v (governor/check {:op :record-pick} ctx
                          (advise st :record-pick
                                  {:task-id (tid :pick)
                                   :picks [{:sku "A1" :qty 2} {:sku "A2" :qty 3}]})
                          st)]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)))))

(deftest picking-something-not-in-the-order-is-a-hard-block
  (let [st (planned)
        v (governor/check {:op :record-pick} ctx
                          (advise st :record-pick
                                  {:task-id (tid :pick) :picks [{:sku "ZZ" :qty 1}]})
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:sku-not-in-order} (mapv :rule (:violations v))))))

(deftest over-picking-across-TWO-passes-is-still-caught
  (testing "a second pass that individually looks fine but takes the total
            over the ordered quantity"
    (let [st (planned)]
      (store/commit-record! st {:op :record-pick
                                :value {:task-id (tid :pick) :picks [{:sku "A1" :qty 2}]}})
      (let [v (governor/check {:op :record-pick} ctx
                              (advise st :record-pick
                                      {:task-id (tid :pick) :picks [{:sku "A1" :qty 1}]})
                              st)]
        (is (true? (:hard? v)))
        (is (some #{:over-pick} (mapv :rule (:violations v))))))))

(deftest a-short-pick-escalates-rather-than-blocking
  (testing "a stock discrepancy is ordinary; shipping fewer units than the
            buyer paid for, unremarked, is not"
    (let [st (planned)
          v (governor/check {:op :record-pick} ctx
                            (advise st :record-pick
                                    {:task-id (tid :pick) :picks [{:sku "A1" :qty 1}]})
                            st)]
      (is (false? (:hard? v)) "not an error")
      (is (true? (:high-stakes? v)))
      (is (true? (:escalate? v)))
      (is (false? (:ok? v))))))

;; ───────────────────── the robotics gate ─────────────────────

(deftest a-robot-not-certified-for-the-class-is-denied-whole
  (testing "amr-01 is :low only, so even a :medium pick is refused —
            and the WHOLE dispatch goes, not just the offending action"
    (let [st (planned "amr-01")
          v (governor/check {:op :dispatch-actions} ctx
                            (advise st :dispatch-actions {:task-id (tid :pick)})
                            st)]
      (is (true? (:hard? v)))
      (is (some #{:robot-action-denied} (mapv :rule (:violations v)))))))

(deftest a-certified-pick-dispatches-unattended
  (let [st (planned "amr-07")
        v (governor/check {:op :dispatch-actions} ctx
                          (advise st :dispatch-actions {:task-id (tid :pick)})
                          st)]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)) ":medium is within amr-07's certification")))

(deftest a-handover-ALWAYS-reaches-a-human
  (testing "a robot moving a parcel into space a human courier occupies is
            :high, which kotoba.robotics puts behind a sign-off"
    (doseq [robot ["amr-07" "amr-99"]]
      (let [st (planned robot)
            v (governor/check {:op :dispatch-actions} ctx
                              (advise st :dispatch-actions {:task-id (tid :handover)})
                              st)]
        (if (= "amr-07" robot)
          (testing "amr-07 is not even certified for :high — hard block"
            (is (true? (:hard? v)))
            (is (some #{:robot-action-denied} (mapv :rule (:violations v)))))
          (testing "amr-99 IS certified, and it still escalates"
            (is (false? (:hard? v)) (pr-str (:violations v)))
            (is (true? (:high-stakes? v)))
            (is (false? (:ok? v))
                "certification permits the class; it does not waive the sign-off")))))))

(deftest re-dispatch-is-refused
  (testing "after a crash or a retry, re-issuing a :grasp double-actuates"
    (let [st (planned "amr-07")
          p (advise st :dispatch-actions {:task-id (tid :pick)})]
      (store/commit-record! st {:op :dispatch-actions :value (:value p)})
      (let [v (governor/check {:op :dispatch-actions} ctx
                              (advise st :dispatch-actions {:task-id (tid :pick)}) st)]
        (is (true? (:hard? v)))
        (is (some #{:already-dispatched} (mapv :rule (:violations v))))))))

(deftest dispatching-an-unknown-task-is-refused
  (let [v (check (planned) :dispatch-actions {:task-id "ff.nope"})]
    (is (true? (:hard? v)))
    (is (some #{:task-unknown} (mapv :rule (:violations v))))))

;; ───────────────────── handover readiness ─────────────────────

(deftest completing-a-handover-needs-both-views-to-agree
  (let [st (planned)
        v (governor/check {:op :complete-task} ctx
                          (advise st :complete-task {:task-id (tid :handover)}) st)]
    (is (true? (:hard? v)))
    (is (some #{:handover-not-ready} (mapv :rule (:violations v)))
        "the pack task is not done and the order is not :packed")))

(deftest completing-a-pick-is-clean
  (let [st (planned)
        v (governor/check {:op :complete-task} ctx
                          (advise st :complete-task {:task-id (tid :pick)}) st)]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)))))

;; ───────────────────── halting ─────────────────────

(deftest halting-is-clean-and-needs-a-known-reason
  (let [st (planned)
        v (governor/check {:op :halt-task} ctx
                          (advise st :halt-task {:task-id (tid :pick) :reason :e-stop}) st)]
    (is (false? (:hard? v)))
    (is (true? (:ok? v)) "nothing that makes a robot safer waits for a queue"))
  (testing "an unrecognised stop reason produces no halt record"
    (let [st (planned)
          p (advise st :halt-task {:task-id (tid :pick) :reason :vibes})]
      (is (nil? (get-in p [:value :halt]))))))

;; ───────────────────── structural checks ─────────────────────

(deftest effect-must-be-propose
  (let [st (planned)
        v (governor/check {:op :record-pick} ctx
                          (assoc (advise st :record-pick
                                         {:task-id (tid :pick) :picks [{:sku "A1" :qty 1}]})
                                 :effect :commit)
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:effect-not-propose} (mapv :rule (:violations v))))))

(deftest no-op-in-the-allowlist-actuates-hardware
  (doseq [op [:move-robot :actuate-gripper :drive-to]]
    (let [v (governor/check {:op op} ctx {:op op :effect :propose :confidence 0.99} (db))]
      (is (true? (:hard? v)) (str op))
      (is (some #{:op-not-allowed} (mapv :rule (:violations v))) (str op)))))

(deftest scope-exclusion-blocks-claims-the-robot-already-acted
  (let [st (planned)
        p (advisor/infer st {:op :record-pick
                             :patch {:task-id (tid :pick) :picks [{:sku "A1" :qty 1}]}
                             :out-of-scope? true})
        v (governor/check {:op :record-pick} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:scope-excluded} (mapv :rule (:violations v))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "legitimate proposals must talk about picking and handovers —
            the excluded terms are phrased as COMPLETED robot actions"
    (let [st (planned "amr-99")]
      (doseq [[op patch]
              [[:plan-tasks {:order-id "ord-1" :seller "merchant.beta" :robot "amr-99"}]
               [:record-pick {:task-id (tid :pick) :picks [{:sku "A1" :qty 2}]}]
               [:dispatch-actions {:task-id (tid :pick)}]
               [:complete-task {:task-id (tid :pick)}]
               [:halt-task {:task-id (tid :pick) :reason :governor}]
               [:flag-fulfillment-concern {:task-id (tid :pick) :concern "棚の在庫差異"}]]]
        (let [v (check st op patch)]
          (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))) (str op)))))))

(deftest concern-always-escalates
  (let [st (planned)
        v (check st :flag-fulfillment-concern {:task-id (tid :pick) :concern "x"
                                               :confidence 0.99})]
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)))))

(deftest low-confidence-escalates
  (let [st (planned)
        v (governor/check {:op :complete-task} ctx
                          (assoc (advise st :complete-task {:task-id (tid :pick)})
                                 :confidence 0.2) st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

;; ───────────────────── the gap this actor closes ─────────────────────

(deftest this-actor-actually-uses-the-robotics-library
  (testing "679 blueprints declare :robotics true; 23 repos required the
            library, all heavy industry. This is the commerce side's first"
    (let [st (planned "amr-99")
          t (store/task-record st (tid :handover))
          acts (ff/actions-for t)]
      (is (seq acts))
      (is (every? #(contains? #{:sense :move :grasp :actuate :emit} (:action/kind %)) acts))
      (is (every? #(contains? #{:none :low :medium :high :safety-critical}
                              (:action/safety %))
                  acts))
      (is (= :high (:action/safety (first acts)))))))
