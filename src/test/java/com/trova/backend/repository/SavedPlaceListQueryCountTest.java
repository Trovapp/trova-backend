package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 영상 장소 목록(GET /api/places)이 처리 작업 수와 무관하게 일정한 쿼리로 끝나는지(#29). */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class SavedPlaceListQueryCountTest {

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 처리_작업이_여러_개여도_목록은_한_번의_쿼리로_가져온다() {
        User user = new User("google", "n-plus-one", "쿼리유저", null);
        entityManager.persist(user);
        for (int i = 0; i < 5; i++) {
            ProcessingJob job = new ProcessingJob(user, "https://youtu.be/n1-" + i, SourcePlatform.YOUTUBE);
            entityManager.persist(job);
            entityManager.persist(new SavedPlace(job, user, "장소" + i, null, "cafe", 35.0, 129.0));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<SavedPlace> places = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user);
        // 응답(PlaceResponse)이 읽는 연관 필드까지 접근한다.
        places.forEach(place -> place.getProcessingJob().getSourceUrl());

        assertThat(places).hasSize(5);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
