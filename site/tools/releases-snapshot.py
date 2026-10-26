#!/usr/bin/env python3
"""Writes the SafeC release history the site's blog turns into release-note
posts (site/src/releases.sc reads it).

    python3 site/tools/releases-snapshot.py OUT.json [--safec DIR] [--offline]

Sources, merged per tag:
  - GitHub's releases API for safec-org/SafeC (name, date, prerelease flag,
    URL and the "What's Changed" body), unless --offline or unreachable;
  - the annotated git tags of a SafeC checkout (--safec, default
    $SAFEC_HOME): the tag message (the curated release summary) and, when
    the API is unreachable, the tag's date.
The checked-in snapshot, site/data/releases.json, is this script's output;
CI regenerates it into site/build/releases.json at build time when the
network allows (site/build.sh, SITE_FETCH_RELEASES=1), and the generator
prefers that copy.
"""
import json, os, subprocess, sys, urllib.request

REPO = "safec-org/SafeC"


def from_api():
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases?per_page=100",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "safec-site"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.load(r)
    out = {}
    for rel in data:
        if rel.get("draft"):
            continue
        out[rel["tag_name"]] = {
            "tag_name": rel["tag_name"],
            "name": rel.get("name") or rel["tag_name"],
            "published_at": rel.get("published_at") or "",
            "prerelease": bool(rel.get("prerelease")),
            "html_url": rel.get("html_url") or f"https://github.com/{REPO}/releases/tag/{rel['tag_name']}",
            "body": (rel.get("body") or "").replace("\r\n", "\n"),
        }
    return out


def from_git(safec):
    out = {}
    if not safec or not os.path.isdir(safec):
        return out
    try:
        tags = subprocess.run(
            ["git", "-C", safec, "for-each-ref", "refs/tags", "--sort=-creatordate",
             "--format=%(refname:short)%00%(creatordate:iso-strict)%00%(contents)%00%00"],
            check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return out
    for rec in tags.split("\0\0"):
        parts = rec.strip("\n").split("\0")
        if len(parts) < 3 or not parts[0]:
            continue
        tag, date, msg = parts[0], parts[1], parts[2].strip()
        out[tag] = {"tag_name": tag, "date": date, "tag_message": msg}
    return out


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    path = args[0]
    safec = os.environ.get("SAFEC_HOME", "")
    offline = "--offline" in args
    if "--safec" in args:
        safec = args[args.index("--safec") + 1]
    api = {}
    if not offline:
        try:
            api = from_api()
        except Exception as e:  # network-less CI or sandbox: git tags only
            print(f"releases-snapshot: GitHub API unavailable ({e}); using git tags", file=sys.stderr)
    git = from_git(safec)
    tags = set(api) | set(git)
    rels = []
    for t in tags:
        a, g = api.get(t, {}), git.get(t, {})
        rels.append({
            "tag_name": t,
            "name": a.get("name", t),
            "published_at": a.get("published_at") or g.get("date", ""),
            "prerelease": a.get("prerelease", "-" in t.lstrip("v")),
            "html_url": a.get("html_url", f"https://github.com/{REPO}/releases/tag/{t}"),
            "body": a.get("body", ""),
            "tag_message": g.get("tag_message", ""),
        })
    if not rels:
        sys.exit("releases-snapshot: no releases found")
    rels.sort(key=lambda r: r["published_at"], reverse=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rels, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"releases-snapshot: {len(rels)} release(s) -> {path}")


if __name__ == "__main__":
    main()
