package com.dumio.backend.controller;

import com.dumio.backend.dto.request.CreateVocabularyTopicRequest;
import com.dumio.backend.dto.request.RestoreVocabularyTopicRequest;
import com.dumio.backend.dto.request.UpdateVocabularyTopicRequest;
import com.dumio.backend.dto.request.VocabularyTopicQuery;
import com.dumio.backend.dto.request.VocabularyVersionRequest;
import com.dumio.backend.dto.response.VocabularyPageResponse;
import com.dumio.backend.dto.response.VocabularyTopicResponse;
import com.dumio.backend.service.VocabularyTopicService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/vocabulary/topics")
public class AdminVocabularyTopicController {
    private final VocabularyTopicService topics;

    public AdminVocabularyTopicController(VocabularyTopicService topics) {
        this.topics = topics;
    }

    @GetMapping
    public ResponseEntity<VocabularyPageResponse<VocabularyTopicResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topics.list(VocabularyTopicQuery.from(query)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VocabularyTopicResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topics.get(id));
    }

    @PostMapping
    public ResponseEntity<VocabularyTopicResponse> create(@Valid @RequestBody CreateVocabularyTopicRequest input) {
        VocabularyTopicResponse result = topics.create(input);
        return ResponseEntity.created(URI.create("/api/v1/admin/vocabulary/topics/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<VocabularyTopicResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateVocabularyTopicRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topics.update(id, input));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<VocabularyTopicResponse> archive(@PathVariable UUID id, @Valid @RequestBody VocabularyVersionRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topics.archive(id, input.version()));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<VocabularyTopicResponse> restore(@PathVariable UUID id, @Valid @RequestBody RestoreVocabularyTopicRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topics.restore(id, input));
    }
}
