rootProject.name = "ktserve-static-cli"

includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
