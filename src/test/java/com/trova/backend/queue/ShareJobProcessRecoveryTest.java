package com.trova.backend.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 로컬 브로커 + 별도 JVM/Spring/JPA/H2 파일 DB + 실제 Python 프로세스 경계. 외부 API는 fixture. */
@EnabledIfEnvironmentVariable(named = "TROVA_RUN_PROCESS_RECOVERY", matches = "1")
@EmbeddedKafka(topics = KafkaShareJobQueue.TOPIC, partitions = 2)
class ShareJobProcessRecoveryTest {
    @TempDir Path temporary;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TopicPartition PARTITION = new TopicPartition(KafkaShareJobQueue.TOPIC, 0);

    private Path prepare(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Files.writeString(root.resolve("fixture.py"), """
                import json, os, time
                from pathlib import Path
                root = Path(__file__).resolve().parent
                with (root / 'attempts').open('a') as stream:
                    stream.write(str(os.getpid()) + '\\n')
                deadline = time.monotonic() + 90
                while not (root / 'release').exists():
                    if time.monotonic() > deadline:
                        raise SystemExit('fixture gate timeout')
                    time.sleep(0.05)
                print(json.dumps({'title':'fixture','places':[{'name':'fixture-place','region':None,
                    'category':'attraction','confidence':0.9,'nameCandidates':['fixture-place']}]}))
                """);
        return root;
    }

    private Process start(Path root, String brokers, String mode, int ordinal) throws Exception {
        Files.deleteIfExists(root.resolve("ready"));
        String classpath = System.getProperty("trova.test.runtime.classpath");
        assertThat(classpath).isNotBlank();
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx256m", "-cp", classpath, ProcessRecoveryWorker.class.getName(), root.toString(), brokers, mode);
        String path = System.getenv("PATH");
        builder.environment().clear();
        builder.environment().put("PATH", path);
        builder.redirectErrorStream(true).redirectOutput(root.resolve("worker-" + ordinal + ".log").toFile());
        return builder.start();
    }

