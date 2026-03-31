#!/bin/bash
set -e

POM_FILE="pom.xml"

if command -v mvn &> /dev/null; then
    CURRENT=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || true)
fi
if [ -z "$CURRENT" ]; then
    CURRENT=$(grep -m1 '<version>' "$POM_FILE" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d '[:space:]')
fi
echo "Mevcut versiyon: $CURRENT"

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

PATCH=$((PATCH + 1))
if [ "$PATCH" -ge 10 ]; then
    PATCH=0
    MINOR=$((MINOR + 1))
fi
if [ "$MINOR" -ge 10 ]; then
    MINOR=0
    MAJOR=$((MAJOR + 1))
fi

NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
if command -v mvn &> /dev/null; then
    mvn versions:set -DnewVersion="${NEW_VERSION}" -DgenerateBackupPoms=false -q
else
    sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>${NEW_VERSION}<\/version>/" "$POM_FILE"
fi
echo "Yeni versiyon: $NEW_VERSION"
echo "version=$NEW_VERSION" >> "$GITHUB_OUTPUT"
