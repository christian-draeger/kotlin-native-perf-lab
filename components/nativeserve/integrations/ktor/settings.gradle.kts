rootProject.name = "nativeserve-ktor"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:nativeserve")).using(project(":"))
    }
}
