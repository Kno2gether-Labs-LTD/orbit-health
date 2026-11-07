package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.AppGraph
import dev.avijit.orbithealth.debug.DebugDeveloperTools

/** Debug: the Mac bridge switch and sample-data seeding/reset on Me. */
object BuildDeveloperTools {
    fun create(graph: AppGraph): DeveloperTools? = DebugDeveloperTools(graph)
}
