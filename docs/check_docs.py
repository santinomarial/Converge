"""Check local Markdown links and committed architecture SVGs without dependencies.

Run: python3 docs/check_docs.py
"""

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
LINKS = re.compile(r"!?(?:\[[^\]]*\])\(([^)]+)\)")
EXPECTED_SVGS = {
    "01-system-context.svg",
    "02-runtime-containers.svg",
    "03-event-journey.svg",
    "04-projection-convergence.svg",
    "05-sync-recovery.svg",
    "06-production-topology.svg",
}


def main() -> int:
    errors = []
    markdown_files = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]
    for page in markdown_files:
        for line_number, line in enumerate(page.read_text(encoding="utf-8").splitlines(), 1):
            for raw in LINKS.findall(line):
                target = raw.split(" ", 1)[0].strip("<>")
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc or not parsed.path:
                    continue
                destination = (page.parent / unquote(parsed.path)).resolve()
                if not destination.is_relative_to(ROOT) or not destination.exists():
                    errors.append(f"{page.relative_to(ROOT)}:{line_number}: broken local link: {target}")

    diagram_dir = ROOT / "docs" / "diagrams"
    actual_svgs = {path.name for path in diagram_dir.glob("*.svg")}
    if actual_svgs != EXPECTED_SVGS:
        errors.append(f"Diagram set differs: missing={sorted(EXPECTED_SVGS - actual_svgs)}, "
                      f"extra={sorted(actual_svgs - EXPECTED_SVGS)}")
    for svg in sorted(diagram_dir.glob("*.svg")):
        try:
            root = ElementTree.parse(svg).getroot()
            namespace = "{http://www.w3.org/2000/svg}"
            if root.find(namespace + "title") is None or root.find(namespace + "desc") is None:
                errors.append(f"{svg.relative_to(ROOT)}: missing accessible title or description")
        except ElementTree.ParseError as error:
            errors.append(f"{svg.relative_to(ROOT)}: invalid SVG: {error}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated local links in {len(markdown_files)} Markdown files and {len(actual_svgs)} SVG diagrams")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
