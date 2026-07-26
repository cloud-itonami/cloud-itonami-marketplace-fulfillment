(ns fulfillops.governor
  "FulfillmentGovernor -- the compliance layer standing between a warehouse
  robot and the floor.

  This is the first place in the commerce side of this fleet where
  `kotoba.robotics`'s gate actually governs something. The library has
  always been explicit that it is *policy, not control*; this governor
  is the policy being applied.

  Six HARD checks, ALL permanent, un-overridable by any human approval:

    1. Unknown order/seller  -- a task must fulfil a real sub-order from
                                `-marketplace-order`. Read from that
                                actor's output, never from the proposal.
    2. Over-pick             -- picking an SKU the buyer never ordered,
                                or more of one than they ordered,
                                counting the TOTAL across every pick
                                pass rather than just the newest.
                                Delegated to
                                `marketplace.fulfillment/pick-errors`.
                                A mechanically perfect robot still ships
                                the wrong box if nothing checks this
                                against the order.
    3. Denied robot action   -- a dispatch containing ANY action whose
                                safety class the assigned robot is not
                                certified for. The whole dispatch is
                                refused, not the offending action: a
                                partially-executed physical task is
                                worse than an unstarted one, because
                                the warehouse then does not know where
                                the goods are.
    4. Re-dispatch           -- handing a driver an action already
                                dispatched. After a crash or a retry,
                                re-issuing a `:grasp` double-actuates.
    5. Effect not :propose   -- any other value is a claim to directly
                                actuate outside governance.
    6. Scope exclusion       -- any claim that a robot ALREADY moved,
                                picked or shipped something, plus any op
                                outside the closed allowlist. This actor
                                releases actions to a driver; it never
                                reports having performed one.

  Three ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - A dispatch containing any `:needs-sign-off` action. This is the
      load-bearing one. `kotoba.robotics/human-sign-off-classes` puts
      `:high` and `:safety-critical` behind a human, and
      `marketplace.fulfillment/gate-actions` keeps `:needs-sign-off`
      strictly separate from `:permitted` so the two cannot be
      collapsed. `:handover` -- a robot moving a parcel into space a
      human courier occupies -- is `:high` by construction, so a
      handover ALWAYS reaches a person.
    - A short pick. Not an error: a stock discrepancy is an ordinary
      warehouse state. But shipping a parcel with fewer units than the
      buyer paid for, unremarked, is not, so it goes to a human."
  (:require [clojure.string :as str]
            [fulfillops.store :as store]
            [marketplace.fulfillment :as ff]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: there is no op that
  actuates hardware. `:dispatch-actions` RELEASES gated actions to an
  operator's driver; the driver is outside this repo."
  #{:plan-tasks :record-pick :dispatch-actions :complete-task
    :halt-task :flag-fulfillment-concern})

