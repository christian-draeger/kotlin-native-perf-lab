rootProject.name = "ktserve-webflux"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:ktserve")).using(project(":"))
    }
}
