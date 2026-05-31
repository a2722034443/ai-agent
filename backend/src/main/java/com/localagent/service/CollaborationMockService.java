package com.localagent.service;

import com.localagent.dto.ApiDtos.CommentRequest;
import com.localagent.dto.ApiDtos.ShareRequest;
import com.localagent.dto.ApiDtos.VoteRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class CollaborationMockService {
    private final Map<String, Map<String, Object>> shares = new ConcurrentHashMap<>();

    public Map<String, Object> createShare(ShareRequest request) {
        String shareId = "share_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("shareId", shareId);
        share.put("planId", request == null || request.planId() == null ? "" : request.planId().toString());
        share.put("selectedRank", request == null || request.selectedRank() == null ? 1 : request.selectedRank());
        share.put("title", "小明分享的出行方案");
        share.put("votes", new LinkedHashMap<Integer, Integer>());
        share.put("comments", new ArrayList<Map<String, Object>>());
        share.put("createdAt", Instant.now().toString());
        shares.put(shareId, share);
        return share;
    }

    public Map<String, Object> vote(String shareId, VoteRequest request) {
        Map<String, Object> share = shares.computeIfAbsent(shareId, this::emptyShare);
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> votes = (Map<Integer, Integer>) share.get("votes");
        int rank = request == null || request.rank() == null ? 1 : request.rank();
        votes.put(rank, votes.getOrDefault(rank, 0) + 1);
        share.put("lastVoter", request == null || request.voter() == null ? "同行人" : request.voter());
        share.put("updatedAt", Instant.now().toString());
        return share;
    }

    public Map<String, Object> comment(String shareId, CommentRequest request) {
        Map<String, Object> share = shares.computeIfAbsent(shareId, this::emptyShare);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) share.get("comments");
        comments.add(Map.of(
                "id", UUID.randomUUID().toString(),
                "author", request == null || request.author() == null ? "同行人" : request.author(),
                "text", request == null || request.text() == null ? "" : request.text(),
                "createdAt", Instant.now().toString()
        ));
        share.put("aiAdjustment", "已收到意见，AI 会自动把方案时间、餐厅或路线调整后同步给所有人。");
        share.put("updatedAt", Instant.now().toString());
        return share;
    }

    public Map<String, Object> memory() {
        return Map.of(
                "mode", "mock",
                "engine", "all_member_memory",
                "tags", List.of("老婆减肥", "孩子要亲子设施", "朋友不吃辣", "周末不跑远"),
                "note", "下次规划会自动适配全员偏好。"
        );
    }

    public Map<String, Object> guardStatus() {
        return Map.of(
                "mode", "mock",
                "status", "WATCHING",
                "summary", "天气、路况、商家状态正常；餐厅剩余 4 座。",
                "steps", List.of(
                        Map.of("name", "天气晴，25 度，适合出行", "status", "done"),
                        Map.of("name", "全程通畅，没有拥堵", "status", "done"),
                        Map.of("name", "乐园正常营业，餐厅剩余 4 座", "status", "done")
                ),
                "fallback", "如下雨或满位，AI 会自动换室内乐园或同类型餐厅并通知所有人。"
        );
    }

    private Map<String, Object> emptyShare(String shareId) {
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("shareId", shareId);
        share.put("votes", new LinkedHashMap<Integer, Integer>());
        share.put("comments", new ArrayList<Map<String, Object>>());
        return share;
    }
}
