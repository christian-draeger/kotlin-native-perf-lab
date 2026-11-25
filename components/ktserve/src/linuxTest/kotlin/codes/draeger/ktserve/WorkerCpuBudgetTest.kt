package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerCpuBudgetTest {
    private val status = "/proc/thread-self/status"
    private val cgroup = "/proc/thread-self/cgroup"
    private val parentQuota = "/sys/fs/cgroup/parent/cpu.max"
    private val leafQuota = "/sys/fs/cgroup/parent/leaf/cpu.max"
    private val files = mapOf(
        status to "Cpus_allowed:\t00000001,00000001\n",
        cgroup to "0::/parent/leaf\n",
        "/proc/self/mountinfo" to "1 2 0:3 / /sys/fs/cgroup ro - cgroup2 cgroup rw\n",
        "/sys/fs/cgroup/parent/leaf/cgroup.procs" to "123\n",
        leafQuota to "max 100000\n",
    )

    @Test
    fun requiresTwoCpusInBothAffinityAndQuota() {
        assertTrue(evaluate(parentQuota to "200000 100000\n"))
        assertFalse(evaluate(parentQuota to "199999 100000\n"))
        assertFalse(evaluate(leafQuota to "100000 100000\n"))
        assertFalse(evaluate(status to "Cpus_allowed:\t00000000,00000001\n"))
    }

    @Test
    fun requiresReadableSupportedMetadata() {
        assertFalse(evaluate(leafQuota to null))
        assertFalse(evaluate(leafQuota to "max 0\n"))
        assertFalse(evaluate(cgroup to "0::/parent/leaf\n2:cpu,cpuacct:/\n"))
        assertFalse(evaluate("/proc/self/mountinfo" to "1 2 0:3 /subtree /sys/fs/cgroup ro - cgroup2 cgroup rw\n"))
    }

    private fun evaluate(vararg overrides: Pair<String, String?>): Boolean {
        val configured = files + overrides
        return permitsTwoEpollWorkers { path, missing ->
            if (configured.containsKey(path)) configured[path] else missing
        }
    }
}
