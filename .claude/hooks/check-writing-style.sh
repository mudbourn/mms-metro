#!/usr/bin/env bash
# Guards WRITING_STYLE.md at agent write-time: no non-ASCII (em-dashes) and no
# multi-line // comment blocks in source files. Reads PostToolUse JSON on stdin.

f=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
[ -z "$f" ] || [ ! -f "$f" ] && exit 0

case "$f" in
    *.java|*.gradle|*.groovy|*.json|*.properties|*.md|*.txt) ;;
    *) exit 0 ;;
esac

problems=""

nonascii=$(perl -ne 'print "$.: $_" if /[^\x00-\x7F]/' "$f" | head -20)
if [ -n "$nonascii" ]; then
    problems+="Non-ASCII characters (em-dashes, unicode) are banned by WRITING_STYLE.md. Replace with plain QWERTY:"$'\n'"$nonascii"$'\n'
fi

if [ "${f##*.}" = "java" ] || [ "${f##*.}" = "gradle" ] || [ "${f##*.}" = "groovy" ]; then
    multiline=$(awk '
        /^[[:space:]]*\/\// { run++; if (run == 2) print (NR-1)": start of multi-line // block"; next }
        { run = 0 }
    ' "$f")
    if [ -n "$multiline" ]; then
        problems+="Multi-line // comment blocks are banned by WRITING_STYLE.md. A comment is exactly one line, or delete it:"$'\n'"$multiline"$'\n'
    fi
fi

[ -z "$problems" ] && exit 0

jq -n --arg r "$problems" '{
    decision: "block",
    reason: ("WRITING_STYLE.md violation in the file you just wrote. Fix it now:\n" + $r)
}'
