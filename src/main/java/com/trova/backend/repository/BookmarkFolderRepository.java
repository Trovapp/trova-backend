package com.trova.backend.repository;

import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkFolderRepository extends JpaRepository<BookmarkFolder, Long> {
    List<BookmarkFolder> findByUserOrderByCreatedAtDesc(User user);
    Optional<BookmarkFolder> findByIdAndUser(Long id, User user);
}
