rootProject.name = "ktserve-hello-jvm"

if (!providers.gradleProperty("ktserve.version").isPresent) {
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("dev.perflab:ktserve")).using(project(":"))
        }
    }
}
