# Führt S1–S7 im frischen Testklon aus; S8 verlangt zusätzlich ein manuelles Quelltextreview.
[CmdletBinding()]
param([string] $MavenCommand = 'mvn')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$clientName = 'batch-writer-review-' + [guid]::NewGuid().ToString('N')
$clientStarted = $false
$restartJob = $null
$report = [System.Collections.Generic.List[object]]::new()
$receiptGroups = [System.Collections.Generic.List[object]]::new()
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDirectory = Join-Path $projectRoot "Erklärungen/Schritt 6 Code Beginn/Abnahme-$runId"

# PowerShell 5.1 behandelt umgeleitetes stderr sonst schon bei einer harmlosen Java-Warnung als Abbruch.
function Invoke-MavenTests([string] $LogFile) {
    $ErrorActionPreference = 'Continue'
    & $MavenCommand clean test 2>&1 | Tee-Object -FilePath $LogFile
    if ($LASTEXITCODE -ne 0) { throw 'S1 Maven tests failed' }
}

# Containerlogs dürfen stderr enthalten; über Erfolg entscheidet der native Exitcode.
function Get-WriterLogs([string] $ContainerId, [string] $Since = '') {
    $ErrorActionPreference = 'Continue'
    $arguments = @('logs')
    if ($Since -ne '') { $arguments += @('--since', $Since) }
    $arguments += $ContainerId
    $lines = & docker @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Writer logs unavailable' }
    return $lines
}

# Native Exitcodes werden ausdrücklich geprüft, da PowerShell sie nicht als Ausnahme behandelt.
function Invoke-Compose([string[]] $Arguments) {
    $result = & docker compose --env-file .env.example @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Compose failed: $($Arguments[0])" }
    return $result
}

# Kontrollabfragen verwenden die Container-Zugangsdaten und halten lokale Secrets aus der Ausgabe.
function Invoke-Database([string] $Query) {
    $result = $Query | & docker compose --env-file .env.example exec -T postgres sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'
    if ($LASTEXITCODE -ne 0) { throw 'Database query failed' }
    return $result
}

# Ein bereits gestarteter Testclient vermeidet einen neuen Container bei jedem Polling-Aufruf.
function Invoke-Client([hashtable] $Command) {
    $payload = ConvertTo-Json -InputObject $Command -Depth 10 -Compress
    $result = $payload | & docker exec -i $clientName python /checks/batch-writer-test-client.py
    if ($LASTEXITCODE -ne 0) { throw 'Internal test client failed' }
    $text = $result -join "`n"
    $parsed = ConvertFrom-Json -InputObject $text
    return $parsed
}

# Brokerzustände werden intern abgefragt; der Test verwendet ausschliesslich die Beispielvorlage.
function Invoke-Management([string] $Path, $Body = $null) {
    $command = @{ action='management'; path=$Path; user=$settings.RABBITMQ_USER; password=$settings.RABBITMQ_PASSWORD }
    if ($null -ne $Body) { $command.body = $Body }
    return Invoke-Client $command
}

# Fehlende Statistikfelder oder nicht bestätigte Lieferungen dürfen keinen leeren Zustand vortäuschen.
function Test-EmptyQueue([int] $Consumers) {
    $queue = Invoke-Management 'queues/%2F/chat.persist'
    return ($queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0 -and $queue.consumers -eq $Consumers)
}

# Alle Wartephasen besitzen eine explizite Frist; kein Test darf endlos grün warten.
function Wait-Check([scriptblock] $Check, [datetime] $Deadline, [string] $Description) {
    do {
        $success = & $Check
        if ($success -and [datetime]::UtcNow -le $Deadline) { return }
        Start-Sleep -Milliseconds 200
    } while ([datetime]::UtcNow -lt $Deadline)
    throw "Deadline exceeded: $Description"
}

# Die Raum-ID und jede angenommene Nachrichten-ID bleiben als Nachweis im privaten Ergebnisordner erhalten.
function Send-Group([int] $Count, [string] $Prefix) {
    $room = [guid]::NewGuid().ToString()
    $receipts = @(Invoke-Client @{ action='send'; count=$Count; roomId=$room; prefix=$Prefix })
    if ($receipts.Count -ne $Count) { throw 'Unexpected number of HTTP responses' }
    $uniqueIds = @($receipts.id | Sort-Object -Unique)
    if ($uniqueIds.Count -ne $Count) { throw 'HTTP response IDs are not unique' }
    $lastAcceptance = ($receipts | Measure-Object -Property acceptedAt -Maximum).Maximum
    $accepted = [datetimeoffset]::FromUnixTimeMilliseconds([long]($lastAcceptance * 1000))
    $group = @{ roomId=$room; receipts=$receipts; accepted=$accepted.UtcDateTime }
    $receiptGroups.Add($group)
    return $group
}

