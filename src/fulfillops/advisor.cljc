(ns fulfillops.advisor
  "FulfillmentAdvisor -- the contained intelligence node for the
  warehouse.

  Six ops from a closed allowlist: planning the pick/pack/handover
  sequence for a sub-order, recording what was actually picked,
  releasing gated robot actions to a driver, completing a task, halting
  one, and flagging a concern.

  CRITICAL: every proposal's `:effect` is always `:propose`, and there
  is no op that actuates hardware. `:dispatch-actions` proposes
  RELEASING already-gated actions to an operator's driver; the driver is
  outside this repo.

  What this advisor structurally cannot do:

    - invent an item. `:plan-tasks` builds tasks from the `okaimono`
      sub-order via `marketplace.fulfillment/plan-tasks`, so a task's
      lines are the buyer's lines.
    - talk a robot past its certification. The safety class of each
      action comes from the task kind (a `:handover` is `:high` because
      a robot is moving a parcel into a human's space), and the
      governor gates it against the assigned robot's own registered
      certification.

  Deterministic mock so the actor graph runs offline."
  (:require [fulfillops.store :as store]
            [marketplace.fulfillment :as ff]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-plan
  [st {:keys [patch]}]
  (let [{:keys [order-id seller station robot]} patch
        sub (store/sub-order-for st order-id seller)
        tasks (when sub (ff/plan-tasks order-id sub :station station :robot robot))]
    {:op      :plan-tasks
     :task-id nil
     :summary (if tasks
                (str order-id " / " seller " の " (count tasks) " 工程を " robot " に計画")
                (str order-id " / " seller " の工程を計画できない"))
     :rationale "注文明細に基づく作業計画の作成のみ。ロボットの動作そのものは行わない。"
     :cites   [(str order-id) (str seller)]
     :effect  :propose
     :value   {:order-id order-id :seller seller :robot robot
               :station station :tasks tasks}
     :confidence (if tasks 0.93 0.2)}))

(defn- propose-pick
  "Record what came off the shelf. Deliberately reports the OBSERVATION
  (a barcode read, a weight) rather than asserting the robot succeeded,
  so the default rationale never trips `scope-excluded-terms`."
  [_st {:keys [patch]}]
  {:op      :record-pick
   :task-id (:task-id patch)
   :summary (str (:task-id patch) " のピック実績を記録: " (pr-str (:picks patch)))
   :rationale "棚から取り出された数量の観測記録のみ。過剰ピックや欠品の判定は注文明細との突合に委ねる。"
   :cites   [(str (:task-id patch))]
   :effect  :propose
   :value   {:task-id (:task-id patch) :picks (vec (:picks patch))}
   :confidence (or (:confidence patch) 0.9)})

(defn- propose-dispatch
  "Release a task's gated actions to the operator's driver.

  The actions are computed here but GATED by the governor against the
  robot's certification; a task with any denied action is refused whole,
  and one with a sign-off-class action escalates to a human."
  [st {:keys [patch]}]
  (let [t (store/task-record st (:task-id patch))
        gated (when t (store/gate-for-task st t))]
    {:op      :dispatch-actions
     :task-id (:task-id patch)
     :summary (str (:task-id patch) " の動作をドライバへ解放: permit "
                   (count (:permitted gated)) " / 要人間承認 "
                   (count (:needs-sign-off gated)) " / 拒否 " (count (:denied gated)))
     :rationale "安全クラスを満たした動作の解放提案のみ。ハードウェアへの送出は運用者のドライバが行い、本 actor は動作しない。"
     :cites   [(str (:task-id patch)) (str (:task/robot t))]
     :effect  :propose
     :value   {:task-id (:task-id patch)
               :task t
               :actions (vec (concat (:permitted gated) (:needs-sign-off gated)))}
     :confidence (or (:confidence patch) 0.9)}))

(defn- propose-complete
  [_st {:keys [patch]}]
  {:op      :complete-task
   :task-id (:task-id patch)
   :summary (str (:task-id patch) " の完了を記録")
   :rationale "工程完了の観測記録のみ。次工程への引き渡し可否は倉庫側と注文側の両方の状態が一致して初めて成立する。"
   :cites   [(str (:task-id patch))]
   :effect  :propose
   :value   {:task-id (:task-id patch)}
   :confidence (or (:confidence patch) 0.91)})

(defn- propose-halt
  "Halting is always the safe direction, so it is auto-eligible. The
  reason vocabulary is `kotoba.robotics/stop-reasons`, and an
  unrecognised reason yields no halt record at all rather than one that
  looks understood."
  [st {:keys [patch]}]
  (let [t (store/task-record st (:task-id patch))
        h (when t (ff/halt t (:reason patch) :source (:source patch)
                           :detail (:detail patch)))]
    {:op      :halt-task
     :task-id (:task-id patch)
     :summary (str (:task-id patch) " を安全停止: " (pr-str (:reason patch)))
     :rationale "安全停止の記録のみ。停止後の物理状態は不明として扱い、再開ではなく新規タスクの計画を要する。"
     :cites   [(str (:task-id patch))]
     :effect  :propose
     :value   {:task-id (:task-id patch) :halt h :reason (:reason patch)}
     :confidence 0.97}))

(defn- propose-concern
  [_st {:keys [patch]}]
  {:op      :flag-fulfillment-concern
   :task-id (:task-id patch)
   :summary (str (:task-id patch) " の作業に関する懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale "観察された作業上の懸念事実の報告のみ。原因の断定や機器の停止判断は行わない。"
   :cites   [(str (:task-id patch))]
   :effect  :propose
   :value   patch
   :confidence (or (:confidence patch) 0.8)})

(defn infer
  [st {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :plan-tasks               (propose-plan st request)
                   :record-pick              (propose-pick st request)
                   :dispatch-actions         (propose-dispatch st request)
                   :complete-task            (propose-complete st request)
                   :halt-task                (propose-halt st request)
                   :flag-fulfillment-concern (propose-concern st request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually the robot picked the items and shipped the parcel")
      proposal)))

(defn trace [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :task-id    (:task-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request]
      (infer store request))))
