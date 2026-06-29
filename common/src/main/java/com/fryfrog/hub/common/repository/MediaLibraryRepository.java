package com.fryfrog.hub.common.repository;

import com.fryfrog.hub.common.model.MediaLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MediaLibraryRepository extends JpaRepository<MediaLibrary, Long> {

    List<MediaLibrary> findAllByOrderBySortOrderAsc();

    List<MediaLibrary> findByEnabledTrueOrderBySortOrderAsc();

    List<MediaLibrary> findByType(String type);
}
