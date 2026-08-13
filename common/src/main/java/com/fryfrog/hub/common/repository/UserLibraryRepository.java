package com.fryfrog.hub.common.repository;

import com.fryfrog.hub.common.model.UserLibrary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface UserLibraryRepository extends JpaRepository<UserLibrary, Long> {

    List<UserLibrary> findByUserId(Long userId);

    List<UserLibrary> findByLibraryIdIn(Collection<Long> libraryIds);

    void deleteByUserIdAndLibraryId(Long userId, Long libraryId);
}