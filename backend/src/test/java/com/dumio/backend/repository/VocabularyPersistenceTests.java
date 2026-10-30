package com.dumio.backend.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dumio.backend.entity.MediaAsset;
import com.dumio.backend.entity.User;
import com.dumio.backend.entity.VocabularyEntity;
import com.dumio.backend.entity.VocabularySet;
import com.dumio.backend.entity.VocabularySetItem;
import com.dumio.backend.entity.VocabularyTopic;
import com.dumio.backend.entity.VocabularyWord;
import com.dumio.backend.enums.MediaAssetStatus;
import com.dumio.backend.enums.MediaType;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.VocabularyPublicationStatus;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@Transactional
class VocabularyPersistenceTests {
    @Autowired private VocabularyTopicRepository topics;
    @Autowired private VocabularyWordRepository words;
    @Autowired private VocabularySetRepository sets;
    @Autowired private VocabularySetItemRepository items;
    @Autowired private MediaAssetRepository media;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private User actor;

    @BeforeEach
    void createActor() {
        actor = new User();
        actor.setUsername("Vocabulary test admin");
        actor.setEmail(UUID.randomUUID() + "@example.com");
        actor.setPasswordHash("test-fixture-not-used-for-login");
        actor.setRole(UserRole.ADMIN);
        actor = users.saveAndFlush(actor);
    }

