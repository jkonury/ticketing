package io.github.ticketing.catalog.service

import io.github.ticketing.DomainException
import org.springframework.http.HttpStatus

class VenueNotFoundException : DomainException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND, "공연장을 찾을 수 없습니다.")

class PerformanceNotFoundException : DomainException("PERFORMANCE_NOT_FOUND", HttpStatus.NOT_FOUND, "공연을 찾을 수 없습니다.")

class DuplicateVenueSeatException : DomainException("DUPLICATE_VENUE_SEAT", HttpStatus.BAD_REQUEST, "같은 구역·열·번호의 좌석이 중복되었습니다.")

class DuplicatePriceGradeException : DomainException("DUPLICATE_PRICE_GRADE", HttpStatus.BAD_REQUEST, "같은 등급의 가격이 중복되었습니다.")

class PriceMissingForGradeException(grades: Collection<String>) :
    DomainException("PRICE_MISSING_FOR_GRADE", HttpStatus.BAD_REQUEST, "공연장 좌석에 쓰인 등급의 가격이 없습니다: ${grades.joinToString()}")

class NotPerformanceOwnerException : DomainException("NOT_PERFORMANCE_OWNER", HttpStatus.FORBIDDEN, "해당 공연의 주최자만 회차를 등록할 수 있습니다.")

class DuplicateScheduleException : DomainException("DUPLICATE_SCHEDULE", HttpStatus.CONFLICT, "같은 시작 시각의 회차가 이미 있습니다.")
