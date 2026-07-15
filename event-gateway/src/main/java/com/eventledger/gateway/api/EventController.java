package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.service.EventIngestionService;
import com.eventledger.gateway.service.EventNormalizer;
import com.eventledger.gateway.service.EventQueryService;
import com.eventledger.gateway.service.EventSubmissionResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class EventController {

    private final EventQueryService queryService;
    private final EventIngestionService ingestionService;
    private final EventNormalizer eventNormalizer;
    private final EventResponseMapper responseMapper;

    public EventController(EventQueryService queryService,
                           EventIngestionService ingestionService,
                           EventNormalizer eventNormalizer,
                           EventResponseMapper responseMapper) {
        this.queryService = queryService;
        this.ingestionService = ingestionService;
        this.eventNormalizer = eventNormalizer;
        this.responseMapper = responseMapper;
    }

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        NormalizedEvent event = eventNormalizer.normalize(request);
        EventSubmissionResult result = ingestionService.submit(event);
        EventResponse body = responseMapper.toEventResponse(result.event());
        if (result.created()) {
            return ResponseEntity
                    .created(URI.create("/events/" + result.event().eventId()))
                    .body(body);
        }
        return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(body);
    }

    @GetMapping("/events/{eventId}")
    public EventResponse getEvent(@PathVariable String eventId) {
        return responseMapper.toEventResponse(queryService.getEvent(eventId));
    }

    @GetMapping("/events")
    public List<EventListItemResponse> listEvents(
            @RequestParam(name = "account", required = false) String account) {
        return queryService.listEvents(account).stream()
                .map(responseMapper::toListItem)
                .toList();
    }
}
