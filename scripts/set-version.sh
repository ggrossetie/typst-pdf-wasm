#!/usr/bin/env bash
# Sets the project version in the Maven poms (library and examples) and the crate.
#
# Usage: scripts/set-version.sh <version>   e.g. 1.2.0 or 1.2.1-SNAPSHOT
set -euo pipefail

version="${1:?usage: scripts/set-version.sh <version>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

current="$(mvn -B -q -f "$root/java/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)"
mvn -B -q -f "$root/java/pom.xml" org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
  -DnewVersion="$version" -DgenerateBackupFiles=false -DprocessAllModules=true

# Examples use the same version for themselves and for their typst-pdf dependency
for pom in "$root"/examples/*/pom.xml; do
  sed -i "s|<version>${current}</version>|<version>${version}</version>|g" "$pom"
done

# The README documents the latest release, not development versions
if [[ "$version" != *-SNAPSHOT ]]; then
  sed -i -e "/<artifactId>typst-pdf<\/artifactId>/{n;s|<version>.*</version>|<version>${version}</version>|}" \
    -e "s|\([\"']\)fr.yuzutech.typst:typst-pdf:[^\"']*|\1fr.yuzutech.typst:typst-pdf:${version}|" "$root/README.md"
fi

sed -i "0,/^version = \".*\"/s//version = \"${version}\"/" "$root/typst-pdf-wasm/Cargo.toml"
cargo update --manifest-path "$root/Cargo.toml" --workspace --quiet

echo "Version: ${current} -> ${version}"
