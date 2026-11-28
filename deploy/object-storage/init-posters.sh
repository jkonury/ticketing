#!/bin/sh
set -eu

aws_s3() {
  aws --endpoint-url http://object-storage:9000 "$@"
}

# 이미 존재한다는 응답만 성공으로 본다. 다른 실패는 그대로 드러낸다.
if ! out=$(aws_s3 s3api create-bucket --bucket "$POSTER_BUCKET" 2>&1); then
  case "$out" in
    *BucketAlreadyOwnedByYou*|*BucketAlreadyExists*) ;;
    *) echo "$out" >&2; exit 1 ;;
  esac
fi

cat > /tmp/ticketing-posters-policy.json <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Principal": "*", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::$POSTER_BUCKET/posters/*"}
  ]
}
POLICY
aws_s3 s3api put-bucket-policy --bucket "$POSTER_BUCKET" --policy file:///tmp/ticketing-posters-policy.json
