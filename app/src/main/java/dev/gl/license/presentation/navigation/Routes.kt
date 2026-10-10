package dev.gl.license.presentation.navigation

object Route {
    const val Generator = "generator"
    const val Contact = "contact"
    const val Registry = "registry"
    const val Trust = "trust"
    const val Detail = "registry/{id}"
    fun detail(id: String) = "registry/$id"
}
