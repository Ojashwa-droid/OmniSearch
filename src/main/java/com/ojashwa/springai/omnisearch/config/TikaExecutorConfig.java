package com.ojashwa.springai.omnisearch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class TikaExecutorConfig {

    @Bean(name = "tikaExecutor", destroyMethod = "shutdown")
    public ExecutorService tikaExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "omnisearch-tika-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(12), threads, new ThreadPoolExecutor.AbortPolicy());
    }
}