(def always-escalate-ops
  #{:flag-fulfillment-concern})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming the robot
  already acted.

  CRITICAL: every term is phrased as the COMPLETED action ('the robot
  picked'), never a bare noun like 'pick' or 'handover' -- a bare noun
  would match inside this actor's own legitimate task proposals (whose
  whole job is to talk about picking) and self-block the happy path. See
  `fulfillops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["the robot picked" "the robot has picked" "robot completed the pick"
   "the robot moved the" "the robot has moved" "robot completed the handover"
   "handed the parcel to the courier" "have handed the parcel"
   "shipped the parcel" "have shipped the" "loaded the vehicle"
   "actuated the gripper" "drove the robot to"
   "ロボットが把持した" "ロボットが移動させた" "搬送を完了した"
   "出荷を完了した" "引き渡しを完了した" "実際に動作させた"])

;; ----------------------------- checks -----------------------------

(defn- task-of [proposal st]
  (or (get-in proposal [:value :task])
      (some->> (get-in proposal [:value :task-id]) (store/task-record st))))

(defn- unknown-target-violations
  "A task must fulfil a real sub-order from `-marketplace-order`."
  [proposal st]
  (when (= :plan-tasks (:op proposal))
    (let [{:keys [order-id seller robot]} (:value proposal)]
      (cond
        (nil? (store/order-record st order-id))
        [{:rule :order-unknown :detail (str (or order-id "(order-id missing)"))}]

        (nil? (store/sub-order-for st order-id seller))
        [{:rule :seller-not-on-order :detail (str seller)}]

        (nil? (store/robot-record st robot))
        [{:rule :robot-unknown
          :detail (str (or robot "(robot missing)")
                       " は登録されていないロボット -- 未登録機に作業は割り当てない")}]))))

(defn- over-pick-violations
  "Picks are checked against the ORDER, counting every pass.

  `store/total-picked` folds in what was already picked, so a second
  pass that individually looks fine but takes the total over the ordered
  quantity is still caught."
  [proposal st]
  (when (= :record-pick (:op proposal))
    (let [{:keys [task-id picks]} (:value proposal)
          t (store/task-record st task-id)]
      (if-not t
        [{:rule :task-unknown :detail (str (or task-id "(task-id missing)"))}]
        (let [sub (store/sub-order-for st (:task/order t) (:task/seller t))
              total (store/total-picked st task-id picks)]
          (when-let [errs (seq (ff/pick-errors sub total))]
            (mapv (fn [e] {:rule (:fulfillment.error/code e)
                           :detail (:fulfillment.error/detail e)})
                  errs)))))))

(defn- dispatch-violations
  "A dispatch must name a real task, contain no action the assigned robot
  is not certified for, and repeat nothing already dispatched."
  [proposal st]
  (when (= :dispatch-actions (:op proposal))
    (let [t (task-of proposal st)]
      (if-not t
        [{:rule :task-unknown :detail "対象タスクが特定できない"}]
        (let [{:keys [denied]} (store/gate-for-task st t)
              already (filter #(store/dispatched? st (:action/id %))
                              (ff/actions-for t))]
          (cond
            (seq denied)
            [{:rule :robot-action-denied
              :detail (str (:task/robot t) " は安全クラス "
                           (pr-str (distinct (map :action/safety denied)))
                           " の動作に認定されていない -- ディスパッチ全体を拒否")
              :denied (mapv :action/id denied)}]

            (seq already)
            [{:rule :already-dispatched
              :detail (str "再ディスパッチは二重動作になる: "
                           (pr-str (mapv :action/id already)))}]))))))

(defn- handover-readiness-violations
  "A `:complete-task` on a handover requires both the warehouse's view
  and the order's view to agree that the parcel is packed.

  Two different actors maintain those views, and shipping on either one
  alone is how a parcel leaves before it was actually packed."
  [proposal st]
  (when (= :complete-task (:op proposal))
    (let [t (task-of proposal st)]
      (when (and t (= :handover (:task/kind t)))
        (let [sub (store/sub-order-for st (:task/order t) (:task/seller t))
              tasks (store/tasks-for st (:task/order t) (:task/seller t))]
          (when-not (ff/ready-for-handover? tasks sub)
            [{:rule :handover-not-ready
              :detail "倉庫側の梱包完了と注文側の :packed の両方が必要"}]))))))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "ロボットが既に動作した/出荷したという主張は永久に禁止 -- 本 actor は動作を解放するだけ"}])))

;; ----------------------------- soft gates -----------------------------

(defn- needs-sign-off?
  "A dispatch containing any action `kotoba.robotics` puts behind a
  human. `:handover` is `:high` by construction, so a handover ALWAYS
  reaches a person."
  [proposal st]
  (when (= :dispatch-actions (:op proposal))
    (when-let [t (task-of proposal st)]
      (seq (:needs-sign-off (store/gate-for-task st t))))))

(defn- short-pick?
  "A pick that leaves the buyer short. Not an error -- an ordinary stock
  discrepancy -- but shipping fewer units than were paid for, unremarked,
  is not, so a human looks."
  [proposal st]
  (when (= :record-pick (:op proposal))
    (let [{:keys [task-id picks]} (:value proposal)]
      (when-let [t (store/task-record st task-id)]
        (let [sub (store/sub-order-for st (:task/order t) (:task/seller t))]
          (seq (ff/short-picks sub (store/total-picked st task-id picks))))))))

(defn check
  "Censors a FulfillmentAdvisor proposal."
  [_request _context proposal store]
  (let [hard (into []
                   (concat (unknown-target-violations proposal store)
                           (over-pick-violations proposal store)
                           (dispatch-violations proposal store)
                           (handover-readiness-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        hard? (boolean (seq hard))
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                             (and (not hard?) (needs-sign-off? proposal store))
                             (and (not hard?) (short-pick? proposal store))))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :task-id    (:task-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
