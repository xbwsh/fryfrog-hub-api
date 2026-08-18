package com.fryfrog.hub.music.subsonic;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static com.fryfrog.hub.music.subsonic.SubsonicModel.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class SubsonicRendererTest {

    private final SubsonicRenderer renderer = new SubsonicRenderer();

    @Test
    void rendersJsonEnvelope() {
        Envelope env = okEnvelope();
        String json = renderer.render(env, "json", null);
        assertThat(json).contains("\"subsonic-response\"");
        assertThat(json).contains("\"status\":\"ok\"");
        assertThat(json).contains("\"artist\"");
        assertThat(json).contains("周杰伦");
    }

    @Test
    void rendersXmlEnvelope() {
        Envelope env = okEnvelope();
        String xml = renderer.render(env, "xml", null);
        assertThat(xml).contains("<subsonic-response");
        assertThat(xml).contains("status=\"ok\"");
        assertThat(xml).contains("<artist id=\"ar-1\" name=\"周杰伦\"");
        assertThat(xml).contains("</subsonic-response>");
    }

    @Test
    void rendersJsonp() {
        Envelope env = okEnvelope();
        String jsonp = renderer.render(env, "jsonp", "myCallback");
        assertThat(jsonp).startsWith("myCallback(");
        assertThat(jsonp).endsWith(");");
    }

    private Envelope okEnvelope() {
        Envelope env = new Envelope();
        env.status = "ok";
        env.version = "1.16.1";
        env.type = "fryfrog-hub";
        env.serverVersion = "0.1.0";
        env.openSubsonic = true;

        Artist artist = new Artist();
        artist.id = "ar-1";
        artist.name = "周杰伦";
        artist.albumCount = 2;

        Album album = new Album();
        album.id = "al-1";
        album.name = "七里香";
        album.artist = "周杰伦";
        album.songCount = 1;

        Child song = new Child();
        song.id = "tr-1";
        song.title = "七里香";
        song.artist = "周杰伦";
        song.album = "七里香";
        song.isDir = false;
        song.duration = 247;

        artist.album = java.util.List.of(album);
        album.song = java.util.List.of(song);
        env.artist = artist;
        return env;
    }
}