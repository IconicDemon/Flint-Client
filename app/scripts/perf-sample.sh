#!/bin/zsh
# Launches an installed Flint Client and samples CPU and memory of the app plus
# the WebKit helper processes it spawns (identified as helpers that did not exist before launch).
# Usage: scripts/perf-sample.sh "<path to .app>" <seconds> [interval]
# Output columns: elapsed_s  app_cpu%  web_cpu%  gpu_cpu%  app_MB  web_MB  gpu_MB
app=${1:?app path}
duration=${2:-20}
interval=${3:-1}
before=$(pgrep -f "com.apple.WebKit" | sort)
t0=$(date +%s.%N)
open "$app"
for i in {1..100}; do pgrep -x flint-app >/dev/null && break; sleep 0.05; done
app_pid=$(pgrep -x flint-app | head -1)
printf "process up after %.2fs (pid %s)\n" $(( $(date +%s.%N) - t0 )) "$app_pid"
while true; do
  elapsed=$(( $(date +%s.%N) - t0 ))
  (( elapsed > duration )) && break
  helpers=$(comm -13 <(echo "$before") <(pgrep -f "com.apple.WebKit" | sort) | tr '\n' ',' | sed 's/,$//')
  ps -o pid=,%cpu=,rss=,comm= -p "${app_pid}${helpers:+,$helpers}" | awk -v ap="$app_pid" -v t="$elapsed" '
    $1==ap { a=$2; ar=$3; next }
    /GPU/ { g+=$2; gr+=$3; next }
    { w+=$2; wr+=$3 }
    END { printf "%5.1f  %5.1f %5.1f %5.1f   %6.1f %6.1f %6.1f\n", t, a, w, g, ar/1024, wr/1024, gr/1024 }'
  sleep "$interval"
done
