rootProject.name = "ktserve-hello"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:ktserve")).using(project(":"))
    }
}
