<#
.SYNOPSIS
    Safely rebuilds the local forklift_erp development/test schema from zero.

.DESCRIPTION
    This script intentionally has no host or schema-name parameter. It can only
    connect over TCP to 127.0.0.1 and can only drop/create forklift_erp. The
    application applies Flyway migrations on the next start. Then run
    scripts/seed_closed_loop_test_data.py to create the supported, fully
    traceable test fixture through the application's business APIs.

    Existing data is backed up by default to local-db-backups before the
    schema is dropped. Use -SkipBackup only when that copy is intentionally not
    needed. Upload files are preserved by default; -CleanUploads removes only
    the contents of this checkout's uploads directory after the schema rebuild.

    Supply database authentication through MYSQL_PWD,
    FORKLIFT_ERP_DB_PASSWORD, or the secure -DatabasePassword parameter. No
    password is placed on a command line or written to output.

.EXAMPLE
    $password = Read-Host 'Local MySQL password' -AsSecureString
    .\scripts\rebuild_complete_test_database.ps1 -ConfirmCleanDatabase -DatabasePassword $password

.EXAMPLE
    .\scripts\rebuild_complete_test_database.ps1 -ConfirmCleanDatabase -CleanUploads
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    # This explicit acknowledgement is mandatory for every destructive run.
    [switch]$ConfirmCleanDatabase,

    [ValidateRange(1, 65535)]
    [int]$Port = 3306,

    [ValidateNotNullOrEmpty()]
    [string]$DatabaseUser = $(
        if ([string]::IsNullOrWhiteSpace($env:FORKLIFT_ERP_DB_USERNAME)) {
            'root'
        } else {
            $env:FORKLIFT_ERP_DB_USERNAME
        }
    ),

    [System.Security.SecureString]$DatabasePassword,

    [ValidateNotNullOrEmpty()]
    [string]$MySqlExecutable = 'mysql',

    [ValidateNotNullOrEmpty()]
    [string]$MySqlDumpExecutable = 'mysqldump',

    # Backups are on by default. This switch deliberately makes bypassing one explicit.
    [switch]$SkipBackup,

    # Never enabled by default: this clears only <project>/uploads contents.
    [switch]$CleanUploads
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# These values are deliberately not parameters. This script must never be used
# to drop a remote server or a differently named schema by accident.
$script:TargetHost = '127.0.0.1'
$script:TargetDatabase = 'forklift_erp'
$script:TargetPort = $Port
$script:TargetDatabaseUser = $DatabaseUser

function Resolve-NativeExecutable {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [string]$Label
    )

    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $command -or [string]::IsNullOrWhiteSpace($command.Path)) {
        throw "$Label executable '$Name' was not found. Install the MySQL client tools or pass its executable path."
    }

    return $command.Path
}

