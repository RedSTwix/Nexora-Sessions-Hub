"""Update only the README release badge after a downloadable stable release exists."""

import argparse
import json
import os
import re
import subprocess
from pathlib import Path


BADGE = re.compile(r"(?m)^\[!\[release\]\([^()\r\n]*\)\]\([^()\r\n]*\)[ \t]*(?=\r?$)")


def published_tag(release):
    tag = release.get("tag_name", "")
    if (not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag)
            or release.get("draft") or release.get("prerelease") or not release.get("published_at")):
        raise ValueError("O selo exige uma release estável já publicada.")
    apk = f"Nexora-Sessions-Hub-{tag}.apk"
    if not any(asset.get("name") == apk and asset.get("state") == "uploaded"
               and asset.get("size", 0) > 0 for asset in release.get("assets", [])):
        raise ValueError("A release ainda não tem o APK publicado; README preservado.")
    return tag


def render(readme, repository, release):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repositório inválido.")
    tag = published_tag(release)
    if len(BADGE.findall(readme)) != 1:
        raise ValueError("Era esperado exatamente um selo release no README.")
    badge = (f"[![release](https://img.shields.io/badge/release-{tag}-orange)]"
             f"(https://github.com/{repository}/releases/tag/{tag})")
    return BADGE.sub(lambda _: badge, readme, count=1)


def update(path, repository, release):
    with path.open("r", encoding="utf-8", newline="") as source:
        previous = source.read()
    updated = render(previous, repository, release)
    if previous == updated:
        return False
    with path.open("w", encoding="utf-8", newline="") as output:
        output.write(updated)
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), required=not os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    arguments = parser.parse_args()
    response = subprocess.run(["gh", "api", f"repos/{arguments.repo}/releases/latest"],
                              check=True, capture_output=True, text=True, encoding="utf-8")
    release = json.loads(response.stdout)
    changed = update(arguments.readme, arguments.repo, release)
    print(f"Selo da release {published_tag(release)}: {'atualizado' if changed else 'já está correto'}.")
