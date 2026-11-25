rootProject.name = "ktserve-rest-jvm"

if (!providers.gradleProperty("ktserve.version").isPresent) {
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("codes.draeger:ktserve")).using(project(":"))
        }
    }
}
