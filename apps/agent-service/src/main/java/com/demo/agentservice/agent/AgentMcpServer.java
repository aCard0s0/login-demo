package com.demo.agentservice.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One MCP server an agent may use, and how far: READ or WRITE. The name is short and unique within the agent
 * because it prefixes every tool name the model sees ({@code todos__list_todos}), which keeps two servers'
 * tools apart.
 *
 * <p>Either a fixed {@code Authorization} header value, or the token of the user who started the run,
 * forwarded as-is ({@code forwardCallerToken}) -- the way the built-in todo server knows whose todos to show.
 */
// ponytail: the header value is stored in plain text, like the database credentials in this demo. Encrypt it
// with a key from the environment before any of this holds a real secret.
@Entity
@Table(name = "agent_mcp_servers", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentMcpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Agent agent;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(length = 2000)
    private String authHeader;

    @Column(nullable = false)
    private boolean forwardCallerToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Access access;

    public AgentMcpServer(Agent agent, String name, String url, String authHeader, boolean forwardCallerToken, Access access) {
        this.agent = agent;
        this.name = name;
        this.url = url;
        this.authHeader = authHeader;
        this.forwardCallerToken = forwardCallerToken;
        this.access = access;
    }
}
