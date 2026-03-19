package com.example.mcp.SonarqubeMcpDemo.config;

import com.example.mcp.SonarqubeMcpDemo.tools.SonarQubeMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the SonarQube tools with the Spring AI MCP server.
 * The {@link ToolCallbackProvider} bean is auto-detected by the MCP server auto-configuration
 * and published to any connected MCP client as available tools.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider sonarQubeToolCallbackProvider(SonarQubeMcpTools sonarQubeMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sonarQubeMcpTools)
                .build();
    }
}
