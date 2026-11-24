rootProject.name = "ktserve-ktor"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.christian-draeger:ktserve")).using(project(":"))
    }
}
