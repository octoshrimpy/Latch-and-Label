#!/usr/bin/env sh
set -eu

usage() {
  echo "Usage: $0 -v <10|11> [-c air.toml]" >&2
  exit 2
}

AIR_CONFIG="air.toml"
MC_SHORT=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    -v|--version)
      [ "$#" -ge 2 ] || usage
      MC_SHORT="$2"
      shift 2
      ;;
    -c|--config)
      [ "$#" -ge 2 ] || usage
      AIR_CONFIG="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

case "$MC_SHORT" in
  10)
    export MC_VERSION="1.21.10"
    export YARN_MAPPINGS="1.21.10+build.1"
    export LOADER_VERSION="0.17.2"
    export FABRIC_VERSION="0.138.4+1.21.10"
    ;;
  11)
    export MC_VERSION="1.21.11"
    export YARN_MAPPINGS="1.21.11+build.4"
    export LOADER_VERSION="0.18.1"
    export FABRIC_VERSION="0.141.3+1.21.11"
    ;;
  *)
    usage
    ;;
esac

exec air -c "$AIR_CONFIG"
