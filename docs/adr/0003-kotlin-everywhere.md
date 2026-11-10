# 0003. 백엔드는 전부 Kotlin

- 상태: 수락
- 일자: 2026-11-10

## 맥락

실시간 앱에서 Coroutines를 쓰려면 Kotlin이 필요하다. 코어까지 Kotlin으로 할지 정해야 한다.

## 검토한 대안

- (a) 전부 Kotlin
- (b) 코어는 Java, 실시간 앱만 Kotlin

## 결정

**(a) 전부 Kotlin**으로 한다.

## 결과

- 언어를 섞을 때 생기는 공통 모듈·빌드 설정의 복잡함을 피한다.
- jOOQ와 Coroutines 모두 Kotlin 지원이 좋다.
- JPA 엔티티에는 제약이 따른다. `allopen`(`kotlin-spring`)과 `noarg`(`kotlin-jpa`) 플러그인을 쓰고, 엔티티를 `data class`로 만들지 않는다.