    @Test
    void migrationIsAppliedAndMembershipHasNoPositionColumn() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version = '6' and success", Integer.class));
        assertEquals(5, jdbc.queryForObject("""
                select count(*) from information_schema.tables where table_schema = current_schema()
                    and table_name in ('vocabulary_topics', 'vocabulary_words', 'vocabulary_sets',
                        'vocabulary_set_items', 'media_assets')
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns where table_schema = current_schema()
                    and table_name = 'vocabulary_set_items' and column_name in ('position', 'sort_order')
                """, Integer.class));
        assertTrue(users.existsById(actor.getId()));
    }

    @Test
    void minimalDraftAndWordPersistWithoutOptionalContent() {
        VocabularySet set = newSet("  Draft  ");
        VocabularyWord word = newWord("  meeting  ");
        word.setMeaningVi("  cuo\u0323\u0302c ho\u0323p  ");
        word.setIpa(" \t\n");
        word.setTags(null);
        words.saveAndFlush(word);
        entityManager.clear();

        set = sets.findById(set.getId()).orElseThrow();
        word = words.findById(word.getId()).orElseThrow();
        assertEquals("Draft", set.getTitle());
        assertEquals(VocabularyPublicationStatus.DRAFT, set.getPublicationStatus());
        assertNull(set.getTopic());
        assertNull(set.getCoverAsset());
        assertEquals(0, set.getVersion());
        assertNotNull(set.getCreatedAt());
        assertNotNull(set.getUpdatedAt());
        assertEquals("meeting", word.getTerm());
        assertNull(word.getIpa());
        assertNull(word.getImageAsset());
        assertNull(word.getAudioAsset());
        assertArrayEquals(new String[0], word.getTags());
        assertArrayEquals(new String[0], word.getSynonyms());
        assertArrayEquals(new String[0], word.getCollocations());
    }

    @Test
    void normalizesUnicodeAndPreservesAccentsAndCase() {
        VocabularyTopic topic = newTopic("\u00a0 CAFE\u0301 \t");
        topic.setDescription("  \n");
        topics.saveAndFlush(topic);
        VocabularyWord word = newWord("\u00a0 Meeting \t");
        word.setTags(new String[] {"  Work  ", "Cafe\u0301"});
        word.setCollocations(new String[] {"  attend a meeting  "});
        words.saveAndFlush(word);
        entityManager.clear();

        assertEquals("CAF\u00c9", topics.findById(topic.getId()).orElseThrow().getName());
        assertNull(topics.findById(topic.getId()).orElseThrow().getDescription());
        assertTrue(topics.existsByNameIgnoreCaseAndArchivedAtIsNull("caf\u00e9"));
        assertFalse(topics.existsByNameIgnoreCaseAndArchivedAtIsNull("cafe"));
        word = words.findById(word.getId()).orElseThrow();
        assertEquals("Meeting", word.getTerm());
        assertArrayEquals(new String[] {"Work", "Caf\u00e9"}, word.getTags());
        assertArrayEquals(new String[] {"attend a meeting"}, word.getCollocations());
    }

    @Test
    void rejectsDuplicateActiveTopicAfterCaseAndUnicodeNormalization() {
        newTopic("Caf\u00e9");
        assertThrows(DataIntegrityViolationException.class, () -> newTopic("  CAFE\u0301  "));
    }

    @Test
    void archivedTopicReleasesNameButDoesNotDisappearFromItsSets() {
        VocabularyTopic topic = newTopic("Work");
        VocabularySet set = newSet("Office");
        set.setTopic(topic);
        sets.saveAndFlush(set);
        topic.setArchivedAt(Instant.now());
        topics.saveAndFlush(topic);
        VocabularyTopic replacement = newTopic("work");
        entityManager.clear();

        assertEquals(topic.getId(), sets.findById(set.getId()).orElseThrow().getTopic().getId());
        assertNotNull(topics.findById(topic.getId()).orElseThrow().getArchivedAt());
        assertNull(topics.findById(replacement.getId()).orElseThrow().getArchivedAt());
    }

    @Test
    void restoringTopicCannotBypassActiveNameUniqueness() {
        VocabularyTopic topic = newTopic("Work");
        topic.setArchivedAt(Instant.now());
        topics.saveAndFlush(topic);
        newTopic("WORK");
        topic.setArchivedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> topics.saveAndFlush(topic));
    }

    @Test
    void permitsSameWordSpellingAndSameSetTitle() {
        VocabularyWord first = newWord("meeting");
        VocabularyWord second = newWord("meeting");
        second.setMeaningVi("another meaning");
        words.saveAndFlush(second);
        VocabularySet one = newSet("Office");
        VocabularySet two = newSet("Office");
        assertFalse(first.getId().equals(second.getId()));
        assertFalse(one.getId().equals(two.getId()));
    }

    @Test
    void wordsAreSharedAcrossSetsAndRemovingOneMembershipPreservesOtherContent() {
        VocabularyWord word = newWord("meeting");
        VocabularySet one = newSet("Office");
        VocabularySet two = newSet("Business");
        VocabularySetItem first = add(one, word);
        add(two, word);
        word.setMeaningVi("updated meaning");
        words.saveAndFlush(word);
        first.setRemovedAt(Instant.now());
        items.saveAndFlush(first);
        entityManager.clear();

        assertEquals(0, items.countByVocabularySetIdAndRemovedAtIsNull(one.getId()));
        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNull(two.getId()));
        assertEquals("updated meaning", items.findCurrentMembers(two.getId()).getFirst().getWord().getMeaningVi());
        assertNotNull(items.findById(first.getId()).orElseThrow().getRemovedAt());
        assertTrue(words.existsById(word.getId()));
    }

    @Test
    void rejectsDuplicateActiveMembership() {
        VocabularyWord word = newWord("meeting");
        VocabularySet set = newSet("Office");
        add(set, word);
        assertThrows(DataIntegrityViolationException.class, () -> add(set, word));
    }

    @Test
    void canReAddPreviouslyRemovedWordWithoutDeletingHistory() {
        VocabularyWord word = newWord("meeting");
        VocabularySet set = newSet("Office");
        VocabularySetItem old = add(set, word);
        old.setRemovedAt(Instant.now());
        items.saveAndFlush(old);
        VocabularySetItem current = add(set, word);

        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNull(set.getId()));
        assertTrue(items.existsById(old.getId()));
        assertTrue(items.existsById(current.getId()));
        assertFalse(old.getId().equals(current.getId()));
    }

    @Test
    void derivesCurrentAndAvailableWordCountsWithoutStoringPositions() {
        VocabularySet set = newSet("Office");
        VocabularyWord zebra = newWord("zebra");
        VocabularyWord alpha = newWord("Alpha");
        add(set, zebra);
        add(set, alpha);
        zebra.setArchivedAt(Instant.now());
        words.saveAndFlush(zebra);

        assertEquals(2, items.countByVocabularySetIdAndRemovedAtIsNull(set.getId()));
        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNullAndWordArchivedAtIsNull(set.getId()));
        assertEquals(List.of(alpha.getId(), zebra.getId()),
                items.findCurrentMembers(set.getId()).stream().map(i -> i.getWord().getId()).toList());
    }

    @Test
    void onlyUnarchivedPublishedSetsBlockWordArchival() {
        VocabularyWord word = newWord("meeting");
        VocabularySet set = newSet("Office");
        set.setTopic(newTopic("Work"));
        set.setPublicationStatus(VocabularyPublicationStatus.PUBLISHED);
        sets.saveAndFlush(set);
        add(set, word);
        assertTrue(items.isUsedByPublishedSet(word.getId()));

        set.setArchivedAt(Instant.now());
        sets.saveAndFlush(set);
        assertFalse(items.isUsedByPublishedSet(word.getId()));
        assertEquals(1, items.findByWordIdAndRemovedAtIsNull(word.getId(), PageRequest.of(0, 20)).getTotalElements());
    }

    @Test
    void rollbackRestoresMembershipsAfterPartialReplacementFails() {
        VocabularySet set = newSet("Office");
        VocabularyWord oldWord = newWord("meeting");
        VocabularyWord newWord = newWord("deadline");
        VocabularySetItem oldItem = add(set, oldWord);

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var remove = connection.prepareStatement("update vocabulary_set_items set removed_at = current_timestamp where id = ?");
                    var insert = connection.prepareStatement("insert into vocabulary_set_items(id, set_id, word_id) values (?, ?, ?)")) {
                remove.setObject(1, oldItem.getId());
                remove.executeUpdate();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, set.getId());
                insert.setObject(3, newWord.getId());
                insert.executeUpdate();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(3, UUID.randomUUID());
                SQLException failure = assertThrows(SQLException.class, insert::executeUpdate);
                assertEquals("23503", failure.getSQLState());
                connection.rollback(savepoint);
            } finally {
                connection.releaseSavepoint(savepoint);
            }
            return null;
        });
        entityManager.clear();
        assertEquals(1, items.countByVocabularySetIdAndRemovedAtIsNull(set.getId()));
        assertTrue(items.existsByVocabularySetIdAndWordIdAndRemovedAtIsNull(set.getId(), oldWord.getId()));
        assertFalse(items.existsByVocabularySetIdAndWordIdAndRemovedAtIsNull(set.getId(), newWord.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"topic", "word", "set"})
    void staleDetachedEntitiesCannotOverwriteNewerVersions(String kind) {
        switch (kind) {
            case "topic" -> assertStaleWrite(topics, newTopic("Work"), t -> t.setDescription("changed"));
            case "word" -> assertStaleWrite(words, newWord("meeting"), w -> w.setMeaningVi("changed"));
            case "set" -> assertStaleWrite(sets, newSet("Office"), s -> s.setDescription("changed"));
            default -> throw new IllegalArgumentException(kind);
        }
    }

    @Test
    void accepts150UnicodeCodePointsRatherThan150Utf16Units() {
        String value = "\uD83D\uDE00".repeat(150);
        VocabularyWord word = newWord(value);
        entityManager.clear();
        assertEquals(value, words.findById(word.getId()).orElseThrow().getTerm());
    }

    @Test
    void rejectsOverlongTermAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> newWord("a".repeat(151)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"term", "meaning_vi"})
    void databaseRejectsMissingRequiredWordContent(String column) {
        VocabularyWord word = newWord("meeting");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update vocabulary_words set " + column + " = '' where id = ?", word.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"too_many", "too_long", "null", "blank"})
    void rejectsMalformedLabelArrays(String kind) {
        VocabularyWord word = newWord("meeting");
        word.setTags(switch (kind) {
            case "too_many" -> {
                String[] values = new String[21];
                Arrays.fill(values, "tag");
                yield values;
            }
            case "too_long" -> new String[] {"a".repeat(201)};
            case "null" -> new String[] {null};
            default -> new String[] {" \t"};
        });
        assertThrows(DataIntegrityViolationException.class, () -> words.saveAndFlush(word));
    }

    @Test
    void archivesKeepMediaReferencesAndAuditIdentity() {
        MediaAsset image = newImage();
        VocabularyWord word = newWord("meeting");
        word.setImageAsset(image);
        word.setArchivedAt(Instant.now());
        words.saveAndFlush(word);
        VocabularySet set = newSet("Office");
        set.setCoverAsset(image);
        set.setArchivedAt(Instant.now());
        sets.saveAndFlush(set);
        assertTrue(media.isReferenced(image.getId()));

        set.setCoverAsset(null);
        sets.saveAndFlush(set);
        assertTrue(media.isReferenced(image.getId()));
        entityManager.clear();
        assertEquals(actor.getId(), words.findById(word.getId()).orElseThrow().getCreatedBy().getId());
        assertTrue(media.existsById(image.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"word", "set", "topic", "media"})
    void foreignKeysPreventPhysicalCascadeEvenForArchivedRecords(String kind) {
        MediaAsset image = newImage();
        VocabularyTopic topic = newTopic("Work");
        topic.setArchivedAt(Instant.now());
        topics.saveAndFlush(topic);
        VocabularyWord word = newWord("meeting");
        word.setImageAsset(image);
        word.setArchivedAt(Instant.now());
        words.saveAndFlush(word);
        VocabularySet set = newSet("Office");
        set.setTopic(topic);
        set.setArchivedAt(Instant.now());
        sets.saveAndFlush(set);
        VocabularySetItem item = add(set, word);
        item.setRemovedAt(Instant.now());
        items.saveAndFlush(item);
        String table = switch (kind) {
            case "word" -> "vocabulary_words";
            case "set" -> "vocabulary_sets";
            case "topic" -> "vocabulary_topics";
            default -> "media_assets";
        };
        UUID id = switch (kind) {
            case "word" -> word.getId();
            case "set" -> set.getId();
            case "topic" -> topic.getId();
            default -> image.getId();
        };
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("delete from " + table + " where id = ?", id));
    }

    @Test
    void persistsAudioMetadataAndCleanupRetries() {
        MediaAsset audio = new MediaAsset();
        audio.setStorageKey("audio/" + UUID.randomUUID() + ".mp3");
        audio.setMediaType(MediaType.AUDIO);
        audio.setDetectedContentType("audio/mpeg");
        audio.setSizeBytes(10485760);
        audio.setDurationMs(120000L);
        audio.setOriginalName("pronunciation.mp3");
        audio.setCreatedBy(actor);
        audio.setStatus(MediaAssetStatus.PENDING_DELETION);
        audio.setDeleteAttempts(2);
        audio.setNextCleanupAt(Instant.now().minusSeconds(1));
        media.saveAndFlush(audio);
        entityManager.clear();
        MediaAsset stored = media.findById(audio.getId()).orElseThrow();
        assertNull(stored.getUsageRights());
        assertEquals(2, stored.getDeleteAttempts());
        assertTrue(media.findPendingCleanup(Instant.now(), PageRequest.of(0, 100)).stream()
                .anyMatch(m -> m.getId().equals(audio.getId())));
        assertFalse(media.isReferenced(audio.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"too_large", "dimensions", "path", "mime", "extension", "empty"})
    void rejectsInvalidMediaMetadata(String kind) {
        MediaAsset image = imageFor(actor);
        switch (kind) {
            case "too_large" -> image.setSizeBytes(5242881);
            case "dimensions" -> image.setImageWidth(4097);
            case "path" -> image.setStorageKey("../outside.jpg");
            case "mime" -> image.setDetectedContentType("text/html");
            case "extension" -> image.setStorageKey("cards/" + UUID.randomUUID() + ".png");
            default -> image.setSizeBytes(0);
        }
        assertThrows(DataIntegrityViolationException.class, () -> media.saveAndFlush(image));
    }

    @ParameterizedTest
    @ValueSource(strings = {"too_large", "too_long", "missing_duration", "image_dimensions"})
    void rejectsInvalidAudioMetadata(String kind) {
        MediaAsset audio = new MediaAsset();
        audio.setStorageKey("audio/" + UUID.randomUUID() + ".mp3");
        audio.setMediaType(MediaType.AUDIO);
        audio.setDetectedContentType("audio/mpeg");
        audio.setSizeBytes(1024);
        audio.setDurationMs(1000L);
        audio.setOriginalName("pronunciation.mp3");
        audio.setCreatedBy(actor);
        switch (kind) {
            case "too_large" -> audio.setSizeBytes(10485761);
            case "too_long" -> audio.setDurationMs(120001L);
            case "missing_duration" -> audio.setDurationMs(null);
            default -> audio.setImageWidth(1);
        }
        assertThrows(DataIntegrityViolationException.class, () -> media.saveAndFlush(audio));
    }

    @Test
    void acceptsBoundaryImageAndLabelSizes() {
        MediaAsset image = imageFor(actor);
        image.setSizeBytes(5242880);
        image.setImageWidth(4096);
        image.setImageHeight(4096);
        image = media.saveAndFlush(image);
        VocabularyWord word = newWord("meeting");
        String[] labels = new String[20];
        Arrays.fill(labels, "\uD83D\uDE00".repeat(200));
        word.setTags(labels);
        word.setImageAsset(image);
        words.saveAndFlush(word);
        entityManager.clear();
        assertArrayEquals(labels, words.findById(word.getId()).orElseThrow().getTags());
        assertEquals(4096, media.findById(image.getId()).orElseThrow().getImageWidth());
    }

    @Test
    void rejectsPublishedStatusWithoutTopic() {
        VocabularySet set = newSet("Office");
        set.setPublicationStatus(VocabularyPublicationStatus.PUBLISHED);
        assertThrows(DataIntegrityViolationException.class, () -> sets.saveAndFlush(set));
    }

    private <T extends VocabularyEntity> void assertStaleWrite(JpaRepository<T, UUID> repository, T original, Consumer<T> change) {
        entityManager.detach(original);
        T latest = repository.findById(original.getId()).orElseThrow();
        change.accept(latest);
        repository.saveAndFlush(latest);
        entityManager.clear();
        change.accept(original);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(original));
    }

    private VocabularyTopic newTopic(String name) {
        VocabularyTopic topic = new VocabularyTopic();
        topic.setName(name);
        return topics.saveAndFlush(topic);
    }

    private VocabularyWord newWord(String term) {
        VocabularyWord word = new VocabularyWord();
        word.setTerm(term);
        word.setMeaningVi("meaning");
        word.setCreatedBy(actor);
        word.setUpdatedBy(actor);
        return words.saveAndFlush(word);
    }

    private VocabularySet newSet(String title) {
        VocabularySet set = new VocabularySet();
        set.setTitle(title);
        set.setCreatedBy(actor);
        set.setUpdatedBy(actor);
        return sets.saveAndFlush(set);
    }

    private VocabularySetItem add(VocabularySet set, VocabularyWord word) {
        VocabularySetItem item = new VocabularySetItem();
        item.setVocabularySet(set);
        item.setWord(word);
        return items.saveAndFlush(item);
    }

    private MediaAsset newImage() {
        return media.saveAndFlush(imageFor(actor));
    }

    private MediaAsset imageFor(User creator) {
        MediaAsset image = new MediaAsset();
        image.setStorageKey("cards/" + UUID.randomUUID() + ".jpg");
        image.setMediaType(MediaType.IMAGE);
        image.setDetectedContentType("image/jpeg");
        image.setSizeBytes(1024);
        image.setImageWidth(640);
        image.setImageHeight(480);
        image.setOriginalName("illustration.jpg");
        image.setCreatedBy(creator);
        return image;
    }
}
