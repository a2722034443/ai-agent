package com.localagent.controller;

import com.localagent.dto.ApiDtos.ConfirmRequest;
import com.localagent.dto.ApiDtos.FeedbackRequest;
import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import com.localagent.dto.ApiDtos.SessionRequest;
import com.localagent.dto.ApiDtos.SessionResponse;
import com.localagent.service.PlanBlockedException;
import com.localagent.service.PlanningService;
import com.localagent.service.SessionService;
import java.util.Map;
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

    public ApiController(SessionService sessionService, PlanningService planningService) {
        this.sessionService = sessionService;
        this.planningService = planningService;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody SessionRequest request) {
        return sessionService.create(request.nickname());
    }

    @PostMapping("/plans")
    public PlanResponse createPlan(@RequestHeader("X-Session-Token") String token, @RequestBody PlanRequest request) {
        sessionService.validate(token);
        return planningService.createPlan(token, request.message());
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PlanBlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(PlanBlockedException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(Map.of(
                "error", ex.getMessage(),
                "planId", ex.getPlanId() == null ? "" : ex.getPlanId().toString(),
                "trace", planningService.traceFor(ex.getPlanId()),
                "provider", ex.getProvider(),
                "status", "ERROR"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
    }
}
