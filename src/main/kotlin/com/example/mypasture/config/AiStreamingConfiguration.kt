package com.example.mypasture.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/** 동시 AI streaming 요청을 제한해 서버의 요청 스레드와 메모리를 보호합니다. */
@Configuration
class AiStreamingConfiguration {
    @Bean(name = ["aiStreamingExecutor"])
    fun aiStreamingExecutor(properties: AiProperties): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.streamCorePoolSize
            maxPoolSize = properties.streamMaxPoolSize
            queueCapacity = properties.streamQueueCapacity
            setThreadNamePrefix("ai-stream-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
}
