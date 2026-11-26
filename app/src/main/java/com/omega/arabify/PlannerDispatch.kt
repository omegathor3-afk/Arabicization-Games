package com.omega.arabify

import java.util.concurrent.CancellationException

/**
 * Phase B dispatch helper: runs the capability planner over the shared
 * registry and executes EXTRACT on the usable adapters in plan order,
 * falling back to the next adapter when one throws. Pure JVM logic with no
 * Android or Compose dependencies so the whole dispatch policy is unit
 * testable; MainActivity only supplies the registry and consumes the result.
 *
 * Ordering is the planner's: confidence desc, then descriptor priority desc,
 * then id. On the real registry that yields slot -> gettext -> in-place ->
 * resources (probe confidences 90/90/60/40, the slot/gettext tie broken by
 * priority 300/200), which matches the actual MainActivity chain: resources rewriting is NOT a chain step there; it lives
 * in the installed-app and second file-pick paths.
 */
internal data class DispatchResult(
    val plan: LocalizationPlan,
    val adapterId: String,
    val report: AdapterReport,
    val attempted: List<String>,
)

internal class LocalizationDispatchException(
    val plan: LocalizationPlan,
    val attempted: List<String>,
    val failures: List<String>,
) : Exception(buildString {
    append("No usable adapter completed extraction.")
    if (attempted.isNotEmpty()) append(" Attempted: ").append(attempted.joinToString())
    if (failures.isNotEmpty()) append(" Failures: ").append(failures.joinToString(" | "))
})

internal class PlannerDispatch(private val registry: LocalizationAdapterRegistry) {

    private val planner = LocalizationCapabilityPlanner(registry)

    suspend fun plan(input: ArtifactRef, budget: ResourceBudget): LocalizationPlan =
        planner.plan(input, setOf(AdapterOperation.EXTRACT), budget)

    /**
     * Try each planned adapter that declares EXTRACT, in plan order. A
     * throwing adapter is recorded and the next one runs (today's
     * runCatching/getOrNull chain semantics); a CancellationException is
     * rethrown, never treated as an adapter failure.
     */
    suspend fun dispatch(input: ArtifactRef, budget: ResourceBudget): DispatchResult {
        val plan = plan(input, budget)
        val attempted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (descriptor in plan.selectedAdapters) {
            if (AdapterOperation.EXTRACT !in descriptor.operations) continue
            val adapter = registry.find(descriptor.id) ?: continue
            attempted += descriptor.id
            try {
                val report = adapter.extract(input, budget)
                return DispatchResult(plan, descriptor.id, report, attempted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += "${descriptor.id}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw LocalizationDispatchException(plan, attempted, failures)
    }
}
