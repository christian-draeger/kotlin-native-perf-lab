rootProject.name = "ktserve-extension-consumer"

if (!providers.gradleProperty("ktserve.version").isPresent) {
    includeBuild("../../extensions")
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("codes.draeger:ktserve")).using(project(":"))
        }
    }
}
