(ns fulfillops.sim
  "Offline demo: plan warehouse work, watch a robot's certification gate
  its own actions, and watch a handover stop for a human.
  `clojure -M:dev:run`."
  (:require [fulfillops.operation :as operation]
            [fulfillops.store :as store]
            [langgraph.graph :as g]))

(def ^:private ctx {:actor-id "fulfill-demo" :phase 3})

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- tid [kind] (str "ff.ord-1.merchant.alpha." (name kind)))

(defn- plan! [actor robot]
  (run-req! actor (str "plan-" robot)
            {:op :plan-tasks
             :patch {:order-id "ord-1" :seller "merchant.alpha"
                     :station "ST-1" :robot robot}}))

(defn -main [& _]
  (println "\n=== 1. 未認定ロボット amr-01(:low のみ) に :medium のピックを出す ===")
  (let [s (store/seed-db) actor (operation/build s)]
    (plan! actor "amr-01")
    (let [r (run-req! actor "d1" {:op :dispatch-actions :patch {:task-id (tid :pick)}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s)))))))

  (let [s (store/seed-db) actor (operation/build s)]
    (plan! actor "amr-99")

    (println "\n=== 2. 認定済み amr-99 のピック → 無人で解放 ===")
    (let [r (run-req! actor "d2" {:op :dispatch-actions :patch {:task-id (tid :pick)}})]
      (println "  status     :" (:status r) " disposition:" (:disposition (:state r))))

    (println "\n=== 3. 注文にない SKU / 過剰ピック → HARD hold ===")
    (let [_ (run-req! actor "p-bad" {:op :record-pick
                                     :patch {:task-id (tid :pick)
                                             :picks [{:sku "ZZ" :qty 1}]}})]
      (println "  ZZ         :" (mapv :rule (:violations (last (store/ledger s))))))
    (let [_ (run-req! actor "p-over" {:op :record-pick
                                      :patch {:task-id (tid :pick)
                                              :picks [{:sku "A1" :qty 3}]}})]
      (println "  A1 x3      :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 4. 欠品ピックは拒否ではなく人間へエスカレーション ===")
    (let [r (run-req! actor "p-short" {:op :record-pick
                                       :patch {:task-id (tid :pick)
                                               :picks [{:sku "A1" :qty 1}]}})]
      (println "  status     :" (:status r) "← 在庫差異は通常の状態、ただし黙って出荷はしない"))

    (println "\n=== 5. 引き渡しは必ず人間に届く（:high は sign-off クラス）===")
    (let [held (run-req! actor "d3" {:op :dispatch-actions :patch {:task-id (tid :handover)}})]
      (println "  status     :" (:status held))
      (println "  frontier   :" (:frontier held))
      (let [ok (g/run* actor {:approval {:status :approved :by "supervisor-01"}}
                       {:thread-id "d3" :resume? true})]
        (println "  --- 人間 supervisor-01 が承認 ---")
        (println "  status     :" (:status ok) " disposition:" (:disposition (:state ok)))))

    (println "\n=== 6. 安全停止はいつでも無人で通る ===")
    (let [r (run-req! actor "halt" {:op :halt-task
                                    :patch {:task-id (tid :pack) :reason :e-stop
                                            :source "floor-button"}})]
      (println "  status     :" (:status r) " task:" (:task/status (store/task-record s (tid :pack))))
      (println "  再開は不可（停止後の物理状態は不明）"))

    (println "\n=== 監査台帳 ===")
    (println " " (count (store/ledger s)) "件")))
