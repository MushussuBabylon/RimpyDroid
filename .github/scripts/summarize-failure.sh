#!/usr/bin/env bash
# Turns the log of a failed Gradle run into a short box of text for the GitHub "Summary" page of the run,
# so the errors can be copied from there instead of being searched for in a log of thousands of lines.
#
#   usage: summarize-failure.sh <gradle-log> "<heading>" >> "$GITHUB_STEP_SUMMARY"

log="${1:-}"
heading="${2:-The build failed}"
[ -f "$log" ] || exit 0

# the repository path of the runner is noise: .../work/<repo>/<repo>/app/src/... -> app/src/...
short() { sed -E 's#file:///home/runner/work/[^/]+/[^/]+/##'; }

echo "### $heading"
echo ""
echo "Copy everything in the box below and send it along when asking for a fix."
echo ""
echo '```'

# Kotlin compiler errors, including the lines that follow an error (candidates, expected types, ...)
awk '/^e: /{p=1} /^(w: |> Task |BUILD |FAILURE|\* )/{p=0} p' "$log" | short | head -n 120

# errors of other tools (resources, manifest, dex, signing), failed tasks and failed tests
grep -E '(^|[^A-Za-z])(ERROR|error:|Error:)|FAILED$' "$log" | grep -v '^e: ' | short | head -n 40

# Gradle's own summary of what went wrong
grep -E -A 20 -m 1 '^\* What went wrong:' "$log" | head -n 30

echo '```'
exit 0
