package com.yooooo.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Demo data initialization is disabled for the literature-focused project.
 */
@Component
@Slf4j
public class DataInitializer implements ApplicationRunner {
    @Value("${rag.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (demoDataEnabled) {
            log.info("[DataInit] Demo data is no longer seeded automatically. Use paper upload instead.");
            return;
        }
        log.info("[DataInit] Demo data initialization is disabled");
    }
}