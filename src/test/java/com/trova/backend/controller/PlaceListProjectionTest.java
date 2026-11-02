package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.SavedPlaceListRow;
import com.trova.backend.repository.SavedPlaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PlaceListProjectionTest {
    @Autowired private SavedPlaceRepository repository;
    @Autowired private EntityManager em;
    @Autowired private EntityManagerFactory emf;

    @Test
    void 이전_조회와_모든_응답필드와_대표선택과_순서가_같고_엔티티는_로딩하지_않는다() {
        User user = new User("google", "projection-owner", "Owner", null);
        User other = new User("google", "projection-other", "Other", null);
        em.persist(user);
        em.persist(other);
        save(user, "https://youtu.be/same?si=old", "old", false);
        SavedPlace latest = save(user, "https://www.youtube.com/watch?v=same", "new", true);
        SavedPlace second = new SavedPlace(latest.getProcessingJob(), user, "second", null, "cafe", null, null);
        em.persist(second);
        save(user, "legacy-invalid-url", "legacy", false);
        save(user, "https://www.instagram.com/reel/abc/?utm_source=x", "insta", true);
        save(other, "https://youtu.be/same", "not mine", true);
        // 장소가 없는 최신 작업은 기존 결과를 가리지 않는다.
        em.persist(new ProcessingJob(user, "https://youtu.be/same", SourcePlatform.YOUTUBE));
        em.flush();
        // 같은 생성 시각에도 id 내림차순이 유지돼야 한다.
        em.createQuery("update SavedPlace p set p.createdAt = :at")
                .setParameter("at", java.time.LocalDateTime.of(2026, 1, 1, 0, 0)).executeUpdate();
        em.clear();
        List<SavedPlace> entities = repository.findByUserOrderByCreatedAtDescIdDesc(user);
        Map<String, Long> latestIds = VideoResults.latestJobIdByVideo(entities);
        List<PlacesController.PlaceResponse> before = entities.stream()
                .filter(p -> p.getProcessingJob().getId().equals(latestIds.get(ShareUrl.videoKey(p.getProcessingJob().getSourceUrl()))))
                .map(PlacesController.PlaceResponse::from).toList();
        em.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        List<SavedPlaceListRow> rows = repository.findListRowsByUserId(user.getId());
        List<PlacesController.PlaceResponse> after = VideoResults.latestRows(rows).stream()
                .map(PlacesController.PlaceResponse::from).toList();
        assertThat(after).containsExactlyElementsOf(before);
        assertThat(after).extracting(PlacesController.PlaceResponse::placeName)
                .containsExactly("insta", "legacy", "second", "new");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(repository.findListRowsByUserId(-1L)).isEmpty();
    }

    private SavedPlace save(User user, String url, String name, boolean enriched) {
        ProcessingJob job = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        job.setTitle("title " + name);
        em.persist(job);
        SavedPlace place = new SavedPlace(job, user, name, "region", "cafe", 37.0, 127.0,
                1, 2, "phone", "address", "road", "category", "https://place.test");
        if (enriched) {
            place.applyVideoNotes(List.of("note one", "note two"));
            place.applyOpeningHours("unused", "unused opening data");
        }
        em.persist(place);
        return place;
    }
}
