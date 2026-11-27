rootProject.name = "ktserve-static-cli"

includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}

includeBuild("../../../extensions") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve-access-log")).using(project(":ktserve-access-log"))
    }
}
