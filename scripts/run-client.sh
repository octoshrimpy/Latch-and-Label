#!/usr/bin/env sh
set -eu

MC_VERSION="${MC_VERSION:-1.21.11}"
YARN_MAPPINGS="${YARN_MAPPINGS:-1.21.11+build.4}"
LOADER_VERSION="${LOADER_VERSION:-0.18.1}"
FABRIC_VERSION="${FABRIC_VERSION:-0.141.3+1.21.11}"

exec ./gradlew runClient --console=plain \
  -Pminecraft_version="${MC_VERSION}" \
  -Pyarn_mappings="${YARN_MAPPINGS}" \
  -Ploader_version="${LOADER_VERSION}" \
  -Pfabric_version="${FABRIC_VERSION}"
