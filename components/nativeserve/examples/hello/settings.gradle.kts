rootProject.name = "nativeserve-hello"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:nativeserve")).using(project(":"))
    }
}
