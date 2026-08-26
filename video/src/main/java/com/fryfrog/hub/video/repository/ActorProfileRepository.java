package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.ActorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActorProfileRepository extends JpaRepository<ActorProfile, Long> {

    Optional<ActorProfile> findByActorId(Long actorId);

    void deleteByActorId(Long actorId);
}