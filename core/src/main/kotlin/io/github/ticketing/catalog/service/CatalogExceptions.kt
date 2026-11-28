package io.github.ticketing.catalog.service

import io.github.ticketing.DomainException
import org.springframework.http.HttpStatus

class VenueNotFoundException : DomainException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND, "공연장을 찾을 수 없습니다.")

class PerformanceNotFoundException : DomainException("PERFORMANCE_NOT_FOUND", HttpStatus.NOT_FOUND, "공연을 찾을 수 없습니다.")

class DuplicateVenueSeatException : DomainException("DUPLICATE_VENUE_SEAT", HttpStatus.BAD_REQUEST, "같은 구역·열·번호의 좌석이 중복되었습니다.")

class DuplicatePriceGradeException : DomainException("DUPLICATE_PRICE_GRADE", HttpStatus.BAD_REQUEST, "같은 등급의 가격이 중복되었습니다.")

class PriceMissingForGradeException(grades: Collection<String>) :
    DomainException("PRICE_MISSING_FOR_GRADE", HttpStatus.BAD_REQUEST, "공연장 좌석에 쓰인 등급의 가격이 없습니다: ${grades.joinToString()}")

class NotPerformanceOwnerException : DomainException("NOT_PERFORMANCE_OWNER", HttpStatus.FORBIDDEN, "해당 공연의 주최자만 변경할 수 있습니다.")

class DuplicateScheduleException : DomainException("DUPLICATE_SCHEDULE", HttpStatus.CONFLICT, "같은 시작 시각의 회차가 이미 있습니다.")

class ScheduleNotFoundException : DomainException("SCHEDULE_NOT_FOUND", HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다.")

class PosterAlreadyAttachedException : DomainException("POSTER_ALREADY_ATTACHED", HttpStatus.CONFLICT, "이미 공연에 포스터가 등록되었습니다.")

class InvalidPosterTypeException : DomainException("INVALID_POSTER_TYPE", HttpStatus.BAD_REQUEST, "JPEG, PNG, WebP 포스터만 등록할 수 있습니다.")

class InvalidPosterSizeException : DomainException("INVALID_POSTER_SIZE", HttpStatus.BAD_REQUEST, "포스터 크기가 허용 범위를 벗어났습니다.")

class PosterUploadNotFoundException : DomainException("POSTER_UPLOAD_NOT_FOUND", HttpStatus.NOT_FOUND, "업로드한 포스터를 찾을 수 없습니다.")

class PosterStorageUnavailableException(cause: Throwable) :
    DomainException("POSTER_STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "포스터 저장소에 연결할 수 없습니다.") {
    init {
        initCause(cause)
    }
}

class PerformanceSuspendedException : DomainException("PERFORMANCE_SUSPENDED", HttpStatus.CONFLICT, "판매가 중지된 공연입니다.")

class PerformanceSuspensionUnchangedException :
    DomainException("PERFORMANCE_SUSPENSION_UNCHANGED", HttpStatus.CONFLICT, "공연의 판매 상태가 이미 요청한 상태입니다.")
