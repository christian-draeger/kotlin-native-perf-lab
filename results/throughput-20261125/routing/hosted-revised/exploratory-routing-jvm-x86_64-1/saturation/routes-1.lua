-- This callback runs after the load, with no per-response Lua processing.
function done(summary, latency, requests)
    local errors = summary.errors
    io.write(string.format(
        "perflab requests=%d duration_us=%d p50_us=%g p95_us=%g p99_us=%g " ..
        "connect=%d read=%d write=%d timeout=%d http_errors=%d\n",
        summary.requests, summary.duration,
        latency:percentile(50), latency:percentile(95), latency:percentile(99),
        errors.connect, errors.read, errors.write, errors.timeout, errors.status
    ))
end

local i = 0
function request()
  i = (i + 1) % 4096
  local resource = (i * 137 + 29) % 1
  local id = i % 8 == 0 and "current" or (i + 1)
  return wrk.format("GET", "/api/v1/r" .. resource .. "/items/" .. id)
end
