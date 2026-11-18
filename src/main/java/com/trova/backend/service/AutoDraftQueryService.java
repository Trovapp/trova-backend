package com.trova.backend.service;

import com.trova.backend.controller.TripDraftController.AutoDraftResponse;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.repository.AutoDraftJobRow;
import com.trova.backend.repository.AutoDraftRow;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AutoDraftQueryService {
    private static final List<TripDraftStatus> VISIBLE =
            List.of(TripDraftStatus.PENDING, TripDraftStatus.PROCESSING, TripDraftStatus.READY);

    private final TripDraftRepository drafts;
    private final ProcessingJobRepository jobs;
    private final TripPlaceRepository tripPlaces;

    public AutoDraftQueryService(TripDraftRepository drafts, ProcessingJobRepository jobs,
                                 TripPlaceRepository tripPlaces) {
        this.drafts = drafts;
        this.jobs = jobs;
        this.tripPlaces = tripPlaces;
    }

    // 값만 읽어 지연 로딩이 없다. 요청 간 캐시·추가 읽기 트랜잭션 없이 최신 커밋을 읽는다(ADR 0034·0036).
    public List<AutoDraftResponse> list(Long userId) {
        List<AutoDraftRow> listed = drafts.findListedRowsByUserId(userId, VISIBLE);
        if (listed.isEmpty()) return List.of();

        Map<Long, AutoDraftJobRow> byId = new HashMap<>();
        Map<Long, String> keys = new HashMap<>();
        Map<String, AutoDraftJobRow> latest = new HashMap<>();
        for (AutoDraftJobRow job : jobs.findAutoDraftJobsByUserId(userId)) {
            String key = VideoKey.of(job.sourceUrl());
            byId.put(job.id(), job);
            keys.put(job.id(), key);
            if (job.status() == JobStatus.DONE) {
                latest.merge(key, job, (a, b) -> a.id() > b.id() ? a : b);
            }
        }
        boolean hasJob = listed.stream().anyMatch(d -> byId.containsKey(d.firstJobId()));
        Set<String> tripped = hasJob ? tripPlaces.findSourceUrlsInTripsOfUserId(userId).stream()
                .map(VideoKey::of).collect(Collectors.toSet()) : Set.of();

        List<AutoDraftResponse> result = new ArrayList<>();
        for (AutoDraftRow draft : listed) {
            Long jobId = draft.firstJobId();
            AutoDraftJobRow original = byId.get(jobId);
            String key = keys.get(jobId);
            if (original != null && tripped.contains(key)) continue;
            AutoDraftJobRow shown = original == null ? null : latest.getOrDefault(key, original);
            result.add(new AutoDraftResponse(draft.id(), draft.status().name(),
                    shown == null ? jobId : shown.id(), shown == null ? null : shown.title(),
                    draft.days(), draft.createdAt().toString(), placeCount(draft)));
            // 여행으로 숨길 초안을 먼저 빼고 10개를 고른다. JSON 파싱도 표시할 카드에만 한다.
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private static Integer placeCount(AutoDraftRow draft) {
        if (draft.status() != TripDraftStatus.READY || draft.draftJson() == null) return null;
        try {
            return DraftGenerator.fromJson(draft.draftJson()).days().stream()
                    .mapToInt(day -> day.items().size()).sum();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
