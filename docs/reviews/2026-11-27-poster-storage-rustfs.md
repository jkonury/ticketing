# 포스터 저장소 RustFS 전환 검토

2026-11-27 기준 도입 시점은 미정이다. 개발·CI의 포스터 저장소는 [ADR 0050](../adr/0050-poster-storage-minio-presigned.md)에 따라 MinIO를 사용한다. 버킷 이름과 접근 주소를 유지하면 RustFS 전환에 필요한 변경은 인프라 설정, 초기화, 테스트 환경에 집중된다.

## 변경 범위

| 영역 | 전환 시 작업 |
|---|---|
| Compose | RustFS 이미지 버전과 다이제스트를 고정하고 새 데이터 볼륨을 사용한다. `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY`, `RUSTFS_CORS_ALLOWED_ORIGINS`를 설정하고 readiness를 `/health/ready`로 바꾼다. 기본 바인딩 `127.0.0.1:19010`은 재사용할 수 있다. [Compose](../../compose.yaml) · [Docker 설치](https://github.com/rustfs/docs.rustfs.com/blob/main/content/en/installation/container/docker.md) · [CORS 설정](https://docs.rustfs.com/en/administration/cors) |
| 버킷 초기화 | `ticketing-posters` 생성과 공개 읽기 정책 적용을 반복 실행할 수 있게 한다. 익명 `s3:GetObject`는 `posters/*`에만 허용한다. 기존 `mc` 초기화의 호환 여부를 확인하거나 표준 S3 API를 호출하는 CLI로 교체한다. [현재 초기화 스크립트](../../deploy/minio/init-posters.sh) |
| 개발·CI | 개발 스크립트와 웹 E2E가 기동하는 Compose 서비스를 교체한다. 환경 변수 이름을 바꾸면 `.env.example`, 기존 로컬 `.env`, core 설정을 함께 맞춘다. 웹 CI와 데모 시드는 기존 스크립트와 API를 통해 전환된 저장소를 사용할 수 있다. [개발 기동](../../scripts/dev/run.sh) · [E2E 기동](../../scripts/e2e/run.sh) · [웹 CI](../../.github/workflows/web-e2e.yml) |
| core·웹 | core는 AWS SDK의 path-style S3 주소와 `us-east-1`을 사용한다. 주소와 버킷을 유지한다는 전제에서 포스터 로직, API·BFF·화면, nginx 라우팅 수정은 필요 없어 보인다. nginx 경유 시 서명에 사용한 `Host`와 경로가 보존되어야 한다. [저장소 설정](../../core/src/main/kotlin/io/github/ticketing/catalog/config/PosterStorageConfig.kt) · [nginx 템플릿](../../nginx/ticketing.conf.template) |

## 격리 환경 검증

`ghcr.io/rustfs/rustfs:1.0.6`을 별도 컨테이너로 실행했다. AWS SDK `2.58.0`으로 core와 같은 설정의 presigned PUT을 검증하고, 나머지 저장소 동작은 AWS CLI와 HTTP 요청으로 확인했다. [RustFS 1.0.6 릴리스](https://github.com/rustfs/rustfs/releases/tag/1.0.6) · [포스터 업로드 구현](../../core/src/main/kotlin/io/github/ticketing/catalog/service/PosterUploadService.kt)

- presigned PUT은 정상 요청 `200`, 서명된 `Content-Type` 변경 `403`, 같은 URL 재사용 `412`를 반환했다.
- `http://localhost:13000`에서 `Content-Type`과 `If-None-Match`를 명시한 PUT의 CORS preflight가 `200`과 허용 헤더를 반환했다.
- `HeadObject`의 ETag와 일치하는 조건부 `CopyObject`는 성공했고, 잘못된 ETag는 `PreconditionFailed`로 거부됐다. 임시 객체를 삭제한 뒤 인증된 HEAD는 `404`를 반환했다.
- 버킷 정책 적용 후 `posters/*`의 익명 GET은 `200`, `uploads/*`의 익명 GET은 `403`을 반환했다.

## 데이터 이관과 전환 검증

검토 당시 로컬에는 실행 중인 MinIO와 `ticketing_minio-data` 볼륨이 있었다. 전환을 결정하면 보존할 포스터가 있는지 먼저 확인한다. DB는 `poster_key`를 저장하므로, 새 버킷으로 DB가 참조하는 객체를 동일한 키와 `Content-Type`으로 S3 API를 통해 복사하면 DB 마이그레이션 없이 조회할 수 있다. RustFS 기본 빌드는 MinIO 디스크 형식 호환 기능을 포함하지 않는다. 공개 조회 확인 후 주소를 전환하고 기존 볼륨은 롤백용으로 보관한다. [DB 스키마](../../core/src/main/resources/db/migration/V17__add_performance_genre_and_poster.sql) · [RustFS 기능 및 디스크 호환성](https://github.com/rustfs/rustfs)

실제 전환 시 Testcontainers 이미지를 교체해 core 통합 테스트를 실행한다. 웹 E2E는 주최자의 업로드와 목록·상세의 이미지 표시를 검증한다. nginx 도메인을 쓰는 환경에서는 그 주소에서도 업로드와 공개 조회를 확인한다. 도입 결정을 내리면 후속 ADR과 아키텍처·실행 문서를 갱신한다. [core 통합 테스트](../../core/src/test/kotlin/io/github/ticketing/catalog/PosterUploadApiTests.kt) · [포스터 웹 E2E](../../web/e2e/poster-upload.spec.ts)
