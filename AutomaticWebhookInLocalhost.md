How the Automatic Webhook Flow Works

    Right now you trigger analysis manually via POST /analyze-pr?.... The webhook makes it automatic — GitHub calls your server whenever a PR is opened or updated.

    The 3 pieces involved

    1. Your server's webhook endpoint
    Your app already has /webhook/github listening for POST requests. When GitHub sends a PR event, the controller reads the payload, extracts owner, repo, prNumber, and calls
    PRAnalysisService.analyzePullRequest(...) automatically.

    2. A public URL for your server
    GitHub needs to reach your server over the internet. Your laptop's localhost:8080 isn't reachable from GitHub's servers, so you need a tunnel:

    # Install ngrok (one-time)
    # Then run:
    ngrok http 8080

    ngrok gives you a URL like https://abc123.ngrok.io that forwards to your localhost:8080. Every request to that URL hits your Spring Boot app.

    3. The webhook secret (GITHUB_WEBHOOK_SECRET)
    This is a shared secret you pick (any random string, e.g. mysecret123). GitHub signs every webhook payload with it using HMAC-SHA256 and sends the signature in the X-Hub-Signature-256 header. Your app
    verifies this signature to confirm the request is actually from GitHub, not a random attacker.

    ---
    Step-by-step setup

    Step 1 — Start ngrok
    ngrok http 8080
    # Note the Forwarding URL: https://abc123.ngrok.io

    Step 2 — Set your webhook secret when starting the app
    GITHUB_WEBHOOK_SECRET=mysecret123 ... java -jar app.jar

    Step 3 — Register the webhook on GitHub

    Go to your repo → Settings → Webhooks → Add webhook:
    - Payload URL: https://abc123.ngrok.io/webhook/github
    - Content type: application/json
    - Secret: mysecret123 (same value as GITHUB_WEBHOOK_SECRET)
    - Events: select "Pull requests" only

    Step 4 — Test it

    Open or push to a PR in EnterprisAI/springboot-sonar-snyk-demo. GitHub sends a POST to your webhook URL → ngrok forwards it to your app → analysis runs → review is posted. No manual curl needed.

    ---
    For production (beyond dev)

    ngrok URLs change every restart (on free tier). For a stable setup you'd deploy the Spring Boot app to a cloud VM/container (AWS, Azure, GCP, Railway, etc.) and use its fixed public URL directly in the
    GitHub webhook config — no ngrok needed.