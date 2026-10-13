package com.trova.backend.admin.action;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.repository.TripDraftRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 자동으로 만든 초안을 사용자 화면에서 숨긴다(닫은 초안은 행을 남겨 같은 영상의 중복 생성을 막는다). */
@Component
public class HideDraftAction implements AdminActionHandler {

    private final TripDraftRepository tripDraftRepository;

    public HideDraftAction(TripDraftRepository tripDraftRepository) {
        this.tripDraftRepository = tripDraftRepository;
    }

    @Override
    public AdminAction action() {
        return AdminAction.HIDE_DRAFT;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        TripDraft draft = hideableDraft(targetId);
        return new ActionPreview(action(), draft.getId(), draft.getUser().getId(),
                "자동 초안 #" + draft.getId() + "(" + draft.getStatus() + ")", null);
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        TripDraft draft = hideableDraft(targetId);
        draft.dismiss();
        tripDraftRepository.save(draft);
        return "초안 #" + draft.getId() + " 숨김";
    }

    private TripDraft hideableDraft(Long targetId) {
        TripDraft draft = tripDraftRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (!draft.isAutoCreated()) {
            throw AdminActionException.conflict("자동으로 만든 초안만 숨길 수 있어요.");
        }
        if (draft.getDismissedAt() != null) {
            throw AdminActionException.conflict("이미 숨긴 초안이에요.");
        }
        return draft;
    }
}
