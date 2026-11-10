param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'

function Send-Json([string]$Path, [object]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -Headers $Headers `
        -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 10)
}

$account = Send-Json '/api/auth/register' @{
    email = "demo-$([guid]::NewGuid().ToString('N'))@example.com"
    password = "$([guid]::NewGuid())!"
}
$headers = @{ Authorization = "Bearer $($account.accessToken)" }

$topic = Send-Json '/api/topics' @{
    subject = 'Java'
    name = 'Collections'
    priority = 4
    estimatedMinutes = 180
} $headers

$null = Send-Json "/api/topics/$($topic.id)/quiz-results" @{
    requestId = [guid]::NewGuid().ToString()
    correctAnswers = 4
    totalQuestions = 10
} $headers

$mcpHeaders = $headers.Clone()
$mcpHeaders.Accept = 'application/json, text/event-stream'
$plan = Send-Json '/mcp' @{
    jsonrpc = '2.0'
    id = 1
    method = 'tools/call'
    params = @{
        name = 'create_study_plan'
        arguments = @{
            requestId = [guid]::NewGuid().ToString()
            startDate = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
            days = 3
            dailyMinutes = 60
        }
    }
} $mcpHeaders

if ($plan.result.isError -or $plan.error) {
    throw "MCP request failed: $($plan | ConvertTo-Json -Depth 10)"
}

[pscustomobject]@{
    AccessToken = $account.accessToken
    ExpiresAt = $account.expiresAt
    TopicId = $topic.id
    Plan = $plan.result.structuredContent.data
}
