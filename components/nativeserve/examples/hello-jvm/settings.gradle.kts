rootProject.name = "nativeserve-hello-jvm"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.perflab:nativeserve")).using(project(":"))
    }
}