function ConvertFrom-SecureString {
    param(
        [Parameter(Mandatory)]
        [System.Security.SecureString]$Value
    )

    $bstr = [IntPtr]::Zero
    try {
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory)]
        [string]$Parent,

        [Parameter(Mandatory)]
        [string]$Candidate,

        [Parameter(Mandatory)]
        [string]$Description
    )

    $parentPath = [IO.Path]::GetFullPath($Parent).TrimEnd([char[]]@('\', '/'))
    $candidatePath = [IO.Path]::GetFullPath($Candidate)
    $parentPrefix = $parentPath + [IO.Path]::DirectorySeparatorChar

    if (-not $candidatePath.StartsWith($parentPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must remain inside the project checkout."
    }
}

function Invoke-LocalMySql {
    param(
        [Parameter(Mandatory)]
        [string]$Sql
    )

    # MYSQL_PWD is set only for this process environment; it is never passed as
    # a command-line argument, which would expose it in process listings.
    $arguments = @(
        '--no-defaults',
        '--protocol=TCP',
        "--host=$script:TargetHost",
        "--port=$script:TargetPort",
        "--user=$script:TargetDatabaseUser",
        '--connect-timeout=5',
        '--batch',
        '--skip-column-names',
        '--raw',
        '--default-character-set=utf8mb4',
        '--execute', $Sql
    )

    $output = @(& $script:MySqlPath @arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "The mysql client failed with exit code $LASTEXITCODE."
    }

    return $output
}

function Get-LocalMySqlScalar {
    param(
        [Parameter(Mandatory)]
        [string]$Sql
    )

    $lines = @(Invoke-LocalMySql -Sql $Sql | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
    if ($lines.Count -ne 1) {
        throw 'MySQL returned an unexpected result while validating the local test database.'
    }

    return $lines[0].Trim()
}

function Assert-SessionInspectionPermission {
    $grants = @(Invoke-LocalMySql -Sql 'SHOW GRANTS')
    $hasProcessPrivilege = $false
    foreach ($grant in $grants) {
        # Match a global privilege grant, rather than merely a role or account
        # name that happens to contain the word "PROCESS".
        if ($grant -match '(?i)^GRANT\s+ALL\s+PRIVILEGES\s+ON\s+\*\.\*' -or
            $grant -match '(?i)^GRANT\s+(?=[^;]*\bPROCESS\b)[^;]*\s+ON\s+\*\.\*') {
            $hasProcessPrivilege = $true
            break
        }
    }

    if (-not $hasProcessPrivilege) {
        throw ('The MySQL account cannot prove it can inspect every active session. ' +
            'For a fail-closed reset, use a local account with the global PROCESS privilege.')
    }
}

function Assert-NoUnsafeSessions {
    # Refuse more than just an in-flight writer: an idle connection with
    # forklift_erp selected can begin a write immediately after this check. Any
    # active transaction or write on the local server is also treated as unsafe.
    # The query deliberately omits statement text so a separate session cannot
    # expose a secret through its SQL text in this script's console output.
    $unsafeSessionSql = @'
SELECT CONCAT_WS(CHAR(9),
    p.ID,
    p.USER,
    p.HOST,
    COALESCE(p.DB, '<none>'),
    p.COMMAND,
    p.TIME,
    CASE
        WHEN p.DB = 'forklift_erp' THEN 'target-schema-session'
        WHEN t.trx_mysql_thread_id IS NOT NULL THEN 'active-transaction'
        ELSE 'active-write-statement'
    END)
FROM information_schema.processlist AS p
LEFT JOIN information_schema.innodb_trx AS t
    ON t.trx_mysql_thread_id = p.ID
WHERE p.ID <> CONNECTION_ID()
  AND (
      p.DB = 'forklift_erp'
      OR t.trx_mysql_thread_id IS NOT NULL
      OR UPPER(LTRIM(COALESCE(p.INFO, ''))) REGEXP
          '^(INSERT|UPDATE|DELETE|REPLACE|LOAD[[:space:]]+DATA|ALTER|CREATE|DROP|TRUNCATE|RENAME|GRANT|REVOKE|CALL|DO|HANDLER|LOCK[[:space:]]+TABLES|START[[:space:]]+TRANSACTION|BEGIN)'
  )
ORDER BY p.ID
'@

    $unsafeSessions = @(Invoke-LocalMySql -Sql $unsafeSessionSql | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
    if ($unsafeSessions.Count -gt 0) {
        Write-Warning 'The reset was not started because unsafe non-script MySQL session(s) were found:'
        Write-Host '  id`tuser`thost`tdatabase`tcommand`tseconds`treason'
        foreach ($session in $unsafeSessions) {
            Write-Host "  $session"
        }
        throw 'Stop the listed client(s) and retry. The forklift_erp schema has not been changed.'
    }
}

function Test-TargetSchemaExists {
    $count = Get-LocalMySqlScalar -Sql @'
SELECT COUNT(*)
FROM information_schema.schemata
WHERE schema_name = 'forklift_erp'
'@

    if ($count -notmatch '^[01]$') {
        throw 'MySQL returned an unexpected schema count for forklift_erp.'
    }

    return $count -eq '1'
}

function Backup-TargetSchema {
    param(
        [Parameter(Mandatory)]
        [string]$BackupDirectory
    )

    New-Item -ItemType Directory -Path $BackupDirectory -Force | Out-Null
    $backupName = 'forklift_erp-before-clean-{0:yyyyMMdd-HHmmss}-{1}.sql' -f (Get-Date), ([Guid]::NewGuid().ToString('N').Substring(0, 8))
    $backupPath = Join-Path $BackupDirectory $backupName
    if (Test-Path -LiteralPath $backupPath) {
        throw "Refusing to overwrite an existing backup file: $backupPath"
    }

    $arguments = @(
        '--no-defaults',
        '--protocol=TCP',
        "--host=$script:TargetHost",
        "--port=$script:TargetPort",
        "--user=$script:TargetDatabaseUser",
        '--default-character-set=utf8mb4',
        '--single-transaction',
        '--skip-lock-tables',
        '--routines',
        '--events',
        '--triggers',
        '--hex-blob',
        '--no-tablespaces',
        '--set-gtid-purged=OFF',
        "--result-file=$backupPath",
        '--databases',
        $script:TargetDatabase
    )

    & $script:MySqlDumpPath @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "The mysqldump backup failed with exit code $LASTEXITCODE. The schema has not been changed."
    }
    if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf) -or
        (Get-Item -LiteralPath $backupPath).Length -eq 0) {
        throw 'The mysqldump command did not create a usable backup file. The schema has not been changed.'
    }

    return $backupPath
}

function Assert-NoReparsePoints {
    param(
        [Parameter(Mandatory)]
        [IO.DirectoryInfo]$Directory
    )

    foreach ($entry in $Directory.EnumerateFileSystemInfos()) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing to clean uploads because it contains a reparse point: $($entry.FullName)"
        }
        if ($entry -is [IO.DirectoryInfo]) {
            Assert-NoReparsePoints -Directory $entry
        }
    }
}

