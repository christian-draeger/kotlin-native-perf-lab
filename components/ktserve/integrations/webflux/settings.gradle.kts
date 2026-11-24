rootProject.name = "ktserve-webflux"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.christian-draeger:ktserve")).using(project(":"))
    }
}
