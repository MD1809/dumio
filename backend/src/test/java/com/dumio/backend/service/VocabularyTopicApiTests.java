package com.dumio.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.entity.*;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.enums.VocabularyPublicationStatus;
import com.dumio.backend.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
class VocabularyTopicApiTests {
    private static final String BASE = "/api/v1/admin/vocabulary/topics";
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    @Autowired VocabularyTopicRepository topics;
    @Autowired VocabularySetRepository sets;
    @Autowired VocabularyWordRepository words;
    @Autowired VocabularySetItemRepository items;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    private final Set<UUID> topicIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> setIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> wordIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> itemIds = ConcurrentHashMap.newKeySet();
    private User actor;
    private String admin;
    private String prefix;

    @BeforeEach
    void setup() {
        prefix = "topic-test-" + UUID.randomUUID() + " ";
        actor = new User();
        actor.setUsername("Topic API test");
        actor.setEmail("topic-api-" + UUID.randomUUID() + "@example.com");
        actor.setPasswordHash("test-only-not-for-login");
        actor.setRole(UserRole.ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setEmailVerifiedAt(Instant.now());
        actor = users.saveAndFlush(actor);
        admin = token();
    }

    @AfterEach
    void cleanupOnlyOwnedFixtures() {
        // These requests commit for real so failures/concurrency can be checked in independent transactions.
        transactions.executeWithoutResult(status -> {
            itemIds.forEach(id -> jdbc.update("delete from vocabulary_set_items where id = ?", id));
            setIds.forEach(id -> jdbc.update("delete from vocabulary_sets where id = ?", id));
            wordIds.forEach(id -> jdbc.update("delete from vocabulary_words where id = ?", id));
            topicIds.forEach(id -> jdbc.update("delete from vocabulary_topics where id = ?", id));
            if (actor != null) jdbc.update("delete from users where id = ?", actor.getId());
        });
    }

    @Test
    void createsReadsUpdatesAndConsumesVersionEvenWhenContentIsUnchanged() throws Exception {
        Reply created = send(HttpMethod.POST, "", Map.of("name", "\u00a0" + prefix + "Du lịch  ", "description", "  Mô tả  "));
        assertEquals(201, created.status());
        UUID id = id(created);
        assertEquals(BASE + "/" + id, created.location());
        assertEquals("no-store", created.cacheControl());
        assertEquals(prefix + "Du lịch", created.body().get("name").asString());
        assertEquals("Mô tả", created.body().get("description").asString());
        assertEquals(0, version(created));
        assertNotNull(created.body().get("createdAt"));
        assertNotNull(created.body().get("updatedAt"));
        assertTrue(created.body().get("archivedAt").isNull());
        Reply read = send(HttpMethod.GET, "/" + id, null);
        assertEquals(created.body(), read.body());
        Reply updated = send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Travel", "version", 0));
        assertEquals(200, updated.status());
        assertEquals(1, version(updated));
        assertTrue(updated.body().get("description").isNull(), "PUT clears omitted optional fields");
        assertEquals(created.body().get("createdAt"), updated.body().get("createdAt"));
        Reply unchanged = send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Travel", "version", 1));
        assertEquals(200, unchanged.status());
        assertEquals(2, version(unchanged));
        assertCode(send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Old", "version", 1)), 409, "VOCABULARY_VERSION_CONFLICT");
        assertEquals(prefix + "Travel", send(HttpMethod.GET, "/" + id, null).body().get("name").asString());
    }

    @Test
    void activeNamesAreCaseInsensitiveNfcButAccentSensitive() throws Exception {
        create("Du lịch");
        Reply duplicate = send(HttpMethod.POST, "", Map.of("name", "  " + prefix.toUpperCase(java.util.Locale.ROOT) + "DU LỊCH  "));
        assertCode(duplicate, 409, "VOCABULARY_TOPIC_NAME_EXISTS");
        assertEquals("name", duplicate.body().get("fieldErrors").get(0).get("field").asString());
        assertEquals(201, create("Du lich").status());
    }

    @Test
    void conflictingUpdateRollsBackNameDescriptionAndVersion() throws Exception {
        create("First");
        Reply other = create("Second");
        Reply result = send(HttpMethod.PUT, "/" + id(other), Map.of("name", prefix + "First", "description", "Changed", "version", 0));
        assertCode(result, 409, "VOCABULARY_TOPIC_NAME_EXISTS");
        assertEquals(other.body(), send(HttpMethod.GET, "/" + id(other), null).body());
    }

    @Test
    void archiveRestoreNameConflictIsAtomicAndAllowsRenameDuringRestore() throws Exception {
        Reply original = create("Shared");
        UUID id = id(original);
        Reply archived = send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0));
        assertEquals(200, archived.status());
        assertEquals(1, version(archived));
        assertFalse(archived.body().get("archivedAt").isNull());
        assertCode(send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Edit archived", "version", 1)), 409, "VOCABULARY_ARCHIVED");
        create("Shared");
        assertCode(send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 1)), 409, "VOCABULARY_TOPIC_NAME_EXISTS");
        assertEquals(archived.body(), send(HttpMethod.GET, "/" + id, null).body(), "Failed restore must not rename/unarchive/bump version");
        Reply restored = send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 1, "name", "  " + prefix + "Restored  "));
        assertEquals(200, restored.status());
        assertEquals(2, version(restored));
        assertTrue(restored.body().get("archivedAt").isNull());
        assertEquals(prefix + "Restored", restored.body().get("name").asString());
    }

    @Test
    void repeatedStateOperationsAndStaleVersionsReturn409() throws Exception {
        UUID id = id(create("State"));
        assertCode(send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 0)), 409, "VOCABULARY_STATE_CONFLICT");
        assertEquals(200, send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0)).status());
        assertCode(send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0)), 409, "VOCABULARY_VERSION_CONFLICT");
        assertCode(send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 1)), 409, "VOCABULARY_STATE_CONFLICT");
        assertCode(send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 0)), 409, "VOCABULARY_VERSION_CONFLICT");
        Reply restored = send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 1));
        assertEquals(200, restored.status());
        assertEquals(2, version(restored));
        assertCode(send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 2, "name", prefix + "Ignored?")), 409, "VOCABULARY_STATE_CONFLICT");
        assertEquals(restored.body(), send(HttpMethod.GET, "/" + id, null).body());
    }

    @Test
    void listFiltersSearchSortAndPaginationUseRealCountsAndStableOrder() throws Exception {
        Reply z = create("Zebra");
        Reply alpha = create("alpha");
        Reply beta = create("Beta");
        send(HttpMethod.POST, "/" + id(z) + "/archive", Map.of("version", 0));
        Reply list = query(Map.of("q", prefix, "size", "1"));
        assertEquals(200, list.status());
        assertEquals(2, list.body().get("totalElements").asInt());
        assertEquals(2, list.body().get("totalPages").asInt());
        assertEquals(0, list.body().get("page").asInt());
        assertEquals(1, list.body().get("size").asInt());
        assertEquals(id(alpha).toString(), list.body().get("items").get(0).get("id").asString());
        Reply second = query(Map.of("q", prefix, "size", "1", "page", "1"));
        assertEquals(id(beta).toString(), second.body().get("items").get(0).get("id").asString());
        assertEquals(3, query(Map.of("q", prefix, "archived", "all")).body().get("totalElements").asInt());
        assertEquals(1, query(Map.of("q", prefix, "archived", "true")).body().get("totalElements").asInt());
        assertEquals(id(beta).toString(), query(Map.of("q", prefix, "sort", "name,desc")).body().get("items").get(0).get("id").asString());
        for (String sort : List.of("createdAt,asc", "createdAt,desc", "updatedAt,asc", "updatedAt,desc")) {
            assertEquals(200, query(Map.of("q", prefix, "sort", sort)).status());
        }
        Reply distant = query(Map.of("q", prefix, "page", "2147483647", "size", "100"));
        assertEquals(200, distant.status());
        assertEquals(0, distant.body().get("items").size());
        assertEquals(2, distant.body().get("totalElements").asInt());
        Reply empty = query(Map.of("q", prefix + "missing"));
        assertEquals(0, empty.body().get("totalPages").asInt());
        assertEquals(0, empty.body().get("items").size());
    }

    @Test
    void searchTreatsWildcardsBackslashesAndSqlAsLiteralText() throws Exception {
        create("100%_\\done");
        create("100abdone");
        create("Du lịch");
        assertEquals(1, query(Map.of("q", prefix + "100%_\\")).body().get("totalElements").asInt());
        assertEquals(1, query(Map.of("q", "  " + prefix.toUpperCase(java.util.Locale.ROOT) + "DU LỊCH  ")).body().get("totalElements").asInt());
        assertEquals(0, query(Map.of("q", prefix + "Du lich")).body().get("totalElements").asInt());
        assertEquals(0, query(Map.of("q", prefix + "' OR 1=1 --")).body().get("totalElements").asInt());
    }

    @Test
    void sortingDuplicateArchivedNamesUsesIdAsTieBreaker() throws Exception {
        Reply old = create("Same");
        send(HttpMethod.POST, "/" + id(old) + "/archive", Map.of("version", 0));
        Reply current = create("Same");
        Reply list = query(Map.of("q", prefix, "archived", "all"));
        List<String> expected = Stream.of(id(old).toString(), id(current).toString()).sorted().toList();
        assertEquals(expected.get(0), list.body().get("items").get(0).get("id").asString());
        assertEquals(expected.get(1), list.body().get("items").get(1).get("id").asString());
    }

    @Test
    void unicodeCodePointLimitsAndOptionalBlankDescriptionsAreConsistent() throws Exception {
        String name = prefix + "😀".repeat(150 - prefix.length());
        Reply max = send(HttpMethod.POST, "", Map.of("name", name, "description", "😀".repeat(2000)));
        assertEquals(201, max.status());
        assertField(send(HttpMethod.POST, "", Map.of("name", name + "😀")), "name");
        assertField(send(HttpMethod.POST, "", Map.of("name", prefix + "Too long", "description", "😀".repeat(2001))), "description");
        Reply blank = send(HttpMethod.POST, "", Map.of("name", prefix + "Blank description", "description", "\u00a0  "));
        assertEquals(201, blank.status());
        assertTrue(blank.body().get("description").isNull());
        assertField(query(Map.of("q", "😀".repeat(201))), "q");
        assertEquals(200, query(Map.of("q", "😀".repeat(200))).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"  \\u00a0\"}",
            "{\"name\":42}", "{\"name\":true}", "{\"name\":[]}", "{\"name\":{}}", "null", "[]", "{bad"})
    void invalidBodiesAre400WithoutCreatingRecords(String body) throws Exception {
        assertCode(raw(HttpMethod.POST, "", body, admin), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "archivedAt", "version", "createdBy", "createdAt", "storageKey", "publicationStatus"})
    void createRejectsServerManagedOrUnknownProperties(String field) throws Exception {
        assertCode(send(HttpMethod.POST, "", Map.of("name", prefix + "Unknown", field, "forbidden")), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "0.5", "1e0", "\"0\"", "true", "[]", "{}", "9223372036854775808"})
    void allWriteEndpointsRequireNonnegativeIntegerVersions(String version) throws Exception {
        UUID id = id(create("Version"));
        String update = "{\"name\":\"" + prefix + "Updated\",\"version\":" + version + "}";
        assertCode(raw(HttpMethod.PUT, "/" + id, update, admin), 400, "VALIDATION_ERROR");
        for (String operation : List.of("archive", "restore")) {
            assertCode(raw(HttpMethod.POST, "/" + id + "/" + operation, "{\"version\":" + version + "}", admin), 400, "VALIDATION_ERROR");
        }
        assertEquals(0, version(send(HttpMethod.GET, "/" + id, null)));
    }

    static Stream<Arguments> badQueries() {
        return Stream.of(Arguments.of("page", "-1"), Arguments.of("page", "abc"), Arguments.of("page", "1.5"),
                Arguments.of("page", "2147483648"), Arguments.of("size", "0"), Arguments.of("size", "101"),
                Arguments.of("size", "-1"), Arguments.of("size", ""), Arguments.of("archived", "yes"),
                Arguments.of("sort", "name"), Arguments.of("sort", "name,ASC"), Arguments.of("sort", "id,asc"),
                Arguments.of("sort", "name,asc,updatedAt"), Arguments.of("unknown", "ignored?"));
    }

    @ParameterizedTest
    @MethodSource("badQueries")
    void invalidQueryParametersHaveFieldErrors(String field, String value) throws Exception {
        assertField(query(Map.of(field, value)), field);
    }

    @Test
    void missingVersionsBlankRestoreNamesNullCharactersAndRepeatedQueryAreRejected() throws Exception {
        UUID id = id(create("Validation"));
        assertField(send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Missing")), "version");
        assertField(send(HttpMethod.POST, "/" + id + "/archive", Map.of()), "version");
        assertField(send(HttpMethod.POST, "/" + id + "/restore", Map.of()), "version");
        send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0));
        assertField(send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 1, "name", "  ")), "name");
        assertField(send(HttpMethod.POST, "", Map.of("name", prefix + "\0")), "name");
        assertField(send(HttpMethod.POST, "", Map.of("name", prefix + "Null description", "description", "\0")), "description");
        assertField(query(Map.of("q", "\0")), "q");
        assertField(perform(get(BASE).param("page", "0", "1").header("Authorization", "Bearer " + admin)), "page");
    }

    @Test
    void missingAndMalformedIdsHaveStableErrors() throws Exception {
        UUID missing = UUID.randomUUID();
        assertCode(send(HttpMethod.GET, "/" + missing, null), 404, "VOCABULARY_NOT_FOUND");
        assertCode(send(HttpMethod.PUT, "/" + missing, Map.of("name", prefix + "Missing", "version", 0)), 404, "VOCABULARY_NOT_FOUND");
        assertCode(send(HttpMethod.POST, "/" + missing + "/archive", Map.of("version", 0)), 404, "VOCABULARY_NOT_FOUND");
        assertCode(send(HttpMethod.POST, "/" + missing + "/restore", Map.of("version", 0)), 404, "VOCABULARY_NOT_FOUND");
        assertField(send(HttpMethod.GET, "/not-a-uuid", null), "id");
    }

    @Test
    void topicArchiveAndRestorePreservePublishedSetsWordsAndMemberships() throws Exception {
        UUID topicId = id(create("Referenced"));
        UUID setId = transactions.execute(status -> {
            VocabularySet set = new VocabularySet();
            set.setTitle("Test set");
            set.setTopic(topics.getReferenceById(topicId));
            set.setCreatedBy(users.getReferenceById(actor.getId()));
            set.setUpdatedBy(users.getReferenceById(actor.getId()));
            set.setPublicationStatus(VocabularyPublicationStatus.PUBLISHED);
            set = sets.saveAndFlush(set);
            setIds.add(set.getId());
            VocabularyWord word = new VocabularyWord();
            word.setTerm("example");
            word.setMeaningVi("ví dụ");
            word.setCreatedBy(users.getReferenceById(actor.getId()));
            word.setUpdatedBy(users.getReferenceById(actor.getId()));
            word = words.saveAndFlush(word);
            wordIds.add(word.getId());
            VocabularySetItem item = new VocabularySetItem();
            item.setVocabularySet(set);
            item.setWord(word);
            item = items.saveAndFlush(item);
            itemIds.add(item.getId());
            return set.getId();
        });
        send(HttpMethod.POST, "/" + topicId + "/archive", Map.of("version", 0));
        assertEquals("PUBLISHED", jdbc.queryForObject("select publication_status from vocabulary_sets where id = ?", String.class, setId));
        assertEquals(topicId, jdbc.queryForObject("select topic_id from vocabulary_sets where id = ?", UUID.class, setId));
        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNull(setId));
        assertEquals(0L, sets.findById(setId).orElseThrow().getVersion());
        assertEquals(200, send(HttpMethod.POST, "/" + topicId + "/restore", Map.of("version", 1)).status());
        assertEquals("PUBLISHED", jdbc.queryForObject("select publication_status from vocabulary_sets where id = ?", String.class, setId));
        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNull(setId));
    }

    @Test
    void concurrentCreatesHaveOneWinnerAndANameConflict() throws Exception {
        List<Reply> replies = concurrent(
                () -> create("Concurrent"), () -> create("Concurrent"));
        assertEquals(List.of(201, 409), replies.stream().map(Reply::status).sorted().toList());
        assertEquals(1, query(Map.of("q", prefix)).body().get("totalElements").asInt());
        assertCode(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow(), 409, "VOCABULARY_TOPIC_NAME_EXISTS");
    }

    @Test
    void concurrentUpdatesCannotOverwriteTheSameVersion() throws Exception {
        UUID id = id(create("Concurrent update"));
        List<Reply> replies = concurrent(
                () -> send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "A", "version", 0)),
                () -> send(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "B", "version", 0)));
        assertEquals(List.of(200, 409), replies.stream().map(Reply::status).sorted().toList());
        assertCode(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow(), 409, "VOCABULARY_VERSION_CONFLICT");
        assertEquals(1, version(send(HttpMethod.GET, "/" + id, null)));
    }

    @Test
    void concurrentRestoreAndCreateCannotClaimTheSameActiveName() throws Exception {
        UUID id = id(create("Restore race"));
        send(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0));
        List<Reply> replies = concurrent(
                () -> send(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 1)),
                () -> create("Restore race"));
        assertEquals(1, replies.stream().filter(r -> r.status() == 200 || r.status() == 201).count());
        assertEquals(1, replies.stream().filter(r -> r.status() == 409).count());
        assertEquals(1, query(Map.of("q", prefix)).body().get("totalElements").asInt());
    }

    @Test
    void actualEndpointsRequireAdminAndNoPermanentDeleteExists() throws Exception {
        UUID id = id(create("Authorization"));
        actor.setRole(UserRole.USER);
        actor = users.saveAndFlush(actor);
        String user = token();
        for (var operation : List.of(
                new Operation(HttpMethod.GET, "", null), new Operation(HttpMethod.POST, "", Map.of("name", prefix + "Forbidden")),
                new Operation(HttpMethod.GET, "/" + id, null), new Operation(HttpMethod.PUT, "/" + id, Map.of("name", prefix + "Changed", "version", 0)),
                new Operation(HttpMethod.POST, "/" + id + "/archive", Map.of("version", 0)),
                new Operation(HttpMethod.POST, "/" + id + "/restore", Map.of("version", 0)))) {
            String body = operation.body() == null ? null : json.writeValueAsString(operation.body());
            assertCode(raw(operation.method(), operation.suffix(), body, null), 401, "INVALID_ACCESS_TOKEN");
            assertCode(raw(operation.method(), operation.suffix(), body, user), 403, "ACCESS_DENIED");
        }
        actor.setRole(UserRole.ADMIN);
        actor = users.saveAndFlush(actor);
        admin = token();
        assertEquals(405, send(HttpMethod.DELETE, "/" + id, null).status());
        assertEquals(200, send(HttpMethod.GET, "/" + id, null).status());
    }

    private List<Reply> concurrent(java.util.concurrent.Callable<Reply> first, java.util.concurrent.Callable<Reply> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = Stream.of(first, second).map(action -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timeout");
                return action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private Reply create(String name) throws Exception {
        return send(HttpMethod.POST, "", Map.of("name", prefix + name));
    }

    private Reply query(Map<String, String> parameters) throws Exception {
        var request = get(BASE).header("Authorization", "Bearer " + admin);
        parameters.forEach(request::param);
        return perform(request);
    }

    private Reply send(HttpMethod method, String suffix, Object body) throws Exception {
        return raw(method, suffix, body == null ? null : json.writeValueAsString(body), admin);
    }

    private Reply raw(HttpMethod method, String suffix, String body, String access) throws Exception {
        var request = request(method, BASE + suffix);
        if (access != null) request.header("Authorization", "Bearer " + access);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return perform(request);
    }

    private Reply perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = content.isEmpty() ? json.nullNode() : json.readTree(content);
        if (body.has("id")) topicIds.add(UUID.fromString(body.get("id").asString()));
        return new Reply(result.getResponse().getStatus(), body, result.getResponse().getHeader("Location"),
                result.getResponse().getHeader("Cache-Control"));
    }

    private UUID id(Reply reply) {
        assertTrue(reply.status() == 200 || reply.status() == 201, reply.body().toString());
        return UUID.fromString(reply.body().get("id").asString());
    }

    private long version(Reply reply) { return reply.body().get("version").asLong(); }

    private void assertCode(Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.body().toString());
        assertEquals(code, reply.body().get("code").asString());
        assertTrue(reply.body().get("fieldErrors").isArray());
    }

    private void assertField(Reply reply, String field) {
        assertCode(reply, 400, "VALIDATION_ERROR");
        assertTrue(java.util.stream.StreamSupport.stream(reply.body().get("fieldErrors").spliterator(), false)
                .anyMatch(error -> field.equals(error.get("field").asString())), reply.body().toString());
    }

    private String token() {
        Instant issued = Instant.now().minusSeconds(1);
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
                .subject(actor.getId().toString()).issuedAt(issued).notBefore(issued).expiresAt(issued.plusSeconds(900))
                .claim("roles", List.of(actor.getRole().name())).claim("token_version", actor.getTokenVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private record Operation(HttpMethod method, String suffix, Object body) {}
    private record Reply(int status, JsonNode body, String location, String cacheControl) {}
}
