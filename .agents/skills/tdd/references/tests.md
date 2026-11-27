# 좋은 테스트와 나쁜 테스트

테스트 이름·단언 라이브러리·픽스처 같은 작성 규범은 [백엔드 테스트 지침](../../../../docs/rules/backend-testing.md)을 따른다. 아래 예시는 그 규범을 적용한 형태다(한국어 백틱 이름, AssertJ, given·when·then 사이 빈 줄).

## 좋은 테스트

**통합 스타일**: 내부 부품을 mock하지 않고 실제 인터페이스를 통해 테스트한다.

```kotlin
// GOOD: 관찰 가능한 동작을 검증한다
@Test
fun `유효한 좌석을 선점하면 선점 상태로 조회된다`() {
    val hold = seatHoldService.hold(showId, seatId, memberId)

    val found = seatHoldService.find(hold.id)

    assertThat(found.status).isEqualTo(HoldStatus.HELD)
}
```

특징:

- 사용자·호출자가 관심 있는 동작을 테스트한다
- 공개 API만 쓴다
- 내부 리팩터링에 살아남는다
- HOW가 아니라 WHAT을 서술한다
- 테스트 하나에 논리적 단언 하나

## 나쁜 테스트

**구현 세부 테스트**: 내부 구조에 결합되어 있다.

```kotlin
// BAD: 구현 세부를 테스트한다
@Test
fun `선점은 redisClient를 호출한다`() {
    seatHoldService.hold(showId, seatId, memberId)

    verify(redisClient).eval(eq(HOLD_SCRIPT), any(), any())
}
```

위험 신호:

- 내부 협력자를 mock한다
- private 메서드를 테스트한다
- 호출 횟수·순서를 단언한다
- 동작은 그대로인데 리팩터링하면 깨진다
- 테스트 이름이 WHAT이 아니라 HOW를 서술한다
- 인터페이스 대신 외부 수단으로 검증한다

```kotlin
// BAD: 인터페이스를 우회해 검증한다
@Test
fun `예매 생성은 DB에 저장한다`() {
    createBooking(memberId, showId)

    val count = jdbcTemplate.queryForObject("SELECT count(*) FROM booking", Int::class.java)
    assertThat(count).isEqualTo(1)
}

// GOOD: 인터페이스를 통해 검증한다
@Test
fun `예매를 생성하면 조회할 수 있다`() {
    val booking = createBooking(memberId, showId)

    val found = bookingQuery.get(booking.id)

    assertThat(found.memberId).isEqualTo(memberId)
}
```

**동어반복 테스트**: 기대값이 구현을 그대로 되풀이해서 구성상 통과한다.

```kotlin
// BAD: 기대값을 코드와 같은 방식으로 다시 계산한다
@Test
fun `총액은 항목 가격의 합이다`() {
    val items = listOf(Item(price = 10_000), Item(price = 5_000))
    val expected = items.sumOf { it.price }

    assertThat(calculateTotal(items)).isEqualTo(expected)
}

// GOOD: 기대값은 독립된, 알려진 리터럴이다
@Test
fun `총액은 항목 가격의 합이다`() {
    val total = calculateTotal(listOf(Item(price = 10_000), Item(price = 5_000)))

    assertThat(total).isEqualTo(15_000)
}
```
