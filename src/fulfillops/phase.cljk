(ns fulfillops.phase
  "Phase 0->3 staged rollout for the marketplace fulfillment actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-planning  -- work may be planned, every write needs
                                   human approval.
    Phase 2  assisted-recording -- adds pick recording and task
                                   completion, still approval-gated.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   planning, pick recording, completion
                                   and dispatch may auto-commit.

  `:flag-fulfillment-concern` is absent from every phase's `:auto` set.

  ## `:halt-task` is auto-eligible at EVERY phase above 0

  Deliberately, and it is the only op in this stack with that property.
  Halting is the safe direction: a stop that turns out to have been
  unnecessary costs a restart, while a stop delayed for approval costs
  whatever the robot does in the meantime. Nothing that makes a robot
  safer should have to wait for a human to read a queue.

  ## `:dispatch-actions` is auto-eligible but rarely auto-commits

  It sits in the phase-3 `:auto` set, yet in practice most dispatches
  still stop for a person — because the GOVERNOR marks any dispatch
  containing a sign-off-class action high-stakes, and `:handover` is
  `:high` by construction. So `:pick` and `:pack` flow unattended while
  every `:handover` reaches a supervisor.

  That split is the point. Putting the gate in the governor rather than
  the phase table means it keys off what the robot is actually about to
  do — the safety class of the specific action — instead of a
  coarse-grained op name that would either block all warehouse
  automation or none of it. `fulfillops.governor`'s
  `always-escalate-ops` covers the concern op independently."
  (:require [fulfillops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-fulfillment-concern` is a member of
;; `write-ops` but is NEVER a member of any phase's `:auto` set below.
;; Do not add it there. `:dispatch-actions` IS auto-eligible here; its
;; real gate is the governor's per-action safety check.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"          :writes #{}              :auto #{}}
   1 {:label "assisted-planning"  :writes #{:plan-tasks :halt-task}
      :auto #{:halt-task}}
   2 {:label "assisted-recording" :writes #{:plan-tasks :record-pick
                                            :complete-task :halt-task}
      :auto #{:halt-task}}
   3 {:label "supervised-auto"    :writes write-ops
      :auto #{:plan-tasks :record-pick :complete-task :halt-task :dispatch-actions}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a FulfillmentGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
