package com.trova.backend.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.config.AsyncConfig;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PipelineStageMetrics;
import com.trova.backend.pipeline.PlaceSelectionRunner;
import com.trova.backend.pipeline.PlaceVerificationRunner;
import com.trova.backend.repository.ApiCallLogJdbcRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.ApiCallLogService;
import com.trova.backend.service.AutoDraftService;
import com.trova.backend.service.FoundPlaceNameStore;
import com.trova.backend.service.PlaceExtractionService;
import com.trova.backend.service.ProcessingJobLifecycleService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 별도 JVM fixture. 운영/개발 Supabase·HTTP 서버·외부 API에 연결하지 않는다. */
public class ProcessRecoveryWorker {
    @TestConfiguration
    @EnableAutoConfiguration(exclude = GoogleGenAiChatAutoConfiguration.class)
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = ProcessingJobRepository.class)
    @EnableKafka
    @EnableAsync
    @Import({AsyncConfig.class, PipelineRunner.class,
            ProcessingJobLifecycleService.class, PlaceExtractionService.class,
            QueueWaitMetrics.class, PipelineStageMetrics.class, FoundPlaceNameStore.class,
            ApiCallLogService.class, ApiCallLogJdbcRepository.class})
    static class Config {
        @Bean ShareJobListener listener(ProcessingJobRepository jobs, PlaceExtractionService extraction,
                @org.springframework.beans.factory.annotation.Value("${fixture.root}") String root) {
            return new ShareJobListener(extraction) {
                @Override
                @org.springframework.kafka.annotation.KafkaListener(topics = KafkaShareJobQueue.TOPIC,
                        groupId = "trova-share-jobs", concurrency = "1")
                public void onMessage(String jobId) {
                    try {
                        Files.writeString(Path.of(root, "deliveries"), jobId + "\n",
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException(error);
                    }
                    super.onMessage(jobId);
                }
            };
        }

        @Bean MeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean KakaoGeocodingService geocoding() {
            KakaoGeocodingService service = mock(KakaoGeocodingService.class);
            when(service.searchCandidates(any(), any(), any(), any())).thenReturn(GeocodingResult.empty());
            when(service.resolveRegionFallback(any(), any(), any(), any())).thenReturn(GeocodingResult.empty());
            return service;
        }
        @Bean PlaceSelectionRunner selection() { return mock(PlaceSelectionRunner.class); }
        @Bean PlaceVerificationRunner verification() { return mock(PlaceVerificationRunner.class); }
        @Bean AutoDraftService drafts() { return mock(AutoDraftService.class); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        String brokers = args[1];
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE).run(
                        "--spring.datasource.url=jdbc:h2:file:" + root.resolve("database") + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",
                        "--spring.datasource.username=sa", "--spring.datasource.password=",
                        "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.jpa.hibernate.ddl-auto=update",
                        "--fixture.root=" + root, "--app.queue.type=kafka", "--spring.kafka.bootstrap-servers=" + brokers,
                        "--spring.kafka.consumer.auto-offset-reset=earliest", "--spring.kafka.consumer.max-poll-records=1",
                        "--spring.kafka.consumer.properties.session.timeout.ms=6000",
                        "--spring.kafka.consumer.properties.heartbeat.interval.ms=1000",
                        "--spring.kafka.consumer.properties.group.protocol=classic",
                        "--spring.kafka.consumer.enable-auto-commit=false",
                        "--app.queue.kafka.concurrency=1",
                        "--app.pipeline.script-path=" + root.resolve("fixture.py"),
                        "--app.pipeline.work-dir=" + root.resolve("work"),
                        "--logging.level.root=WARN")) {
            UserRepository users = context.getBean(UserRepository.class);
            ProcessingJobRepository jobs = context.getBean(ProcessingJobRepository.class);
            SavedPlaceRepository places = context.getBean(SavedPlaceRepository.class);
            User user = users.findByProviderAndProviderUserId("google", "process-recovery-fixture")
                    .orElseGet(() -> users.save(new User("google", "process-recovery-fixture", "fixture", null)));
            Path seed = root.resolve("seed-job-id");
            boolean recovery = Files.exists(seed);
            ProcessingJob job;
            if (recovery) {
                // 재시작 때 DB를 새로 만들어도 ID가 같아 우연히 통과하는 것을 막는다.
                job = jobs.findById(Long.parseLong(Files.readString(seed))).orElseThrow();
            } else {
                job = jobs.save(new ProcessingJob(user, "https://youtu.be/fixture", SourcePlatform.YOUTUBE));
                Files.writeString(seed, String.valueOf(job.getId()));
            }
            ObjectMapper mapper = new ObjectMapper();
            if (recovery) {
                Files.writeString(root.resolve("recovered-state.json"), mapper.writeValueAsString(Map.of(
                        "jobId", job.getId(), "status", job.getStatus().name(), "createdAt", job.getCreatedAt().toString())));
            }
            Files.writeString(root.resolve("ready"), String.valueOf(job.getId()));
            if (args[2].equals("async-first")) context.getBean(PlaceExtractionService.class).process(job.getId());
            while (!Files.exists(root.resolve("stop"))) {
                ProcessingJob current = jobs.findById(job.getId()).orElseThrow();
                Path next = root.resolve("state-next.json");
                Files.writeString(next, mapper.writeValueAsString(Map.of(
                        "jobId", job.getId(), "status", current.getStatus().name(),
                        "analysisStatus", Objects.toString(current.getAnalysisStatus(), ""),
                        "places", places.findByProcessingJob(current).size())));
                Files.move(next, root.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(100);
            }
        }
    }
}
