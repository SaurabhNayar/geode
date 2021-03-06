#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

GEODE_BRANCH=$(git rev-parse --abbrev-ref HEAD)
SANITIZED_GEODE_BRANCH=$(echo ${GEODE_BRANCH} | tr "/" "-" | tr '[:upper:]' '[:lower:]')
TARGET=geode
GEODE_FORK=${1:-apache}
SANITIZED_GEODE_FORK=$(echo ${GEODE_FORK} | tr "/" "-" | tr '[:upper:]' '[:lower:]')
TEAM=$(fly targets | grep ^${TARGET} | awk '{print $3}')

PUBLIC=true

echo "Deploying pipline for ${GEODE_FORK}/${GEODE_BRANCH} on team ${TEAM}"

if [ "${TEAM}" = "staging" ]; then
  PUBLIC=false
fi

if [[ "${GEODE_FORK}" == "apache" ]]; then
  META_PIPELINE="meta-${SANITIZED_GEODE_BRANCH}"
  PIPELINE_PREFIX=""
else
  META_PIPELINE="meta-${GEODE_FORK}-${SANITIZED_GEODE_BRANCH}"
  PIPELINE_PREFIX="${GEODE_FORK}-${SANITIZED_GEODE_BRANCH}-"
fi
set -x
fly -t ${TARGET} set-pipeline \
  -p ${META_PIPELINE} \
  -c meta.yml \
  --var geode-build-branch=${GEODE_BRANCH} \
  --var sanitized-geode-build-branch=${SANITIZED_GEODE_BRANCH} \
  --var sanitized-geode-fork=${SANITIZED_GEODE_FORK} \
  --var geode-fork=${GEODE_FORK} \
  --var pipeline-prefix=${PIPELINE_PREFIX} \
  --var concourse-team=${TEAM} \
  --yaml-var public-pipelines=${PUBLIC} 2>&1 |tee flyOutput.log

set +x

if [[ "$(tail -n1 flyOutput.log)" == "bailing out" ]]; then
  exit 1
fi

if [[ "${GEODE_FORK}" != "apache" ]]; then
  echo "Disabling unnecessary jobs for forks."
  set -x
  for job in set set-pr set-images set-metrics set-reaper set-examples; do
    fly -t ${TARGET} pause-job \
        -j ${META_PIPELINE}/${job}-pipeline
  done

  fly -t ${TARGET} trigger-job \
      -j ${META_PIPELINE}/build-meta-mini-docker-image
  fly -t ${TARGET} unpause-pipeline \
      -p ${META_PIPELINE}
  set +x
fi
