package com.example.mypasture.notification.infrastructure

import com.example.mypasture.config.FirebaseProperties
import com.example.mypasture.notification.application.PushSender
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase Admin SDK로 Android FCM 및 iOS APNs 연계 메시지를 발송합니다. */
@Component
@ConditionalOnProperty(name = ["app.firebase.enabled"], havingValue = "true")
class FirebasePushSender(properties: FirebaseProperties) : PushSender {
    private val messaging: FirebaseMessaging
    init {
        require(properties.credentialsPath.isNotBlank()) { "FIREBASE_CREDENTIALS_PATH is required" }
        val options = FirebaseOptions.builder().setCredentials(FileInputStream(properties.credentialsPath).use(GoogleCredentials::fromStream)).setProjectId(properties.projectId).build()
        val app = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(options)
        messaging = FirebaseMessaging.getInstance(app)
    }
    override suspend fun send(token: String, title: String, body: String, data: Map<String, String>) {
        val message = Message.builder().setToken(token)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .putAllData(data)
            .build()
        suspendCancellableCoroutine { continuation ->
            val future = messaging.sendAsync(message)
            continuation.invokeOnCancellation { future.cancel(true) }
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { if (continuation.isActive) continuation.resume(Unit) }
                        .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                },
                { command -> command.run() },
            )
        }
    }
}

/** 개발 환경에서 발송 내용을 로그로 확인하는 대체 구현입니다. */
@Component
@ConditionalOnProperty(name = ["app.firebase.enabled"], havingValue = "false", matchIfMissing = true)
class LoggingPushSender : PushSender {
    private val logger = LoggerFactory.getLogger(javaClass)
    override suspend fun send(token: String, title: String, body: String, data: Map<String, String>) { logger.info("push title={} body={} tokenSuffix={} data={}", title, body, token.takeLast(6), data) }
}
