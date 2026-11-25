from collections import Counter
from pathlib import Path
import json
import sys

def frames(event):
    stack = event.get("stackTrace") or {}
    return [
        ((frame.get("method") or {}).get("type", {}).get("name", ""),
         (frame.get("method") or {}).get("name", ""))
        for frame in stack.get("frames", [])
    ]

summaries = {}
for file in sys.argv[1:]:
    path = Path(file)
    events=json.loads(path.read_text())["recording"]["events"]
    types=Counter()
    allocation=Counter()
    route_cpu=Counter()
    for event in events:
        kind = event["type"]
        types[kind] += 1
        details = event["values"]
        trace = frames(details)
        if kind == "jdk.ExecutionSample":
            for name, method in list(dict.fromkeys(trace)):
                if "ktserve" in name and ("Route" in name or "HttpConnection" in name):
                    route_cpu[f"{name}.{method}"] += 1
        else:
            class_name=(details.get("objectClass") or {}).get("name", "")
            if any("ktserve" in name for name,_ in trace):
                allocation[class_name] += int(details.get("weight",0))
    summaries[path.stem] = {
        "samples_by_event": dict(types),
        "top_weighted_sampled_allocation_classes_with_ktserve_frames": dict(allocation.most_common(12)),
        "sampled_dispatch_frames": dict(route_cpu.most_common(12)),
    }
print(json.dumps(summaries, indent=2))
