package com.fryfrog.hub.common.repository;

import com.fryfrog.hub.common.model.MediaSeriesCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MediaSeriesCharacterRepository extends JpaRepository<MediaSeriesCharacter, Long> {

    List<MediaSeriesCharacter> findBySeries_Id(Long seriesId);

    void deleteBySeries_Id(Long seriesId);
}
