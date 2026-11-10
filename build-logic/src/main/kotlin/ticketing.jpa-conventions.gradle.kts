plugins {
    id("ticketing.spring-conventions")
    kotlin("plugin.jpa")
}

// JPA 엔티티는 프록시 생성을 위해 open이어야 한다
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
