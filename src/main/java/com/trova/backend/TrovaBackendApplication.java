package com.trova.backend;

import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Gemini ChatModel은 SpringAiConfig에서 직접 만든다(#243) — 자동 설정은 키가 비면 서버가 뜨지 않고 읽기 제한을 줄 수 없다.
// OTLP 지표 전송도 OtlpMetricsConfig에서 직접 만든다(#358) — 자동 설정은 OpenTelemetry SDK 모듈(추적 포함)을 요구한다.
@SpringBootApplication(exclude = {GoogleGenAiChatAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class})
@EnableAsync
@EnableScheduling
public class TrovaBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TrovaBackendApplication.class, args);
	}

}
