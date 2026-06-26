package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.VideoActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VideoActorRepository extends JpaRepository<VideoActor, Long> {

    List<VideoActor> findByVideoId(Long videoId);

    void deleteByVideoId(Long videoId);
}
