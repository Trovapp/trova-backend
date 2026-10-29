package com.trova.backend;

import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Gemini ChatModel은 SpringAiConfig에서 직접 만든다(#243) — 자동 설정은 키가 비면 서버가 뜨지 않고 읽기 제한을 줄 수 없다.
@SpringBootApplication(exclude = GoogleGenAiChatAutoConfiguration.class)
@EnableAsync
@EnableScheduling
public class TrovaBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TrovaBackendApplication.class, args);
	}

}
