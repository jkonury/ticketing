#!/usr/bin/env bash
set -euo pipefail

path="$1"
[ -d "$path" ] || exit 0
pattern='(@SpringBootTest|@WebMvcTest|@WebFluxTest|@DataJpaTest|@DataJdbcTest|@JdbcTest|@JsonTest|@RestClientTest|@SpringBootTestContextBootstrapper|SpringExtension|@SpringJUnitConfig|@SpringJUnitWebConfig|@ContextConfiguration|@ApplicationModuleTest|SpringApplication\.(run|from)|SpringApplicationBuilder|runApplication|ApplicationContextRunner|GenericApplicationContext|AnnotationConfigApplicationContext|@Testcontainers|@Container|org\.testcontainers|org\.mockito|io\.mockk|@MockitoBean|@MockitoSpyBean|@MockBean|@MockkBean|\b(mockk|mock|spy|spyk)\s*\()'
status=0
rg -n --glob '*.kt' --glob '*.java' "$pattern" "$path" || status=$?
if [ "$status" -eq 0 ]; then
  echo "단위 테스트에서 Spring 기동, Testcontainers 또는 mock을 사용할 수 없습니다." >&2
  exit 1
fi
[ "$status" -eq 1 ] || exit "$status"
