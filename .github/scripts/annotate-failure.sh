#!/usr/bin/env bash
# Turns the useful part of a build log into GitHub error annotations,
# so failures are readable from the Actions summary (and from the API) without opening raw logs.
# Usage: annotate-failure.sh "<title>" <logfile>
title="$1"
log="$2"
[ -f "$log" ] || { echo "::error title=$title::log file $log not found"; exit 0; }

encode() { sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk 'BEGIN{ORS="%0A"} {print}'; }

# 1) Gradle's own summary ("What went wrong")
summary=$(awk '/^\* What went wrong:/{f=1} /^\* Try:/{f=0} f' "$log" | head -n 40)
[ -n "$summary" ] && echo "::error title=$title (summary)::$(printf '%s\n' "$summary" | encode)"

# 2) compiler / linker / CMake errors with a bit of context
errors=$(grep -n -E "error:|error: |FAILED:|CMake Error|ld: error|e: |Execution failed for task|Could not resolve|Could not find" "$log" | head -n 40)
[ -n "$errors" ] && echo "::error title=$title (errors)::$(printf '%s\n' "$errors" | cut -c1-400 | encode)"

# 3) the tail, in case neither of the above matched
echo "::error title=$title (last lines)::$(tail -n 40 "$log" | cut -c1-400 | encode)"
