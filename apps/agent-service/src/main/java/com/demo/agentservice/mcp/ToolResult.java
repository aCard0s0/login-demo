package com.demo.agentservice.mcp;

import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a tool call came back with, for the model: the content list and structured content exactly as the
 * downstream server produced them -- an image stays an image, with its MIME type and bytes -- plus whether it
 * was an error. Only the activity log flattens it, through {@link #summary()}.
 */
record ToolResult(List<Content> content, Object structuredContent, boolean error) {

    static ToolResult ok(String text) {
        return new ToolResult(List.of(new TextContent(text)), null, false);
    }

    static ToolResult error(String text) {
        return new ToolResult(List.of(new TextContent(text)), null, true);
    }

    /** A downstream result, passed through: nothing is read, renamed or re-encoded. */
    static ToolResult of(CallToolResult result) {
        return new ToolResult(result.content() == null ? List.of() : result.content(), result.structuredContent(),
                Boolean.TRUE.equals(result.isError()));
    }

    /** One line for the log: the text parts as they are, anything else named by kind so the owner knows it was there. */
    String summary() {
        String parts = content.stream().map(part -> switch (part) {
            case TextContent text -> text.text();
            case ImageContent image -> "[image " + image.mimeType() + "]";
            case AudioContent audio -> "[audio " + audio.mimeType() + "]";
            default -> "[" + part.type() + "]";
        }).collect(Collectors.joining("\n"));
        return structuredContent == null ? parts : parts + (parts.isEmpty() ? "" : "\n") + "[structured " + structuredContent + "]";
    }
}
