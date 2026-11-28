rootProject.name = "ktserve-otel-native-probe"
includeBuild("../..") {
    dependencySubstitution { substitute(module("codes.draeger:ktserve")).using(project(":")) }
}
