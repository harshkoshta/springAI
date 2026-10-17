package com.local.rag.config;

import com.local.nativespace.PythonExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PythonExecutorConfiguration {

    @Bean
    public PythonExecutor pythonExecutor() {
        return new PythonExecutor();
    }
}
