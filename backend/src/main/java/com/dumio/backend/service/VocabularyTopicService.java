package com.dumio.backend.service;

import com.dumio.backend.dto.request.CreateVocabularyTopicRequest;
import com.dumio.backend.dto.request.RestoreVocabularyTopicRequest;
import com.dumio.backend.dto.request.UpdateVocabularyTopicRequest;
import com.dumio.backend.dto.request.VocabularyTopicQuery;
import com.dumio.backend.dto.response.VocabularyPageResponse;
import com.dumio.backend.dto.response.VocabularyTopicResponse;
import java.util.UUID;

public interface VocabularyTopicService {
    VocabularyPageResponse<VocabularyTopicResponse> list(VocabularyTopicQuery query);
    VocabularyTopicResponse get(UUID id);
    VocabularyTopicResponse create(CreateVocabularyTopicRequest input);
    VocabularyTopicResponse update(UUID id, UpdateVocabularyTopicRequest input);
    VocabularyTopicResponse archive(UUID id, long version);
    VocabularyTopicResponse restore(UUID id, RestoreVocabularyTopicRequest input);
}
