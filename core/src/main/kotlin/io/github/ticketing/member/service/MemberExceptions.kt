package io.github.ticketing.member.service

import io.github.ticketing.DomainException
import org.springframework.http.HttpStatus

class EmailAlreadyExistsException : DomainException("EMAIL_ALREADY_EXISTS", HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")

// 이메일 존재 여부를 알 수 없도록 이메일 오류와 비밀번호 오류를 구분하지 않는다
class InvalidCredentialsException : DomainException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")

class InvalidRefreshTokenException : DomainException("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.")

class MemberNotFoundException : DomainException("MEMBER_NOT_FOUND", HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.")

class RoleNotAllowedException : DomainException("ROLE_NOT_ALLOWED", HttpStatus.BAD_REQUEST, "가입 시 선택할 수 없는 역할입니다.")

class LastAdminException : DomainException("LAST_ADMIN", HttpStatus.CONFLICT, "마지막 관리자의 역할은 바꿀 수 없습니다.")

class MemberSuspendedException : DomainException("MEMBER_SUSPENDED", HttpStatus.FORBIDDEN, "이용이 정지된 회원입니다.")

class MemberSuspensionForbiddenException : DomainException("MEMBER_SUSPENSION_FORBIDDEN", HttpStatus.FORBIDDEN, "관리자는 이용 정지할 수 없습니다.")

class MemberSuspensionUnchangedException :
    DomainException("MEMBER_SUSPENSION_UNCHANGED", HttpStatus.CONFLICT, "회원의 이용 상태가 이미 요청한 상태입니다.")
