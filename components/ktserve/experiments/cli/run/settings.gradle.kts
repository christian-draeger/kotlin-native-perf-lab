rootProject.name = "ktserve-cli-app"

includeBuild("ktserve") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}

if (file("bundled-extensions.txt").isFile) {
    includeBuild("ktserve/extensions") {
        dependencySubstitution {
            substitute(module("codes.draeger:ktserve-openapi")).using(project(":ktserve-openapi"))
            substitute(module("codes.draeger:ktserve-validation")).using(project(":ktserve-validation"))
        }
    }
}
