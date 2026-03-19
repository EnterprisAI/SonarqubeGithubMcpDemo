# SonarqubeGithubMcpDemo — SonarQube + GitHub MCP Integration

This is an enhanced version of `SonarqubeMcpDemo` that adds **GitHub MCP server** integration
to deliver the full developer workflow envisioned by Mahesh:

> _"Developer writes code → scan → LLM fix suggestions → developer fixes → PR → pipeline passes ✅"_

---

## What Is New in This Project

| Feature | SonarqubeMcpDemo | SonarqubeGithubMcpDemo |
|---------|:---:|:---:|
| SonarQube tools via MCP | ✅ | ✅ |
| `/chat` endpoint (ask questions) | ✅ | ✅ (also answers GitHub questions) |
| GitHub MCP server integration | ❌ | ✅ |
| PR webhook → auto-analysis | ❌ | ✅ |
| `/analyze-pr` manual endpoint | ❌ | ✅ |
| LLM posts fix suggestions to PR | ❌ | ✅ |

---

## Architecture

```
Developer creates / updates PR
           │
           │  GitHub webhook (POST /webhook/github)
           ▼
┌──────────────────────────────────────────────────────────────┐
│              SonarqubeGithubMcpDemo  :8080                   │
│                                                              │
│  GitHubWebhookController                                     │
│       │                                                      │
│       ▼                                                      │
│  PRAnalysisService  ──────  ChatClient (Spring AI)           │
│                                   │                          │
│                          ┌────────┴────────┐                 │
│                          │                 │                 │
│                   SonarQube Tools    GitHub MCP Tools        │
│                   (local @Tool)      (via MCP client)        │
└──────────────────────────────────────────────────────────────┘
           │                          │
           │  SonarQube REST API      │  GitHub REST API
           ▼                          ▼
    SonarQube :9000          GitHub MCP Server :3000
                                      │
                                      │ GitHub API
                                      ▼
                                 github.com
```

**How it works end-to-end:**
1. Developer opens a PR on GitHub
2. GitHub sends a `pull_request` webhook to `POST /webhook/github`
3. `PRAnalysisService` builds a prompt and calls the LLM
4. The LLM calls **GitHub MCP tools** to get PR details and changed files
5. The LLM calls **SonarQube tools** to get issues for the project
6. The LLM matches issues to changed files and composes fix suggestions
7. The LLM calls **GitHub MCP tools** to post a review comment on the PR
8. Developer sees the suggestions inline on the PR and fixes them
9. Pipeline runs → 0 new alerts ✅

---

## Prerequisites

- Java 17+
- Docker Desktop (running)
- A GitHub Personal Access Token (PAT) with `repo` scope
- A SonarQube project already scanned (or use the steps below to set one up)

---

## Step 1 — Start Infrastructure via Docker Compose

```cmd
cd SonarqubeGithubMcpDemo

set GITHUB_TOKEN=ghp_your_github_pat_here
docker-compose up -d
```

This starts:
- **SonarQube** at `http://localhost:9000`
- **GitHub MCP Server** at `http://localhost:3000`

Wait ~60 seconds for SonarQube to become healthy, then open `http://localhost:9000`.

### Troubleshooting GitHub MCP Server

The GitHub MCP server image may use a slightly different command flag. If the container exits,
check its logs:

```cmd
docker logs github-mcp-server
```

If the `--transport sse` flag is not supported, try `streamable-http` instead:

```cmd
docker-compose down
```

Edit `docker-compose.yml` line:
```yaml
command: ["--transport", "streamable-http", "--port", "3000"]
```

And update `application.yaml` accordingly (use the matching client connection type).

---

## Step 2 — Set Up SonarQube (First Time Only)

### Change admin password

```cmd
curl -X POST "http://localhost:9000/api/users/change_password" ^
  -u admin:admin ^
  -d "login=admin&previousPassword=admin&password=Admin@123456"
```

### Generate a User Token

```cmd
curl -X POST "http://localhost:9000/api/user_tokens/generate" ^
  -u admin:Admin@123456 ^
  -d "name=github-mcp-demo-token&type=USER_TOKEN"
```

