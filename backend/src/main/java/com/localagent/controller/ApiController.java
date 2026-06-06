package com.localagent.controller;

import com.localagent.dto.ApiDtos.ConfirmRequest;
import com.localagent.dto.ApiDtos.CommentRequest;
import com.localagent.dto.ApiDtos.FeedbackRequest;
import com.localagent.dto.ApiDtos.NearbyPoiRequest;
import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import com.localagent.dto.ApiDtos.RenameThreadRequest;
import com.localagent.dto.ApiDtos.SessionRequest;
import com.localagent.dto.ApiDtos.SessionResponse;
import com.localagent.dto.ApiDtos.ShareRequest;
import com.localagent.dto.ApiDtos.SpeechTranscribeResponse;
import com.localagent.dto.ApiDtos.ThreadDetailResponse;
import com.localagent.dto.ApiDtos.ThreadSummaryResponse;
import com.localagent.dto.ApiDtos.VoteRequest;
import com.localagent.service.AmapPoiSearchTool;
import com.localagent.service.CollaborationMockService;
import com.localagent.service.GuardService;
import com.localagent.service.HistoryService;
import com.localagent.service.PlanBlockedException;
import com.localagent.service.PlanningService;
import com.localagent.service.SessionAuthException;
import com.localagent.service.SessionService;
import com.localagent.service.SpeechTranscriptionException;
import com.localagent.service.SpeechTranscriptionService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SessionService sessionService;
    private final PlanningService planningService;
    private final AmapPoiSearchTool poiSearchTool;
    private final CollaborationMockService collaborationMockService;
    private final GuardService guardService;
    private final SpeechTranscriptionService speechTranscriptionService;
    private final HistoryService historyService;

    public ApiController(SessionService sessionService, PlanningService planningService,
                         AmapPoiSearchTool poiSearchTool,
                         CollaborationMockService collaborationMockService,
                         GuardService guardService,
                         SpeechTranscriptionService speechTranscriptionService,
                         HistoryService historyService) {
        this.sessionService = sessionService;
        this.planningService = planningService;
        this.poiSearchTool = poiSearchTool;
        this.collaborationMockService = collaborationMockService;
        this.guardService = guardService;
        this.speechTranscriptionService = speechTranscriptionService;
        this.historyService = historyService;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody(required = false) SessionRequest request) {
        return sessionService.create(request == null ? null : request.nickname());
    }

    @PostMapping("/plans")
    public PlanResponse createPlan(@RequestHeader("X-Session-Token") String token,
                                   @RequestHeader("X-Client-Id") String clientId,
                                   @RequestBody PlanRequest request) {
        sessionService.validate(token);
        return planningService.createPlan(token, clientId, request);
    }

    @PostMapping("/nearby-pois")
    public Map<String, Object> nearbyPois(@RequestHeader("X-Session-Token") String token,
                                          @RequestBody NearbyPoiRequest request) {
        sessionService.validate(token);
        return poiSearchTool.nearbyPois(request);
    }

    @GetMapping("/plans/{id}")
    public PlanResponse getPlan(@PathVariable UUID id) {
        return planningService.getPlan(id);
    }

    @PostMapping("/plans/{id}/confirm")
    public PlanResponse confirm(@RequestHeader("X-Session-Token") String token,
                                @RequestHeader("X-Client-Id") String clientId,
                                @PathVariable UUID id, @RequestBody ConfirmRequest request) {
        sessionService.validate(token);
        return planningService.confirm(id, clientId, request.rank());
    }

    @PostMapping("/plans/{id}/feedback")
    public PlanResponse feedback(@RequestHeader("X-Session-Token") String token,
                                 @RequestHeader("X-Client-Id") String clientId,
                                 @PathVariable UUID id, @RequestBody FeedbackRequest request) {
        sessionService.validate(token);
        return planningService.feedback(id, clientId, request.message());
    }

    @GetMapping("/history/threads")
    public List<ThreadSummaryResponse> historyThreads(@RequestHeader("X-Session-Token") String token,
                                                      @RequestHeader("X-Client-Id") String clientId) {
        sessionService.validate(token);
        return historyService.listThreads(clientId);
    }

    @GetMapping("/history/threads/{id}")
    public ThreadDetailResponse historyThread(@RequestHeader("X-Session-Token") String token,
                                              @RequestHeader("X-Client-Id") String clientId,
                                              @PathVariable UUID id) {
        sessionService.validate(token);
        return historyService.getThread(clientId, id);
    }

    @PatchMapping("/history/threads/{id}")
    public ThreadDetailResponse renameHistoryThread(@RequestHeader("X-Session-Token") String token,
                                                    @RequestHeader("X-Client-Id") String clientId,
                                                    @PathVariable UUID id,
                                                    @RequestBody RenameThreadRequest request) {
        sessionService.validate(token);
        return historyService.renameThread(clientId, id, request == null ? null : request.title());
    }

    @DeleteMapping("/history/threads/{id}")
    public Map<String, Object> deleteHistoryThread(@RequestHeader("X-Session-Token") String token,
                                                   @RequestHeader("X-Client-Id") String clientId,
                                                   @PathVariable UUID id) {
        sessionService.validate(token);
        historyService.deleteThread(clientId, id);
        return Map.of("ok", true);
    }

    @PostMapping("/collab/shares")
    public Map<String, Object> createShare(@RequestBody ShareRequest request) {
        return collaborationMockService.createShare(request);
    }

    @PostMapping("/collab/shares/{shareId}/votes")
    public Map<String, Object> voteShare(@PathVariable String shareId, @RequestBody VoteRequest request) {
        return collaborationMockService.vote(shareId, request);
    }

    @PostMapping("/collab/shares/{shareId}/comments")
    public Map<String, Object> commentShare(@PathVariable String shareId, @RequestBody CommentRequest request) {
        return collaborationMockService.comment(shareId, request);
    }

    @GetMapping("/memory")
    public Map<String, Object> memory() {
        return collaborationMockService.memory();
    }

    @GetMapping("/guard/status")
    public Map<String, Object> guardStatus(@RequestHeader("X-Session-Token") String token,
                                           @RequestParam(required = false) UUID planId) {
        sessionService.validate(token);
        return guardService.status(planId, token);
    }

    @PostMapping("/speech/transcribe")
    public SpeechTranscribeResponse transcribe(@RequestHeader("X-Session-Token") String token,
                                               @RequestParam("file") MultipartFile file) {
        sessionService.validate(token);
        return speechTranscriptionService.transcribe(file);
    }

    @ExceptionHandler(SessionAuthException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(SessionAuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", ex.getMessage(),
                "status", "INVALID_REQUEST"
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "没有找到这个方案，请重新生成或检查链接是否正确。",
                "status", "NOT_FOUND"
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getMessage(),
                "status", "NOT_READY"
        ));
    }

    @ExceptionHandler(SpeechTranscriptionException.class)
    public ResponseEntity<Map<String, Object>> transcribeFailed(SpeechTranscriptionException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", ex.getMessage(),
                "status", "TRANSCRIBE_FAILED"
        ));
    }

    @ExceptionHandler(PlanBlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(PlanBlockedException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(Map.of(
                "error", ex.getMessage(),
                "planId", ex.getPlanId() == null ? "" : ex.getPlanId().toString(),
                "trace", planningService.traceFor(ex.getPlanId()),
                "provider", ex.getProvider(),
                "status", "BLOCKED"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception ex) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "系统暂时不可用，请稍后重试",
                "status", "ERROR"
        ));
    }
}
