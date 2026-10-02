package com.demo.agentservice.activity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Appends to and reads an agent's history. Who may read which agent's history is {@code AgentService}'s call. */
@Service
public class ActivityLog {

    private final ActivityRepository rows;

    public ActivityLog(ActivityRepository rows) {
        this.rows = rows;
    }

    @Transactional
    public Activity record(Long agentId, String kind, String detail) {
        return rows.save(new Activity(agentId, kind, detail));
    }

    /** The last hundred lines, newest first. */
    public List<Activity> recent(Long agentId) {
        return rows.findTop100ByAgentIdOrderByIdDesc(agentId);
    }

    @Transactional
    public void deleteFor(Long agentId) {
        rows.deleteByAgentId(agentId);
    }

    /** The first few hundred characters of something, for a log line. */
    public static String brief(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 199) + "…" : flat;
    }
}
