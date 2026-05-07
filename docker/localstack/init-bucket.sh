#!/bin/bash
# LocalStack runs scripts in /etc/localstack/init/ready.d/ once services are ready.
set -e

awslocal s3 mb s3://demo-uploads || true
awslocal s3api put-bucket-versioning \
    --bucket demo-uploads \
    --versioning-configuration Status=Enabled || true

echo ">> bucket demo-uploads ready"
