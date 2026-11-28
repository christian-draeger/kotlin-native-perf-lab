package codes.draeger.examples.extensions

fun main(args: Array<String>) {
    val benchmark = Benchmark.parse(args)
    if (benchmark == null) runServer(args) else benchmark.run()
}
