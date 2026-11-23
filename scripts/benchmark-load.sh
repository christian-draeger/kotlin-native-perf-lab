# Run stock wrk locally or via SSH. Source from the Linux measurement scripts.
load_scratch=''
remote_load_dir=''
load_script="$ROOT/scripts/wrk-stats.lua"

load_ssh() {
  local command
  printf -v command '%q ' "$@"
  ssh "${load_ssh_options[@]}" -- "$LOAD_SSH" "$command"
}

prepare_load() {
  if [[ -z "${LOAD_SSH:-}" ]]; then
    command -v wrk >/dev/null || { echo 'Missing wrk.' >&2; return 1; }
    return
  fi

  load_scratch="$(mktemp -d /tmp/perflab-ssh.XXXXXXXX)"
  load_ssh_options=(
    -T -o BatchMode=yes -o ConnectTimeout=10 -o ControlMaster=auto
    -o ControlPersist=10 -o "ControlPath=$load_scratch/control"
  )
  local local_machine remote_machine
  local_machine="$(cat /etc/machine-id)"
  remote_machine="$(load_ssh cat /etc/machine-id)"
  if [[ -z "$remote_machine" || "$remote_machine" == "$local_machine" ]]; then
    echo 'Use an independent Linux load machine with its own machine ID.' >&2
    return 1
  fi

  remote_load_dir="$(load_ssh mktemp -d /tmp/perflab-load.XXXXXXXX)"
  [[ "$remote_load_dir" =~ ^/tmp/perflab-load\.[a-zA-Z0-9]+$ ]] || return 1
  load_ssh tee "$remote_load_dir/wrk-stats.lua" < "$load_script" >/dev/null
  load_script="$remote_load_dir/wrk-stats.lua"
}

load_environment() {
  if [[ -z "${LOAD_SSH:-}" ]]; then
    wrk --version 2>&1 | sed -n '1p' || true
    if [[ "$BIND_HOST" == 127.0.0.1 ]]; then
      echo 'load_generator=local HTTP/1.1 loopback; same host and CPU pool as servers'
    else
      echo 'load_generator=local HTTP/1.1; same host and CPU pool as servers'
    fi
    return
  fi

  local before remote_time after
  before="$(date +%s%N)"
  remote_time="$(load_ssh date +%s%N)"
  after="$(date +%s%N)"
  [[ "$remote_time" =~ ^[0-9]{19}$ ]] || return 1
  echo 'load_generator=remote HTTP/1.1 via SSH orchestration'
  echo "load_clock_offset_lower_bound_ns=$(( remote_time - after ))"
  echo "load_clock_offset_upper_bound_ns=$(( remote_time - before ))"
  echo 'time_boundary=server /proc snapshots bracket the remote wrk command'
  if (( remote_time < before - 1000000000 || remote_time > after + 1000000000 )); then
    echo 'Synchronize the two machine clocks before starting the benchmark.' >&2
    return 1
  fi
  load_ssh sh -c 'command -v wrk >/dev/null && uname -srm && lscpu && taskset -pc $$ && (wrk --version 2>&1 | head -n 1)'
}

run_load() {
  if [[ -n "${LOAD_SSH:-}" ]]; then
    load_ssh wrk -s "$load_script" "$@" </dev/null
  else
    wrk -s "$load_script" "$@"
  fi
}

cleanup_load() {
  if [[ "$remote_load_dir" =~ ^/tmp/perflab-load\.[a-zA-Z0-9]+$ ]]; then
    load_ssh rm -f -- "$remote_load_dir/wrk-stats.lua" </dev/null || true
    load_ssh rmdir -- "$remote_load_dir" </dev/null || true
  fi
  if [[ -n "$load_scratch" ]]; then
    ssh "${load_ssh_options[@]}" -O exit -- "$LOAD_SSH" >/dev/null 2>&1 || true
    rmdir -- "$load_scratch" 2>/dev/null || true
  fi
}
