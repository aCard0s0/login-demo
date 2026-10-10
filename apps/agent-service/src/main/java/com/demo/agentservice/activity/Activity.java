package com.demo.agentservice.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One line of an agent's history: an external agent connecting as it, a tool it called, a tool it was refused,
 * or a change to its configuration. Plain text, so the page can show it as it is.
 *
 * <p>A column rather than a foreign key to the agent: the log is read far more than it is joined, and it is
 * deleted with its agent by {@link ActivityLog#deleteFor}.
 */
@Entity
// The index carries the id too, so "the last hundred of this agent" walks it backwards and stops, rather than sorting every line the agent has.
@Table(name = "agent_activity", indexes = @Index(name = "agent_activity_agent_id_id", columnList = "agentId, id"))
@Getter
@NoArgsConstructor
public class Activity {

    public static final String CONNECTED = "connected";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_DENIED = "tool_denied";
    public static final String CONFIG_CHANGED = "config_changed";

    static final int MAX_DETAIL = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentId;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false, length = MAX_DETAIL)
    private String detail;

    public Activity(Long agentId, String kind, String detail) {
        this.agentId = agentId;
        this.at = Instant.now();
        this.kind = kind;
        this.detail = detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL - 1) + "…" : detail;
    }
}
