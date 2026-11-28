# ADR 0053: 개발·CI 포스터 저장소의 RustFS 전환과 벤더 중립 설정 이름

- 상태: 합의
- 작성일: 2026-11-28
- 합의일: 2026-11-28
- 결정 주체: 사용자 합의
- 대체: [ADR 0050](0050-poster-storage-minio-presigned.md)의 결정 1 중 "로컬과 개발 환경은 MinIO를 Compose에 추가해 쓴다"
- 대체됨·일부 변경: 없음
- 관련: [ADR 0050](0050-poster-storage-minio-presigned.md), [전환 검토](../reviews/2026-11-27-poster-storage-rustfs.md), [로드맵](../roadmap.md), [설계](../architecture.md)

## 배경

[ADR 0050](0050-poster-storage-minio-presigned.md)에 따라 개발·CI는 MinIO를 포스터 저장소로 쓴다. MinIO가 공식 컨테이너 이미지 배포를 중단해서, Compose와 core 통합 테스트는 서드파티가 다시 빌드한 이미지를 다이제스트로 고정해 쓰고 있다. 이 이미지는 보안 패치와 지속성을 보장할 수 없다.

M6에서 곧 k8s 매니페스트를 작성하는데, 오브젝트 스토리지 구성도 매니페스트에 들어간다. 저장소 제품을 지금 정하지 않으면 매니페스트를 두 번 작성하게 된다. 그리고 설정 이름(`TICKETING_MINIO_*`)과 Compose 서비스 이름에 벤더 이름이 박혀 있어서, 제품을 바꿀 때마다 이름도 바꿔야 한다.

[전환 검토](../reviews/2026-11-27-poster-storage-rustfs.md)에서 RustFS 1.0.6이 core가 쓰는 S3 동작(서명에 묶인 presigned PUT, 조건부 PUT, ETag 조건부 복사, CORS preflight, 접두사별 익명 읽기 정책)을 지원하는 것을 확인했다.

## 결정

1. 개발·CI의 포스터 저장소는 RustFS로 완전히 바꾼다. MinIO 구성은 지우고, 두 제품을 고를 수 있게 두지 않는다. 코드가 S3 API만 쓴다는 ADR 0050 결정 1의 나머지는 유지한다.
2. 저장소 설정 이름은 벤더 중립으로 한다. 환경 변수는 `TICKETING_OBJECT_STORAGE_*`, Compose 서비스는 `object-storage`·`object-storage-init`, 볼륨은 `object-storage-data`, 초기화 스크립트 디렉터리는 `deploy/object-storage/`를 쓴다. 이전 이름(`TICKETING_MINIO_*`)으로 대체하는 경로는 두지 않는다.
3. 버킷 초기화는 표준 S3 API를 호출하는 AWS CLI로 한다. 여러 번 실행해도 결과가 같아야 한다. 익명 `s3:GetObject`는 `posters/*`에만 허용한다.
4. 저장소 이미지와 초기화 이미지는 버전 태그와 다이제스트로 고정한다.
5. 기존 로컬 MinIO 데이터는 자동으로 옮기지 않는다. 개발 데이터는 시드 스크립트로 다시 만든다. 보존이 필요할 때 쓸 수동 절차만 런북에 둔다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 서드파티 MinIO 이미지 유지 | 이미지의 출처와 패치 주기를 보장할 수 없다. |
| MinIO·RustFS를 설정으로 선택 | 두 제품의 초기화·헬스체크·테스트를 함께 유지해야 한다. 롤백은 git revert로 충분하다. |
| `TICKETING_MINIO_*` 이름 유지 | 당장의 변경은 줄지만 실제 제품과 이름이 어긋나고, k8s·클라우드 S3로 옮길 때 다시 바꿔야 한다. |
| 초기화에 `mc` 계속 사용 | RustFS와의 호환을 검증하지 않았고, S3 API만 쓴다는 원칙과도 맞지 않는다. |
| 기존 볼륨 데이터 자동 이전 스크립트 | RustFS 기본 빌드는 MinIO 디스크 형식을 읽지 못해 S3 복사가 필요하다. 개발 데이터는 시드로 다시 만들 수 있어 비용에 비해 얻는 것이 적다. |

## 결과

- 얻는 것:
  - 공식 배포 이미지를 쓴다.
  - 설정 이름이 제품과 분리돼서, 이후 k8s·클라우드 S3로 옮길 때 이름을 바꾸지 않아도 된다.
  - k8s 매니페스트를 RustFS 기준으로 한 번만 작성한다.
- 감수하는 것:
  - 기존 로컬 `.env`의 `TICKETING_MINIO_*`를 새 이름으로 바꿔야 한다. 이전 `minio` 컨테이너와 `minio-data` 볼륨은 수동으로 정리해야 한다.
  - 로컬에 있던 포스터는 다시 올리거나 시드로 다시 만들어야 한다.
  - RustFS는 MinIO보다 짧은 기간 검증된 제품이다. 문제가 생기면 이 ADR을 대체하는 결정이 필요하다.
- 바뀌는 문서·지침: `docs/architecture.md`(기술 스택 표, 로컬 실행), `docs/runbook.md`(환경 변수 이전, 이전 볼륨 정리), `.env.example`. AGENTS.md 5절은 명령만 가리키므로 바뀌지 않는다.

## 검증

- `PosterUploadApiTests`가 RustFS Testcontainers로 ADR 0050의 검증 항목(발급 권한, 키 위조, 콘텐츠 유형, 크기 초과 삭제)을 검사한다.
- 웹 E2E `web/e2e/poster-upload.spec.ts`가 `scripts/e2e/run.sh --web`으로 기동한 RustFS에서 업로드와 공개 표시를 검사한다. PR의 CI(`web-e2e.yml`)에서 확인한다.
- `object-storage-init`을 두 번 실행해도 성공해야 한다. 그 뒤 `posters/*` 익명 GET은 허용되고 `uploads/*` 익명 GET은 `403`이어야 한다.

## 변경 이력

- 2026-11-28: 작성·합의
