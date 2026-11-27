rootProject.name = "ktserve-extensions"

include("ktserve-cors", "ktserve-bearer", "ktserve-openapi", "ktserve-opentelemetry", "ktserve-access-log", "ktserve-validation")
project(":ktserve-cors").projectDir = file("cors")
project(":ktserve-bearer").projectDir = file("bearer")
project(":ktserve-openapi").projectDir = file("openapi")
project(":ktserve-opentelemetry").projectDir = file("opentelemetry")
project(":ktserve-access-log").projectDir = file("access-log")
project(":ktserve-validation").projectDir = file("validation")

includeBuild("..") {
    dependencySubstitution {
        substitute(module("codes.draeger:ktserve")).using(project(":"))
    }
}
