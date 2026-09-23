# SmartCab — Corporate Cab Pooling & Smart Routing System

SmartCab is an enterprise employee cab pooling and routing engine designed for corporate transit management.

---

## Night Safety Rule & Operational Interpretation

### 1. Case Study Requirement
> *"A woman employee must not be the first pickup or the last drop alone during night hours."*

### 2. Operational Interpretation of "Alone"
Because real-world corporate transit systems operate on two distinct shift journeys, "alone" is formally interpreted as follows:

* **Inbound Pickup Journeys (Home &rarr; Office):**
  * The vehicle starts empty from the driver/depot.
  * The first passenger boarded (`stop.sequence == 1`) travels unaccompanied by any colleagues with only the driver until the second passenger is boarded (or until reaching the office in single-occupant trips).
  * **Rule Enforced:** If the first boarded passenger is a woman employee (`Gender.FEMALE`) and travel takes place during configured night hours (default `20:00` to `06:00`), she is considered **alone with the driver** on leg 1. This violates the night safety rule unless a designated security escort guard is on board from the start.
  * Subsequent passengers board an already-occupied vehicle and disembark together at the office, so they are never alone.

* **Outbound Drop-off Journeys (Office &rarr; Home):**
  * The vehicle starts with all passengers at the office and drops them off sequentially.
  * The final passenger left in the vehicle after preceding drops travels alone with the driver on the final leg.
  * **Rule Enforced:** A female employee must not be the last drop alone during night hours unless accompanied by an escort guard.

### 3. Multi-Tier Resolution Strategy
When a proposed route encounters a safety violation, the system handles it with zero silent violations:

1. **Phase 1: Exhaustive Permutation Reordering (No Cost):**
   * The `ExactPickupRouteOptimizer` evaluates all \(k!\) candidate pickup sequences.
   * If a mixed group exists (e.g., Male + Female), the optimizer tests alternative sequences where a male colleague is boarded first (`[Male, Female] &rarr; Office`).
   * If valid under all ride time, office arrival, and capacity constraints, this reordered safe route is chosen without requiring any security personnel.
2. **Phase 2: Security Escort Guard Allocation:**
   * If no unescorted permutation satisfies safety (e.g., all passengers in the cab are women), the optimizer attempts to assign an escort guard (`hasEscortGuard = true`).
   * **Seat Accounting:** The security guard consumes 1 passenger seat in the vehicle, strictly validated by `CapacityConstraint` (`passengers + 1 <= cabCapacity`).
   * If capacity permits (e.g., 3 female passengers in a 4-seater cab), the route is scheduled safely with `requiresEscortGuard = true`.
3. **Phase 3: Reject Impossible Routes (Safety First):**
   * If a cab has 4 female passengers in a 4-seater cab, no seat is available for an escort guard ($4 + 1 = 5 > 4$).
   * Rather than silently violating safety, the optimizer rejects the route candidate (`Optional.empty()`), flagging the need for an operational split or larger vehicle.

### 4. Configurable Night Hours Window
* Default: `20:00` (8:00 PM) to `06:00` (6:00 AM) next day.
* Custom windows supported via `NightSafetyConstraint(LocalTime start, LocalTime end)`.
