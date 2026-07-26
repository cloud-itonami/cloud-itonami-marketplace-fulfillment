# cloud-itonami-marketplace-fulfillment

Open Business Blueprint (implemented actor): **the warehouse — and the
first place on the commerce side of this fleet where `kotoba.robotics`
actually governs something.**

**FulfillmentAdvisor ⊣ FulfillmentGovernor** on
[`langgraph`](https://github.com/kotoba-lang/langgraph). Tasks and the
robotics composition come from `marketplace.fulfillment` in
[`kotoba-lang/marketplace`](https://github.com/kotoba-lang/marketplace).
Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn).

## The robotics gap this closes

679 blueprints in this fleet declare `:robotics true`. Only **23 repos
actually require** [`kotoba.robotics`](https://github.com/kotoba-lang/robotics),
and every one is heavy industry — mining, steel, vehicle assembly, road
works. No commerce-side actor wired it at all: ISIC 4791's own "warehouse
fulfillment scheduling" contains zero robotics references.

This actor is the first. It does not make the library do anything new —
it is still *policy, not control*, and drives no motors. It puts the
existing gate where warehouse work actually happens.

## Certification is per machine, not per operator

The store holds a robot registry, and the permitted safety classes come
from **the assigned robot's own certification** rather than an
operator-wide constant:

| Robot | Certified for | `:pick` (`:medium`) | `:handover` (`:high`) |
|---|---|---|---|
| `amr-01` | `:low` | **denied** | denied |
| `amr-07` | `:low :medium` | auto | denied |
| `amr-99` | `:low :medium :high` | auto | **human sign-off** |

An unregistered machine is certified for **nothing** — no benefit of the
doubt on a warehouse floor.

Note `amr-99` and `:handover`: certification permits the *class*; it does
not waive the *sign-off*. `kotoba.robotics/human-sign-off-classes` puts
`:high` behind a person, `marketplace.fulfillment/gate-actions` keeps
`:needs-sign-off` strictly separate from `:permitted`, and a handover —
a robot moving a parcel into space a human courier occupies — is `:high`
by construction. So **every handover reaches a supervisor.**

## The failure a perfect robot still causes

The characteristic warehouse failure is a **quantity** question, not a
robotics one: picking an SKU the buyer never ordered, or more of one
than they ordered. A mechanically flawless robot with a clean barcode
read still ships the wrong box if nothing checks against the order.

So picks are checked against the order — and **aggregated per SKU across
every pass**. Two passes of 2 and 1 against an order for 2 both look
fine individually while three units leave the shelf, and a second pass
is the normal way a short pick gets topped up, so that is not
hypothetical. (This was a real bug in the library, found by this actor's
tests and fixed there.)

A **short** pick is the opposite: not an error, just an ordinary stock
discrepancy — but shipping fewer units than the buyer paid for,
unremarked, is not, so it escalates to a human.

## Six HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Unknown order/seller/robot** | a task that fulfils nothing real, or is assigned to an unregistered machine |
| **Over-pick** | an SKU not in the order, or more than ordered, counting every pass |
| **Denied robot action** | any action outside the assigned robot's certification — the **whole** dispatch is refused, because a partially-executed physical task is worse than an unstarted one |
| **Re-dispatch** | handing a driver an action already dispatched; after a crash, re-issuing a `:grasp` double-actuates |
| **Effect not `:propose`** | a proposal claiming to directly actuate |
| **Scope exclusion** | any claim that a robot *already* picked, moved or shipped; any op outside the allowlist |

There is no op in the allowlist that actuates hardware.
`:dispatch-actions` **releases** gated actions to an operator's driver;
the driver is outside this repo.

## `:halt-task` never waits

It is auto-eligible at every phase above 0 — the only op in this stack
with that property. Halting is the safe direction: a stop that turns out
unnecessary costs a restart, while a stop delayed for approval costs
whatever the robot does in the meantime. Nothing that makes a robot
safer should wait for someone to read a queue.

A halt is **terminal**. Recovering means planning a new task, not
resuming one a robot was stopped mid-way through — the physical state
after a stop is unknown.

```bash
clojure -M:dev:run   # certification denial, clean pick, over-pick, short pick, human-gated handover, e-stop
clojure -M:test      # 22 tests, 64 assertions
clojure -M:lint
```

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-planning | `:plan-tasks` `:halt-task` | `:halt-task` |
| 2 assisted-recording | + `:record-pick` `:complete-task` | `:halt-task` |
| 3 supervised-auto | all | all except `:flag-fulfillment-concern` |

`:dispatch-actions` is in the phase-3 auto set, yet most dispatches still
stop for a person — the **governor** marks any dispatch containing a
sign-off-class action high-stakes. Keying the gate off the specific
action's safety class rather than a coarse op name is what lets `:pick`
and `:pack` flow unattended while every `:handover` reaches a human.
