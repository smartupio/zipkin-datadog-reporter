package io.smartup.zipkin;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.sleuth.sampler.SamplerProperties;
import org.springframework.cloud.sleuth.zipkin2.ZipkinAutoConfiguration;
import org.springframework.cloud.sleuth.zipkin2.ZipkinProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

@Configuration
@EnableConfigurationProperties(ZipkinProperties.class)
@ConditionalOnProperty(value = "spring.zipkin.datadog.enabled", matchIfMissing = true)
@AutoConfigureBefore(ZipkinAutoConfiguration.class)
public class ZipkinDatadogReporterAutoConfiguration {

    @Bean
    public Reporter<Span> reporter(DatadogProperties properties) {
        return new DatadogReporter(properties.getHost(), properties.getPort(), properties.getMessageTimeout());
    }
}
