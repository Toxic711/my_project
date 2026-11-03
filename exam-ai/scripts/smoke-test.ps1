param([string]$BaseUrl = "http://127.0.0.1:8081")

$ErrorActionPreference = 'Stop'
function J($obj) { $obj | ConvertTo-Json -Depth 8 -Compress }
function Line($t) { Write-Host ("`n=== " + $t + " ===") -ForegroundColor Cyan }

Line "1) 教师登录"
$tLogin = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body (J @{ username = 'teacher1'; password = 'teacher123' })
Write-Host ("role=" + $tLogin.user.role + " tokenLen=" + $tLogin.token.Length)
$tH = @{ Authorization = "Bearer $($tLogin.token)" }

Line "2) 学生登录 + 权限校验（学生调教师接口应 403）"
$sLogin = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body (J @{ username = 'student1'; password = 'student123' })
Write-Host ("role=" + $sLogin.user.role)
$sH = @{ Authorization = "Bearer $($sLogin.token)" }
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/questions/generate" -Headers $sH -ContentType 'application/json' `
        -Body (J @{ type = 'SINGLE'; knowledgePoint = 'x'; count = 1 })
    Write-Host "RBAC 校验失败：学生竟然能调出题接口" -ForegroundColor Red
} catch {
    Write-Host ("RBAC OK：学生调出题接口被拒 -> " + $_.Exception.Message)
}

Line "3) 学生视角考试列表（只应看到 PUBLISHED）"
$exams = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/exams" -Headers $sH
Write-Host ("考试数=" + $exams.Count + " status=" + $exams[0].status)
$examId = $exams[0].id

Line "4) 开始考试（发卷）"
# 幂等脚本：可能因为重复执行而遇到"已提交"的考试，遍历到一场可开始的
$start = $null
foreach ($ex in $exams) {
    try {
        $start = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/exams/$($ex.id)/start" -Headers $sH
        $examId = $ex.id
        break
    } catch { }
}
if ($null -eq $start) { throw "所有考试均已提交，无法继续冒烟（重启应用可重置 H2 内存库）" }
Write-Host ("examId=" + $examId + " recordId=" + $start.recordId + " 题目数=" + $start.questionCount + " 总分=" + $start.totalScore + " 续考=" + $start.resumed)
$recordId = $start.recordId
$qids = @($start.questions | ForEach-Object { $_.id })

Line "5) 防作弊：三档风险事件（正常 / 大幅偏转+视线离开 / 人脸丢失）"
$evt = @(
    @{ name = '正常帧';    body = @{ faceDetected = $true;  faceCount = 1; yaw = 3.0;  pitch = 2.0;  roll = 1.0;  gazeOffScreen = $false; ear = 0.28 } },
    @{ name = '大幅偏转';  body = @{ faceDetected = $true;  faceCount = 1; yaw = 46.0; pitch = 35.0; roll = 30.0; gazeOffScreen = $true;  ear = 0.14 } },
    @{ name = '人脸丢失';  body = @{ faceDetected = $false; faceCount = 0; yaw = 0.0;  pitch = 0.0;  roll = 0.0;  gazeOffScreen = $false; ear = 0.0  } }
)
$ts = 1000
foreach ($e in $evt) {
    $ts = $ts + 4000
    $payload = $e.body
    $payload.examRecordId = $recordId
    $payload.sessionId = 'smoke-session-1'
    $payload.eventTs = $ts
    $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/proctor/events" -Headers $sH -ContentType 'application/json; charset=utf-8' -Body (J $payload)
    Write-Host ("  " + $e.name + ": risk=" + $r.riskScore + " level=" + $r.riskLevel + " alert=" + $r.alertTriggered + " reason=" + $r.reason)
}

Line "6) 教师查询告警（按等级聚合）"
$alerts = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/proctor/alerts?examRecordId=$recordId" -Headers $tH
Write-Host ("告警总数=" + $alerts.total + " 分档=" + (J $alerts.byLevel))

Line "7) 提交答卷（第一次）"
$answers = @()
foreach ($q in $qids) { $answers += @{ questionId = $q; answer = 'A' } }
$sub1 = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/exams/records/$recordId/submit" -Headers $sH -ContentType 'application/json; charset=utf-8' -Body (J @{ requestId = 'smoke-1'; answers = $answers })
Write-Host ("得分=" + $sub1.score + "/" + $sub1.totalScore + " 正确=" + $sub1.correctCount + " idempotent=" + $sub1.idempotent)

Line "8) 重复提交（幂等：应 idempotent=true 且分数不变）"
$sub2 = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/exams/records/$recordId/submit" -Headers $sH -ContentType 'application/json; charset=utf-8' -Body (J @{ requestId = 'smoke-2'; answers = $answers })
Write-Host ("得分=" + $sub2.score + " idempotent=" + $sub2.idempotent)

Line "9) 查看答卷结果（含正确答案与解析）"
$res = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/exams/records/$recordId/result" -Headers $sH
Write-Host ("title=" + $res.title + " score=" + $res.score + "/" + $res.totalScore + " 题目数=" + $res.items.Count)

Line "10) LLM 出题（默认 mock，离线可用）"
$gen = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/questions/generate" -Headers $tH -ContentType 'application/json; charset=utf-8' -Body (J @{ type = 'SINGLE'; knowledgePoint = 'JVM 内存模型'; count = 2; difficulty = 'MEDIUM' })
Write-Host ("provider=" + $gen.provider + " fallback=" + $gen.fallback + " tokens=" + $gen.usage.totalTokens + " 题目数=" + $gen.questions.Count)
foreach ($q in $gen.questions) { Write-Host ("  - " + $q.stem) }

Line "11) LLM 用量统计（token 成本可观测）"
$usage = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/llm/usage" -Headers $tH
Write-Host ("tasks=" + $usage.taskCount + " success=" + $usage.successCount + " fallback=" + $usage.fallbackCount + " totalTokens=" + $usage.totalTokens + " avgLatency=" + $usage.avgLatencyMs + "ms")

Line "12) 缓存与运行态统计"
$stats = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/stats/cache" -Headers $tH
Write-Host ("lockBackend=" + $stats.lockBackend + " riskEngine=" + $stats.riskEngine)
foreach ($c in $stats.caches) { Write-Host ("  cache=" + $c.name + " hits=" + $c.hits + " misses=" + $c.misses + " hitRate=" + $c.hitRatePct + "%") }

Line "全部通过"