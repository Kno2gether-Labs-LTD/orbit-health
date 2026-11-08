package dev.avijit.orbithealth.devtools

import dev.avijit.orbithealth.AppGraph
import dev.avijit.orbithealth.ui.me.DeveloperTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The same actions as the DEBUG_USE_BRIDGE / DEBUG_SEED / DEBUG_RESET broadcasts, from Me's Developer section. */
class AppDeveloperTools(private val graph: AppGraph) : DeveloperTools {
    override val useMacBridge: Flow<Boolean> = graph.settings.values.map { it.useMacBridge }.distinctUntilChanged()

    override val bridgeCode: Flow<String?> = graph.bridgeCode

    override suspend fun setUseMacBridge(on: Boolean) {
        withContext(Dispatchers.IO) { graph.setUseMacBridge(on) }
    }

    override suspend fun seedSampleData(): String = withContext(Dispatchers.IO) {
        val n = SampleDataSeeder.seed(graph)
        "Added ${"%,d".format(n)} sample readings"
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            graph.syncEngine.disconnect()
            graph.database.clearAllTables()
            graph.settings.clearAll()
        }
    }
}
