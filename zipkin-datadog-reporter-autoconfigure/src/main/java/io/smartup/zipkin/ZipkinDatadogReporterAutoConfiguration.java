package io.smartup.zipkin;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.cloud.sleuth.zipkin2.ZipkinAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

@Configuration
@AutoConfigureBefore(ZipkinAutoConfiguration.class)
public class ZipkinDatadogReporterAutoConfiguration {
    @Bean
    public Reporter<Span> reporter() {
        return new DatadogReporter();
    }
}
