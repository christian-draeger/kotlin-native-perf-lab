#!/usr/bin/env python3
"""Print process CPU seconds, peak RSS KiB and current RSS KiB on Linux."""
import os
import pathlib
import sys

pid = int(sys.argv[1])
proc = pathlib.Path('/proc') / str(pid)
stat = (proc / 'stat').read_text()
# The process name is in parentheses and may itself contain spaces.
fields = stat.rsplit(') ', 1)[1].split()
utime, stime = int(fields[11]), int(fields[12])  # proc stat fields 14/15
cpu = (utime + stime) / os.sysconf('SC_CLK_TCK')
status = (proc / 'status').read_text()
rss = {line.split()[0]: int(line.split()[1]) for line in status.splitlines()
       if line.startswith(('VmHWM:', 'VmRSS:'))}
print(f"{cpu:.6f} {rss['VmHWM:']} {rss['VmRSS:']}")