function Clear-ProjectUploads {
    param(
        [Parameter(Mandatory)]
        [string]$UploadsDirectory
    )

    if (-not (Test-Path -LiteralPath $UploadsDirectory)) {
        Write-Host 'No uploads directory exists, so no upload files needed removal.'
        return
    }

    $uploadsItem = Get-Item -LiteralPath $UploadsDirectory -Force
    if (-not $uploadsItem.PSIsContainer) {
        throw "Refusing to clean uploads because the expected directory is a file: $UploadsDirectory"
    }
    if (($uploadsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing to clean uploads because the uploads directory is a reparse point: $UploadsDirectory"
    }

    # Scan before deleting anything. The scan never descends through a
    # reparse point, avoiding accidental deletion outside the checkout.
    Assert-NoReparsePoints -Directory $uploadsItem
    $children = @($uploadsItem.EnumerateFileSystemInfos())
    foreach ($child in $children) {
        Remove-Item -LiteralPath $child.FullName -Force -Recurse
    }

    Write-Host 'Cleared only the contents of this checkout''s uploads directory.'
}

if (-not $ConfirmCleanDatabase.IsPresent) {
    throw 'Refusing to continue. Re-run with the explicit -ConfirmCleanDatabase switch.'
}
if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
    throw 'DatabaseUser must not be empty.'
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'pom.xml') -PathType Leaf)) {
    throw 'This script must remain under the scripts directory of a forklift-erp project checkout.'
}
$backupDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot 'local-db-backups'))
$uploadsDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot 'uploads'))
Assert-ChildPath -Parent $projectRoot -Candidate $backupDirectory -Description 'The backup directory'
Assert-ChildPath -Parent $projectRoot -Candidate $uploadsDirectory -Description 'The uploads directory'

$actionDescription = 'back up the existing schema unless -SkipBackup is supplied, then DROP and CREATE the local utf8mb4 forklift_erp schema'
if ($CleanUploads) {
    $actionDescription += ', then clear this checkout''s uploads contents'
}
if (-not $PSCmdlet.ShouldProcess("$script:TargetHost`:$script:TargetPort/$script:TargetDatabase", $actionDescription)) {
    return
}

$hadOriginalMySqlPwd = Test-Path -LiteralPath 'Env:MYSQL_PWD'
$originalMySqlPwd = if ($hadOriginalMySqlPwd) { $env:MYSQL_PWD } else { $null }
$resolvedPassword = $null
$backupPath = $null

