# Zipkin DataDog Reporter

### Intro

This library reports Brave tracing to DataDog APM.

### Usage

----

#### Using Spring Boot

Just include this dependency:

```xml
<dependency>
	<groupId>io.smartup.zipkin</groupId>
	<artifactId>zipkin-datadog-reporter-starter</artifactId>
	<version>1.0.4</version>
</dependency>
```

And annotate your main class with `@EnableZipkinDatadogReporter`.

----

### Credit

All the credit goes to [tylerbenson](https://github.com/tylerbenson) for writing the reporter.

