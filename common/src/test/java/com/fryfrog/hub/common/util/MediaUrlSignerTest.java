package com.fryfrog.hub.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class MediaUrlSignerTest {

    private static final String PATH = "/api/v1/video/1/cover";

    @Test
    void sign_appendsExpAndSig() {
        String signed = MediaUrlSigner.sign(PATH);

        assertThat(signed).startsWith(PATH + "?exp=");
        assertThat(signed).contains("&sig=");
        assertThat(MediaUrlSigner.verify(PATH, Long.parseLong(signed.substring(signed.indexOf("exp=") + 4, signed.indexOf("&sig="))),
                signed.substring(signed.indexOf("sig=") + 4))).isTrue();
    }

    @Test
    void sign_thenVerify() {
        long exp = System.currentTimeMillis() + 60000;
        String signed = MediaUrlSigner.sign(PATH, exp);
        String sig = signed.substring(signed.indexOf("sig=") + 4);

        assertThat(MediaUrlSigner.verify(PATH, exp, sig)).isTrue();
    }

    @Test
    void verify_rejectsTamperedPath() {
        long exp = System.currentTimeMillis() + 60000;
        String sig = MediaUrlSigner.sign(PATH, exp);
        sig = sig.substring(sig.indexOf("sig=") + 4);

        assertThat(MediaUrlSigner.verify("/api/v1/video/2/cover", exp, sig)).isFalse();
    }

    @Test
    void verify_rejectsWrongSignature() {
        assertThat(MediaUrlSigner.verify(PATH, System.currentTimeMillis() + 60000, "deadbeef")).isFalse();
        assertThat(MediaUrlSigner.verify(PATH, System.currentTimeMillis() + 60000, null)).isFalse();
    }

    @Test
    void verify_rejectsExpired() {
        long exp = System.currentTimeMillis() - 1000;
        String signed = MediaUrlSigner.sign(PATH, exp);

        assertThat(MediaUrlSigner.verify(PATH, exp, signed.substring(signed.indexOf("sig=") + 4))).isFalse();
    }
}