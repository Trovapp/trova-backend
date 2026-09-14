package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private BookmarkFolderRepository bookmarkFolderRepository;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    private Place place(String googlePlaceId, String name) {
        return placeRepository.save(
                new Place(googlePlaceId, name, "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소"));
    }

    @Test
    void 찜_목록_응답에_folderId가_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "bm-list-1", "찜유저1", null));
        Place unclassifiedPlace = place("g-list-1", "미분류 카페");
        Place foldedPlace = place("g-list-2", "폴더 카페");
        BookmarkFolder folder = bookmarkFolderRepository.save(new BookmarkFolder(me, "카페 모음", "#4A90D9"));

        bookmarkRepository.save(new Bookmark(me, unclassifiedPlace));
        Bookmark folded = new Bookmark(me, foldedPlace);
        folded.applyFolder(folder);
        bookmarkRepository.save(folded);

        String body = mockMvc.perform(get("/api/bookmarks").with(loginAs("bm-list-1", "찜유저1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode unclassifiedNode = findByPlaceName(root, "미분류 카페");
        JsonNode foldedNode = findByPlaceName(root, "폴더 카페");

        assertThat(unclassifiedNode.get("folderId").isNull()).isTrue();
        assertThat(foldedNode.get("folderId").asLong()).isEqualTo(folder.getId());
    }

    private JsonNode findByPlaceName(JsonNode array, String placeName) {
        for (JsonNode node : array) {
            if (placeName.equals(node.get("placeName").asText())) {
                return node;
            }
        }
        throw new AssertionError("placeName을 찾을 수 없음: " + placeName);
    }

    @Test
    void 찜을_folderId_null로_PATCH하면_미분류로_바뀐다() throws Exception {
        User me = userRepository.save(new User("google", "bm-patch-1", "찜유저2", null));
        Place p = place("g-patch-1", "옮길 카페");
        BookmarkFolder folder = bookmarkFolderRepository.save(new BookmarkFolder(me, "카페 모음", "#4A90D9"));
        Bookmark bookmark = new Bookmark(me, p);
        bookmark.applyFolder(folder);
        bookmark = bookmarkRepository.save(bookmark);

        mockMvc.perform(patch("/api/bookmarks/" + bookmark.getId())
                        .with(loginAs("bm-patch-1", "찜유저2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(org.hamcrest.Matchers.nullValue()));

        org.assertj.core.api.Assertions.assertThat(
                        bookmarkRepository.findById(bookmark.getId()).orElseThrow().getFolder())
                .isNull();
    }

    @Test
    void 타인_소유_폴더로_옮기려_하면_404() throws Exception {
        User me = userRepository.save(new User("google", "bm-patch-2", "찜유저3", null));
        User other = userRepository.save(new User("google", "bm-patch-3", "찜유저4", null));
        Place p = place("g-patch-2", "내 카페");
        Bookmark bookmark = bookmarkRepository.save(new Bookmark(me, p));
        BookmarkFolder otherFolder = bookmarkFolderRepository.save(new BookmarkFolder(other, "남의 폴더", "#000000"));

        mockMvc.perform(patch("/api/bookmarks/" + bookmark.getId())
                        .with(loginAs("bm-patch-2", "찜유저3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\": " + otherFolder.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 폴더_생성시_이름이_공백이면_400() throws Exception {
        User me = userRepository.save(new User("google", "bm-folder-1", "찜유저5", null));

        mockMvc.perform(post("/api/bookmarks/folders")
                        .with(loginAs("bm-folder-1", "찜유저5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  \", \"color\": \"#4A90D9\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 폴더_생성시_색상이_공백이면_400() throws Exception {
        User me = userRepository.save(new User("google", "bm-folder-2", "찜유저6", null));

        mockMvc.perform(post("/api/bookmarks/folders")
                        .with(loginAs("bm-folder-2", "찜유저6"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"카페 모음\", \"color\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 폴더_생성_성공시_placeCount는_0이다() throws Exception {
        User me = userRepository.save(new User("google", "bm-folder-3", "찜유저7", null));

        mockMvc.perform(post("/api/bookmarks/folders")
                        .with(loginAs("bm-folder-3", "찜유저7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"카페 모음\", \"color\": \"#4A90D9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("카페 모음"))
                .andExpect(jsonPath("$.color").value("#4A90D9"))
                .andExpect(jsonPath("$.placeCount").value(0));
    }

    @Test
    void 폴더_삭제시_소속_찜은_미분류로_남는다() throws Exception {
        User me = userRepository.save(new User("google", "bm-folder-4", "찜유저8", null));
        BookmarkFolder folder = bookmarkFolderRepository.save(new BookmarkFolder(me, "카페 모음", "#4A90D9"));
        Place p = place("g-folder-1", "카페");
        Bookmark member = new Bookmark(me, p);
        member.applyFolder(folder);
        member = bookmarkRepository.save(member);

        mockMvc.perform(delete("/api/bookmarks/folders/" + folder.getId())
                        .with(loginAs("bm-folder-4", "찜유저8")))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(bookmarkFolderRepository.findById(folder.getId())).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                        bookmarkRepository.findById(member.getId()).orElseThrow().getFolder())
                .isNull();
    }

    @Test
    void 타인_소유_폴더_삭제는_404() throws Exception {
        User me = userRepository.save(new User("google", "bm-folder-5", "찜유저9", null));
        User other = userRepository.save(new User("google", "bm-folder-6", "찜유저10", null));
        BookmarkFolder otherFolder = bookmarkFolderRepository.save(new BookmarkFolder(other, "남의 폴더", "#000000"));

        mockMvc.perform(delete("/api/bookmarks/folders/" + otherFolder.getId())
                        .with(loginAs("bm-folder-5", "찜유저9")))
                .andExpect(status().isNotFound());
    }
}
