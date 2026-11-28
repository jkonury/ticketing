---
status: approved
approved_at: 2026-11-28T16:28:04+09:00
---

# 포스터 저장소 RustFS 전환

## 목표와 근거

개발·CI의 포스터 저장소를 서드파티 MinIO 이미지에서 공식 RustFS 이미지로 바꾼다. 저장소 설정 이름도 벤더 중립으로 바꾼다. k8s 매니페스트 작성 전에 끝내야 하는 [로드맵 우선 처리 1순위](../../roadmap.md#우선-처리-순서-성능-항목-제외-2026-11-28-정리)다. 결정은 [ADR 0053](../../adr/0053-poster-storage-rustfs.md), 업로드 흐름은 [ADR 0050](../../adr/0050-poster-storage-minio-presigned.md), 사전 검증은 [전환 검토](../../reviews/2026-11-27-poster-storage-rustfs.md)를 따른다.

끝났을 때 관찰할 결과:

- `scripts/dev/run.sh --infra`와 `scripts/e2e/run.sh --web`이 RustFS(`object-storage`)를 기동하고 포스터 버킷을 준비한다. 주소는 지금과 같은 `127.0.0.1:19010`이다.
- 저장소에 `TICKETING_MINIO_*`, `minio` 서비스, `projekt-manager-org` 이미지 참조가 남지 않는다. 역사 기록인 ADR 0049·0050, `docs/ui/roadmap.md`, `docs/reviews/`, `docs/backup/`은 예외다.
- core 포스터 통합 테스트와 웹 포스터 E2E가 RustFS에서 통과한다.

## 범위

- 포함: `compose.yaml`, 초기화 스크립트(`deploy/minio/` → `deploy/object-storage/`), `.env.example`, `core/src/main/resources/application.yml`, `scripts/dev/run.sh`, `scripts/e2e/run.sh`, `PosterUploadApiTests`, 아키텍처·런북·로드맵 문서.
- 범위 밖: core 포스터 로직·API·BFF·화면, nginx 템플릿(주소와 경로를 그대로 둔다), k8s 매니페스트(M6 본작업), 기존 로컬 데이터 이전 스크립트, 미완료 업로드 정리 Batch.

## task

### T1. core 통합 테스트의 저장소를 RustFS로 교체

- [ ] 완료
- Blocked by: 없음
- 변경 범위와 제약: `core/src/test/kotlin/io/github/ticketing/catalog/PosterUploadApiTests.kt`의 컨테이너를 `ghcr.io/rustfs/rustfs:1.0.6@sha256:ee717072290bec9b11abfc0365f8310b00df1cd9b3639943edae47bdef05044a`로 바꾼다. 환경 변수는 `RUSTFS_ACCESS_KEY`·`RUSTFS_SECRET_KEY`로, 대기 조건은 `/health/ready`로 바꾼다. 이미지 기본 명령이 `/data`를 쓰는지 확인하고 필요할 때만 명령을 지정한다. 필드 이름 `minio`는 `objectStorage`로 바꾼다. 테스트 본문과 검증 항목은 바꾸지 않는다.
- 테스트 경계와 선례: HTTP 공개 경계와 실제 S3 저장소를 쓰는 기존 `PosterUploadApiTests`를 그대로 쓴다. 기대 결과는 기존 테스트가 모두 통과하는 것이다(발급 권한, 키 위조 거부, 콘텐츠 유형 거부, 크기 초과 삭제, 재사용 URL 거부).
- AC:

  ```bash
  ./scripts/agent/test/run-tests.sh :core:test --tests '*PosterUpload*'
  ```

### T2. Compose·초기화·실행 스크립트를 RustFS와 중립 이름으로 교체

- [ ] 완료
- Blocked by: 없음
- 변경 범위와 제약:
  - `compose.yaml`: `minio`·`minio-init`을 `object-storage`·`object-storage-init`으로, 볼륨 `minio-data`를 `object-storage-data`로 바꾼다. 저장소 이미지는 T1과 같은 다이제스트를 쓴다. `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY`, CORS 허용 출처 변수(검토 문서 기준 `RUSTFS_CORS_ALLOWED_ORIGINS`, 구현 시 1.0.6 문서로 이름 확인)를 설정한다. 바인딩은 `127.0.0.1:${TICKETING_OBJECT_STORAGE_PORT:-19010}:9000`, 헬스체크는 `curl -fsS http://localhost:9000/health/ready`로 한다. 이미지에 `curl`이 있는 것은 확인했다. 이미지는 uid 10001로 실행되므로 새 볼륨에 쓰기 권한이 있는지도 확인한다.
  - 초기화: `deploy/minio/init-posters.sh`를 `deploy/object-storage/init-posters.sh`로 옮기고 AWS CLI(`amazon/aws-cli:2.31.38@sha256:4ba70977d0261c1d5bf7c094df11d1c6b1c7c3e9cfaf84269bbb1676b8cac3a2`)로 다시 쓴다. `s3api create-bucket`은 이미 존재한다는 응답만 성공으로 처리한다. `put-bucket-policy`는 기존과 같은 정책(`posters/*`에만 익명 `s3:GetObject`)을 적용한다. 엔드포인트는 `http://object-storage:9000`, path-style 주소, 리전 `us-east-1`을 쓴다.
  - 환경 변수: `TICKETING_MINIO_USER/PASSWORD/PORT/CORS_ORIGINS`를 `TICKETING_OBJECT_STORAGE_ACCESS_KEY/SECRET_KEY/PORT/CORS_ORIGINS`로 바꾼다. 대상은 `.env.example`(주석의 MinIO 표기 포함), `application.yml`의 `ticketing.poster.access-key/secret-key`, `scripts/dev/run.sh`(도움말, `up`, `run` 대상), `scripts/e2e/run.sh`(CORS export, 기동, `poster_endpoint`)다. 기본값(`ticketing-local`, `ticketing-local-dev`, `19010`)은 유지하고 이전 이름 대체 경로는 두지 않는다.
- 테스트 경계와 선례: 저장소 경계는 Compose로 기동한 컨테이너의 HTTP다. 기존 `scripts/e2e/run.sh`의 저장소 기동 단계가 선례다. 이전 `minio` 컨테이너가 19010을 점유하고 있으면 `docker compose up -d --remove-orphans`로 먼저 정리한다.
- AC: 아래 명령이 순서대로 모두 성공하고, 마지막 두 줄이 각각 `404`(객체 없음, 정책상 읽기 허용)와 `403`을 출력한다. 이어서 `rg -n 'TICKETING_MINIO|minio' compose.yaml .env.example core/src/main scripts deploy`는 결과가 없어야 한다.

  ```bash
  docker compose up -d --wait --remove-orphans object-storage
  docker compose run --rm --no-deps object-storage-init
  docker compose run --rm --no-deps object-storage-init
  curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19010/ticketing-posters/posters/none.png
  curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19010/ticketing-posters/uploads/none.png
  ./scripts/agent/test/run-tests.sh :core:test --tests '*PosterUpload*'
  ```

### T3. 문서 갱신

- [ ] 완료
- Blocked by: T1, T2
- 변경 범위와 제약:
  - `docs/architecture.md`: 기술 스택 표의 "MinIO(개발·CI)"를 RustFS로 바꾸고 ADR 0053 링크를 단다. 로컬 실행 문단의 서비스 이름과 `minio-init`을 바꾼다.
  - `docs/runbook.md`: 격리 포트 예시의 `TICKETING_MINIO_PORT`를 바꾼다. 기존 로컬 환경에서 옮겨 오는 절차를 추가한다(`.env`의 변수 이름 변경, `--remove-orphans`로 이전 컨테이너 정리, `minio-data` 볼륨은 롤백용으로 두었다가 직접 삭제, 시드 재실행, 보존이 필요할 때 같은 키와 `Content-Type`으로 S3 복사).
  - `docs/rules/backend-core.md`: 포스터 문단에 ADR 0053 링크를 추가한다.
  - `docs/roadmap.md`: 우선 처리 표에서 RustFS 행을 지우고 "별도 구현 항목"에 완료로 계획 링크와 함께 기록한다.
  - ADR 0049·0050 본문, `docs/ui/roadmap.md`, `docs/reviews/`는 역사 기록이므로 고치지 않는다.
- 테스트 경계와 선례: 문서 검사. 선례는 관리자 기능 강화 계획의 문서 task다.
- AC:

  ```bash
  ./scripts/agent/docs/check-docs.sh docs
  ```

## 최종 검증

- 시작 SHA부터 HEAD까지 `./scripts/agent/test/verify-changed.sh`를 실행한다.
- JVM 변경(T1·T2의 `application.yml`)이 있으므로 `./scripts/agent/test/run-tests.sh`를 필터 없이 실행한다.
- 저장소 전체에서 `rg -n 'TICKETING_MINIO|projekt-manager-org|minio-init'`를 실행하고, 역사 기록(ADR 0049·0050, `docs/ui/roadmap.md`, `docs/reviews/`, `docs/backup/`) 밖에 결과가 없는지 확인한다.
- 웹 포스터 E2E(`web/e2e/poster-upload.spec.ts`)는 PR의 CI `web-e2e.yml`에서 확인한다. 로컬 E2E는 실행하지 않는다.