# SQL liefert IDs und Inhalte in einem Abruf; Gesamtzähler fremder Szenarien werden nicht angerechnet.
function Test-Stored($Group) {
    $room = [guid]$Group.roomId
    $sql = "SELECT coalesce(json_agg(row_to_json(m)), '[]'::json) FROM (SELECT id, content FROM message WHERE room_id='$room') m;"
    $json = Invoke-Database $sql
    $parsed = ConvertFrom-Json -InputObject $json
    $rows = @($parsed)
    if ($rows.Count -ne $Group.receipts.Count) { return $false }
    $stored = @{}
    foreach ($row in $rows) { $stored[$row.id] = $row.content }
    foreach ($receipt in $Group.receipts) {
        if (-not $stored.ContainsKey($receipt.id)) { return $false }
        if ($stored[$receipt.id] -cne $receipt.content) { throw 'Stored content differs from HTTP request' }
    }
    return $true
}

# Vor S7 muss die DLQ absolut leer sein; unerwartete Altfehler dürfen nicht verdeckt werden.
function Assert-NoDeadLetters {
    $queue = Invoke-Management 'queues/%2F/chat.dlq'
    if ($queue.messages -ne 0) { throw 'Unexpected dead letters' }
}

# Nur erfolgreich ausgeführte Szenarien erhalten einen erfolgreichen Eintrag.
function Record-Result([string] $Scenario, [string] $Evidence) {
    $report.Add(@{ scenario=$Scenario; result='passed'; evidence=$Evidence; time=[datetime]::UtcNow })
    Write-Host "$Scenario passed: $Evidence"
}

# Container-ID, Startzeit und Restart-Zähler müssen über S7 unverändert bleiben.
function Get-WriterState {
    $ids = @(Invoke-Compose @('ps', '-q', 'batch-writer'))
    if ($ids.Count -ne 2) { throw 'Expected two writer containers' }
    $states = @()
    foreach ($id in $ids) {
        $raw = & docker inspect $id
        if ($LASTEXITCODE -ne 0) { throw 'Container inspection failed' }
        $parsed = ConvertFrom-Json -InputObject ($raw -join "`n")
        $container = @($parsed)[0]
        if (-not $container.State.Running) { throw 'Writer is not running' }
        $states += "$id|$($container.State.StartedAt)|$($container.RestartCount)"
    }
    return @($states | Sort-Object)
}

