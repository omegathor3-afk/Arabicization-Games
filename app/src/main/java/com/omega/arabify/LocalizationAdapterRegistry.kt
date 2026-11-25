package com.omega.arabify

internal interface LocalizationAdapter {
    val descriptor: AdapterDescriptor
    suspend fun probe(input: ArtifactRef, budget: ResourceBudget): AdapterProbe
    suspend fun inspect(input: ArtifactRef, budget: ResourceBudget): AdapterReport =
        throw UnsupportedOperationException("${descriptor.id}: INSPECT")
    suspend fun extract(input: ArtifactRef, budget: ResourceBudget): AdapterReport =
        throw UnsupportedOperationException("${descriptor.id}: EXTRACT")
    suspend fun rewrite(input: ArtifactRef, overlays: List<TranslationOverlay>, budget: ResourceBudget): AdapterReport =
        throw UnsupportedOperationException("${descriptor.id}: REWRITE")
    suspend fun verify(original: ArtifactRef, rewritten: ArtifactRef, budget: ResourceBudget): AdapterReport =
        throw UnsupportedOperationException("${descriptor.id}: VERIFY")
}

internal class LocalizationAdapterRegistry(adapters: Collection<LocalizationAdapter>) {
    private val adaptersById: Map<String, LocalizationAdapter>
    init {
        val duplicates = adapters.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate adapter IDs: ${duplicates.joinToString()}" }
        adaptersById = adapters.associateBy { it.descriptor.id }
    }
    fun all() = adaptersById.values.sortedWith(
        compareByDescending<LocalizationAdapter> { it.descriptor.priority }.thenBy { it.descriptor.id }
    )
    fun find(id: String) = adaptersById[id]
    fun forInput(kind: ArtifactKind) = all().filter { kind in it.descriptor.inputKinds }
}

internal class LocalizationCapabilityPlanner(private val registry: LocalizationAdapterRegistry) {
    suspend fun plan(artifact: ArtifactRef, requested: Set<AdapterOperation>, budget: ResourceBudget): LocalizationPlan {
        require(requested.isNotEmpty())
        artifact.sizeBytes?.let(budget::requireInputWithinLimit)
        val probes = registry.forInput(artifact.kind).map { adapter ->
            val probe = adapter.probe(artifact, budget)
            require(probe.adapterId == adapter.descriptor.id)
            adapter to probe
        }
        val matched = probes.filter { it.second.matched }
        val blocked = matched.filter { it.second.blockedReason != null }
        val usable = matched.filter { it.second.blockedReason == null }.sortedWith(
            compareByDescending<Pair<LocalizationAdapter, AdapterProbe>> { it.second.confidence }
                .thenByDescending { it.first.descriptor.priority }.thenBy { it.first.descriptor.id }
        )
        val covered = usable.flatMap { it.first.descriptor.operations }.toSet().intersect(requested)
        val missing = requested - covered
        val status = when {
            usable.isEmpty() && blocked.isNotEmpty() -> CapabilityStatus.BLOCKED
            usable.isEmpty() -> CapabilityStatus.UNSUPPORTED
            missing.isEmpty() -> CapabilityStatus.SUPPORTED
            AdapterOperation.INSPECT in covered && AdapterOperation.EXTRACT !in covered &&
                AdapterOperation.REWRITE !in covered -> CapabilityStatus.INSPECT_ONLY
            else -> CapabilityStatus.PARTIAL
        }
        val notes = buildList {
            if (usable.isEmpty() && blocked.isEmpty()) add("No adapter matched.")
            blocked.forEach { (a, p) -> add("${a.descriptor.id} blocked: ${p.blockedReason}") }
            usable.forEach { (a, p) -> add("${a.descriptor.id}: ${p.confidence}%"); addAll(p.evidence) }
            if (missing.isNotEmpty()) add("Missing operations: ${missing.joinToString()}")
        }
        return LocalizationPlan(artifact, status, usable.map { it.first.descriptor }, requested, covered, missing, notes)
    }
}
