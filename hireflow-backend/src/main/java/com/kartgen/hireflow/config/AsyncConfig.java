package com.kartgen.hireflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean
    public TaskExecutor applicationTaskExecutor() {
        return new TaskExecutor() {
            @Override
            public void execute(Runnable task) {
                Thread.startVirtualThread(task);
            }
        };
    }
}
