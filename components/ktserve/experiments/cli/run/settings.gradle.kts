rootProject.name = "ktserve-cli-app"

includeBuild("ktserve") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
