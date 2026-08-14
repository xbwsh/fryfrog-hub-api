package com.fryfrog.hub.config;

import com.fryfrog.hub.common.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private AuthManager authManager;
    @Mock
    private UserService userService;

    private AuthInterceptor interceptor;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(authManager, userService);
        response = new MockHttpServletResponse();
        when(authManager.isEnabled()).thenReturn(true);
    }

    private boolean handle(MockHttpServletRequest request) throws Exception {
        request.addHeader("Authorization", "Bearer tok");
        when(authManager.getUserId("tok")).thenReturn(7L);
        return interceptor.preHandle(request, response, new Object());
    }

    @Test
    void mutatingRequest_forbiddenForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/video/1/metadata");

        boolean pass = handle(req);

        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void mediaLibraryWrite_forbiddenForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/media-libraries/1/toggle");

        assertThat(handle(req)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void userOwnedFavorite_allowedForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/video/1/favorite");
        req.addParameter("status", "true");

        assertThat(handle(req)).isTrue();
    }

    @Test
    void userOwnedProgress_allowedForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/video/1/progress");

        assertThat(handle(req)).isTrue();
    }

    @Test
    void mutatingRequest_allowedForAdmin() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/media-libraries/1/scan");

        assertThat(handle(req)).isTrue();
    }

    @Test
    void dataRead_allowedForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/video/1");

        assertThat(handle(req)).isTrue();
    }

    @Test
    void adminOnlyRead_forbiddenForNormalUser() throws Exception {
        when(userService.isAdmin(7L)).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/media-libraries/browse");

        assertThat(handle(req)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}