try {
    # Der Frischstart ist eine Voraussetzung und wird nie durch Löschen vorhandener Daten erzwungen.
    if (Test-Path -LiteralPath '.env') { throw 'Use a fresh test clone without a personal .env file' }
    $null = Get-Command docker -ErrorAction Stop
    $null = Get-Command $MavenCommand -ErrorAction Stop
    & docker version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker server unavailable' }
    & docker compose version
    if ($LASTEXITCODE -ne 0) { throw 'Compose unavailable' }
    $existingNetwork = & docker network ls --filter name=^chat-net$ --format '{{.Name}}'
    if ($LASTEXITCODE -ne 0 -or $existingNetwork) { throw 'chat-net must not already exist for S2' }
    $modelText = Invoke-Compose @('config', '--format', 'json')
    $model = ConvertFrom-Json -InputObject ($modelText -join "`n")
    foreach ($volume in $model.volumes.PSObject.Properties) {
        $existing = & docker volume ls --filter "name=^$($volume.Value.name)$" --format '{{.Name}}'
        if ($LASTEXITCODE -ne 0 -or $existing) { throw 'S2 requires new test volumes' }
    }
    & git check-ignore --quiet 'Erklärungen/abnahme-probe'
    if ($LASTEXITCODE -eq 1) {
        $excludeFile = & git rev-parse --git-path info/exclude
        if ($LASTEXITCODE -ne 0) { throw 'Git exclude path unavailable' }
        Add-Content -Encoding UTF8 -LiteralPath $excludeFile -Value "`n/Erklärungen/"
    } elseif ($LASTEXITCODE -ne 0) { throw 'Git ignore check failed' }
    $null = New-Item -ItemType Directory -Path $resultDirectory -Force
    $revision = & git rev-parse HEAD
    $dirty = & git status --porcelain
    if ($dirty) { throw 'Commit the tested code first; the test clone must be clean' }
    $settings = @{}
    foreach ($line in Get-Content -Encoding UTF8 .env.example) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
    }
    Invoke-MavenTests (Join-Path $resultDirectory 'maven.log')
    $testCount = 0
    $reports = Get-ChildItem 'chat-service/target/surefire-reports/TEST-*.xml', 'batch-writer/target/surefire-reports/TEST-*.xml'
    foreach ($file in $reports) {
        [xml] $suite = Get-Content -Raw -LiteralPath $file.FullName
        if ([int]$suite.testsuite.skipped -ne 0 -or [int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0) {
            throw 'S1 contains skipped or failed tests'
        }
        $testCount += [int]$suite.testsuite.tests
    }
    foreach ($required in @('DuplicateMessageIntegrationTest','DatabaseOutageIntegrationTest','MultipleWritersIntegrationTest','ConsumerLifecycleIntegrationTest')) {
        $found = @($reports | Where-Object Name -Like "*.$required.xml")
        if ($found.Count -ne 1) { throw "Missing required test report: $required" }
    }
    if ($testCount -eq 0) { throw 'No tests executed' }
    Record-Result 'S1' "$testCount tests; no failures or skipped tests; revision $revision"

    # Startet die vier Produktdienste; der Diagnoseclient gehört nicht zur Compose-Anwendung.
    $services = @($model.services.PSObject.Properties)
    if ($services.Count -ne 4) { throw 'Expected exactly four application services' }
    foreach ($service in $services) {
        $ports = $service.Value.PSObject.Properties['ports']
        if ($null -ne $ports -and @($ports.Value).Count -gt 0) { throw 'Published host ports are forbidden' }
        $networks = @($service.Value.networks.PSObject.Properties.Name)
        if ($networks -notcontains 'chat-net') { throw 'Service missing chat-net' }
    }
    Invoke-Compose @('up', '-d', '--build', '--wait', '--wait-timeout', '180')
    $mount = "type=bind,source=$PSScriptRoot,target=/checks,readonly"
    & docker run -d --name $clientName --network chat-net --mount $mount python:3.12-alpine python -c 'import time; time.sleep(86400)'
    if ($LASTEXITCODE -ne 0) { throw 'Could not start internal test client' }
    $clientStarted = $true
    Wait-Check { Test-EmptyQueue 1 } ([datetime]::UtcNow.AddSeconds(30)) 'first consumer'
    Assert-NoDeadLetters
    $schema = Invoke-Database "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='message' ORDER BY ordinal_position;"
    $expectedSchema = @('id:uuid:NO','room_id:uuid:NO','sender_id:character varying:NO','sender_name:character varying:NO','content:text:NO','sent_at:timestamp with time zone:NO')
    if (($schema -join ',') -ne ($expectedSchema -join ',')) { throw 'Schema differs from specification' }
    $primaryKey = Invoke-Database "SELECT count(*) FROM pg_constraint WHERE conrelid='public.message'::regclass AND contype='p' AND pg_get_constraintdef(oid)='PRIMARY KEY (id)';"
    if ([int]$primaryKey -ne 1) { throw 'Primary key on id missing' }
    $indexes = Invoke-Database "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='message';"
    $indexText = $indexes -join "`n"
    if ($indexText -notmatch 'UNIQUE INDEX message_pkey' -or $indexText -notmatch 'USING btree \(room_id, sent_at DESC\)') { throw 'Required indexes missing' }
    Record-Result 'S2' 'Fresh four-service stack, schema, indexes, one consumer, no host ports'

    $group = Send-Group 1000 'S3'
    Wait-Check { (Test-Stored $group) -and (Test-EmptyQueue 1) } ($group.accepted.AddSeconds(60)) 'S3 persistence and ACK'
    Assert-NoDeadLetters
    Record-Result 'S3' '1000 exact IDs and contents persisted; ready=unacknowledged=0 within 60 s'

    Invoke-Compose @('stop', 'batch-writer')
    $group = Send-Group 1000 'S4'
    Wait-Check {
        $queue = Invoke-Management 'queues/%2F/chat.persist'
        $queue.messages_ready -eq 1000 -and $queue.messages_unacknowledged -eq 0 -and $queue.consumers -eq 0
    } ([datetime]::UtcNow.AddSeconds(15)) 'S4 backlog'
    $room = [guid]$group.roomId
    $beforeRows = Invoke-Database "SELECT count(*) FROM message WHERE room_id='$room';"
    if ([int]$beforeRows -ne 0) { throw 'S4 already has persisted rows' }
    Start-Sleep -Seconds 2
    $statisticsSql = 'SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname=current_database();'
    $before = [long](Invoke-Database $statisticsSql)
    Invoke-Compose @('start', 'batch-writer')
    Wait-Check { Test-EmptyQueue 1 } ([datetime]::UtcNow.AddSeconds(60)) 'S4 local diagnostic limit'
    Start-Sleep -Seconds 2
    $after = [long](Invoke-Database $statisticsSql)
    $delta = $after - $before
    if ($delta -lt 1 -or $delta -gt 100) { throw "S4 transaction delta: $delta" }
    if (-not (Test-Stored $group)) { throw 'S4 missing IDs or contents' }
    Assert-NoDeadLetters
    Record-Result 'S4' "Raw transaction delta $delta (before=$before, after=$after); all 1000 IDs stored"

    # Zweimal derselbe Body über den Default-Exchange; keine zusätzlichen AMQP-Eigenschaften.
    $id = [guid]::NewGuid().ToString()
    $room = [guid]::NewGuid().ToString()
    $message = @{ id=$id; roomId=$room; senderId='review'; senderName='Review'; content='S5-duplicate'; sentAt='2026-10-02T12:00:00Z' }
    $body = ConvertTo-Json -InputObject $message -Compress
    $queueBefore = Invoke-Management 'queues/%2F/chat.persist'
    $ackBefore = [long]$queueBefore.message_stats.ack
    for ($i = 0; $i -lt 2; $i++) {
        $published = Invoke-Management 'exchanges/%2F/amq.default/publish' @{ properties=@{content_type='application/json'}; routing_key='chat.persist'; payload=$body; payload_encoding='string' }
        if (-not $published.routed) { throw 'S5 message was not routed' }
    }
    Wait-Check {
        $rows = [int](Invoke-Database "SELECT count(*) FROM message WHERE id='$id' AND room_id='$room' AND sender_id='review' AND sender_name='Review' AND content='S5-duplicate' AND sent_at='2026-10-02T12:00:00Z';")
        $queue = Invoke-Management 'queues/%2F/chat.persist'
        $rows -eq 1 -and $queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0 -and [long]$queue.message_stats.ack -ge ($ackBefore + 2)
    } ([datetime]::UtcNow.AddSeconds(20)) 'S5 duplicate ACKs'
    Assert-NoDeadLetters
    Record-Result 'S5' "Raw duplicate ID ${id}: one unchanged row, both deliveries acknowledged, DLQ empty"

    Invoke-Compose @('up', '-d', '--scale', 'batch-writer=2')
    Wait-Check { Test-EmptyQueue 2 } ([datetime]::UtcNow.AddSeconds(30)) 'two consumers'
    $writerState = @(Get-WriterState)
    $group = Send-Group 1000 'S6'
    Wait-Check { (Test-Stored $group) -and (Test-EmptyQueue 2) } ($group.accepted.AddSeconds(60)) 'S6 persistence'
    Assert-NoDeadLetters
    Record-Result 'S6' 'Two unchanged writer containers and consumers; all 1000 IDs persisted'

    foreach ($state in $writerState) {
        $containerId = $state.Split('|')[0]
        $logs = Get-WriterLogs $containerId
        if (($logs -join "`n") -notmatch 'Batch committed:') {
            throw 'Before S7 both writers must have used their database connections successfully'
        }
    }

    # Der unabhängige Job startet Postgres auch dann nach 15 Sekunden, wenn das Senden noch dauert.
    Invoke-Compose @('stop', 'postgres')
    $stopped = [datetime]::UtcNow
    $restartAt = $stopped.AddSeconds(15)
    $restartJob = Start-Job -ArgumentList $projectRoot,$restartAt -ScriptBlock {
        param($Root, $RestartAt)
        Set-Location -LiteralPath $Root
        $remaining = $RestartAt - [datetime]::UtcNow
        if ($remaining.TotalMilliseconds -gt 0) { Start-Sleep -Milliseconds ([int]$remaining.TotalMilliseconds) }
        $started = [datetime]::UtcNow
        $null = docker compose --env-file .env.example start postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL restart failed' }
        return $started
    }
    $group = Send-Group 300 'S7'
    if ($group.accepted -gt $restartAt) { throw 'S7 requests were not all accepted during the outage' }
    $finished = Wait-Job -Job $restartJob -Timeout 30
    if ($null -eq $finished -or $restartJob.State -ne 'Completed') { throw 'PostgreSQL restart job failed' }
    $actualRestart = Receive-Job -Job $restartJob -ErrorAction Stop
    $restartDelay = ($actualRestart - $stopped).TotalSeconds
    if ($restartDelay -lt 15 -or $restartDelay -gt 16) { throw "Invalid outage timing: $restartDelay seconds" }
    $expectedIds = @($group.receipts.id | Sort-Object)
    Wait-Check {
        try { $stored = @(Invoke-Database "SELECT id FROM message WHERE room_id='$($group.roomId)';") }
        catch { return $false }
        $dlq = Invoke-Management 'queues/%2F/chat.dlq'
        $limit = [Math]::Max(10000, [int]$dlq.messages)
        $dead = @(Invoke-Management 'queues/%2F/chat.dlq/get' @{ count=$limit; ackmode='ack_requeue_true'; encoding='auto'; truncate=1000000 })
        $accounted = @{}
        foreach ($value in $stored) { $accounted[$value] = $true }
        foreach ($delivery in $dead) {
            $payload = ConvertFrom-Json -InputObject $delivery.payload
            if ($payload.roomId -eq $group.roomId) { $accounted[$payload.id] = $true }
        }
        $actual = @($accounted.Keys | Sort-Object)
        ($actual -join ',') -eq ($expectedIds -join ',') -and (Test-EmptyQueue 2)
    } ($stopped.AddSeconds(90)) 'S7 accounting within 90 seconds'
    $recoveryStart = [datetime]::UtcNow
    $recoveryDeadline = $recoveryStart.AddSeconds(30)
    $bothCommitted = $false
    do {
        $control = Send-Group 1000 'S7-control'
        $deadline = $control.accepted.AddSeconds(10)
        if ($deadline -gt $recoveryDeadline) { $deadline = $recoveryDeadline }
        Wait-Check { (Test-Stored $control) -and (Test-EmptyQueue 2) } $deadline 'S7 control group'
        $bothCommitted = $true
        foreach ($state in $writerState) {
            $containerId = $state.Split('|')[0]
            $since = $recoveryStart.ToString('o')
            $logs = Get-WriterLogs $containerId $since
            if (($logs -join "`n") -notmatch 'Batch committed:') { $bothCommitted = $false }
        }
    } while (-not $bothCommitted -and [datetime]::UtcNow -lt $recoveryDeadline)
    if (-not $bothCommitted) { throw 'Not both writers recovered within 30 seconds' }
    $afterState = @(Get-WriterState)
    if (($writerState -join ',') -ne ($afterState -join ',')) { throw 'Writer restarted during S7' }
    Record-Result 'S7' "300 IDs accounted; PostgreSQL restart after $restartDelay seconds; both original writers recovered"

    # Diese automatischen Kontrollen ersetzen ausdrücklich nicht die manuelle Kommentar-/Stilprüfung.
    $trackedSecret = & git ls-files -- .env
    if ($trackedSecret) { throw '.env is tracked' }
    & git check-ignore .env
    if ($LASTEXITCODE -ne 0) { throw '.env is not ignored' }
    $streams = Get-ChildItem batch-writer/src -Recurse -Filter '*.java' | Select-String -Pattern '\.stream\s*\(|\.parallelStream\s*\(|java\.util\.stream|\bStream\s*<'
    if ($streams) { throw 'Stream usage found' }
    $report.Add(@{scenario='S8'; result='manual review required'; evidence='No tracked .env or streams found; inspect all class and method comments'})
    Write-Host 'S1-S7 completed. S8 manual review and additional targeted integration evidence remain separate.'
} catch {
    $report.Add(@{scenario='execution'; result='failed'; evidence=$_.Exception.Message})
    throw
} finally {
    # Nur der temporäre Prüfclient wird entfernt; Produktcontainer und Daten bleiben zur Diagnose bestehen.
    if ($null -ne $restartJob) {
        $null = Wait-Job -Job $restartJob -Timeout 30
        if ($restartJob.State -eq 'Running') { Stop-Job -Job $restartJob }
        Remove-Job -Job $restartJob
    }
    if ($clientStarted) {
        try {
            $logs = Invoke-Compose @('logs', '--no-color', 'batch-writer')
            $logs | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $resultDirectory 'writer.log')
        } catch {
            Write-Warning 'Could not collect writer logs; the scenario result is still recorded'
        }
        & docker rm -f $clientName | Out-Null
    }
    if (Test-Path -LiteralPath $resultDirectory) {
        ConvertTo-Json -InputObject @($report.ToArray()) -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $resultDirectory 'results.json')
        ConvertTo-Json -InputObject @($receiptGroups.ToArray()) -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $resultDirectory 'receipts.json')
    }
}
