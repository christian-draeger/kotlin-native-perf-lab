rootProject.name = "ktserve-inference-example"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
