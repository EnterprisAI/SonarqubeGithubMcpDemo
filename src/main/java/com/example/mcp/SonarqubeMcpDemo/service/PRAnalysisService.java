package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.tools.SonarQubeMcpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core service that orchestrates PR analysis using an LLM with access to both
 * SonarQube tools (local @Tool beans) and GitHub tools (via GitHub MCP server).
 *
 * <p>The {@link SyncMcpToolCallbackProvider} is auto-created by Spring AI when the
 * {@code spring.ai.mcp.client.sse.connections.github} property is configured and the
 * GitHub MCP server is reachable. It exposes all GitHub MCP tools to the ChatClient.
 *
 * <p>Flow for PR analysis:
 * <ol>
 *   <li>LLM calls GitHub MCP tool {@code get_pull_request} to get PR context</li>
 *   <li>LLM calls GitHub MCP tool {@code list_pull_request_files} to see changed files</li>
 *   <li>LLM calls SonarQube tool {@code searchIssues} to get open issues</li>
 *   <li>LLM matches issues to changed files and composes fix suggestions</li>
 *   <li>LLM calls GitHub MCP tool {@code create_pull_request_review} to post review</li>
 * </ol>
 */
@Service
public class PRAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PRAnalysisService.class);

    private static final String SYSTEM_PROMPT = """
            You are a code quality and security assistant with access to SonarQube and GitHub.

            You have two sets of tools:
            1. SonarQube tools — to query code quality issues, metrics, and quality gate status
            2. GitHub tools — to read pull request details, list changed files, and post review comments

            When asked to analyze a pull request:
            - Use get_pull_request to understand the PR context (title, description, branch)
            - Use list_pull_request_files to see exactly which files were changed
            - Use searchIssues to get SonarQube issues for the relevant project
            - Match SonarQube issues to the changed files (by file path / component name)
            - For each matched issue, write a concise, actionable fix suggestion
            - Use create_pull_request_review to post all suggestions as a single COMMENT review
              (do NOT use APPROVE or REQUEST_CHANGES — use COMMENT only)
            - Return a plain-text summary: how many files changed, issues found, and comments posted

            When answering general questions:
            - Use the available tools to fetch live data
            - Always cite the project key or PR number in your response
            - Be concise and developer-friendly
            """;

    private final ChatClient chatClient;

    /**
     * Builds the ChatClient with SonarQube tools and — when available — GitHub MCP tools.
     *
     * @param builder                    Spring AI ChatClient builder (auto-configured)
     * @param sonarQubeMcpTools          local SonarQube tools (@Tool annotated)
     * @param githubMcpToolCallbackProvider auto-created by Spring AI MCP client starter when the
     *                                   GitHub MCP server SSE endpoint is reachable; null if not
     *                                   configured or if the server is unavailable at startup
     */
    public PRAnalysisService(
            ChatClient.Builder builder,
            SonarQubeMcpTools sonarQubeMcpTools,
            @Autowired(required = false) SyncMcpToolCallbackProvider githubMcpToolCallbackProvider) {

        var chatClientBuilder = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(sonarQubeMcpTools);

        if (githubMcpToolCallbackProvider != null) {
            try {
                chatClientBuilder.defaultToolCallbacks(githubMcpToolCallbackProvider.getToolCallbacks());
                log.info("GitHub MCP tools registered successfully");
            } catch (Exception e) {
                log.warn("Could not register GitHub MCP tools (GitHub MCP server may not be running): {}",
                        e.getMessage());
            }
        } else {
            log.info("SyncMcpToolCallbackProvider not available — GitHub tools will not be accessible. " +
                    "Ensure the GitHub MCP server is running and GITHUB_MCP_URL is set.");
        }

        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Analyzes a GitHub pull request using SonarQube data and posts review comments.
     *
     * @param owner           GitHub repository owner (user or org)
     * @param repo            GitHub repository name
     * @param prNumber        Pull request number
     * @param sonarProjectKey SonarQube project key to check for issues
     * @return LLM-generated summary of the analysis and actions taken
     */
    public String analyzePullRequest(String owner, String repo, int prNumber, String sonarProjectKey) {
        String prompt = String.format("""
                Analyze GitHub pull request #%d in the repository %s/%s.

                Steps to follow:
                1. Call get_pull_request with owner="%s", repo="%s", pullNumber=%d
                   to get the PR title, description, and branch information.
                2. Call list_pull_request_files with the same owner/repo/pullNumber
                   to get the list of changed files.
                3. Call searchIssues for SonarQube project key "%s" with empty severity and type
                   to get all open issues.
                4. For each SonarQube issue whose component path matches one of the changed files,
                   compose a specific fix suggestion (include: issue message, severity, file path, fix idea).
                5. Call create_pull_request_review with:
                   - owner="%s", repo="%s", pullNumber=%d
                   - event="COMMENT"
                   - body: a markdown summary listing all matched issues and their fix suggestions
                6. Return a plain-text summary:
                   - Number of files changed in the PR
                   - Number of SonarQube issues found for the project
                   - Number of issues that matched changed files
                   - Whether the review comment was posted successfully
                """,
                prNumber, owner, repo,
                owner, repo, prNumber,
                sonarProjectKey,
                owner, repo, prNumber);

        return chatClient.prompt(prompt).call().content();
    }

    /**
     * General-purpose chat using both SonarQube and GitHub tools.
     *
     * @param question user question
     * @return LLM response
     */
    public String chat(String question) {
        return chatClient.prompt(question).call().content();
    }
}
