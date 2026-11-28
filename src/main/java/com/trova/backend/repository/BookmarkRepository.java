package com.trova.backend.repository;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {
    void deleteByUser(User user);

    List<Bookmark> findByUserOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    // 장소·폴더·사용자를 한 쿼리로 같이 읽는다 — 찜마다 장소·폴더를 따로 읽던 N+1을 없앤다(#335).
    @ListQueryTimeout
    @EntityGraph(attributePaths = {"place", "folder", "user"})
    List<Bookmark> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Bookmark> findByUserAndPlace(User user, Place place);
    long countByFolder(BookmarkFolder folder);

    /** 폴더별 찜 개수를 한 쿼리로 센다(#335). 찜이 없는 폴더는 결과에 없다. */
    @Query("select b.folder.id as folderId, count(b) as bookmarkCount from Bookmark b"
            + " where b.folder.id in :folderIds group by b.folder.id")
    List<FolderBookmarkCount> countByFolderIds(@Param("folderIds") List<Long> folderIds);

    interface FolderBookmarkCount {
        Long getFolderId();

        long getBookmarkCount();
    }
    List<Bookmark> findByFolder(BookmarkFolder folder);
}
