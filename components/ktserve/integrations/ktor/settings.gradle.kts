rootProject.name = "ktserve-ktor"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:ktserve")).using(project(":"))
    }
}
