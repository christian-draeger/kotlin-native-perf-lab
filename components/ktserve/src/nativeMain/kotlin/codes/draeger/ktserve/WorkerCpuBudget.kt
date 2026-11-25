@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fgets
import platform.posix.fopen

private const val CGROUP_MOUNT = "/sys/fs/cgroup"

internal fun canRunTwoEpollWorkers(): Boolean = permitsTwoEpollWorkers(::readCpuBudgetFile)

internal fun permitsTwoEpollWorkers(read: (path: String, missing: String?) -> String?): Boolean {
    val status = read("/proc/thread-self/status", null) ?: return false
    val mask = status.lineSequence().firstOrNull { it.startsWith("Cpus_allowed:") }
        ?.substringAfter(':')?.trim() ?: return false
    var cpuCount = 0
    for (digit in mask) {
        if (digit != ',') cpuCount += (digit.digitToIntOrNull(16) ?: return false).countOneBits()
    }
    if (cpuCount < 2) return false

    val cgroups = read("/proc/thread-self/cgroup", null)?.trim()?.lines() ?: return false
    // The pilot can only check quotas on cgroup v2.
    if (cgroups.any { "cpu" in it.substringAfter(':').substringBefore(':').split(',') }) return false
    val group = cgroups.singleOrNull { it.startsWith("0::/") }?.removePrefix("0::") ?: return false
    if (group != "/" && group.drop(1).split('/').any { it.isEmpty() || it == "." || it == ".." }) return false

    val mounts = read("/proc/self/mountinfo", null) ?: return false
    val cgroupMounted = mounts.lineSequence().any { line ->
        val fields = line.substringBefore(" - ").split(' ', limit = 6)
        fields.getOrNull(3) == "/" && fields.getOrNull(4) == CGROUP_MOUNT &&
            line.substringAfter(" - ").startsWith("cgroup2 ")
    }
    if (!cgroupMounted) return false

    var directory = if (group == "/") CGROUP_MOUNT else CGROUP_MOUNT + group
    if (read("$directory/cgroup.procs", null) == null) return false
    while (true) {
        // A level without the CPU controller has no quota of its own.
        val limit = read("$directory/cpu.max", "max 1")?.trim()?.split(' ') ?: return false
        if (limit.size != 2) return false
        val period = limit[1].toLongOrNull()?.takeIf { it > 0 } ?: return false
        val budget = limit[0]
        if (budget != "max" && (budget.toLongOrNull() ?: return false) / period < 2) return false
        if (directory == CGROUP_MOUNT) return true
        directory = directory.substringBeforeLast('/')
    }
}

private fun readCpuBudgetFile(path: String, missing: String?): String? {
    val file = fopen(path, "r") ?: return if (errno == ENOENT) missing else null
    try {
        return memScoped {
            val bufferSize = 4096
            val buffer = allocArray<ByteVar>(bufferSize)
            val content = StringBuilder()
            while (fgets(buffer, bufferSize, file) != null) {
                content.append(buffer.toKString())
                if (content.length > 1_048_576) return@memScoped null
            }
            if (ferror(file) == 0) content.toString() else null
        }
    } finally {
        fclose(file)
    }
}
