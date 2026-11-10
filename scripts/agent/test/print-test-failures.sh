#!/usr/bin/env bash
# Print failed test cases from JUnit XML reports.
# Usage: scripts/agent/test/print-test-failures.sh TEST-*.xml...
# Prints "### Class > test FAILED" blocks and a final "Summary: N test failure(s)" line.
# Exit code: 0 if no failures, 1 otherwise.

set -uo pipefail

failure_count=0

for xml_file in "$@"; do
  if rg -q 'failures="0"' "$xml_file" && rg -q 'errors="0"' "$xml_file"; then
    continue
  fi

  awk '
    /<testcase / {
      classname = ""
      name = ""
      s = $0
      if (match(s, /classname="/)) {
        s = substr(s, RSTART + 11)
        classname = substr(s, 1, index(s, "\"") - 1)
      }
      s = $0
      if (match(s, / name="/)) {
        s = substr(s, RSTART + 7)
        name = substr(s, 1, index(s, "\"") - 1)
      }
      in_testcase = 1
      in_failure = 0
      failure_msg = ""
      failure_text = ""
    }
    in_testcase && (/<failure/ || /<error/) {
      in_failure = 1
      s = $0
      if (match(s, /message="/)) {
        s = substr(s, RSTART + 9)
        failure_msg = substr(s, 1, index(s, "\"") - 1)
      }
      if (match($0, />/)) {
        line = substr($0, RSTART + 1)
        sub(/<\/(failure|error)>.*/, "", line)
        if (line != "") failure_text = failure_text line "\n"
      }
      next
    }
    in_failure && (/<\/failure>/ || /<\/error>/) { in_failure = 0; next }
    in_failure { failure_text = failure_text $0 "\n" }
    /<\/testcase>/ || /<testcase [^>]*\/>$/ {
      if (failure_msg != "" || failure_text != "") {
        printf "### %s > %s FAILED\n", classname, name
        if (failure_msg != "") printf "%s\n", substr(failure_msg, 1, 500)
        n = split(failure_text, lines, "\n")
        printed = 0
        for (i = 1; i <= n && printed < 8; i++) {
          if (lines[i] != "") {
            gsub(/^[[:space:]]+/, "  ", lines[i])
            printf "%s\n", lines[i]
            printed++
          }
        }
        printf "---\n"
      }
      in_testcase = 0
      in_failure = 0
      failure_msg = ""
      failure_text = ""
    }
  ' "$xml_file"

  file_failures=$(rg -o 'failures="[0-9]*"' "$xml_file" | rg -o '[0-9]*')
  file_errors=$(rg -o 'errors="[0-9]*"' "$xml_file" | rg -o '[0-9]*')
  failure_count=$((failure_count + file_failures + file_errors))
done

echo ""
echo "Summary: $failure_count test failure(s)"
[ "$failure_count" -eq 0 ]
