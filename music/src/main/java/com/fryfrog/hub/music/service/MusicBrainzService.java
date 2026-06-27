package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
@Slf4j
public class MusicBrainzService {

    private static final String BASE_URL = "https://musicbrainz.org/ws/2";
    private static final String COVER_ART_URL = "https://coverartarchive.org";
    private static final String USER_AGENT = "FryfrogHub/1.0 ( https://github.com/xbwsh/fryfrog-hub-api )";

    private final RestTemplate restTemplate;

    public MusicBrainzService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, String> searchRecording(String artist, String title, String album) {
        String query = String.format("recording:\"%s\" AND artist:\"%s\"", title, artist);
        if (album != null && !album.isBlank()) {
            query += String.format(" AND release:\"%s\"", album);
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/recording")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", "5")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> recordings = (List<Map>) response.getBody().get("recordings");
                if (recordings != null && !recordings.isEmpty()) {
                    Map recording = recordings.get(0);
                    Map<String, String> result = new HashMap<>();
                    result.put("id", (String) recording.get("id"));
                    result.put("title", (String) recording.get("title"));

                    List<Map> releases = (List<Map>) recording.get("releases");
                    if (releases != null && !releases.isEmpty()) {
                        Map release = releases.get(0);
                        result.put("releaseId", (String) release.get("id"));
                        result.put("releaseDate", (String) release.get("date"));

                        Map releaseGroup = (Map) release.get("release-group");
                        if (releaseGroup != null) {
                            List<Map> labels = (List<Map>) releaseGroup.get("label-info");
                            if (labels != null && !labels.isEmpty()) {
                                Map labelInfo = labels.get(0);
                                Map label = (Map) labelInfo.get("label");
                                if (label != null) {
                                    result.put("label", (String) label.get("name"));
                                    result.put("catalogNumber", (String) labelInfo.get("catalog-number"));
                                }
                            }

                            String releaseGroupId = (String) releaseGroup.get("id");
                            if (releaseGroupId != null) {
                                String genre = getReleaseGroupTags(releaseGroupId);
                                if (genre != null) {
                                    result.put("genre", genre);
                                }
                            }
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("MusicBrainz search failed: {}", e.getMessage());
        }
        return null;
    }

    private String getReleaseGroupTags(String releaseGroupId) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/release-group/" + releaseGroupId)
                .queryParam("fmt", "json")
                .queryParam("inc", "tags")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> tags = (List<Map>) response.getBody().get("tags");
                if (tags != null && !tags.isEmpty()) {
                    StringBuilder genreBuilder = new StringBuilder();
                    for (Map tag : tags) {
                        String tagName = (String) tag.get("name");
                        if (tagName != null && !tagName.isBlank()) {
                            if (!genreBuilder.isEmpty()) {
                                genreBuilder.append(", ");
                            }
                            genreBuilder.append(tagName);
                        }
                    }
                    if (!genreBuilder.isEmpty()) {
                        return genreBuilder.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get release-group tags: {}", e.getMessage());
        }
        return null;
    }

    public String getCoverArtUrl(String releaseId) {
        String url = COVER_ART_URL + "/release/" + releaseId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> images = (List<Map>) response.getBody().get("images");
                if (images != null && !images.isEmpty()) {
                    for (Map image : images) {
                        String type = (String) image.get("types");
                        if ("Front".equals(type)) {
                            return (String) image.get("image");
                        }
                    }
                    return (String) images.get(0).get("image");
                }
            }
        } catch (Exception e) {
            log.debug("Cover Art Archive lookup failed for release {}: {}", releaseId, e.getMessage());
        }
        return null;
    }

    public Map<String, String> getArtistInfo(String artistName) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/artist")
                .queryParam("query", artistName)
                .queryParam("fmt", "json")
                .queryParam("limit", "1")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> artists = (List<Map>) response.getBody().get("artists");
                if (artists != null && !artists.isEmpty()) {
                    Map artist = artists.get(0);
                    Map<String, String> result = new HashMap<>();
                    result.put("id", (String) artist.get("id"));
                    result.put("name", (String) artist.get("name"));

                    String type = (String) artist.get("type");
                    if ("Person".equals(type)) {
                        result.put("type", "person");
                    } else {
                        result.put("type", "group");
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("MusicBrainz artist search failed: {}", e.getMessage());
        }
        return null;
    }

