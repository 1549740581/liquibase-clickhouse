#!/usr/bin/env bash
set -euo pipefail

GROUP_ID="${GROUP_ID:-com.darcytech}"
ARTIFACT_ID="${ARTIFACT_ID:-liquibase-clickhouse}"
VERSION="${VERSION:-dx-20260630.2}"
REPOSITORY_ID="${REPOSITORY_ID:-nexus-darcy}"
REPOSITORY_URL="${REPOSITORY_URL:-http://nexus.darcytech.com/repository/thirdparty}"

mvn clean \
  org.apache.maven.plugins:maven-resources-plugin:2.6:resources \
  org.apache.maven.plugins:maven-compiler-plugin:3.1:compile \
  org.apache.maven.plugins:maven-jar-plugin:2.4:jar \
  org.apache.maven.plugins:maven-source-plugin:3.2.1:jar

if [[ "${SKIP_DEPLOY:-false}" == "true" ]]; then
  exit 0
fi

mvn deploy:deploy-file -e \
  -Dversion="${VERSION}" \
  -DgroupId="${GROUP_ID}" \
  -DartifactId="${ARTIFACT_ID}" \
  -Dpackaging=jar \
  -Dfile="target/${ARTIFACT_ID}-${VERSION}.jar" \
  -Dsources="target/${ARTIFACT_ID}-${VERSION}-sources.jar" \
  -DrepositoryId="${REPOSITORY_ID}" \
  -Durl="${REPOSITORY_URL}"
