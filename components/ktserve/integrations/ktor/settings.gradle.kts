rootProject.name = "ktserve-ktor"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
