#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../build/test-game/source"

python3 - <<'PY'
from pathlib import Path

strings = Path("app/src/main/res/values/strings.xml")
xml = strings.read_text(encoding="utf-8")
marker = "</resources>"
assert marker in xml, "Minesweeper strings.xml has no resources closing tag"
for name, value in (
    ("difficulty_easy", "Easy"),
    ("difficulty_medium", "Medium"),
    ("difficulty_hard", "Hard"),
):
    entry = f'    <string name="{name}">{value}</string>'
    if f'name="{name}"' not in xml:
        xml = xml.replace(marker, entry + "\n" + marker)
strings.write_text(xml, encoding="utf-8")

prefs = Path("app/src/main/java/com/joeld/minesweeper/PrefsRepository.kt")
source = prefs.read_text(encoding="utf-8")
needle = '    private val prefs = context.getSharedPreferences("minesweeper_prefs", Context.MODE_PRIVATE)'
replacement = needle + '\n    private val appContext = context.applicationContext'
assert needle in source, "Pinned Minesweeper PrefsRepository constructor changed"
source = source.replace(needle, replacement, 1)

replacements = {
    'GameMode("easy", "Easy", 8, 8, 8, true, false)':
        'GameMode("easy", appContext.getString(R.string.difficulty_easy), 8, 8, 8, true, false)',
    'GameMode("medium", "Medium", 12, 12, 25, true, false)':
        'GameMode("medium", appContext.getString(R.string.difficulty_medium), 12, 12, 25, true, false)',
    'GameMode("hard", "Hard", 16, 16, 40, true, false)':
        'GameMode("hard", appContext.getString(R.string.difficulty_hard), 16, 16, 40, true, false)',
}
for old, new in replacements.items():
    assert old in source, f"Expected default mode declaration not found: {old}"
    source = source.replace(old, new, 1)
prefs.write_text(source, encoding="utf-8")
print("Prepared pinned Minesweeper fixture: Easy/Medium/Hard are Android string resources.")
PY
