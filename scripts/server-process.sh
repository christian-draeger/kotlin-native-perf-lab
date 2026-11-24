# Shared lifecycle for validation and measurement scripts. Callers export PORT
# and can set BIND_HOST to their chosen server interface.
server_pid=''

start_server() {
  local variant="$1" log="$2" base="http://${BIND_HOST:-127.0.0.1}:$PORT" attempt
  local -a server=("$ROOT/scripts/run-server.sh" "$variant")
  if [[ -n "${SERVER_CPUS:-}" ]]; then
    server=(taskset -c "$SERVER_CPUS" "${server[@]}")
  fi
  if curl -s --max-time 1 -o /dev/null "$base/health"; then
    echo "Port $PORT already serves HTTP" >&2
    return 1
  fi
  "${server[@]}" > "$log" 2>&1 &
  server_pid=$!
  for ((attempt = 0; attempt < 100; attempt++)); do
    kill -0 "$server_pid" 2>/dev/null || break
    if curl -fsS --max-time 1 "$base/health" >/dev/null 2>&1; then
      if [[ "$variant" == ktserve-workers-2* ]]; then
        grep -qFx 'ktServe backend=epoll tcp=ipv4 workers=2' "$log" || {
          echo 'The candidate did not start with two epoll workers.' >&2
          cat "$log" >&2
          return 1
        }
      fi
      case "$variant" in
        ktserve-workers-2-reuseport) grep -qFx 'ktServe worker_listener=reuseport' "$log" || return 1 ;;
        ktserve-epoll) grep -qFx 'ktServe backend=epoll tcp=ipv4' "$log" || return 1 ;;
        ktserve-io-uring) grep -q '^ktServe backend=io_uring tcp=ipv4 features=0x' "$log" || return 1 ;;
      esac
      return 0
    fi
    sleep .2
  done
  echo "$variant did not become ready" >&2
  cat "$log" >&2
  return 1
}

stop_server() {
  local attempt
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    for ((attempt = 0; attempt < 20; attempt++)); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep .1
    done
    # Some runtimes do not exit on SIGTERM. Never stall the next trial.
    kill -KILL "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=''
  fi
}
