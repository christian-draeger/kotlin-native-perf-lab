rootProject.name = "ktserve-webflux"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
