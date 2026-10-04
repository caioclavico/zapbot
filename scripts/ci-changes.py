#!/usr/bin/env python3
"""Select monorepo services without credentials or third-party path filters."""

import argparse
import json
import os
import re
import subprocess
import urllib.request


SERVICES = ("odisseu", "pokemon")


def deployment_baselines(fetch, repository):
    """Only trusted main push/dispatch artifacts may establish a service baseline.

    A service marker survives a failure of the other service in the same run.
    Rollback markers invalidate that service's baseline. Bounded missing history
    deliberately returns None, selecting its entire runtime on the next push.
    """
    baselines, runs, collected = {}, {}, []
    total = None
    for page in range(1, 4):
        response = fetch(f"/repos/{repository}/actions/artifacts?per_page=100&page={page}")
        artifacts = response["artifacts"]
        total = response.get("total_count", total)
        collected.extend(artifacts)
        if len(artifacts) < 100:
            break
    if total is not None and total > len(collected):
        # The API does not promise ordering. A newer rollback marker could be
        # outside a bounded scan; incomplete history cannot establish a baseline.
        return {service: None for service in SERVICES}
    collected.sort(key=lambda artifact: (artifact.get("created_at", ""), artifact.get("id", 0)), reverse=True)
    for artifact in collected:
        marker = re.fullmatch(r"(deployed|rolled-back)-(odisseu|pokemon)-([a-f0-9]{40})", artifact.get("name", ""))
        if not marker or marker[2] in baselines:
            continue
        run_id = artifact["workflow_run"]["id"]
        if run_id not in runs:
            runs[run_id] = fetch(f"/repos/{repository}/actions/runs/{run_id}")
        run = runs[run_id]
        if (run.get("event") not in {"push", "workflow_dispatch"} or run.get("head_branch") != "main"
                or (run.get("head_repository") or {}).get("full_name", "").casefold() != repository.casefold()
                or run.get("path", "").split("@", 1)[0] != ".github/workflows/deploy.yml"
                or run.get("head_sha") != marker[3]):
            continue
        baselines[marker[2]] = marker[3] if marker[1] == "deployed" and not artifact.get("expired") else None
        if len(baselines) == len(SERVICES):
            return baselines
    return {service: baselines.get(service) for service in SERVICES}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None  # A token must never follow an unexpected redirect.


def github_baselines():
    repository = os.environ["GITHUB_REPOSITORY"]
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    if not api.startswith("https://"):
        raise ValueError("GitHub API must use HTTPS")
    opener = urllib.request.build_opener(NoRedirect())
    def fetch(path):
        request = urllib.request.Request(api + path, headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        with opener.open(request, timeout=20) as response:
            return json.load(response)
    try:
        return deployment_baselines(fetch, repository)
    except (OSError, ValueError, KeyError):
        # Do not print response bodies, URLs with credentials or exception details.
        print("Deployment baseline unavailable; validating both complete runtimes.")
        return {service: None for service in SERVICES}


def services_since_baselines(baselines, head, paths=None):
    # Resolve at runtime so tests can substitute the Git diff without a network.
    paths = paths or changed_paths
    affected = []
    for service in SERVICES:
        if service in affected_services(paths(baselines.get(service), head)):
            affected.append(service)
    return affected


def affected_services(paths):
    affected = set()
    for path in paths:
        # Documentation does not change either runtime. Unknown paths validate both.
        if path.startswith("docs/") or path.endswith(".md") or re.fullmatch(r"(?:.*/)?(?:README(?:\.[^/]+)?|LICENSE(?:\.[^/]+)?)", path):
            continue
        if path.startswith("pokemon-service/"):
            affected.add("pokemon")
        elif path.startswith(("src/", "test/", "assets/")) or path in {
            "Dockerfile", ".dockerignore", ".env.example", "package.json",
            "package-lock.json", "shadow-cljs.edn", "scripts/healthcheck.js",
            "scripts/patch-whatsapp-media.js",
        }:
            affected.add("odisseu")
        else:
            affected.update(SERVICES)
    return [service for service in SERVICES if service in affected]


def changed_paths(base, head):
    history_available = bool(base) and set(base) != {"0"}
    if history_available:
        history_available = subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, head], capture_output=True,
        ).returncode == 0
    if not history_available:
        command = ["git", "ls-tree", "-r", "--name-only", "-z", head]
    else:
        # --no-renames includes both the old and new paths when files move services.
        command = ["git", "diff", "--no-renames", "--name-only", "-z", base, head, "--"]
    result = subprocess.run(command, check=True, capture_output=True)
    return [path for path in result.stdout.decode("utf-8", errors="surrogateescape").split("\0") if path]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", choices=("push", "pull_request", "workflow_dispatch"), required=True)
    parser.add_argument("--event-path", required=True)
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--service", choices=(*SERVICES, "both"), default="both")
    parser.add_argument("--github-output")
    parser.add_argument("--production-baselines", action="store_true")
    args = parser.parse_args()
    if args.event == "workflow_dispatch":
        services = list(SERVICES) if args.service == "both" else [args.service]
    elif args.production_baselines and args.event == "push" and os.environ.get("GITHUB_REF") == "refs/heads/main":
        services = services_since_baselines(github_baselines(), args.head)
    else:
        with open(args.event_path, encoding="utf-8") as event_file:
            event = json.load(event_file)
        base = event.get("before") if args.event == "push" else event["pull_request"]["base"]["sha"]
        services = affected_services(changed_paths(base, args.head))
    outputs = {
        "odisseu": str("odisseu" in services).lower(),
        "pokemon": str("pokemon" in services).lower(),
        "any": str(bool(services)).lower(),
        "services": json.dumps(services, separators=(",", ":")),
    }
    result = "".join(f"{key}={value}\n" for key, value in outputs.items())
    print(result, end="")
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as output:
            output.write(result)


if __name__ == "__main__":
    main()
