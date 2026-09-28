package com.fantasy.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** Injected wherever "now" matters so tests can pin the time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
