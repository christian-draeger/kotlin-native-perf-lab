#!/usr/bin/env python3
"""Parse wrk output to append one conservative, per-run CSV record."""
import argparse
import csv
import pathlib
import re

p=argparse.ArgumentParser()
for key in ('log','csv','variant','endpoint','run','connections','threads','duration','warmup','cpu_start','cpu_end','peak_rss_kib','git_sha'):
    p.add_argument('--'+key.replace('_','-'),required=True)
a=p.parse_args()
log=pathlib.Path(a.log).read_text()
def match(pattern):
    m=re.search(pattern,log,re.MULTILINE)
    if m is None: raise ValueError('Required wrk field not found: '+pattern+'\n'+log)
    return m.group(1)
def micro(value):
    m=re.fullmatch(r'([\d.]+)(us|ms|s)',value)
    if not m: raise ValueError('Invalid duration: '+value)
    return round(float(m.group(1))*{'us':1,'ms':1000,'s':1_000_000}[m.group(2)],2)
rps=float(match(r'^\s*Requests/sec:\s*([\d.]+)'))
requests=int(match(r'^\s*(\d+) requests in '))
lat99=micro(match(r'^\s*99%\s+([\d.]+(?:us|ms|s))\s*$'))
# wrk's default --latency table gives 50%, 75%, 90%, 99%.
lat50=micro(match(r'^\s*50%\s+([\d.]+(?:us|ms|s))\s*$'))
lat90=micro(match(r'^\s*90%\s+([\d.]+(?:us|ms|s))\s*$'))
cpu=max(0.0,float(a.cpu_end)-float(a.cpu_start))
row=dict(variant=a.variant,endpoint=a.endpoint,run=int(a.run),requests=requests,rps=rps,
         p50_us=lat50,p90_us=lat90,p99_us=lat99,cpu_seconds=round(cpu,4),
         cpu_seconds_per_million_requests=round(cpu/requests*1_000_000,3) if requests else '',
         peak_rss_kib=int(a.peak_rss_kib),threads=a.threads,connections=a.connections,
         duration_s=a.duration,warmup_s=a.warmup,git_sha=a.git_sha)
path=pathlib.Path(a.csv)
write_header=not path.exists()
with path.open('a',newline='') as f:
    w=csv.DictWriter(f,fieldnames=row.keys())
    if write_header: w.writeheader()
    w.writerow(row)
print(f"{a.variant:12s} {a.endpoint:12s} run={a.run} rps={rps:.1f} p99={lat99:.1f}us cpu={cpu:.2f}s rss={a.peak_rss_kib}KiB")
