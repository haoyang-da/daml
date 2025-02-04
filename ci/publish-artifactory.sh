#!/usr/bin/env bash
# Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

STAGING_DIR=$1
RELEASE_TAG=$2

# We’ve already published to GH so focus on artifactory

INPUTS=$STAGING_DIR/release-artifacts/artifactory

push() {
    local file repository md5 sha1
    repository=$1
    file=$2
    md5=$(md5sum ${file} | awk '{print $1}')
    sha1=$(sha1sum ${file} | awk '{print $1}')
    curl -f \
         -u "$AUTH" \
         -H "X-Checksum-MD5:${md5}" \
         -H "X-Checksum-SHA1:${sha1}" \
         -X PUT \
         -T ${file} \
         https://digitalasset.jfrog.io/artifactory/${repository}/$RELEASE_TAG/${file}
}

TRIGGER_RUNNER=daml-trigger-runner-$RELEASE_TAG.jar
TRIGGER_SERVICE=trigger-service-$RELEASE_TAG-ee.jar
SCRIPT_RUNNER=daml-script-$RELEASE_TAG.jar
NON_REPUDIATION=non-repudiation-$RELEASE_TAG-ee.jar
HTTP_JSON=http-json-$RELEASE_TAG-ee.jar

cd $INPUTS
push daml-trigger-runner $TRIGGER_RUNNER
push daml-trigger-runner $TRIGGER_RUNNER.asc
push daml-script-runner $SCRIPT_RUNNER
push daml-script-runner $SCRIPT_RUNNER.asc
push non-repudiation $NON_REPUDIATION
push non-repudiation $NON_REPUDIATION.asc
push trigger-service $TRIGGER_SERVICE
push trigger-service $TRIGGER_SERVICE.asc
push http-json $HTTP_JSON
push http-json $HTTP_JSON.asc

for base in non-repudiation-core non-repudiation-client; do
    for end in .jar .pom -sources.jar -javadoc.jar; do
        for sign in "" .asc; do
            push connect-ee-mvn/com/daml/$base $base-${RELEASE_TAG}${end}${sign}
        done
    done
done

# For the split release process these are not published to artifactory.
if [[ "$#" -lt 3 || $3 != "split" ]]; then
   for platform in linux macos windows; do
       EE_TARBALL=daml-sdk-$RELEASE_TAG-$platform-ee.tar.gz
       push sdk-ee $EE_TARBALL
       push sdk-ee $EE_TARBALL.asc
   done
   EE_INSTALLER=daml-sdk-$RELEASE_TAG-windows-ee.exe
   push sdk-ee $EE_INSTALLER
   push sdk-ee $EE_INSTALLER.asc
else
    # For the split release process, we publish intermediate artifacts to the
    # assembly repo, under the daml folder.
    cd $STAGING_DIR/split-release
    for d in split-release github; do
        (
        cd $d
        for file in $(find . -type f); do
            push assembly/daml $file
        done
        )
    done
fi
