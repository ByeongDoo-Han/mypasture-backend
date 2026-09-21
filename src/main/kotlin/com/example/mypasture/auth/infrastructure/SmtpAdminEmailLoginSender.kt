package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.AdminEmailDeliveryException
import com.example.mypasture.auth.application.AdminEmailLoginSender
import com.example.mypasture.config.AdminEmailLoginProperties
import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.core.env.Environment
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component
import java.time.Instant

/** 공용 SMTP 자격증명을 재사용하되 관리자 로그인 발송의 시간 제한과 비밀 로그 금지를 강제합니다. */
@Component
class SmtpAdminEmailLoginSender(
    private val properties: AdminEmailLoginProperties,
    mail: MailProperties,
    environment: Environment,
) : AdminEmailLoginSender {
    private val sender = if (!properties.enabled) null else JavaMailSenderImpl().apply {
        require(!mail.host.isNullOrBlank()) { "Admin email login requires SMTP host configuration." }
        if (environment.activeProfiles.contains("prod")) {
            require(!mail.username.isNullOrBlank() && !mail.password.isNullOrBlank()) {
                "Production admin email login requires SMTP credentials."
            }
            require(mail.properties["mail.smtp.starttls.enable"] == "true" || mail.properties["mail.smtp.ssl.enable"] == "true") {
                "Production admin email login requires encrypted SMTP transport."
            }
        }
        host = mail.host
        port = mail.port ?: 587
        username = mail.username
        password = mail.password
        defaultEncoding = "UTF-8"
        javaMailProperties.putAll(mail.properties)
        javaMailProperties["mail.smtp.connectiontimeout"] = "3000"
        javaMailProperties["mail.smtp.timeout"] = "3000"
        javaMailProperties["mail.smtp.writetimeout"] = "3000"
        javaMailProperties["mail.debug"] = "false"
        if (mail.properties["mail.smtp.starttls.enable"] == "true") {
            javaMailProperties["mail.smtp.starttls.required"] = "true"
        }
        javaMailProperties["mail.smtp.ssl.checkserveridentity"] = "true"
    }

    override fun send(email: String, code: String, expiresAt: Instant) {
        val transport = sender ?: throw AdminEmailDeliveryException()
        val message = SimpleMailMessage().apply {
            from = properties.from
            setTo(email)
            subject = "[My Pasture] 관리자 로그인 인증번호"
            text = "관리자 로그인 인증번호는 $code 입니다.\n5분 이내에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일을 무시해 주세요."
        }
        try {
            transport.send(message)
        } catch (_: MailException) {
            throw AdminEmailDeliveryException()
        }
    }
}
