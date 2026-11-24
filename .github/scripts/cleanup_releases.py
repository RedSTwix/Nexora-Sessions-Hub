"""Keep the newest published releases. Git tags are never deleted."""

import argparse
import json
import os
import re
import subprocess
from datetime import datetime


def request(method, endpoint):
    result = subprocess.run(
        ["gh", "api", "--method", method, endpoint],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    return json.loads(result.stdout) if result.stdout.strip() else None


def list_releases(repository, api):
    releases = []
    page = 1
    while True:
        batch = api("GET", f"repos/{repository}/releases?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise ValueError("Resposta inválida ao listar releases; limpeza interrompida.")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
        page += 1


def plan(releases, keep=10):
    if keep < 1:
        raise ValueError("É necessário preservar pelo menos uma release.")
    published = []
    seen = set()
    for release in releases:
        if release.get("draft") or not release.get("published_at"):
            continue
        identifier = release["id"]
        if not isinstance(identifier, int) or isinstance(identifier, bool) or identifier <= 0 or identifier in seen:
            raise ValueError("Lista de releases inválida ou duplicada; limpeza interrompida.")
        seen.add(identifier)
        timestamp = datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))
        if timestamp.tzinfo is None:
            raise ValueError("Data de publicação sem fuso; limpeza interrompida.")
        if not isinstance(release.get("tag_name"), str) or not release["tag_name"]:
            raise ValueError("Release sem tag; limpeza interrompida.")
        published.append((timestamp, identifier, release))
    published.sort(key=lambda entry: (entry[0], entry[1]), reverse=True)
    retained = [entry[2] for entry in published[:keep]]
    # Remove oldest first, and only after all pages and metadata were checked.
    obsolete = [entry[2] for entry in reversed(published[keep:])]
    return retained, obsolete


def cleanup(repository, keep=10, apply=False, api=request):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repositório inválido.")
    retained, obsolete = plan(list_releases(repository, api), keep)
    print(f"Preservar {len(retained)} releases; {len(obsolete)} antigas fora do limite de {keep}.")
    deleted = []
    for release in obsolete:
        if not apply:
            print(f"Simulação: excluir release {release['tag_name']} (tag Git preservada).")
            continue
        endpoint = f"repos/{repository}/releases/{release['id']}"
        current = api("GET", endpoint)
        if (current.get("draft") or current.get("published_at") != release["published_at"]
                or current.get("tag_name") != release["tag_name"] or current.get("id") != release["id"]):
            print(f"Preservada {release['tag_name']}: a release mudou durante a limpeza.")
            continue
        api("DELETE", endpoint)
        deleted.append(release["tag_name"])
        print(f"Excluída release {release['tag_name']} e seus anexos. Tag Git preservada.")
    return {"retained": len(retained), "obsolete": len(obsolete), "deleted": deleted}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), required=not os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--keep", type=int, default=10)
    parser.add_argument("--apply", action="store_true", help="Executa as exclusões; sem esta opção, apenas simula.")
    arguments = parser.parse_args()
    result = cleanup(arguments.repo, arguments.keep, arguments.apply)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"\nReleases preservadas: **{result['retained']}**. Antigas excluídas: **{len(result['deleted'])}**. Tags Git preservadas.\n")