try {
    $script:MySqlPath = Resolve-NativeExecutable -Name $MySqlExecutable -Label 'mysql'
    if (-not $SkipBackup) {
        $script:MySqlDumpPath = Resolve-NativeExecutable -Name $MySqlDumpExecutable -Label 'mysqldump'
    }

    if ($PSBoundParameters.ContainsKey('DatabasePassword')) {
        $resolvedPassword = ConvertFrom-SecureString -Value $DatabasePassword
    } elseif (-not [string]::IsNullOrEmpty($env:MYSQL_PWD)) {
        $resolvedPassword = $env:MYSQL_PWD
    } elseif (-not [string]::IsNullOrEmpty($env:FORKLIFT_ERP_DB_PASSWORD)) {
        $resolvedPassword = $env:FORKLIFT_ERP_DB_PASSWORD
    } else {
        throw ('No database password was supplied. Set MYSQL_PWD or FORKLIFT_ERP_DB_PASSWORD, ' +
            'or pass -DatabasePassword (Read-Host -AsSecureString).')
    }
    if ([string]::IsNullOrEmpty($resolvedPassword)) {
        throw 'No database password was supplied.'
    }
    $env:MYSQL_PWD = $resolvedPassword

    $serverVersion = Get-LocalMySqlScalar -Sql 'SELECT VERSION()'
    if ($serverVersion -notmatch '^8\.') {
        throw "This reset script supports local MySQL 8 only; server version '$serverVersion' was returned."
    }

    Assert-SessionInspectionPermission
    Assert-NoUnsafeSessions
    $schemaExists = Test-TargetSchemaExists

    if ($schemaExists -and -not $SkipBackup) {
        $backupPath = Backup-TargetSchema -BackupDirectory $backupDirectory
        Write-Host "Database backup completed: $backupPath"
    } elseif ($schemaExists) {
        Write-Warning 'The existing forklift_erp schema backup was explicitly skipped.'
    } else {
        Write-Host 'The forklift_erp schema does not exist yet; no database backup is needed.'
    }

    # Check again immediately before DDL, so a client that connected while the
    # backup was running prevents the destructive step.
    Assert-NoUnsafeSessions

    Invoke-LocalMySql -Sql 'DROP DATABASE IF EXISTS `forklift_erp`' | Out-Null
    Invoke-LocalMySql -Sql 'CREATE DATABASE `forklift_erp` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci' | Out-Null

    $schemaSettings = Get-LocalMySqlScalar -Sql @'
SELECT CONCAT(DEFAULT_CHARACTER_SET_NAME, CHAR(9), DEFAULT_COLLATION_NAME)
FROM information_schema.schemata
WHERE schema_name = 'forklift_erp'
'@
    if ($schemaSettings -ne "utf8mb4`tutf8mb4_0900_ai_ci") {
        throw "The recreated schema did not report the required utf8mb4 settings: $schemaSettings"
    }

    if ($CleanUploads) {
        Clear-ProjectUploads -UploadsDirectory $uploadsDirectory
    }

    Write-Host ''
    Write-Host 'Local forklift_erp test schema has been recreated.' -ForegroundColor Green
    Write-Host "Target: $script:TargetHost`:$script:TargetPort/$script:TargetDatabase (utf8mb4)"
    if ($backupPath) {
        Write-Host "Backup: $backupPath"
    }
    Write-Host 'Next, start the application to apply Flyway migrations, keeping the legacy demo fixture disabled:'
    Write-Host ('  Set-Location -LiteralPath "' + $projectRoot + '"')
    $startUrl = "jdbc:mysql://127.0.0.1:$script:TargetPort/forklift_erp?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    Write-Host ('  $env:FORKLIFT_ERP_DB_URL = "' + $startUrl + '"')
    Write-Host '  # Ensure FORKLIFT_ERP_DB_USERNAME and FORKLIFT_ERP_DB_PASSWORD are set securely.'
    Write-Host '  $env:FORKLIFT_ERP_SEED_DEMO_DATA = "false"'
    Write-Host '  .\mvnw.cmd spring-boot:run'
    Write-Host '  python .\scripts\seed_closed_loop_test_data.py --base-url http://127.0.0.1:8080'
} finally {
    # Do not leave a password copied into MYSQL_PWD solely because this script
    # needed it. Preserve a caller-provided MYSQL_PWD exactly as it was.
    $resolvedPassword = $null
    if ($hadOriginalMySqlPwd) {
        $env:MYSQL_PWD = $originalMySqlPwd
    } else {
        Remove-Item -LiteralPath 'Env:MYSQL_PWD' -ErrorAction SilentlyContinue
    }
}
