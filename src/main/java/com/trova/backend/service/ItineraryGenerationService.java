package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.pipeline.ItineraryAssignment;
import com.trova.backend.pipeline.ItineraryPipelineRunner;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ItineraryGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryGenerationService.class);

    private final ProcessingJobLifecycleService lifecycleService;
    private final ItineraryPipelineRunner itineraryPipelineRunner;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public ItineraryGenerationService(
            ProcessingJobLifecycleService lifecycleService,
            ItineraryPipelineRunner itineraryPipelineRunner,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository
    ) {
        this.lifecycleService = lifecycleService;
        this.itineraryPipelineRunner = itineraryPipelineRunner;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Async("pipelineTaskExecutor")
    public void generate(Long jobId) {
        try {
            lifecycleService.markProcessing(jobId);
            ProcessingJob job = processingJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
            List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);

            List<ItineraryAssignment> assignments = itineraryPipelineRunner.run(places, jobId);
            lifecycleService.applyItinerary(jobId, assignments);

            lifecycleService.markDone(jobId);
            log.info("ProcessingJob {} 일정 생성 완료: {}개 장소", jobId, assignments.size());
        } catch (Exception e) {
            log.error("ProcessingJob {} 일정 생성 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }
}