Copy the `token` value from the response — this is your `SONARQUBE_TOKEN`.

### Scan your project into SonarQube

Run this from the project you want to analyze (not this Spring Boot project):

```cmd
.\gradlew sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=squ_yourtoken -Dsonar.projectKey=my-project
```

Or with the standalone sonar-scanner CLI:
```cmd
sonar-scanner -Dsonar.projectKey=my-project -Dsonar.sources=. -Dsonar.host.url=http://localhost:9000 -Dsonar.token=squ_yourtoken
```

---

## Step 3 — Build and Run the Spring Boot App

```cmd
cd SonarqubeGithubMcpDemo

.\gradlew build

set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_yourtoken
set GITHUB_TOKEN=ghp_your_github_pat_here
set GITHUB_MODEL=gpt-4o-mini

java -jar build\libs\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`.

> **Important:** Docker Compose must be running before you start the Spring Boot app, because
> Spring AI connects to the GitHub MCP server at startup to discover available tools.

---

## Step 4 — Test the Endpoints

### A. General Chat (SonarQube + GitHub questions)

```cmd
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: text/plain" ^
  -d "List all SonarQube projects"
```

```cmd
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: text/plain" ^
  -d "What files were changed in PR #1 of myorg/myrepo?"
```

### B. Manual PR Analysis

Trigger analysis without a webhook — useful for testing:

```cmd
curl -X POST "http://localhost:8080/analyze-pr?owner=myorg&repo=myrepo&prNumber=1&sonarProjectKey=myrepo"
```

**PowerShell:**
```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8080/analyze-pr?owner=myorg&repo=myrepo&prNumber=1&sonarProjectKey=myrepo" `
  -Method POST
