#!/usr/bin/env bash

set -exuo pipefail

version="${1:-$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)}"

if [ "${SKIP_EXAMPLE_PROJECT:-false}" == "true" ]; then
  echo "skipping example project"
else
  echo "::group::Test building example project"
  (cd planetiler-examples && mvn -B -ntp -Dplanetiler.version="${version}" verify --file standalone.pom.xml)
  echo "::endgroup::"
fi

echo "Test java build"
echo "::group::OpenMapTiles monaco (java)"
rm -f data/out.mbtiles
java -jar planetiler-dist/target/*with-deps.jar --download --area=monaco --output=data/out.mbtiles
./scripts/check-monaco.sh data/out.mbtiles
echo "::endgroup::"
echo "::group::Example (java)"
rm -f data/out.mbtiles
java -jar planetiler-dist/target/*with-deps.jar example-toilets --download --area=monaco --output=data/out.mbtiles
./scripts/check-mbtiles.sh data/out.mbtiles
echo "::endgroup::"

echo "::endgroup::"
echo "::group::OpenMapTiles monaco (docker)"
rm -f data/out.mbtiles
docker run -v "$(pwd)/data":/data ghcr.io/onthegomap/planetiler:"${version}" --area=monaco --output=data/out.mbtiles
./scripts/check-monaco.sh data/out.mbtiles
echo "::endgroup::"
echo "::group::Example (docker)"
rm -f data/out.mbtiles
docker run -v "$(pwd)/data":/data ghcr.io/onthegomap/planetiler:"${version}" example-toilets --area=monaco --output=data/out.mbtiles
./scripts/check-mbtiles.sh data/out.mbtiles
echo "::endgroup::"
