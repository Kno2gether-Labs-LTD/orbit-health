package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.AppGraph

/** Release: no developer tools, so Me shows no Developer section. */
object BuildDeveloperTools {
    @Suppress("UNUSED_PARAMETER")
    fun create(graph: AppGraph): DeveloperTools? = null
}