```

The response is a plain-text summary of:
- Files changed in the PR
- SonarQube issues found
- Issues matched to changed files
- Whether a review comment was posted to the PR

### C. GitHub Webhook (Automatic Trigger)

Register the webhook in your GitHub repository:

1. Go to **Repository → Settings → Webhooks → Add webhook**
2. **Payload URL**: `http://your-server:8080/webhook/github`
   - For local testing, use [ngrok](https://ngrok.com/): `ngrok http 8080`
3. **Content type**: `application/json`
4. **Secret**: set `GITHUB_WEBHOOK_SECRET` env var and enter the same value here (optional but recommended)
5. **Events**: select "Pull requests"

Now open or push to a PR — the analysis runs automatically and a review comment appears on the PR.

**Optional: Set GITHUB_WEBHOOK_SECRET for security**

```cmd
set GITHUB_WEBHOOK_SECRET=my-secret-value
```

Then set the same value in the GitHub webhook settings. The app verifies the HMAC-SHA256 signature on every webhook call.

**Override SonarQube project key per webhook:**

If your SonarQube project key differs from the repository name, add the header:
```
X-Sonar-Project-Key: my-custom-project-key
```

---

## Environment Variables Reference

| Variable | Default | Required | Description |
|----------|---------|:--------:|-------------|
| `GITHUB_TOKEN` | — | ✅ | GitHub PAT — used for both GitHub Models API and GitHub MCP server |
| `SONARQUBE_TOKEN` | — | ✅ | SonarQube User Token |
| `SONARQUBE_URL` | `http://localhost:9000` | | SonarQube base URL |
| `GITHUB_MODEL` | `gpt-4o-mini` | | LLM model name (GitHub Models) |
| `GITHUB_MCP_URL` | `http://localhost:3000/sse` | | GitHub MCP server SSE endpoint |
| `GITHUB_WEBHOOK_SECRET` | _(empty)_ | | Optional webhook HMAC secret |

---

## Available Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/chat` | General Q&A using SonarQube + GitHub tools |
| `POST` | `/analyze-pr` | Manual PR analysis (query params: owner, repo, prNumber, sonarProjectKey) |
| `POST` | `/webhook/github` | GitHub webhook receiver (pull_request events) |
| `GET` | `/sse` | MCP server SSE endpoint (for Claude Desktop) |
| `POST` | `/mcp/message` | MCP server message endpoint |

---

## Available Tools (LLM Tool Set)

### SonarQube Tools (local)

| Tool | Description |
|------|-------------|
| `listProjects` | List all SonarQube projects |
| `getProjectMetrics` | Bugs, vulnerabilities, code smells, coverage, ratings |
| `getQualityGateStatus` | PASSED/FAILED status with conditions |
| `searchIssues` | Issues filtered by severity and type |
| `getSecurityHotspots` | Security hotspots for manual review |
| `getServerInfo` | SonarQube server version |

### GitHub Tools (via GitHub MCP Server)

These are dynamically discovered from the GitHub MCP server at startup.
Typical tools available include:

| Tool | Description |
|------|-------------|
| `get_pull_request` | Get PR details (title, description, branch, author) |
| `list_pull_request_files` | List all files changed in a PR |
| `create_pull_request_review` | Post a review (COMMENT / APPROVE / REQUEST_CHANGES) |
| `add_pull_request_review_comment` | Add inline comment on a specific line |
| `get_file_contents` | Read file content from the repository |
| `list_commits` | List commits on a branch |
| `search_repositories` | Search for repositories |

---

## Integration with Claude Desktop

This app also works as an MCP server for Claude Desktop. Add to your config:

**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "sonarqube-github": {
      "command": "java",
      "args": ["-jar", "C:\\path\\to\\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar"],
      "env": {
        "SONARQUBE_URL": "http://localhost:9000",
        "SONARQUBE_TOKEN": "squ_yourtoken",
        "GITHUB_TOKEN": "ghp_your_token",
        "GITHUB_MCP_URL": "http://localhost:3000/sse"
      }
    }
  }
}
```

Claude Desktop will then have access to **both** SonarQube and GitHub tools in one server.

---

## Mahesh's Vision — What This POC Covers

| Capability | Status | Notes |
|------------|:------:|-------|
| Trigger on PR creation | ✅ | GitHub webhook → `/webhook/github` |
| SonarQube issues aggregation | ✅ | `searchIssues` MCP tool |
| GitHub PR context (files changed) | ✅ | GitHub MCP `list_pull_request_files` |
| LLM fix suggestions | ✅ | LLM matches issues to files |
| Post suggestions to PR | ✅ | GitHub MCP `create_pull_request_review` |
| Multi-tool orchestration | ✅ | SonarQube + GitHub in one LLM call |
| Snyk integration | 🔜 | V2 — to be added in `SnykGithubMcpDemo` |
| IDE plugin | 🔜 | Future — out of scope for POC |
| Pre-commit hook | 🔜 | Future — developer-level trigger |

---

## Example LLM Interaction

When a PR is opened, the LLM will automatically:

```
1. [Calls get_pull_request] → PR #5: "Add payment processing feature"
                               Branch: feature/payment | Files: 3 changed

2. [Calls list_pull_request_files] → PaymentService.java, PaymentController.java,
                                      PaymentRepository.java

3. [Calls searchIssues for "my-service"] → 12 open issues found
   - 2 CRITICAL bugs in PaymentService.java
   - 1 MAJOR vulnerability in PaymentController.java
   - 3 CODE_SMELLs in PaymentRepository.java

4. [Calls create_pull_request_review] → Posts review comment:

   ## SonarQube Analysis for PR #5

   Found 6 issues in files changed by this PR:

   **PaymentService.java**
   - 🔴 CRITICAL BUG (line 42): NullPointerException risk — `payment.getAmount()`
     may return null. Fix: add null check before calling `.getAmount()`.
   - 🔴 CRITICAL BUG (line 78): Resource leak — `Connection` not closed in finally block.
     Fix: use try-with-resources.

   **PaymentController.java**
   - 🟠 MAJOR VULNERABILITY (line 15): HTTP parameter exposed to SQL query.
     Fix: use parameterized queries / PreparedStatement.

   [... remaining issues ...]

5. Returns summary: "3 files changed, 12 total SonarQube issues, 6 matched to changed files.
   Review comment posted successfully."
```
