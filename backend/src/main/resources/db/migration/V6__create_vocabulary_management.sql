-- Arrays contain optional vocabulary labels, not independent taxonomy records.
CREATE FUNCTION vocabulary_valid_labels(labels TEXT[]) RETURNS BOOLEAN
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(array_ndims(labels), 1) = 1
       AND cardinality(labels) <= 20
       AND NOT EXISTS (
           SELECT 1 FROM unnest(labels) AS entry
           WHERE entry IS NULL OR char_length(entry) NOT BETWEEN 1 AND 200
              OR entry <> btrim(entry) OR entry !~ '[^[:space:]]'
              OR entry IS NOT NFC NORMALIZED
       )
$$;

CREATE TABLE vocabulary_topics (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_topics_name CHECK (
        name = btrim(name) AND name ~ '[^[:space:]]' AND name IS NFC NORMALIZED
    ),
    CONSTRAINT ck_vocabulary_topics_description CHECK (
        description IS NULL OR (description = btrim(description)
            AND description ~ '[^[:space:]]' AND description IS NFC NORMALIZED)
    )
);

CREATE UNIQUE INDEX uk_vocabulary_topics_active_name
    ON vocabulary_topics (lower(name)) WHERE archived_at IS NULL;
CREATE INDEX ix_vocabulary_topics_archived ON vocabulary_topics (archived_at, id);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    storage_key TEXT NOT NULL UNIQUE,
    media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('IMAGE', 'AUDIO')),
    detected_content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    original_name TEXT NOT NULL,
    image_width INTEGER,
    image_height INTEGER,
    duration_ms BIGINT,
    source TEXT,
    usage_rights TEXT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL DEFAULT 'TEMPORARY'
        CHECK (status IN ('TEMPORARY', 'ATTACHED', 'PENDING_DELETION', 'DELETED')),
    delete_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delete_attempts >= 0),
    next_cleanup_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_media_assets_metadata CHECK (
        (media_type = 'IMAGE' AND detected_content_type IN ('image/jpeg', 'image/png', 'image/webp')
            AND size_bytes <= 5242880 AND image_width BETWEEN 1 AND 4096
            AND image_height BETWEEN 1 AND 4096
            AND image_width IS NOT NULL AND image_height IS NOT NULL AND duration_ms IS NULL)
        OR
        (media_type = 'AUDIO' AND detected_content_type = 'audio/mpeg'
            AND size_bytes <= 10485760 AND duration_ms BETWEEN 1 AND 120000
            AND duration_ms IS NOT NULL AND image_width IS NULL AND image_height IS NULL)
    ),
    CONSTRAINT ck_media_assets_storage_key CHECK (
        (media_type = 'IMAGE' AND storage_key ~ '^cards/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
            AND ((detected_content_type = 'image/jpeg' AND storage_key ~ '\.(jpg|jpeg)$')
                OR (detected_content_type = 'image/png' AND storage_key ~ '\.png$')
                OR (detected_content_type = 'image/webp' AND storage_key ~ '\.webp$')))
        OR
        (media_type = 'AUDIO' AND storage_key ~ '^audio/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.mp3$')
    )
);
CREATE INDEX ix_media_assets_cleanup ON media_assets (status, next_cleanup_at, created_at, id);
CREATE INDEX ix_media_assets_creator ON media_assets (created_by);

CREATE TABLE vocabulary_words (
    id UUID PRIMARY KEY,
    term VARCHAR(150) NOT NULL,
    meaning_vi VARCHAR(2000) NOT NULL,
    ipa VARCHAR(150),
    part_of_speech VARCHAR(50),
    meaning_en VARCHAR(2000),
    example VARCHAR(2000),
    example_translation VARCHAR(2000),
    image_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    audio_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    tags TEXT[] NOT NULL DEFAULT '{}',
    synonyms TEXT[] NOT NULL DEFAULT '{}',
    collocations TEXT[] NOT NULL DEFAULT '{}',
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_words_term CHECK (
        term = btrim(term) AND term ~ '[^[:space:]]' AND term IS NFC NORMALIZED
    ),
    CONSTRAINT ck_vocabulary_words_meaning CHECK (
        meaning_vi = btrim(meaning_vi) AND meaning_vi ~ '[^[:space:]]' AND meaning_vi IS NFC NORMALIZED
    ),
    CONSTRAINT ck_vocabulary_words_labels CHECK (
        vocabulary_valid_labels(tags) AND vocabulary_valid_labels(synonyms)
        AND vocabulary_valid_labels(collocations)
    )
);
CREATE INDEX ix_vocabulary_words_active_term ON vocabulary_words (lower(term), id) WHERE archived_at IS NULL;
CREATE INDEX ix_vocabulary_words_archived ON vocabulary_words (archived_at, id);
CREATE INDEX ix_vocabulary_words_image ON vocabulary_words (image_asset_id) WHERE image_asset_id IS NOT NULL;
CREATE INDEX ix_vocabulary_words_audio ON vocabulary_words (audio_asset_id) WHERE audio_asset_id IS NOT NULL;
CREATE INDEX ix_vocabulary_words_created_by ON vocabulary_words (created_by);
CREATE INDEX ix_vocabulary_words_updated_by ON vocabulary_words (updated_by);

CREATE TABLE vocabulary_sets (
    id UUID PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    topic_id UUID REFERENCES vocabulary_topics(id) ON DELETE RESTRICT,
    cover_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_sets_title CHECK (
        title = btrim(title) AND title ~ '[^[:space:]]' AND title IS NFC NORMALIZED
    ),
    CONSTRAINT ck_vocabulary_sets_published_topic CHECK (
        publication_status <> 'PUBLISHED' OR topic_id IS NOT NULL
    )
);
CREATE INDEX ix_vocabulary_sets_active_updated ON vocabulary_sets (updated_at DESC, id) WHERE archived_at IS NULL;
CREATE INDEX ix_vocabulary_sets_topic ON vocabulary_sets (topic_id);
CREATE INDEX ix_vocabulary_sets_status ON vocabulary_sets (archived_at, publication_status, id);
CREATE INDEX ix_vocabulary_sets_cover ON vocabulary_sets (cover_asset_id) WHERE cover_asset_id IS NOT NULL;
CREATE INDEX ix_vocabulary_sets_created_by ON vocabulary_sets (created_by);
CREATE INDEX ix_vocabulary_sets_updated_by ON vocabulary_sets (updated_by);

CREATE TABLE vocabulary_set_items (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL REFERENCES vocabulary_sets(id) ON DELETE RESTRICT,
    word_id UUID NOT NULL REFERENCES vocabulary_words(id) ON DELETE RESTRICT,
    removed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_vocabulary_set_items_active_word
    ON vocabulary_set_items (set_id, word_id) WHERE removed_at IS NULL;
CREATE INDEX ix_vocabulary_set_items_word ON vocabulary_set_items (word_id, set_id);
CREATE INDEX ix_vocabulary_set_items_set_history ON vocabulary_set_items (set_id, removed_at);
