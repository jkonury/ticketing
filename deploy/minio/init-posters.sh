#!/bin/sh
set -eu

mc alias set local http://minio:9000 "$MINIO_USER" "$MINIO_PASSWORD" >/dev/null
mc mb --ignore-existing "local/$POSTER_BUCKET"
cat > /tmp/ticketing-posters-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Principal": "*", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::$POSTER_BUCKET/posters/*"}
  ]
}
EOF
mc anonymous set-json /tmp/ticketing-posters-policy.json "local/$POSTER_BUCKET"
