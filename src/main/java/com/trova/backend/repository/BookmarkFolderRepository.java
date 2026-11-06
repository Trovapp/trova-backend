package com.trova.backend.repository;

import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkFolderRepository extends JpaRepository<BookmarkFolder, Long> {
    void deleteByUser(User user);

    List<BookmarkFolder> findByUserOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    // 폴더의 사용자(EAGER)를 따로 읽지 않게 같이 읽는다(#335).
    @EntityGraph(attributePaths = "user")
    List<BookmarkFolder> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<BookmarkFolder> findByIdAndUser(Long id, User user);
}
