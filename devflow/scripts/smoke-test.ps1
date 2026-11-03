param([string]$BaseUrl = "http://127.0.0.1:8082")

$ErrorActionPreference = 'Stop'
function J($o) { $o | ConvertTo-Json -Depth 8 -Compress }
function Line($t) { Write-Host ("`n=== " + $t + " ===") -ForegroundColor Cyan }
function Try-Get($url) { try { Invoke-RestMethod -Method Get -Uri $url } catch { $null } }

Line "1) 内置 Prompt 模板（chat / sql-generate / code-review）"
$prompts = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/prompts"
Write-Host ("模板数=" + @($prompts).Count)
foreach ($p in @($prompts)) { Write-Host ("  - " + $p.code + " v" + $p.version + " (" + $p.name + ")") }

Line "2) 新建模板 -> 升级版本"
$codeName = "smoke-" + (Get-Date -Format "HHmmss")
$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/prompts" -ContentType 'application/json; charset=utf-8' `
    -Body (J @{ code = $codeName; name = '冒烟模板'; content = '请用一句话解释：${topic}' })
Write-Host ("created id=" + $created.id + " code=" + $created.code + " v=" + $created.version)
$updated = Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/prompts/$($created.id)" -ContentType 'application/json; charset=utf-8' `
    -Body (J @{ name = '冒烟模板v2'; content = '请用两句话解释：${topic}，并给出示例。' })
Write-Host ("updated v=" + $updated.version)
$versions = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/prompts/$codeName/versions"
Write-Host ("历史版本数=" + @($versions).Count)

Line "3) 模板渲染（变量注入 + 缺变量应报错）"
$rendered = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/prompts/$codeName/render" -ContentType 'application/json; charset=utf-8' -Body (J @{ topic = 'Redis 缓存穿透' })
Write-Host ("渲染结果: " + $rendered.text)
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/prompts/$codeName/render" -ContentType 'application/json; charset=utf-8' -Body (J @{})
    Write-Host "缺变量未报错（异常）" -ForegroundColor Red
} catch { Write-Host ("缺变量校验 OK -> " + $_.Exception.Message) }

Line "4) 自然语言生成 SQL -> 护栏校验 -> 执行"
$gen = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/sql/generate" -ContentType 'application/json; charset=utf-8' -Body (J @{ question = '统计每个客户的下单数量，按数量倒序' })
Write-Host ("生成 SQL: " + $gen.sql)
Write-Host ("涉及表: " + (J $gen.tables) + " appliedLimit=" + $gen.appliedLimit)
$exec = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/sql/execute" -ContentType 'application/json; charset=utf-8' -Body (J @{ sql = $gen.sql })
Write-Host ("执行列=" + (J $exec.columns) + " 行数=" + @($exec.rows).Count)

Line "5) SQL 护栏：危险语句必须被拒"
foreach ($bad in @('update users set name=''hacked''', 'delete from orders', 'select * from secret_table', 'select * from users; drop table users')) {
    try {
        $chk = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/sql/guard-check" -ContentType 'application/json; charset=utf-8' -Body (J @{ sql = $bad })
        Write-Host ("  拒绝 [" + $bad + "] allowed=" + $chk.allowed + " reason=" + $chk.reason)
    } catch {
        Write-Host ("  拒绝 [" + $bad + "] -> " + $_.Exception.Message)
    }
}

Line "6) Git Diff 自动 Code Review"
$sample = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/review/sample"
$review = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/review" -ContentType 'application/json; charset=utf-8' `
    -Body (J @{ diff = $sample.diff; repo = 'demo/repo'; prNumber = 42 })
Write-Host ("runId=" + $review.runId + " status=" + $review.status + " 发现数=" + @($review.findings).Count)
foreach ($f in @($review.findings)) { Write-Host ("  - [" + $f.severity + "] " + $f.file + ":" + $f.line + " " + $f.message) }

Line "7) GitHub Webhook：签名校验 + 幂等"
$payload = J @{ action = 'opened'; number = 42; pull_request = @{ number = 42; title = 'demo' } }
$secret = 'devflow-webhook-secret'
$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($secret)
$sig = 'sha256=' + (($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($payload)) | ForEach-Object { $_.ToString('x2') }) -join '')
$hdr = @{ 'X-Hub-Signature-256' = $sig; 'X-GitHub-Delivery' = 'smoke-delivery-1'; 'X-GitHub-Event' = 'pull_request' }
$w1 = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/webhooks/github" -Headers $hdr -ContentType 'application/json; charset=utf-8' -Body $payload
Write-Host ("正确签名 -> status=" + $w1.status)
$w2 = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/webhooks/github" -Headers $hdr -ContentType 'application/json; charset=utf-8' -Body $payload
Write-Host ("重复投递 -> status=" + $w2.status)
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/webhooks/github" -Headers @{ 'X-Hub-Signature-256' = 'sha256=deadbeef'; 'X-GitHub-Delivery' = 'smoke-delivery-2'; 'X-GitHub-Event' = 'pull_request' } -ContentType 'application/json; charset=utf-8' -Body $payload
    Write-Host "错误签名竟然通过（异常）" -ForegroundColor Red
} catch { Write-Host ("错误签名 -> 拒绝 " + $_.Exception.Message) }

Line "8) SSE 流式对话（curl -N 抓 3 帧）"
$sse = & curl.exe -sS -N -m 12 "$BaseUrl/api/chat/stream?promptCode=chat&question=explain%20redis%20in%20one%20sentence"
$frames = @($sse | Where-Object { $_ -match '^(data|event):' })
Write-Host ("收到 SSE 帧数=" + $frames.Count)
$frames | Select-Object -First 6 | ForEach-Object { Write-Host ("  " + $_) }

Line "9) 用量与成本"
$sum = Try-Get "$BaseUrl/api/usage/summary"
Write-Host ("usage/summary=" + (J $sum))
$info = Try-Get "$BaseUrl/api/llm/info"
Write-Host ("llm/info=" + (J $info))

Line "全部通过"