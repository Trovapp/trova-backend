package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlaceExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
public class SharesController {

    private static final Set<String> YOUTUBE_HOSTS =
            Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
                    "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> INSTAGRAM_HOSTS =
            Set.of("instagram.com", "www.instagram.com", "m.instagram.com");

    private final CurrentUserService currentUserService;
    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;

    public SharesController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            PlaceExtractionService placeExtractionService
    ) {
        this.currentUserService = currentUserService;
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
    }

    public record CreateShareRequest(String url) {
    }

    public record ShareResponse(Long jobId, String status) {
    }

    public record ErrorResponse(String message) {
    }

    @PostMapping("/api/shares")
    public ResponseEntity<?> create(
            Authentication authentication,
            @RequestBody CreateShareRequest request
    ) {
        String url = request == null ? null : request.url();
        SourcePlatform platform = resolvePlatform(url);
        if (platform == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("지원하지 않는 URL입니다. 유튜브 또는 인스타그램 링크만 등록할 수 있습니다."));
        }

        User user = currentUserService.resolve(authentication);

        // 같은 URL이 이미 처리 대기/진행 중이면 새 job을 또 만들지 않는다 — 중복 제출로
        // Gemini/카카오 호출이 두 번 나가는 걸 막기 위함. Trova는 단일 인스턴스라 분산 락
        // 없이 이 조회-후-생성만으로 충분하지만, 두 요청이 정말 동시에 도착하는 극히 드문
        // 경우까지 완벽히 막지는 못한다(체크와 저장 사이 짧은 틈은 남아있음).
        List<ProcessingJob> inFlight = processingJobRepository.findByUserAndSourceUrlAndStatusIn(
                user, url, List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (!inFlight.isEmpty()) {
            ProcessingJob existing = inFlight.get(0);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ShareResponse(existing.getId(), existing.getStatus().name()));
        }

        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, platform));
        placeExtractionService.process(job.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ShareResponse(job.getId(), job.getStatus().name()));
    }

    /**
     * 허용된 호스트의 http(s) URL이면 해당 플랫폼을, 아니면 null을 반환한다.
     * (yt-dlp argv 주입 및 내부망 SSRF 방지를 위한 화이트리스트 검증)
     */
    private SourcePlatform resolvePlatform(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }

        if (YOUTUBE_HOSTS.contains(normalizedHost)) {
            return SourcePlatform.YOUTUBE;
        }
        if (INSTAGRAM_HOSTS.contains(normalizedHost)) {
            return SourcePlatform.INSTAGRAM;
        }
        return null;
    }
}
