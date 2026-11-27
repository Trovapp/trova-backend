package com.trova.backend.service;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.repository.TripDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class DraftProcessingService {
    private final TripDraftRepository drafts;

    public DraftProcessingService(TripDraftRepository drafts) {
        this.drafts = drafts;
    }

    @Transactional
    public Optional<TripDraft> start(Long id) {
        Optional<TripDraft> runnable = drafts.findByIdForUpdate(id).filter(draft ->
                draft.getStatus() == TripDraftStatus.PENDING || draft.getStatus() == TripDraftStatus.PROCESSING);
        runnable.ifPresent(TripDraft::markProcessing);
        return runnable;
    }

    /** 결과 저장과 원본 닫기를 함께 커밋한다. 정리기가 먼저 실패 처리했으면 둘 다 건너뛴다. */
    @Transactional
    public boolean finish(Long id, TripDraft result) {
        if (drafts.finishIfProcessing(id, result) == 0) {
            return false;
        }
        if (result.getStatus() == TripDraftStatus.READY && result.getReplacesDraftId() != null) {
            drafts.dismissSourceIfReplacementVisible(id, LocalDateTime.now());
        }
        return true;
    }
}