    private void await(BooleanSupplier condition, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (!process.isAlive() || System.nanoTime() > deadline) {
                String text = Files.exists(log) ? Files.readString(log) : "no worker log";
                throw new AssertionError("worker wait failed: " + text.substring(Math.max(0, text.length() - 9000)));
            }
            Thread.sleep(100);
        }
    }

    private long lines(Path path) {
        try { return Files.exists(path) ? Files.readAllLines(path).size() : 0; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private JsonNode state(Path root) {
        try { return Files.exists(root.resolve("state.json")) ? MAPPER.readTree(Files.readString(root.resolve("state.json"))) : MAPPER.createObjectNode(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private long offset(Admin admin) {
        try {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets("trova-share-jobs").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            return offsets.get(PARTITION) == null ? -1 : offsets.get(PARTITION).offset();
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) return -1;
            throw new IllegalStateException(error);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void kill(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        // JVM 강제 종료는 Python fixture를 종료하지 않으므로 테스트가 자식도 명시적으로 정리한다.
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private void cleanFixtureChildren(Path root) throws Exception {
        if (!Files.exists(root.resolve("attempts"))) return;
        for (String pid : Files.readAllLines(root.resolve("attempts"))) {
            ProcessHandle.of(Long.parseLong(pid)).ifPresent(handle -> {
                String fixture = root.resolve("fixture.py").toString();
                boolean ownFixture = java.util.Arrays.stream(handle.info().arguments().orElse(new String[0]))
                        .anyMatch(fixture::equals);
                if (handle.isAlive() && ownFixture) handle.destroyForcibly();
            });
        }
    }

    @Test
    @Timeout(240)
    void 처리중_강제종료는_재수신하고_중복수신은_완료전에_커밋하지_않는다(EmbeddedKafkaBroker broker) throws Exception {
        String brokers = broker.getBrokersAsString();
        List<Process> workers = new ArrayList<>();
        Path restart = prepare("restart");
        Path concurrent = prepare("concurrent");
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("recordedAt", Instant.now().toString());
        evidence.put("environment", "local embedded Kafka; separate worker JVMs; real Spring/JPA/file H2/Python boundary; no HTTP server or Supabase; external API fixture");
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers));
             KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                     ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                     ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                     ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            Process first = start(restart, brokers, "kafka", 1); workers.add(first);
            Path log1 = restart.resolve("worker-1.log");
            await(() -> Files.exists(restart.resolve("ready")), first, log1);
            String id = Files.readString(restart.resolve("ready"));
            RecordMetadata sent = producer.send(new ProducerRecord<>(KafkaShareJobQueue.TOPIC, 0, id, id)).get(10, TimeUnit.SECONDS);
            await(() -> lines(restart.resolve("attempts")) == 1 && state(restart).path("status").asText().equals("PROCESSING"), first, log1);
            assertThat(offset(admin)).isLessThan(sent.offset() + 1);
            kill(first);
            evidence.put("killedWhileProcessingAndUncommitted", true);

            Process second = start(restart, brokers, "kafka", 2); workers.add(second);
            Path log2 = restart.resolve("worker-2.log");
            await(() -> lines(restart.resolve("attempts")) == 2 && Files.exists(restart.resolve("recovered-state.json")), second, log2);
            JsonNode recovered = MAPPER.readTree(Files.readString(restart.resolve("recovered-state.json")));
            assertThat(recovered.path("jobId").asText()).isEqualTo(id);
            assertThat(recovered.path("status").asText()).isEqualTo("PROCESSING");
            evidence.put("persistedProcessingJobLoaded", true);
            assertThat(offset(admin)).isLessThan(sent.offset() + 1);
            Files.writeString(restart.resolve("release"), "go");
            await(() -> state(restart).path("analysisStatus").asText().equals("DONE") && offset(admin) == sent.offset() + 1, second, log2);
            assertThat(state(restart).path("places").asInt()).isEqualTo(1);
            RecordMetadata again = producer.send(new ProducerRecord<>(KafkaShareJobQueue.TOPIC, 0, id, id)).get(10, TimeUnit.SECONDS);
            await(() -> offset(admin) == again.offset() + 1, second, log2);
            assertThat(lines(restart.resolve("attempts"))).isEqualTo(2);
            assertThat(state(restart).path("places").asInt()).isEqualTo(1);
            evidence.put("restart", Map.of("pipelineAttemptsIncludingKilled", 2, "savedPlaces", 1,
                    "offsetAfterCompletion", sent.offset() + 1, "offsetAfterDoneDuplicate", again.offset() + 1));
            Files.writeString(restart.resolve("stop"), "stop");
            assertThat(second.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.exitValue()).isZero();

            Process third = start(concurrent, brokers, "async-first", 3); workers.add(third);
            Path log3 = concurrent.resolve("worker-3.log");
            await(() -> lines(concurrent.resolve("attempts")) == 1 && Files.exists(concurrent.resolve("ready")), third, log3);
            String duplicateId = Files.readString(concurrent.resolve("ready"));
            RecordMetadata late = producer.send(new ProducerRecord<>(KafkaShareJobQueue.TOPIC, 0, duplicateId, duplicateId)).get(10, TimeUnit.SECONDS);
            await(() -> lines(concurrent.resolve("deliveries")) == 1, third, log3);
            long holdUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < holdUntil) {
                assertThat(offset(admin)).isEqualTo(again.offset() + 1);
                assertThat(lines(concurrent.resolve("attempts"))).isEqualTo(1);
                Thread.sleep(100);
            }
            Files.writeString(concurrent.resolve("release"), "go");
            await(() -> state(concurrent).path("analysisStatus").asText().equals("DONE") && offset(admin) == late.offset() + 1, third, log3);
            assertThat(state(concurrent).path("places").asInt()).isEqualTo(1);
            assertThat(lines(concurrent.resolve("attempts"))).isEqualTo(1);
            evidence.put("concurrent", Map.of("pipelineAttempts", 1, "savedPlaces", 1,
                    "heldOffsetBeforeCompletion", again.offset() + 1, "offsetAfterCompletion", late.offset() + 1));
            Files.writeString(concurrent.resolve("stop"), "stop");
            assertThat(third.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(third.exitValue()).isZero();
            evidence.put("workerJvmsExited", workers.stream().noneMatch(Process::isAlive));
            Path output = Path.of("docs/bench/results/kafka-process-recovery-20261031.json");
            Files.createDirectories(output.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
        } finally {
            for (Process worker : workers) kill(worker);
            cleanFixtureChildren(restart);
            cleanFixtureChildren(concurrent);
        }
    }
}
