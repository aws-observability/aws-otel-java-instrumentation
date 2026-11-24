#!/bin/bash
set -e

SOURCEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "$SOURCEDIR"
./build-upstream-deps.sh
./build-downstream-deps.sh