package com.fryfrog.hub.video.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.video.dto.ActorDetailDTO;
import com.fryfrog.hub.video.dto.TmdbPersonDetail;
import com.fryfrog.hub.video.model.ActorProfile;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.repository.ActorProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 演员详情服务：本地库缓存 + TMDB 兜底拉取。
 * 首次访问从 TMDB 拉取人物资料落库，过期后刷新；TMDB 不可用时降级返回旧缓存或仅本地信息。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActorProfileService {

    private final ActorProfileRepository repository;
    private final TmdbService tmdbService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 缓存有效期（小时），默认 168（7 天） */
    @Value("${actor-profile.ttl-hours:168}")
    private long ttlHours;

    public ActorDetailDTO getActorDetail(VideoActor actor) {
        ActorProfile cached = repository.findByActorId(actor.getId()).orElse(null);
        if (isFresh(cached)) {
            return fromProfile(cached);
        }

        if (actor.getSourceActorId() != null) {
            try {
                TmdbPersonDetail person = tmdbService.getPersonDetail(actor.getSourceActorId());
                if (person != null) {
                    ActorDetailDTO dto = buildFromPerson(actor, person);
                    saveProfile(actor.getId(), actor.getSourceActorId(), dto);
                    return dto;
                }
            } catch (Exception e) {
                log.warn("[ActorProfile] Failed to fetch person {}: {}", actor.getSourceActorId(), e.getMessage());
            }
        }

        // TMDB 无数据/失败：有旧缓存则降级返回，否则仅本地信息
        if (cached != null) {
            return fromProfile(cached);
        }
        return localOnly(actor);
    }

    /** 手动触发时强制刷新（供 refresh 接口复用） */
    public ActorDetailDTO refreshActorDetail(VideoActor actor) {
        repository.deleteByActorId(actor.getId());
        repository.flush();
        return getActorDetail(actor);
    }

    private boolean isFresh(ActorProfile p) {
        return p != null && p.getFetchedAt() != null
                && Duration.between(p.getFetchedAt(), LocalDateTime.now()).toHours() < ttlHours;
    }

    private void saveProfile(Long actorId, Long tmdbId, ActorDetailDTO dto) {
        try {
            ActorProfile profile = repository.findByActorId(actorId).orElse(new ActorProfile());
            profile.setActorId(actorId);
            profile.setTmdbId(tmdbId);
            profile.setName(dto.getName());
            profile.setBiography(dto.getBiography());
            profile.setAlsoKnownAsJson(toJson(dto.getAlsoKnownAs()));
            profile.setBirthday(dto.getBirthday());
            profile.setDeathday(dto.getDeathday());
            profile.setGender(dto.getGender());
            profile.setPlaceOfBirth(dto.getPlaceOfBirth());
            profile.setHomepage(dto.getHomepage());
            profile.setImdbId(dto.getImdbId());
            profile.setKnownForDepartment(dto.getKnownForDepartment());
            profile.setPopularity(dto.getPopularity());
            profile.setCastJson(toJson(dto.getCredits() != null ? dto.getCredits().getCast() : List.of()));
            profile.setCrewJson(toJson(dto.getCredits() != null ? dto.getCredits().getCrew() : List.of()));
            profile.setFetchedAt(LocalDateTime.now());
            repository.save(profile);
        } catch (Exception e) {
            log.warn("[ActorProfile] Failed to save profile for actor {}: {}", actorId, e.getMessage());
        }
    }

    private ActorDetailDTO buildFromPerson(VideoActor actor, TmdbPersonDetail person) {
        ActorDetailDTO dto = new ActorDetailDTO();
        dto.setId(actor.getId());
        dto.setName(actor.getName());
        dto.setImageUrl(actor.getActorImageUrl());
        dto.setTmdbId(person.getId());
        dto.setBiography(person.getBiography());
        dto.setAlsoKnownAs(person.getAlsoKnownAs());
        dto.setBirthday(person.getBirthday());
        dto.setDeathday(person.getDeathday());
        dto.setGender(person.getGender());
        dto.setGenderLabel(genderLabel(person.getGender()));
        dto.setPlaceOfBirth(person.getPlaceOfBirth());
        dto.setHomepage(person.getHomepage());
        dto.setImdbId(person.getImdbId());
        dto.setKnownForDepartment(person.getKnownForDepartment());
        dto.setPopularity(person.getPopularity());

        List<ActorDetailDTO.Credit> cast = toCreditDtos(
                person.getCombinedCredits() != null ? person.getCombinedCredits().getCast() : List.of());
        List<ActorDetailDTO.Credit> crew = toCreditDtos(
                person.getCombinedCredits() != null ? person.getCombinedCredits().getCrew() : List.of());
        dto.setCastCount(cast.size());
        dto.setCrewCount(crew.size());
        dto.setTotalCredits(cast.size() + crew.size());
        dto.setKnownFor(knownFor(cast, crew));

        ActorDetailDTO.CreditList creditList = new ActorDetailDTO.CreditList();
        creditList.setCast(cast);
        creditList.setCrew(crew);
        dto.setCredits(creditList);
        return dto;
    }

    private ActorDetailDTO fromProfile(ActorProfile p) {
        ActorDetailDTO dto = new ActorDetailDTO();
        dto.setId(p.getActorId());
        dto.setName(p.getName());
        dto.setTmdbId(p.getTmdbId());
        dto.setBiography(p.getBiography());
        dto.setAlsoKnownAs(fromJson(p.getAlsoKnownAsJson(), new TypeReference<List<String>>() {}));
        dto.setBirthday(p.getBirthday());
        dto.setDeathday(p.getDeathday());
        dto.setGender(p.getGender());
        dto.setGenderLabel(genderLabel(p.getGender()));
        dto.setPlaceOfBirth(p.getPlaceOfBirth());
        dto.setHomepage(p.getHomepage());
        dto.setImdbId(p.getImdbId());
        dto.setKnownForDepartment(p.getKnownForDepartment());
        dto.setPopularity(p.getPopularity());

        List<ActorDetailDTO.Credit> cast = fromJson(p.getCastJson(), new TypeReference<List<ActorDetailDTO.Credit>>() {});
        List<ActorDetailDTO.Credit> crew = fromJson(p.getCrewJson(), new TypeReference<List<ActorDetailDTO.Credit>>() {});
        dto.setCastCount(cast.size());
        dto.setCrewCount(crew.size());
        dto.setTotalCredits(cast.size() + crew.size());
        dto.setKnownFor(knownFor(cast, crew));

        ActorDetailDTO.CreditList creditList = new ActorDetailDTO.CreditList();
        creditList.setCast(cast);
        creditList.setCrew(crew);
        dto.setCredits(creditList);

        // 缓存中无本地演员ID对应的签名头像；用 video_actors 的 ID 重新签名
        // （imageUrl 由 controller 层在本地 actor 上生成，这里通过外层补填）
        return dto;
    }

    private ActorDetailDTO localOnly(VideoActor actor) {
        ActorDetailDTO dto = new ActorDetailDTO();
        dto.setId(actor.getId());
        dto.setName(actor.getName());
        dto.setImageUrl(actor.getActorImageUrl());
        dto.setTmdbId(actor.getSourceActorId());
        dto.setGender(0);
        dto.setGenderLabel(genderLabel(0));
        dto.setCredits(new ActorDetailDTO.CreditList());
        dto.setKnownFor(List.of());
        return dto;
    }

    /** 知名作品：cast+crew 合并按票数降序去重取前 10 */
    private List<ActorDetailDTO.Credit> knownFor(List<ActorDetailDTO.Credit> cast, List<ActorDetailDTO.Credit> crew) {
        List<ActorDetailDTO.Credit> all = new ArrayList<>(cast);
        all.addAll(crew);
        List<ActorDetailDTO.Credit> knownFor = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        all.stream()
                .sorted(Comparator.comparing(ActorDetailDTO.Credit::getVoteCount,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(c -> {
                    if (seen.add(c.getId())) {
                        knownFor.add(c);
                    }
                });
        return knownFor.subList(0, Math.min(10, knownFor.size()));
    }

    private List<ActorDetailDTO.Credit> toCreditDtos(List<TmdbPersonDetail.Credit> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().map(c -> {
            ActorDetailDTO.Credit dto = new ActorDetailDTO.Credit();
            dto.setId(c.getId());
            dto.setMediaType(c.getMediaType());
            dto.setTitle(c.getDisplayTitle());
            dto.setOriginalTitle(c.getDisplayOriginalTitle());
            dto.setCharacter(c.getCharacter());
            dto.setJob(c.getJob());
            dto.setDepartment(c.getDepartment());
            dto.setReleaseDate(c.getDisplayDate());
            dto.setYear(c.getYear());
            dto.setPosterUrl(tmdbService.buildProxyImageUrl(c.getPosterPath(), "w500"));
            dto.setOverview(c.getOverview());
            dto.setVoteAverage(c.getVoteAverage());
            dto.setVoteCount(c.getVoteCount());
            dto.setEpisodeCount(c.getEpisodeCount());
            dto.setAdult(c.getAdult());
            return dto;
        }).toList();
    }

    private String genderLabel(Integer gender) {
        return switch (gender == null ? 0 : gender) {
            case 1 -> "女";
            case 2 -> "男";
            case 3 -> "非二元";
            default -> "未设置";
        };
    }

    private String toJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[ActorProfile] Failed to serialize: {}", e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return objectMapper.convertValue(List.of(), type);
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[ActorProfile] Failed to deserialize: {}", e.getMessage());
            return objectMapper.convertValue(List.of(), type);
        }
    }
}