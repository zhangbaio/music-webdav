package com.example.musicwebdav.common.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskExecutionConfig {

    private ExecutorService scanTaskExecutor;

    @Bean
    public ExecutorService scanTaskExecutor(AppScanProperties appScanProperties) {
        int core = Math.max(1, Math.min(4, appScanProperties.getParserThreadCount()));
        int max = Math.max(core, core * 2);
        int queueSize = Math.max(20, appScanProperties.getBatchSize());
        this.scanTaskExecutor = new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new NamedThreadFactory("scan-task-"),
                new ThreadPoolExecutor.AbortPolicy());
        return this.scanTaskExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (scanTaskExecutor != null) {
            scanTaskExecutor.shutdown();
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger idx = new AtomicInteger(1);
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + idx.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
