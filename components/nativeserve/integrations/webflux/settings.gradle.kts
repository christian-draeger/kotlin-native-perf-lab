rootProject.name = "nativeserve-webflux"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:nativeserve")).using(project(":"))
    }
}
