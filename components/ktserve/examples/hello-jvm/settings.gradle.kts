rootProject.name = "ktserve-hello-jvm"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:ktserve")).using(project(":"))
    }
}
