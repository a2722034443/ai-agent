package com.localagent.controller;

import com.localagent.dto.ApiDtos.ConfirmRequest;
import com.localagent.dto.ApiDtos.CommentRequest;
import com.localagent.dto.ApiDtos.FeedbackRequest;
import com.localagent.dto.ApiDtos.NearbyPoiRequest;
import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import com.localagent.dto.ApiDtos.SessionRequest;
import com.localagent.dto.ApiDtos.SessionResponse;
import com.localagent.dto.ApiDtos.ShareRequest;
import com.localagent.dto.ApiDtos.VoteRequest;
import com.localagent.service.AmapPoiSearchTool;
import com.localagent.service.CollaborationMockService;
import com.localagent.service.PlanBlockedException;
import com.localagent.service.PlanningService;
import com.localagent.service.SessionAuthException;
import com.localagent.service.SessionService;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SessionService sessionService;
    private final PlanningService planningService;
    private final AmapPoiSearchTool poiSearchTool;
    private final CollaborationMockService collaborationMockService;

    public ApiController(SessionService sessionService, PlanningService planningService,
                         AmapPoiSearchTool poiSearchTool,
                         CollaborationMockService collaborationMockService) {
        this.sessionService = sessionService;
        this.planningService = planningService;
        this.poiSearchTool = poiSearchTool;
        this.collaborationMockService = collaborationMockService;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody(required = false) SessionRequest request) {
        return sessionService.create(request == null ? null : request.nickname());
    }

    @PostMapping("/plans")
    public PlanResponse createPlan(@RequestHeader("X-Session-Token") String token, @RequestBody PlanRequest request) {
        sessionService.validate(token);
        return planningService.createPlan(token, request);
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
    public PlanResponse confirm(@PathVariable UUID id, @RequestBody ConfirmRequest request) {
        return planningService.confirm(id, request.rank());
    }

    @PostMapping("/plans/{id}/feedback")
    public PlanResponse feedback(@PathVariable UUID id, @RequestBody FeedbackRequest request) {
        return planningService.feedback(id, request.message());
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
    public Map<String, Object> guardStatus() {
        return collaborationMockService.guardStatus();
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
    public ResponseEntity<Map<String, Object>> serverError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "系统暂时不可用，请稍后重试",
                "status", "ERROR"
        ));
    }
}
