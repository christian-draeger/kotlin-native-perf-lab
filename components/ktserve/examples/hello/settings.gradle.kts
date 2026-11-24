rootProject.name = "ktserve-hello"

if (!providers.gradleProperty("ktserve.version").isPresent) {
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("io.github.christian-draeger:ktserve")).using(project(":"))
        }
    }
}