    public String getArtistImageUrl(String artistName) {
        Map<String, String> artistInfo = getArtistInfo(artistName);
        if (artistInfo == null) {
            return null;
        }

        String artistId = artistInfo.get("id");
        if (artistId == null) {
            return null;
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/artist/" + artistId)
                .queryParam("fmt", "json")
                .queryParam("inc", "url-rels")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> relations = (List<Map>) response.getBody().get("relations");
                if (relations != null) {
                    for (Map relation : relations) {
                        String type = (String) relation.get("type");
                        Map urlObj = (Map) relation.get("url");
                        if (urlObj != null) {
                            String resource = (String) urlObj.get("resource");
                            if (resource != null && resource.contains("commons.wikimedia.org/wiki/File:")) {
                                String filename = resource.substring(resource.lastIndexOf("/File:") + 6);
                                return getWikimediaImageUrl(filename);
                            }
                        }
                    }

                    for (Map relation : relations) {
                        Map urlObj = (Map) relation.get("url");
                        if (urlObj != null) {
                            String resource = (String) urlObj.get("resource");
                            if (resource != null && resource.contains("wikidata.org/wiki/")) {
                                String wikidataId = resource.substring(resource.lastIndexOf('/') + 1);
                                String imageUrl = getWikidataImage(wikidataId);
                                if (imageUrl != null) {
                                    return imageUrl;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get artist image URL from MusicBrainz: {}", e.getMessage());
        }
        return null;
    }

    private String getWikidataImage(String wikidataId) {
        String url = "https://www.wikidata.org/w/api.php?action=wbgetclaims&entity=" + wikidataId + "&property=P18&format=json";

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map claims = (Map) response.getBody().get("claims");
                if (claims != null) {
                    List<Map> p18Claims = (List<Map>) claims.get("P18");
                    if (p18Claims != null && !p18Claims.isEmpty()) {
                        Map claim = p18Claims.get(0);
                        Map mainsnak = (Map) claim.get("mainsnak");
                        if (mainsnak != null) {
                            Map datavalue = (Map) mainsnak.get("datavalue");
                            if (datavalue != null) {
                                String filename = (String) datavalue.get("value");
                                if (filename != null) {
                                    return getWikimediaImageUrl(filename);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get Wikidata image: {}", e.getMessage());
        }
        return null;
    }

    private String getWikimediaImageUrl(String filename) {
        String encodedFilename = filename.replace(" ", "_");
        String url = "https://commons.wikimedia.org/w/api.php?action=query&titles=File:" + encodedFilename + "&prop=imageinfo&iiprop=url&format=json";

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map query = (Map) response.getBody().get("query");
                if (query != null) {
                    Map pages = (Map) query.get("pages");
                    if (pages != null && !pages.isEmpty()) {
                        for (Object pageObj : pages.values()) {
                            Map page = (Map) pageObj;
                            List<Map> imageinfo = (List<Map>) page.get("imageinfo");
                            if (imageinfo != null && !imageinfo.isEmpty()) {
                                Map info = imageinfo.get(0);
                                return (String) info.get("url");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Wikimedia API failed, using fallback URL: {}", e.getMessage());
        }

        return "https://commons.wikimedia.org/wiki/Special:FilePath/" + encodedFilename + "?width=400";
    }

    public void enrichTrack(MusicTrack track) {
        if (track.getArtist() == null || track.getTitle() == null) {
            return;
        }

        try {
            Map<String, String> recording = searchRecording(
                    track.getArtist(), track.getTitle(), track.getAlbum());
            if (recording != null) {
                track.setMusicBrainzId(recording.get("id"));
                if (track.getLabel() == null || track.getLabel().isBlank()) {
                    track.setLabel(recording.get("label"));
                }
                if (track.getCatalogNumber() == null || track.getCatalogNumber().isBlank()) {
                    track.setCatalogNumber(recording.get("catalogNumber"));
                }
                if (track.getReleaseDate() == null || track.getReleaseDate().isBlank()) {
                    track.setReleaseDate(recording.get("releaseDate"));
                }
                if (track.getGenre() == null || track.getGenre().isBlank()) {
                    track.setGenre(recording.get("genre"));
                }

                String releaseId = recording.get("releaseId");
                if (releaseId != null && (track.getCoverArtPath() == null || track.getCoverArtPath().isBlank())) {
                    String coverUrl = getCoverArtUrl(releaseId);
                    if (coverUrl != null) {
                        track.setCoverSource("musicbrainz");
                    }
                }
            }

            if (track.getArtistImage() == null || track.getArtistImage().isBlank()) {
                String artistImageUrl = getArtistImageUrl(track.getArtist());
                if (artistImageUrl != null) {
                    track.setArtistImage(artistImageUrl);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich track {} - {}: {}", track.getArtist(), track.getTitle(), e.getMessage());
        }
    }
}
