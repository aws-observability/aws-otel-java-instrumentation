#!/bin/bash -e

# Find the latest release tag that's in the current branch's history
latest_tag=""
for tag in $(git tag -l "v*.*.*" --sort=-version:refname); do
  if git merge-base --is-ancestor "$tag" HEAD; then
    latest_tag="$tag"
    break
  fi
done

if [[ -z $latest_tag ]]; then
  echo "No release tags found in current branch history"
  exit 1
fi

echo "# Changes since $latest_tag:"
echo

# Generate changelog entries from commits since the tag
git log --reverse \
        --perl-regexp \
        --author='^(?!dependabot\[bot\] )(?!github-actions\[bot\] )' \
        --pretty=format:"- %s" \
        "$latest_tag..HEAD" \
  | grep -E '\(#[0-9]+\)$' \
  | grep -v '^- Post release ' \
  | sed -E 's,\(#([0-9]+)\)$, ([#\1](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/\1)),'
