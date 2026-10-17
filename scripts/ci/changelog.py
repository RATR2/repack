#!/usr/bin/env python3
import os
import re
import subprocess
import sys

REPO = os.environ.get("GITHUB_REPOSITORY", "RATR2/repack")
WEB = f"https://github.com/{REPO}"
DROPDOWN_AFTER = int(os.environ.get("HISTORY_DROPDOWN_AFTER", "10"))
NOREPLY = re.compile(r"^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout

def previous_release(end):
    try:
        return git("describe", "--tags", "--abbrev=0", "--match", "[0-9]*", end).strip()
    except subprocess.CalledProcessError:
        return ""

def login_of(sha, email, name):
    match = NOREPLY.match(email)
    if match:
        return match.group(1)
    try:
        found = subprocess.run(
            ["gh", "api", f"repos/{REPO}/commits/{sha}", "--jq", ".author.login // empty"],
            check=True, capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        if found:
            return found
    except Exception:
        pass
    return name

def main():
    end = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
    previous = sys.argv[1] if len(sys.argv) > 1 else previous_release(end)
    rev_range = f"{previous}..{end}" if previous else end
    commits = []
    for line in git("log", "--no-merges", "--max-count=500", "--format=%H%x09%s%x09%an%x09%ae", rev_range).splitlines():
        sha, subject, name, email = line.split("\t")
        if "[skip ci]" in subject or name.endswith("[bot]"):
            continue
        commits.append((sha, subject.replace("<", "&lt;").replace(">", "&gt;"), name, email))
    if not commits:
        return
    out = ["## Changelog", ""]
    bullets = [f"* [`{sha[:7]}`]({WEB}/commit/{sha}) {subject}" for sha, subject, _, _ in commits]
    if len(bullets) > DROPDOWN_AFTER:
        since = f" since {previous}" if previous else ""
        out += [f"<details>", f"<summary>{len(bullets)} commits{since}</summary>", ""] + bullets + ["", "</details>"]
    else:
        out += bullets
    if previous:
        target = os.environ.get("VERSION") or end
        out += ["", f"[Click here to view the full list of commits made since {previous}]({WEB}/compare/{previous}...{target})"]
    earlier = set(git("log", "--format=%ae", previous).split()) if previous else None
    counts, order = {}, []
    for sha, _, name, email in commits:
        if email not in counts:
            counts[email] = [0, sha, name]
            order.append(email)
        counts[email][0] += 1
    people = []
    for email in sorted(order, key=lambda e: -counts[e][0]):
        _, sha, name = counts[email]
        login = login_of(sha, email, name)
        first = " ⭐ First contribution! ⭐" if earlier is not None and email not in earlier else ""
        people.append(f"* [@{login}](https://github.com/{login}){first}" if "/" not in login and " " not in login else f"* {login}{first}")
    out += ["", "## Thank You", "", "Special thanks to the contributors whose work was included in this version:", ""] + people
    out += ["", f"As always, if you run into any issues, please report them at {WEB}/issues."]
    print("\n".join(out))

if __name__ == "__main__":
    main()
